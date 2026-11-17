package com.qmusic.ui.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ==================================================================== the building blocks

/**
 * The cryptography, all standard and from the platform: P-256 key pairs (ECDH), HKDF-SHA256, AES-256-GCM, PBKDF2-
 * HMAC-SHA256 (written out: the platform has it only from Android 8). Nothing home-made beyond putting them together.
 */
internal object E2eCrypto {
    private val random = SecureRandom()

    fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    // Kotlin's own Base64 (standard alphabet, padded): the same on the phone and in the tests.
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun b64(b: ByteArray): String = kotlin.io.encoding.Base64.encode(b)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun unb64(s: String): ByteArray = kotlin.io.encoding.Base64.decode(s)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    /** AES-256-GCM: a fresh 12-byte nonce in front of the sealed bytes; [aad] binds it to where it belongs. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = bytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        aad?.let { c.updateAAD(it) }
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sealed.size > 28) { "short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        aad?.let { c.updateAAD(it) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018). */
    fun pbkdf2(password: String, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password.toByteArray(), "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (r in 1 until rounds) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            block++
        }
        return out
    }

    fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicFrom(b: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b))
    fun privateFrom(b: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b))

    private fun ecdh(private: PrivateKey, public: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(public, true)
            generateSecret()
        }

    /** Locks [secret] for the holder of [recipient] (ECIES: a one-time key pair, ECDH, HKDF, AES-GCM). */
    fun wrap(recipient: PublicKey, secret: ByteArray, context: String): JSONObject {
        val eph = newPair()
        val shared = ecdh(eph.private, recipient)
        val key = hkdf(shared, eph.public.encoded + recipient.encoded, "nuvelo-wrap:$context")
        return JSONObject().put("epk", b64(eph.public.encoded)).put("c", b64(seal(key, secret, context.toByteArray())))
    }

    fun unwrap(mine: KeyPair, epk: String, sealed: String, context: String): ByteArray {
        val eph = publicFrom(unb64(epk))
        val shared = ecdh(mine.private, eph)
        val key = hkdf(shared, eph.encoded + mine.public.encoded, "nuvelo-wrap:$context")
        return open(key, unb64(sealed), context.toByteArray())
    }

    /** Short name of a public key: what the key wraps and the safety codes are checked against. */
    fun fingerprint(public: ByteArray) = hex(sha256(public)).take(32)
}

/** A key that never leaves the phone's secure hardware, which locks what this phone keeps of the encryption keys. */
private object DeviceLock {
    private const val ALIAS = "nuvelo_e2e_device"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return E2eCrypto.b64(c.iv + c.doFinal(plain))
    }

    fun open(sealed: String): ByteArray? = runCatching {
        val b = E2eCrypto.unb64(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        c.doFinal(b, 12, b.size - 12)
    }.getOrNull()
}

// ==================================================================== the engine

/**
 *   publicKeys/{uid}                   = {k: X.509 public key, fp, at}
 *   chatKeys/{chatId}/{uid}/{version}  = {epk, c, fp}         the conversation's key locked for that member
 *   chats/{chatId}/e2e                 = {v: version, by, at} on, and the key version messages use now
 *   keyBackup/{uid}                    = {salt, rounds, c, fp, at}   the private key, locked with PIN + vault secret
 *   secretSpace/{uid}                  = {on, at}            the person has a PIN (the secret space is set up)
 */
internal class E2eEngine(private val app: Context, private val root: DatabaseReference) : E2eApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val auth = FirebaseAuth.getInstance()
    private val prefs = app.getSharedPreferences("nuvelo_e2e", Context.MODE_PRIVATE)

    override val ready = MutableStateFlow(false)
    override val needsRestore = MutableStateFlow(false)
    override val hasBackup = MutableStateFlow(false)
    override val hasOwnKey = MutableStateFlow(false)
    override val hasPin = MutableStateFlow(false)
    override val changes = MutableStateFlow(0)
    override val keyChanged = MutableStateFlow<Set<String>>(emptySet())

    @Volatile private var pair: KeyPair? = null
    @Volatile private var uid: String? = null

    /** chatId -> version on (0 when off, absent when not known yet). */
    private val versions = ConcurrentHashMap<String, Int>()
    /** "chatId/version" -> the conversation's key. */
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val watched = ConcurrentHashMap<String, ValueEventListener>()
    private val publicCache = ConcurrentHashMap<String, Pair<String, PublicKey>>()

    private fun bump() = changes.update { it + 1 }

    /**
     * A group's members and who looks after its keys (its first admin): set by the backend. The keeper gives the
     * group a new key when the members holding one are not its members (someone left or joined by link).
     */
    var groupMembers: suspend (String) -> Pair<List<String>, String?>? = { null }
    var shareHistoryOf: suspend (String) -> Boolean = { false }

    // ------------------------------------------------------------------ signing in and out

    /** Called when someone signs in (or out: null). */
    fun signedIn(newUid: String?) {
        if (newUid == uid) return
        watched.forEach { (chatId, l) -> root.child("chats").child(chatId).child("e2e").removeEventListener(l) }
        watched.clear()
        versions.clear()
        keys.clear()
        pair = null
        uid = newUid
        ready.value = false
        needsRestore.value = false
        hasBackup.value = false
        hasOwnKey.value = false
        publishedWatch?.let { (ref, l) -> ref.removeEventListener(l) }
        publishedWatch = null
        hasPin.value = false
        keyChanged.value = emptySet()
        if (newUid == null) return
        hasPin.value = prefs.contains("pin_$newUid")
        keyChanged.value = prefs.getStringSet("changed_$newUid", emptySet()).orEmpty()
        scope.launch { loadIdentity(newUid) }
    }

    /** This device's stored key pair, if it has one. */
    private fun stored(u: String): KeyPair? {
        val local = prefs.getString("id_$u", null)?.let { DeviceLock.open(it) } ?: return null
        val pub = prefs.getString("pub_$u", null) ?: return null
        return runCatching { KeyPair(E2eCrypto.publicFrom(E2eCrypto.unb64(pub)), E2eCrypto.privateFrom(local)) }.getOrNull()
    }

    /** The account's published key (null: none); failing (no connection) it is asked again, never guessed. */
    private suspend fun publishedKey(u: String): String? {
        var wait = 2_000L
        while (uid == u) {
            val r = runCatching { root.child("publicKeys").child(u).child("k").get().await() }
            r.getOrNull()?.let { return it.getValue(String::class.java) }
            kotlinx.coroutines.delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
        error("signed out")
    }

    private suspend fun loadIdentity(u: String) {
        val mine = stored(u)
        val published = runCatching { publishedKey(u) }.getOrElse { return }
        if (uid != u) return
        // The account's key is this device's (or there is none yet): used, and published when there is none.
        if (mine != null && (published == null || published == E2eCrypto.b64(mine.public.encoded))) {
            pair = mine
            ready.value = true
            if (published == null) publish(u)
            syncPinFlag(u)
            followPublished(u)
            bump()
            return
        }
        // The account has a key and it is not here: one device holds it for all of them. It comes over from that
        // device (a QR) or from the backup (the PIN); a key of this device's own would push the others' out.
        if (published != null) {
            val backup = runCatching { root.child("keyBackup").child(u).get().await() }.getOrNull()
            val publishedFp = runCatching { E2eCrypto.fingerprint(E2eCrypto.unb64(published)) }.getOrNull()
            hasBackup.value = backup?.exists() == true && backup.child("fp").getValue(String::class.java) == publishedFp
            if (hasBackup.value) hasPin.value = true
            hasOwnKey.value = mine != null
            needsRestore.value = true
            return
        }
        makeNew(u)
    }

    private var publishedWatch: Pair<DatabaseReference, ValueEventListener>? = null

    /** Another device made the account's key another one (started over): this one steps back and asks to be linked. */
    private fun followPublished(u: String) {
        if (publishedWatch != null) return
        val ref = root.child("publicKeys").child(u).child("k")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val k = s.getValue(String::class.java) ?: return
                val p = pair ?: return
                if (uid != u || k == E2eCrypto.b64(p.public.encoded)) return
                Log.i(TAG, "the account's key changed on another device")
                pair = null
                keys.clear()
                ready.value = false
                ref.removeEventListener(this)
                publishedWatch = null
                scope.launch { loadIdentity(u) }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        publishedWatch = ref to l
        ref.addValueEventListener(l)
    }

    private suspend fun makeNew(u: String, force: Boolean = false) {
        val p = E2eCrypto.newPair()
        keep(u, p)
        pair = p
        needsRestore.value = false
        hasOwnKey.value = false
        ready.value = true
        publish(u, force)
        followPublished(u)
        bump()
    }

    /** The key [p] is the account's on this device now (it came from the backup or the other device). */
    private fun adopt(u: String, p: KeyPair) {
        keep(u, p)
        pair = p
        prefs.edit().remove("fails_$u").remove("waitUntil_$u").apply()
        needsRestore.value = false
        hasOwnKey.value = false
        ready.value = true
        keys.clear()
        versions.keys.toList().forEach { chatId -> versions[chatId]?.takeIf { it > 0 }?.let { v -> scope.launch { loadKey(chatId, v) } } }
        followPublished(u)
        scope.launch { syncPinFlag(u) }
        bump()
    }

    // ------------------------------------------------------------------ linking another device of the account

    override suspend fun awaitLink(onCode: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val u = uid ?: return@withContext false
        val eph = E2eCrypto.newPair()
        val code = E2eCrypto.hex(E2eCrypto.bytes(8))
        val ref = root.child("keyLink").child(u).child(code)
        withContext(Dispatchers.Main) { onCode("nuvelo-link:1:$u:$code:" + E2eCrypto.b64(eph.public.encoded)) }
        try {
            val until = System.currentTimeMillis() + 10 * 60_000L
            while (System.currentTimeMillis() < until && uid == u) {
                val s = runCatching { ref.get().await() }.getOrNull()
                val epk = s?.child("epk")?.getValue(String::class.java)
                val c = s?.child("c")?.getValue(String::class.java)
                if (epk != null && c != null) {
                    val private = E2eCrypto.privateFrom(E2eCrypto.unwrap(eph, epk, c, "link/$u/$code"))
                    // The key that came is the account's published one: its public half, and a lock opened with it.
                    val published = publishedKey(u) ?: error("no published key")
                    val public = E2eCrypto.publicFrom(E2eCrypto.unb64(published))
                    val p = KeyPair(public, private)
                    val probe = E2eCrypto.bytes(16)
                    val w = E2eCrypto.wrap(public, probe, "probe")
                    check(E2eCrypto.unwrap(p, w.getString("epk"), w.getString("c"), "probe").contentEquals(probe)) { "not the account's key" }
                    adopt(u, p)
                    return@withContext true
                }
                kotlinx.coroutines.delay(1_500)
            }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "link: ${e.message}")
            false
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { ref.removeValue().await() } }
        }
    }

    override suspend fun sendLink(scanned: String): LinkResult = withContext(Dispatchers.IO) {
        val parts = scanned.trim().split(":")
        if (parts.size != 5 || parts[0] != "nuvelo-link" || parts[1] != "1") return@withContext LinkResult.NotALink
        val u = uid ?: return@withContext LinkResult.Failed
        if (parts[2] != u) return@withContext LinkResult.OtherAccount
        val p = pair ?: return@withContext LinkResult.NoKey
        val code = parts[3]
        if (code.length != 16 || !code.all { it in '0'..'9' || it in 'a'..'f' }) return@withContext LinkResult.NotALink
        // Only the account's key goes (this device must hold the published one).
        val published = runCatching { root.child("publicKeys").child(u).child("k").get().await().getValue(String::class.java) }.getOrNull()
        if (published != E2eCrypto.b64(p.public.encoded)) return@withContext LinkResult.NoKey
        runCatching {
            val w = E2eCrypto.wrap(E2eCrypto.publicFrom(E2eCrypto.unb64(parts[4])), p.private.encoded, "link/$u/$code")
            root.child("keyLink").child(u).child(code).setValue(
                mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "at" to ServerValue.TIMESTAMP),
            ).await()
            LinkResult.Ok
        }.getOrElse {
            Log.w(TAG, "send link: ${it.message}")
            LinkResult.Failed
        }
    }

    override suspend fun useThisDevice(): Result<Unit> = runCatching {
        val u = uid ?: error("not signed in")
        val p = stored(u) ?: error("no key here")
        pair = p
        needsRestore.value = false
        hasOwnKey.value = false
        ready.value = true
        publish(u, force = true)
        followPublished(u)
        bump()
    }

    private fun keep(u: String, p: KeyPair) {
        prefs.edit()
            .putString("id_$u", DeviceLock.seal(p.private.encoded))
            .putString("pub_$u", E2eCrypto.b64(p.public.encoded))
            .apply()
    }

    private suspend fun publish(u: String, force: Boolean = false) {
        val p = pair ?: return
        val k = E2eCrypto.b64(p.public.encoded)
        val now = runCatching { root.child("publicKeys").child(u).child("k").get().await().getValue(String::class.java) }.getOrNull()
        // Never over another device's key unless asked (starting over, or this device's key chosen).
        if (now != k && (now == null || force)) {
            runCatching {
                root.child("publicKeys").child(u).setValue(
                    mapOf("k" to k, "fp" to E2eCrypto.fingerprint(p.public.encoded), "at" to ServerValue.TIMESTAMP),
                ).await()
            }.onFailure { Log.w(TAG, "publish: ${it.message}") }
        }
    }

    private suspend fun syncPinFlag(u: String) {
        if (prefs.contains("pin_$u")) return
        // Set on another phone: the PIN is checked with the vault the first time here.
        val on = runCatching { root.child("secretSpace").child(u).child("on").get().await().getValue(Boolean::class.java) }.getOrNull() == true
        hasPin.value = on
    }

    // ------------------------------------------------------------------ public keys

    private suspend fun publicKey(u: String, fresh: Boolean = false): Pair<String, PublicKey>? {
        if (!fresh) publicCache[u]?.let { return it }
        val k = runCatching { root.child("publicKeys").child(u).child("k").get().await().getValue(String::class.java) }.getOrNull() ?: return null
        val key = runCatching { E2eCrypto.publicFrom(E2eCrypto.unb64(k)) }.getOrNull() ?: return null
        val fp = E2eCrypto.fingerprint(key.encoded)
        val me = uid
        if (me != null && u != me) {
            // Their key changed since we last saw it: a new phone (or someone else): the conversation says so.
            val seen = prefs.getString("seen_${me}_$u", null)
            if (seen != null && seen != fp) {
                val set = keyChanged.value + u
                keyChanged.value = set
                prefs.edit().putStringSet("changed_$me", set).remove("verified_${me}_$u").apply()
            }
            prefs.edit().putString("seen_${me}_$u", fp).apply()
        }
        return (fp to key).also { publicCache[u] = it }
    }

    override suspend fun allHaveKeys(uids: List<String>): Boolean = uids.all { publicKey(it, fresh = true) != null }

    // ------------------------------------------------------------------ conversations

    override fun version(chatId: String): Int? = versions[chatId]?.takeIf { it > 0 }

    /** Known whether on or off (a send waits for this). */
    suspend fun versionNow(chatId: String): Int? {
        versions[chatId]?.let { return it.takeIf { v -> v > 0 } }
        val v = runCatching { root.child("chats").child(chatId).child("e2e").child("v").get().await().getValue(Int::class.java) }.getOrNull() ?: 0
        versions[chatId] = v
        watch(chatId)
        if (v > 0) loadKey(chatId, v)
        return v.takeIf { it > 0 }
    }

    override fun watch(chatId: String) {
        if (uid == null || watched.containsKey(chatId)) return
        val ref = root.child("chats").child(chatId).child("e2e")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val v = s.child("v").getValue(Int::class.java) ?: 0
                val before = versions.put(chatId, v)
                if (v > 0) scope.launch { loadKey(chatId, v); repairWraps(chatId, v) }
                if (before != v) bump()
            }

            override fun onCancelled(error: DatabaseError) {
                versions.putIfAbsent(chatId, 0)
            }
        }
        watched[chatId] = l
        ref.keepSynced(true)
        ref.addValueEventListener(l)
    }

    /** The conversation's key [version] on this phone (unlocked from what is kept for this person). */
    fun keyFor(chatId: String, version: Int): ByteArray? {
        keys["$chatId/$version"]?.let { return it }
        scope.launch { loadKey(chatId, version) }
        return null
    }

    private val loading = ConcurrentHashMap.newKeySet<String>()

    suspend fun loadKey(chatId: String, version: Int): ByteArray? {
        val id = "$chatId/$version"
        keys[id]?.let { return it }
        val me = uid ?: return null
        val p = pair ?: return null
        if (!loading.add(id)) {
            // Being fetched already: waited for (a send, or a notification, needs it now).
            repeat(60) {
                kotlinx.coroutines.delay(100)
                keys[id]?.let { return it }
                if (id !in loading) return keys[id]
            }
            return null
        }
        try {
            val ref = root.child("chatKeys").child(chatId).child(me).child(version.toString())
            ref.keepSynced(true)
            val s = runCatching { ref.get().await() }.getOrNull() ?: return null
            val epk = s.child("epk").getValue(String::class.java) ?: return null
            val c = s.child("c").getValue(String::class.java) ?: return null
            val key = runCatching { E2eCrypto.unwrap(p, epk, c, "$chatId/$me/$version") }.getOrNull() ?: return null
            keys[id] = key
            bump()
            return key
        } finally {
            loading.remove(id)
        }
    }

    override suspend fun enable(chatId: String, members: List<String>, shareHistoryWith: List<String>): Result<Unit> = runCatching {
        val me = uid ?: error("not signed in")
        check(pair != null) { "no key" }
        val everyone = (members + me).distinct()
        val publics = everyone.associateWith { u -> publicKey(u, fresh = true) ?: error("nokey:$u") }
        val current = runCatching { root.child("chats").child(chatId).child("e2e").child("v").get().await().getValue(Int::class.java) }.getOrNull() ?: 0
        val next = current + 1
        val key = E2eCrypto.bytes(32)
        val updates = HashMap<String, Any?>()
        publics.forEach { (u, pk) ->
            val w = E2eCrypto.wrap(pk.second, key, "$chatId/$u/$next")
            updates["chatKeys/$chatId/$u/$next"] = mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "fp" to pk.first)
        }
        // The new members may read what came before (the group chose to share its history).
        for (u in shareHistoryWith) {
            val pk = publics[u] ?: continue
            for (v in 1..current) {
                val old = loadKey(chatId, v) ?: continue
                val w = E2eCrypto.wrap(pk.second, old, "$chatId/$u/$v")
                updates["chatKeys/$chatId/$u/$v"] = mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "fp" to pk.first)
            }
        }
        updates["chats/$chatId/e2e"] = mapOf("v" to next, "by" to me, "at" to ServerValue.TIMESTAMP)
        root.updateChildren(updates).await()
        keys["$chatId/$next"] = key
        versions[chatId] = next
        watch(chatId)
        bump()
    }

    /** A member's key changed (a new phone): the conversation's key is locked again for their new one. */
    private suspend fun repairWraps(chatId: String, version: Int) {
        val me = uid ?: return
        val key = loadKey(chatId, version) ?: return
        val s = runCatching { root.child("chatKeys").child(chatId).get().await() }.getOrNull() ?: return
        if (chatId.startsWith("grp")) {
            val (members, keeper) = groupMembers(chatId) ?: return
            if (keeper == me) {
                val holders = s.children.filter { it.child(version.toString()).exists() }.mapNotNull { it.key }.toSet()
                val withKeys = members.filter { publicKey(it, fresh = true) != null }
                val stale = holders.any { it !in members }
                val missing = withKeys.filter { it !in holders }
                if (stale || missing.isNotEmpty()) {
                    enable(chatId, withKeys, if (shareHistoryOf(chatId)) missing else emptyList())
                    return
                }
            }
        }
        val updates = HashMap<String, Any?>()
        for (member in s.children) {
            val u = member.key ?: continue
            if (u == me) continue
            val entry = member.child(version.toString())
            if (!entry.exists()) continue
            val pk = publicKey(u, fresh = true) ?: continue
            if (entry.child("fp").getValue(String::class.java) == pk.first) continue
            val w = E2eCrypto.wrap(pk.second, key, "$chatId/$u/$version")
            updates["chatKeys/$chatId/$u/$version"] = mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "fp" to pk.first)
        }
        if (updates.isNotEmpty()) runCatching { root.updateChildren(updates).await() }
    }

    // ------------------------------------------------------------------ sealing what goes out

    /** "e2e:<version>:<sealed>": text sealed with the conversation's key, bound to it. */
    fun sealText(chatId: String, version: Int, plain: String): String? {
        val key = keys["$chatId/$version"] ?: return null
        return "e2e:$version:" + E2eCrypto.b64(E2eCrypto.seal(key, plain.toByteArray(), chatId.toByteArray()))
    }

    /** The plain text of a sealed one; null when it is not sealed, [E2e.UNREADABLE] without its key. */
    fun openText(chatId: String, sealed: String): String? {
        if (!sealed.startsWith("e2e:")) return null
        val version = sealed.substringAfter("e2e:").substringBefore(':').toIntOrNull() ?: return E2e.UNREADABLE
        val key = keyFor(chatId, version) ?: return E2e.UNREADABLE
        return runCatching { String(E2eCrypto.open(key, E2eCrypto.unb64(sealed.substringAfter("e2e:$version:")), chatId.toByteArray())) }
            .getOrDefault(E2e.UNREADABLE)
    }

    // ------------------------------------------------------------------ the safety number

    private fun numberHalf(u: String, key: ByteArray): String {
        var h = MessageDigest.getInstance("SHA-512").run {
            update(byteArrayOf(0, 0))
            update(key)
            update(u.toByteArray())
            digest()
        }
        repeat(5200) { h = MessageDigest.getInstance("SHA-512").run { update(h); update(key); digest() } }
        return (0 until 6).joinToString("") { i ->
            val n = ((h[i * 5].toLong() and 0xff) shl 32) or ((h[i * 5 + 1].toLong() and 0xff) shl 24) or
                ((h[i * 5 + 2].toLong() and 0xff) shl 16) or ((h[i * 5 + 3].toLong() and 0xff) shl 8) or (h[i * 5 + 4].toLong() and 0xff)
            (n % 100000).toString().padStart(5, '0')
        }
    }

    override suspend fun safetyNumber(otherUid: String): SafetyNumber? = withContext(Dispatchers.Default) {
        val me = uid ?: return@withContext null
        val mine = pair?.public?.encoded ?: return@withContext null
        val theirs = publicKey(otherUid, fresh = true)?.second?.encoded ?: return@withContext null
        val a = numberHalf(me, mine)
        val b = numberHalf(otherUid, theirs)
        val digits = if (me < otherUid) a + b else b + a
        val fpMine = E2eCrypto.fingerprint(mine)
        val fpTheirs = E2eCrypto.fingerprint(theirs)
        val qr = "nuvelo-sn:1:" + listOf(me to fpMine, otherUid to fpTheirs).sortedBy { it.first }.joinToString(":") { "${it.first}:${it.second}" }
        SafetyNumber(digits, qr, prefs.getString("verified_${me}_$otherUid", null) == fpTheirs)
    }

    override fun markVerified(otherUid: String) {
        val me = uid ?: return
        val fp = publicCache[otherUid]?.first ?: return
        prefs.edit().putString("verified_${me}_$otherUid", fp).apply()
        dismissKeyChange(otherUid)
        bump()
    }

    override suspend fun checkScanned(otherUid: String, scanned: String): Boolean {
        val mine = safetyNumber(otherUid) ?: return false
        val ok = scanned.trim() == mine.qr
        if (ok) markVerified(otherUid)
        return ok
    }

    override fun dismissKeyChange(uid: String) {
        val me = this.uid ?: return
        val set = keyChanged.value - uid
        keyChanged.value = set
        prefs.edit().putStringSet("changed_$me", set).apply()
    }

    // ------------------------------------------------------------------ the PIN, the backup and the vault

    private suspend fun idToken(): String = auth.currentUser?.getIdToken(false)?.await()?.token ?: error("not signed in")

    private suspend fun vault(action: String, proof: String?): Pair<Int, JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { if (proof != null) put("proof", proof) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${MediaUpload.BASE}/media/vault/$action")
            .header("Authorization", "Bearer ${idToken()}")
            .post(body)
            .build()
        MediaUpload.client.newCall(request).execute().use { r ->
            r.code to runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
        }
    }

    private class Derived(val lockPart: ByteArray, val proof: String)

    private fun derive(pin: String, salt: ByteArray, rounds: Int): Derived {
        val out = E2eCrypto.pbkdf2(pin, salt, rounds, 64)
        return Derived(out.copyOfRange(0, 32), E2eCrypto.hex(out.copyOfRange(32, 64)))
    }

    /** The PIN kept on the phone for the lock: a slow hash only (checked here, without the network). */
    private fun keepLocalPin(u: String, pin: String, slot: String = "pin") {
        val salt = E2eCrypto.bytes(16)
        val hash = E2eCrypto.pbkdf2(pin, salt, LOCAL_ROUNDS, 32)
        prefs.edit().putString("${slot}_$u", E2eCrypto.b64(salt) + ":" + E2eCrypto.b64(hash)).apply()
    }

    private fun matchesLocal(u: String, pin: String, slot: String = "pin"): Boolean? {
        val kept = prefs.getString("${slot}_$u", null) ?: return null
        val salt = E2eCrypto.unb64(kept.substringBefore(':'))
        val hash = E2eCrypto.unb64(kept.substringAfter(':'))
        return MessageDigest.isEqual(E2eCrypto.pbkdf2(pin, salt, LOCAL_ROUNDS, 32), hash)
    }

    /** A decoy PIN: opens an empty secret space (null removes it). */
    override fun setDecoyPin(pin: String?) {
        val u = uid ?: return
        if (pin == null) prefs.edit().remove("decoy_$u").apply() else keepLocalPin(u, pin, "decoy")
    }

    override fun hasDecoy(): Boolean = uid?.let { prefs.contains("decoy_$it") } == true

    override suspend fun setPin(pin: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            require(pin.length == 6 && pin.all { it.isDigit() }) { "pin" }
            val u = uid ?: error("not signed in")
            val p = pair ?: error("no key")
            val salt = E2eCrypto.bytes(16)
            val d = derive(pin, salt, BACKUP_ROUNDS)
            val (code, json) = vault("store", d.proof)
            check(code == 200) { "vault $code" }
            val secret = hexBytes(json.getString("secret"))
            val lock = E2eCrypto.hkdf(d.lockPart + secret, salt, "nuvelo-backup")
            val sealed = E2eCrypto.seal(lock, p.private.encoded, u.toByteArray())
            root.updateChildren(
                mapOf(
                    "keyBackup/$u" to mapOf(
                        "salt" to E2eCrypto.b64(salt),
                        "rounds" to BACKUP_ROUNDS,
                        "c" to E2eCrypto.b64(sealed),
                        "fp" to E2eCrypto.fingerprint(p.public.encoded),
                        "at" to ServerValue.TIMESTAMP,
                    ),
                    "secretSpace/$u" to mapOf("on" to true, "at" to ServerValue.TIMESTAMP),
                ),
            ).await()
            keepLocalPin(u, pin)
            prefs.edit().remove("fails_$u").remove("waitUntil_$u").apply()
            hasPin.value = true
        }
    }

    private fun hexBytes(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** Wrong tries on the phone: 5 → 1 min, 10 → 5 min, 15+ → 30 min. */
    private fun waitLeft(u: String): Long = (prefs.getLong("waitUntil_$u", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun wrongTry(u: String): PinResult {
        val fails = prefs.getInt("fails_$u", 0) + 1
        val wait = when {
            fails >= 15 -> 30 * 60_000L
            fails >= 10 && fails % 5 == 0 -> 5 * 60_000L
            fails % 5 == 0 -> 60_000L
            else -> 0L
        }
        prefs.edit().putInt("fails_$u", fails).putLong("waitUntil_$u", if (wait > 0) System.currentTimeMillis() + wait else 0L).apply()
        return if (wait > 0) PinResult.Wait(wait) else PinResult.Wrong(5 - fails % 5)
    }

    override suspend fun checkPin(pin: String): PinResult = withContext(Dispatchers.Default) {
        val u = uid ?: return@withContext PinResult.Failed("signin")
        waitLeft(u).takeIf { it > 0 }?.let { return@withContext PinResult.Wait(it) }
        if (matchesLocal(u, pin, "decoy") == true) return@withContext PinResult.Decoy
        when (matchesLocal(u, pin)) {
            true -> {
                prefs.edit().remove("fails_$u").apply()
                PinResult.Ok
            }
            false -> wrongTry(u)
            // Set on another phone: the vault says whether it is right (and the key comes back with it).
            null -> restore(pin)
        }
    }

    override suspend fun restore(pin: String): PinResult = withContext(Dispatchers.Default) {
        val u = uid ?: return@withContext PinResult.Failed("signin")
        runCatching {
            val backup = root.child("keyBackup").child(u).get().await()
            if (!backup.exists()) return@withContext PinResult.Failed("nobackup")
            val salt = E2eCrypto.unb64(backup.child("salt").getValue(String::class.java) ?: error("salt"))
            val rounds = backup.child("rounds").getValue(Int::class.java) ?: BACKUP_ROUNDS
            val d = derive(pin, salt, rounds)
            val (code, json) = vault("open", d.proof)
            when (code) {
                200 -> {}
                403 -> return@withContext PinResult.Wrong(json.optInt("left", 0))
                410 -> return@withContext PinResult.Gone
                404 -> return@withContext PinResult.Failed("nobackup")
                else -> return@withContext PinResult.Failed("vault $code")
            }
            val secret = hexBytes(json.getString("secret"))
            val lock = E2eCrypto.hkdf(d.lockPart + secret, salt, "nuvelo-backup")
            val privateBytes = E2eCrypto.open(lock, E2eCrypto.unb64(backup.child("c").getValue(String::class.java) ?: error("c")), u.toByteArray())
            val published = root.child("publicKeys").child(u).child("k").get().await().getValue(String::class.java)
            // This phone already has the key in use (the published one): the PIN was right, so it is kept for the
            // lock here; a backup of an older key is made again with this phone's key.
            val current = pair
            if (current != null && published == E2eCrypto.b64(current.public.encoded)) {
                if (E2eCrypto.fingerprint(current.public.encoded) == backup.child("fp").getValue(String::class.java)) {
                    keepLocalPin(u, pin)
                    prefs.edit().remove("fails_$u").remove("waitUntil_$u").apply()
                    hasPin.value = true
                    return@withContext PinResult.Ok
                }
                return@withContext if (setPin(pin).isSuccess) PinResult.Ok else PinResult.Failed("backup")
            }
            val private = E2eCrypto.privateFrom(privateBytes)
            // The public half is the one published (it belongs to this private key: the backup's fingerprint says so).
            val public = published?.let { E2eCrypto.publicFrom(E2eCrypto.unb64(it)) }
                ?.takeIf { E2eCrypto.fingerprint(it.encoded) == backup.child("fp").getValue(String::class.java) }
                ?: error("public")
            keepLocalPin(u, pin)
            hasPin.value = true
            adopt(u, KeyPair(public, private))
            PinResult.Ok
        }.getOrElse { PinResult.Failed(it.message ?: "failed") }
    }

    override suspend fun startOver(): Result<Unit> = runCatching {
        val u = uid ?: error("not signed in")
        runCatching { vault("forget", null) }
        root.updateChildren(mapOf("keyBackup/$u" to null, "secretSpace/$u" to null)).await()
        prefs.edit().remove("pin_$u").remove("decoy_$u").remove("fails_$u").remove("waitUntil_$u").apply()
        hasPin.value = false
        keys.clear()
        // Chosen: the new key is the account's (its other devices are linked again).
        makeNew(u, force = true)
    }

    override suspend fun clearPin(): Result<Unit> = runCatching {
        val u = uid ?: error("not signed in")
        runCatching { vault("forget", null) }
        root.updateChildren(mapOf("keyBackup/$u" to null, "secretSpace/$u" to null)).await()
        prefs.edit().remove("pin_$u").remove("decoy_$u").remove("fails_$u").remove("waitUntil_$u").apply()
        hasPin.value = false
    }

    // ------------------------------------------------------------------ media

    /** A file sealed with its own key (the key goes inside the encrypted message). */
    fun sealMedia(plain: ByteArray): Pair<ByteArray, String> {
        val key = E2eCrypto.bytes(32)
        return E2eCrypto.seal(key, plain) to E2eCrypto.b64(key)
    }

    fun openMedia(sealed: ByteArray, key: String): ByteArray? = runCatching { E2eCrypto.open(E2eCrypto.unb64(key), sealed) }.getOrNull()

    /**
     * The content of a message, sealed: everything but who sent it, when, and what the servers need to keep it
     * (expireAt, the system marks).
     */
    fun sealContent(chatId: String, version: Int, content: Map<String, Any?>): String? = sealText(chatId, version, toJson(content).toString())

    fun openContent(chatId: String, sealed: String): Map<String, Any?>? {
        val text = openText(chatId, sealed) ?: return null
        if (text == E2e.UNREADABLE) return null
        return runCatching { fromJson(JSONObject(text)) }.getOrNull()
    }

    companion object {
        private const val TAG = "NuveloE2e"
        const val BACKUP_ROUNDS = 150_000
        const val LOCAL_ROUNDS = 60_000

        @Volatile var instance: E2eEngine? = null

        fun toJson(map: Map<String, Any?>): JSONObject = JSONObject().apply {
            map.forEach { (k, v) -> if (v != null) put(k, toJsonValue(v)) }
        }

        private fun toJsonValue(v: Any?): Any? = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> if (x != null) put(k.toString(), toJsonValue(x)) } }
            is List<*> -> JSONArray().apply { v.forEach { put(toJsonValue(it)) } }
            is Array<*> -> JSONArray().apply { v.forEach { put(toJsonValue(it)) } }
            else -> v
        }

        fun fromJson(o: JSONObject): Map<String, Any?> = buildMap {
            o.keys().forEach { k -> put(k, fromJsonValue(o.get(k))) }
        }

        private fun fromJsonValue(v: Any?): Any? = when (v) {
            JSONObject.NULL -> null
            is JSONObject -> fromJson(v)
            is JSONArray -> (0 until v.length()).map { fromJsonValue(v.get(it)) }
            // Numbers always as Long or Double, whichever JSON parser read them.
            is Int -> v.toLong()
            is Short -> v.toLong()
            is java.math.BigInteger -> v.toLong()
            is java.math.BigDecimal -> if (v.scale() <= 0) v.toLong() else v.toDouble()
            is Float -> v.toDouble()
            else -> v
        }
    }
}
