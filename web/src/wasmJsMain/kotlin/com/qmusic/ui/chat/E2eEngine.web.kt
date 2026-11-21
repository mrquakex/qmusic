package com.qmusic.ui.chat

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.nuvelo.web.api
import com.nuvelo.web.deviceOpen
import com.nuvelo.web.deviceSeal
import com.nuvelo.web.ecNew
import com.nuvelo.web.ecPublicOf
import com.nuvelo.web.ecValid
import com.nuvelo.web.ecdh
import com.nuvelo.web.gcmOpen
import com.nuvelo.web.gcmSeal
import com.nuvelo.web.hmac256
import com.nuvelo.web.randomB64
import com.nuvelo.web.safetyHalf
import com.nuvelo.web.sha256B64
import com.nuvelo.web.storeGet
import com.nuvelo.web.storeSet
import com.nuvelo.web.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The web's end-to-end encryption: the phone's E2eEngine (E2eEngine.android.kt) with the same keys, formats and
// steps, its cryptography from the browser (web/bridge: P-256, AES-256-GCM, SHA-2). A browser joins an account's
// encryption as one more device: the account's key comes over from the phone (a QR) or from the PIN backup.

// ==================================================================== the building blocks

/** A P-256 public key: [encoded] is X.509 (as Java gives it, and as publicKeys keeps it); [raw] the 65-byte point. */
class EcPublic(val raw: ByteArray) {
    val encoded: ByteArray get() = X509_PREFIX + raw

    companion object {
        val X509_PREFIX = hexOf("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    }
}

/** A P-256 private key: [d] the 32-byte number; [encoded] PKCS#8 (as Java reads and writes it). */
class EcPrivate(val d: ByteArray, private val pub: ByteArray) {
    val encoded: ByteArray
        get() = hexOf("308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420") + d + hexOf("a144034200") + pub
}

class KeyPair(val public: EcPublic, val private: EcPrivate)

internal fun hexOf(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

/** The wrapped secret: the one-time public key and the sealed bytes (base64), as the JSON the phone writes. */
class Wrapped(private val epk: String, private val c: String) {
    fun getString(name: String): String = if (name == "epk") epk else c
}

internal object E2eCrypto {
    fun bytes(n: Int) = unb64(randomB64(n))

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun b64(b: ByteArray): String = kotlin.io.encoding.Base64.encode(b)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun unb64(s: String): ByteArray = kotlin.io.encoding.Base64.decode(s)

    fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun sha256(vararg parts: ByteArray): ByteArray = unb64(sha256B64(b64(parts.fold(ByteArray(0)) { a, p -> a + p })))

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = bytes(12)
        return nonce + unb64(gcmSeal(b64(key), b64(nonce), b64(plain), aad?.let { b64(it) }))
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sealed.size > 28) { "short" }
        return unb64(gcmOpen(b64(key), b64(sealed.copyOfRange(0, 12)), b64(sealed.copyOfRange(12, sealed.size)), aad?.let { b64(it) }))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = unb64(hmac256(b64(if (key.isEmpty()) ByteArray(32) else key), b64(data)))

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t + info.encodeToByteArray() + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            i++
        }
        return out
    }

    /** PBKDF2-HMAC-SHA256, by the browser. */
    suspend fun pbkdf2(password: String, salt: ByteArray, rounds: Int, length: Int): ByteArray =
        unb64(com.nuvelo.web.pbkdf2(password, b64(salt), rounds, length).text())

    fun newPair(): KeyPair {
        val o = Json.parseToJsonElement(ecNew()).jsonObject
        val pub = unb64(o["pub"]!!.jsonPrimitive.content)
        return KeyPair(EcPublic(pub), EcPrivate(unb64(o["priv"]!!.jsonPrimitive.content), pub))
    }

    fun publicFrom(b: ByteArray): EcPublic {
        val raw = if (b.size == 91) b.copyOfRange(26, 91) else if (b.size == 65) b else error("public key")
        require(ecValid(b64(raw))) { "public key" }
        return EcPublic(raw)
    }

    /** The private number out of a PKCS#8 key (as Java writes it: the ECPrivateKey's octet string). */
    fun privateFrom(b: ByteArray): EcPrivate {
        var i = 0
        while (i + 4 < b.size) {
            if (b[i] == 0x02.toByte() && b[i + 1] == 0x01.toByte() && b[i + 2] == 0x01.toByte() && b[i + 3] == 0x04.toByte()) {
                val len = b[i + 4].toInt() and 0xff
                val v = b.copyOfRange(i + 5, i + 5 + len)
                val d = if (v.size >= 32) v.copyOfRange(v.size - 32, v.size) else ByteArray(32 - v.size) + v
                return EcPrivate(d, unb64(ecPublicOf(b64(d))))
            }
            i++
        }
        error("private key")
    }

    private fun ecdhOf(private: EcPrivate, public: EcPublic): ByteArray = unb64(ecdh(b64(private.d), b64(public.raw)))

    /** Locks [secret] for the holder of [recipient] (ECIES: a one-time key pair, ECDH, HKDF, AES-GCM). */
    fun wrap(recipient: EcPublic, secret: ByteArray, context: String): Wrapped {
        val eph = newPair()
        val shared = ecdhOf(eph.private, recipient)
        val key = hkdf(shared, eph.public.encoded + recipient.encoded, "nuvelo-wrap:$context")
        return Wrapped(b64(eph.public.encoded), b64(seal(key, secret, context.encodeToByteArray())))
    }

    fun unwrap(mine: KeyPair, epk: String, sealed: String, context: String): ByteArray {
        val eph = publicFrom(unb64(epk))
        val shared = ecdhOf(mine.private, eph)
        val key = hkdf(shared, eph.encoded + mine.public.encoded, "nuvelo-wrap:$context")
        return open(key, unb64(sealed), context.encodeToByteArray())
    }

    fun fingerprint(public: ByteArray) = hex(sha256(public)).take(32)
}

/** This browser's own lock (a key kept where it cannot be read out) over what it keeps of the encryption keys. */
private object DeviceLock {
    suspend fun seal(plain: ByteArray): String = deviceSeal(E2eCrypto.b64(plain)).text()

    suspend fun open(sealed: String): ByteArray? = runCatching { deviceOpen(sealed).await<kotlin.js.JsString?>()?.toString()?.let { E2eCrypto.unb64(it) } }.getOrNull()
}

/** What the phone keeps in its preferences, kept in the browser's storage. */
private object Prefs {
    private fun k(key: String) = "nuvelo.e2e.$key"
    fun getString(key: String): String? = storeGet(k(key))
    fun putString(key: String, value: String?) = storeSet(k(key), value)
    fun contains(key: String) = getString(key) != null
    fun remove(vararg keys: String) = keys.forEach { putString(it, null) }
    fun getInt(key: String) = getString(key)?.toIntOrNull() ?: 0
    fun getLong(key: String) = getString(key)?.toLongOrNull() ?: 0L
    fun getSet(key: String): Set<String> = getString(key)?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    fun putSet(key: String, value: Set<String>) = putString(key, value.joinToString("\n"))
}

// ==================================================================== the engine

internal class E2eEngine(private val root: DatabaseReference) : E2eApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> com.nuvelo.web.logJs("bg: " + e) })
    private val auth = FirebaseAuth.getInstance()

    override val ready = MutableStateFlow(false)
    override val needsRestore = MutableStateFlow(false)
    override val hasBackup = MutableStateFlow(false)
    override val hasOwnKey = MutableStateFlow(false)
    override val hasPin = MutableStateFlow(false)
    override val changes = MutableStateFlow(0)
    override val keyChanged = MutableStateFlow<Set<String>>(emptySet())

    private var pair: KeyPair? = null
    private var uid: String? = null

    private val versions = HashMap<String, Int>()
    private val keys = HashMap<String, ByteArray>()
    private val watched = HashMap<String, ValueEventListener>()
    private val publicCache = HashMap<String, Pair<String, EcPublic>>()

    private fun bump() = changes.update { it + 1 }

    var groupMembers: suspend (String) -> Pair<List<String>, String?>? = { null }
    var shareHistoryOf: suspend (String) -> Boolean = { false }

    // ------------------------------------------------------------------ signing in and out

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
        hasPin.value = Prefs.contains("pin_$newUid")
        keyChanged.value = Prefs.getSet("changed_$newUid")
        scope.launch { loadIdentity(newUid) }
    }

    private suspend fun stored(u: String): KeyPair? {
        val local = Prefs.getString("id_$u")?.let { DeviceLock.open(it) } ?: return null
        val pub = Prefs.getString("pub_$u") ?: return null
        return runCatching {
            val p = E2eCrypto.publicFrom(E2eCrypto.unb64(pub))
            KeyPair(p, EcPrivate(local, p.raw))
        }.getOrNull()
    }

    private suspend fun publishedKey(u: String): String? {
        var wait = 2_000L
        while (uid == u) {
            val r = runCatching { root.child("publicKeys").child(u).child("k").get().await() }
            r.getOrNull()?.let { return it.getValue(String::class) }
            kotlinx.coroutines.delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
        error("signed out")
    }

    private suspend fun loadIdentity(u: String) {
        val mine = stored(u)
        val published = runCatching { publishedKey(u) }.getOrElse { return }
        if (uid != u) return
        if (mine != null && (published == null || published == E2eCrypto.b64(mine.public.encoded))) {
            pair = mine
            ready.value = true
            if (published == null) publish(u)
            syncPinFlag(u)
            followPublished(u)
            bump()
            return
        }
        if (published != null) {
            val backup = runCatching { root.child("keyBackup").child(u).get().await() }.getOrNull()
            val publishedFp = runCatching { E2eCrypto.fingerprint(E2eCrypto.unb64(published)) }.getOrNull()
            hasBackup.value = backup?.exists() == true && backup.child("fp").getValue(String::class) == publishedFp
            if (hasBackup.value) hasPin.value = true
            hasOwnKey.value = mine != null
            needsRestore.value = true
            return
        }
        makeNew(u)
    }

    private var publishedWatch: Pair<DatabaseReference, ValueEventListener>? = null

    private fun followPublished(u: String) {
        if (publishedWatch != null) return
        val ref = root.child("publicKeys").child(u).child("k")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val k = snapshot.getValue(String::class) ?: return
                val p = pair ?: return
                if (uid != u || k == E2eCrypto.b64(p.public.encoded)) return
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

    private suspend fun adopt(u: String, p: KeyPair) {
        keep(u, p)
        pair = p
        Prefs.remove("fails_$u", "waitUntil_$u")
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

    override suspend fun awaitLink(onCode: (String) -> Unit): Boolean {
        val u = uid ?: return false
        val eph = E2eCrypto.newPair()
        val code = E2eCrypto.hex(E2eCrypto.bytes(8))
        val ref = root.child("keyLink").child(u).child(code)
        onCode("nuvelo-link:1:$u:$code:" + E2eCrypto.b64(eph.public.encoded))
        try {
            val until = System.currentTimeMillis() + 10 * 60_000L
            while (System.currentTimeMillis() < until && uid == u) {
                val s = runCatching { ref.get().await() }.getOrNull()
                val epk = s?.child("epk")?.getValue(String::class)
                val c = s?.child("c")?.getValue(String::class)
                if (epk != null && c != null) {
                    val private = E2eCrypto.privateFrom(E2eCrypto.unwrap(eph, epk, c, "link/$u/$code"))
                    val published = publishedKey(u) ?: error("no published key")
                    val public = E2eCrypto.publicFrom(E2eCrypto.unb64(published))
                    val p = KeyPair(public, EcPrivate(private.d, public.raw))
                    val probe = E2eCrypto.bytes(16)
                    val w = E2eCrypto.wrap(public, probe, "probe")
                    check(E2eCrypto.unwrap(p, w.getString("epk"), w.getString("c"), "probe").contentEquals(probe)) { "not the account's key" }
                    adopt(u, p)
                    return true
                }
                kotlinx.coroutines.delay(1_500)
            }
            return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { ref.removeValue().await() } }
        }
    }

    override suspend fun sendLink(scanned: String): LinkResult {
        val parts = scanned.trim().split(":")
        if (parts.size != 5 || parts[0] != "nuvelo-link" || parts[1] != "1") return LinkResult.NotALink
        val u = uid ?: return LinkResult.Failed
        if (parts[2] != u) return LinkResult.OtherAccount
        val p = pair ?: return LinkResult.NoKey
        val code = parts[3]
        if (code.length != 16 || !code.all { it in '0'..'9' || it in 'a'..'f' }) return LinkResult.NotALink
        val published = runCatching { root.child("publicKeys").child(u).child("k").get().await().getValue(String::class) }.getOrNull()
        if (published != E2eCrypto.b64(p.public.encoded)) return LinkResult.NoKey
        return runCatching {
            val w = E2eCrypto.wrap(E2eCrypto.publicFrom(E2eCrypto.unb64(parts[4])), p.private.encoded, "link/$u/$code")
            root.child("keyLink").child(u).child(code).setValue(mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "at" to ServerValue.TIMESTAMP)).await()
            LinkResult.Ok
        }.getOrElse { LinkResult.Failed }
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

    private suspend fun keep(u: String, p: KeyPair) {
        Prefs.putString("id_$u", DeviceLock.seal(p.private.d))
        Prefs.putString("pub_$u", E2eCrypto.b64(p.public.encoded))
    }

    private suspend fun publish(u: String, force: Boolean = false) {
        val p = pair ?: return
        val k = E2eCrypto.b64(p.public.encoded)
        val now = runCatching { root.child("publicKeys").child(u).child("k").get().await().getValue(String::class) }.getOrNull()
        if (now != k && (now == null || force)) {
            runCatching {
                root.child("publicKeys").child(u).setValue(mapOf("k" to k, "fp" to E2eCrypto.fingerprint(p.public.encoded), "at" to ServerValue.TIMESTAMP)).await()
            }
        }
    }

    private suspend fun syncPinFlag(u: String) {
        if (Prefs.contains("pin_$u")) return
        val on = runCatching { root.child("secretSpace").child(u).child("on").get().await().getValue(Boolean::class) }.getOrNull() == true
        hasPin.value = on
    }

    // ------------------------------------------------------------------ public keys

    private suspend fun publicKey(u: String, fresh: Boolean = false): Pair<String, EcPublic>? {
        if (!fresh) publicCache[u]?.let { return it }
        val k = runCatching { root.child("publicKeys").child(u).child("k").get().await().getValue(String::class) }.getOrNull() ?: return null
        val key = runCatching { E2eCrypto.publicFrom(E2eCrypto.unb64(k)) }.getOrNull() ?: return null
        val fp = E2eCrypto.fingerprint(key.encoded)
        val me = uid
        if (me != null && u != me) {
            val seen = Prefs.getString("seen_${me}_$u")
            if (seen != null && seen != fp) {
                val set = keyChanged.value + u
                keyChanged.value = set
                Prefs.putSet("changed_$me", set)
                Prefs.remove("verified_${me}_$u")
            }
            Prefs.putString("seen_${me}_$u", fp)
        }
        return (fp to key).also { publicCache[u] = it }
    }

    override suspend fun allHaveKeys(uids: List<String>): Boolean = uids.all { publicKey(it, fresh = true) != null }

    // ------------------------------------------------------------------ conversations

    override fun version(chatId: String): Int? = versions[chatId]?.takeIf { it > 0 }

    suspend fun versionNow(chatId: String): Int? {
        versions[chatId]?.let { return it.takeIf { v -> v > 0 } }
        val v = runCatching { root.child("chats").child(chatId).child("e2e").child("v").get().await().getValue(Int::class) }.getOrNull() ?: 0
        versions[chatId] = v
        watch(chatId)
        if (v > 0) loadKey(chatId, v)
        return v.takeIf { it > 0 }
    }

    override fun watch(chatId: String) {
        if (uid == null || watched.containsKey(chatId)) return
        val ref = root.child("chats").child(chatId).child("e2e")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val v = snapshot.child("v").getValue(Int::class) ?: 0
                val before = versions.put(chatId, v)
                if (v > 0) scope.launch { loadKey(chatId, v); repairWraps(chatId, v) }
                if (before != v) bump()
            }

            override fun onCancelled(error: DatabaseError) {
                if (chatId !in versions) versions[chatId] = 0
            }
        }
        watched[chatId] = l
        ref.addValueEventListener(l)
    }

    fun keyFor(chatId: String, version: Int): ByteArray? {
        keys["$chatId/$version"]?.let { return it }
        scope.launch { loadKey(chatId, version) }
        return null
    }

    private val loading = HashSet<String>()

    suspend fun loadKey(chatId: String, version: Int): ByteArray? {
        val id = "$chatId/$version"
        keys[id]?.let { return it }
        val me = uid ?: return null
        val p = pair ?: return null
        if (!loading.add(id)) {
            repeat(60) {
                kotlinx.coroutines.delay(100)
                keys[id]?.let { return it }
                if (id !in loading) return keys[id]
            }
            return null
        }
        try {
            val ref = root.child("chatKeys").child(chatId).child(me).child(version.toString())
            val s = runCatching { ref.get().await() }.getOrNull() ?: return null
            val epk = s.child("epk").getValue(String::class) ?: return null
            val c = s.child("c").getValue(String::class) ?: return null
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
        val current = runCatching { root.child("chats").child(chatId).child("e2e").child("v").get().await().getValue(Int::class) }.getOrNull() ?: 0
        val next = current + 1
        val key = E2eCrypto.bytes(32)
        val updates = HashMap<String, Any?>()
        publics.forEach { (u, pk) ->
            val w = E2eCrypto.wrap(pk.second, key, "$chatId/$u/$next")
            updates["chatKeys/$chatId/$u/$next"] = mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "fp" to pk.first)
        }
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
            if (entry.child("fp").getValue(String::class) == pk.first) continue
            val w = E2eCrypto.wrap(pk.second, key, "$chatId/$u/$version")
            updates["chatKeys/$chatId/$u/$version"] = mapOf("epk" to w.getString("epk"), "c" to w.getString("c"), "fp" to pk.first)
        }
        if (updates.isNotEmpty()) runCatching { root.updateChildren(updates).await() }
    }

    // ------------------------------------------------------------------ sealing what goes out

    fun sealText(chatId: String, version: Int, plain: String): String? {
        val key = keys["$chatId/$version"] ?: return null
        return "e2e:$version:" + E2eCrypto.b64(E2eCrypto.seal(key, plain.encodeToByteArray(), chatId.encodeToByteArray()))
    }

    fun openText(chatId: String, sealed: String): String? {
        if (!sealed.startsWith("e2e:")) return null
        val version = sealed.substringAfter("e2e:").substringBefore(':').toIntOrNull() ?: return E2e.UNREADABLE
        val key = keyFor(chatId, version) ?: return E2e.UNREADABLE
        return runCatching { E2eCrypto.open(key, E2eCrypto.unb64(sealed.substringAfter("e2e:$version:")), chatId.encodeToByteArray()).decodeToString() }
            .getOrDefault(E2e.UNREADABLE)
    }

    // ------------------------------------------------------------------ the safety number

    override suspend fun safetyNumber(otherUid: String): SafetyNumber? {
        val me = uid ?: return null
        val mine = pair?.public?.encoded ?: return null
        val theirs = publicKey(otherUid, fresh = true)?.second?.encoded ?: return null
        val a = safetyHalf(me, E2eCrypto.b64(mine))
        val b = safetyHalf(otherUid, E2eCrypto.b64(theirs))
        val digits = if (me < otherUid) a + b else b + a
        val fpMine = E2eCrypto.fingerprint(mine)
        val fpTheirs = E2eCrypto.fingerprint(theirs)
        val qr = "nuvelo-sn:1:" + listOf(me to fpMine, otherUid to fpTheirs).sortedBy { it.first }.joinToString(":") { "${it.first}:${it.second}" }
        return SafetyNumber(digits, qr, Prefs.getString("verified_${me}_$otherUid") == fpTheirs)
    }

    override fun markVerified(otherUid: String) {
        val me = uid ?: return
        val fp = publicCache[otherUid]?.first ?: return
        Prefs.putString("verified_${me}_$otherUid", fp)
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
        Prefs.putSet("changed_$me", set)
    }

    // ------------------------------------------------------------------ the PIN, the backup and the vault

    private suspend fun idToken(): String = auth.currentUser?.getIdToken(false)?.await()?.token ?: error("not signed in")

    private suspend fun vault(action: String, proof: String?): Pair<Int, JsonObject> {
        val body = if (proof != null) JsonObject(mapOf("proof" to JsonPrimitive(proof))).toString() else "{}"
        val r = Json.parseToJsonElement(api("POST", "${MediaUpload.BASE}/media/vault/$action", idToken(), body).text()).jsonObject
        val status = r["status"]!!.jsonPrimitive.intOrNull ?: 0
        val json = runCatching { Json.parseToJsonElement(r["body"]!!.jsonPrimitive.content).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return status to json
    }

    private class Derived(val lockPart: ByteArray, val proof: String)

    private suspend fun derive(pin: String, salt: ByteArray, rounds: Int): Derived {
        val out = E2eCrypto.pbkdf2(pin, salt, rounds, 64)
        return Derived(out.copyOfRange(0, 32), E2eCrypto.hex(out.copyOfRange(32, 64)))
    }

    private suspend fun keepLocalPin(u: String, pin: String, slot: String = "pin") {
        val salt = E2eCrypto.bytes(16)
        val hash = E2eCrypto.pbkdf2(pin, salt, LOCAL_ROUNDS, 32)
        Prefs.putString("${slot}_$u", E2eCrypto.b64(salt) + ":" + E2eCrypto.b64(hash))
    }

    private suspend fun matchesLocal(u: String, pin: String, slot: String = "pin"): Boolean? {
        val kept = Prefs.getString("${slot}_$u") ?: return null
        val salt = E2eCrypto.unb64(kept.substringBefore(':'))
        val hash = E2eCrypto.unb64(kept.substringAfter(':'))
        return E2eCrypto.pbkdf2(pin, salt, LOCAL_ROUNDS, 32).contentEquals(hash)
    }

    override fun setDecoyPin(pin: String?) {
        val u = uid ?: return
        if (pin == null) Prefs.remove("decoy_$u") else scope.launch { keepLocalPin(u, pin, "decoy") }
    }

    override fun hasDecoy(): Boolean = uid?.let { Prefs.contains("decoy_$it") } == true

    override suspend fun setPin(pin: String): Result<Unit> = runCatching {
        require(pin.length == 6 && pin.all { it.isDigit() }) { "pin" }
        val u = uid ?: error("not signed in")
        val p = pair ?: error("no key")
        val salt = E2eCrypto.bytes(16)
        val d = derive(pin, salt, BACKUP_ROUNDS)
        val (code, json) = vault("store", d.proof)
        check(code == 200) { "vault $code" }
        val secret = hexOf(json["secret"]!!.jsonPrimitive.content)
        val lock = E2eCrypto.hkdf(d.lockPart + secret, salt, "nuvelo-backup")
        val sealed = E2eCrypto.seal(lock, p.private.encoded, u.encodeToByteArray())
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
        Prefs.remove("fails_$u", "waitUntil_$u")
        hasPin.value = true
    }

    private fun waitLeft(u: String): Long = (Prefs.getLong("waitUntil_$u") - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun wrongTry(u: String): PinResult {
        val fails = Prefs.getInt("fails_$u") + 1
        val wait = when {
            fails >= 15 -> 30 * 60_000L
            fails >= 10 && fails % 5 == 0 -> 5 * 60_000L
            fails % 5 == 0 -> 60_000L
            else -> 0L
        }
        Prefs.putString("fails_$u", fails.toString())
        Prefs.putString("waitUntil_$u", (if (wait > 0) System.currentTimeMillis() + wait else 0L).toString())
        return if (wait > 0) PinResult.Wait(wait) else PinResult.Wrong(5 - fails % 5)
    }

    override suspend fun checkPin(pin: String): PinResult {
        val u = uid ?: return PinResult.Failed("signin")
        waitLeft(u).takeIf { it > 0 }?.let { return PinResult.Wait(it) }
        if (matchesLocal(u, pin, "decoy") == true) return PinResult.Decoy
        return when (matchesLocal(u, pin)) {
            true -> {
                Prefs.remove("fails_$u")
                PinResult.Ok
            }
            false -> wrongTry(u)
            null -> restore(pin)
        }
    }

    override suspend fun restore(pin: String): PinResult {
        val u = uid ?: return PinResult.Failed("signin")
        return runCatching {
            val backup = root.child("keyBackup").child(u).get().await()
            if (!backup.exists()) return PinResult.Failed("nobackup")
            val salt = E2eCrypto.unb64(backup.child("salt").getValue(String::class) ?: error("salt"))
            val rounds = backup.child("rounds").getValue(Int::class) ?: BACKUP_ROUNDS
            val d = derive(pin, salt, rounds)
            val (code, json) = vault("open", d.proof)
            when (code) {
                200 -> {}
                403 -> return PinResult.Wrong(json["left"]?.jsonPrimitive?.intOrNull ?: 0)
                410 -> return PinResult.Gone
                404 -> return PinResult.Failed("nobackup")
                else -> return PinResult.Failed("vault $code")
            }
            val secret = hexOf(json["secret"]!!.jsonPrimitive.content)
            val lock = E2eCrypto.hkdf(d.lockPart + secret, salt, "nuvelo-backup")
            val privateBytes = E2eCrypto.open(lock, E2eCrypto.unb64(backup.child("c").getValue(String::class) ?: error("c")), u.encodeToByteArray())
            val published = root.child("publicKeys").child(u).child("k").get().await().getValue(String::class)
            val current = pair
            if (current != null && published == E2eCrypto.b64(current.public.encoded)) {
                if (E2eCrypto.fingerprint(current.public.encoded) == backup.child("fp").getValue(String::class)) {
                    keepLocalPin(u, pin)
                    Prefs.remove("fails_$u", "waitUntil_$u")
                    hasPin.value = true
                    return PinResult.Ok
                }
                return if (setPin(pin).isSuccess) PinResult.Ok else PinResult.Failed("backup")
            }
            val private = E2eCrypto.privateFrom(privateBytes)
            val public = published?.let { E2eCrypto.publicFrom(E2eCrypto.unb64(it)) }
                ?.takeIf { E2eCrypto.fingerprint(it.encoded) == backup.child("fp").getValue(String::class) }
                ?: error("public")
            keepLocalPin(u, pin)
            hasPin.value = true
            adopt(u, KeyPair(public, EcPrivate(private.d, public.raw)))
            PinResult.Ok
        }.getOrElse { PinResult.Failed(it.message ?: "failed") }
    }

    override suspend fun startOver(): Result<Unit> = runCatching {
        val u = uid ?: error("not signed in")
        runCatching { vault("forget", null) }
        root.updateChildren(mapOf("keyBackup/$u" to null, "secretSpace/$u" to null)).await()
        Prefs.remove("pin_$u", "decoy_$u", "fails_$u", "waitUntil_$u")
        hasPin.value = false
        keys.clear()
        makeNew(u, force = true)
    }

    override suspend fun clearPin(): Result<Unit> = runCatching {
        val u = uid ?: error("not signed in")
        runCatching { vault("forget", null) }
        root.updateChildren(mapOf("keyBackup/$u" to null, "secretSpace/$u" to null)).await()
        Prefs.remove("pin_$u", "decoy_$u", "fails_$u", "waitUntil_$u")
        hasPin.value = false
    }

    // ------------------------------------------------------------------ content

    fun sealContent(chatId: String, version: Int, content: Map<String, Any?>): String? = sealText(chatId, version, toJson(content).toString())

    fun openContent(chatId: String, sealed: String): Map<String, Any?>? {
        val text = openText(chatId, sealed) ?: return null
        if (text == E2e.UNREADABLE) return null
        return runCatching { fromJson(Json.parseToJsonElement(text).jsonObject) }.getOrNull()
    }

    companion object {
        const val BACKUP_ROUNDS = 150_000
        const val LOCAL_ROUNDS = 60_000

        var instance: E2eEngine? = null

        /** As the phone's org.json writes it: nulls left out. */
        fun toJson(map: Map<String, Any?>): JsonObject = JsonObject(map.filterValues { it != null }.mapValues { (_, v) -> toJsonValue(v) })

        private fun toJsonValue(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(v.entries.filter { it.value != null }.associate { (k, x) -> k.toString() to toJsonValue(x) })
            is List<*> -> JsonArray(v.map { toJsonValue(it) })
            is Array<*> -> JsonArray(v.map { toJsonValue(it) })
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v.toLong())
            is Long -> JsonPrimitive(v)
            is Double -> JsonPrimitive(v)
            is Float -> JsonPrimitive(v.toDouble())
            is Number -> JsonPrimitive(v.toDouble())
            else -> JsonPrimitive(v.toString())
        }

        fun fromJson(o: JsonObject): Map<String, Any?> = o.mapValues { (_, v) -> fromJsonValue(v) }

        private fun fromJsonValue(v: JsonElement): Any? = when (v) {
            is JsonNull -> null
            is JsonObject -> fromJson(v)
            is JsonArray -> v.map { fromJsonValue(it) }
            is JsonPrimitive -> when {
                v.isString -> v.content
                v.booleanOrNull != null -> v.booleanOrNull
                v.content.any { it == '.' || it == 'e' || it == 'E' } -> v.content.toDouble()
                else -> v.content.toLongOrNull() ?: v.content.toDouble()
            }
        }
    }
}

// ==================================================================== messages and media

/** A message as read (the phone's MsgNode): an encrypted one opened and laid over the snapshot's own values. */
internal class MsgNode(val key: String?, val value: Any?) {
    fun child(name: String): MsgNode = MsgNode(
        name,
        when (value) {
            is Map<*, *> -> value[name]
            is List<*> -> name.toIntOrNull()?.let { value.getOrNull(it) }
            else -> null
        },
    )

    fun exists() = value != null

    val children: List<MsgNode>
        get() = when (value) {
            is Map<*, *> -> value.entries.filter { it.value != null }.map { MsgNode(it.key.toString(), it.value) }
            is List<*> -> value.mapIndexedNotNull { i, v -> v?.let { MsgNode(i.toString(), it) } }
            else -> emptyList()
        }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getValue(type: kotlin.reflect.KClass<T>): T? {
        val v = value ?: return null
        return when (type) {
            String::class -> v as? String
            Long::class -> (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull()
            Int::class -> (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull()
            Double::class -> (v as? Number)?.toDouble()
            Boolean::class -> v as? Boolean
            else -> v
        } as T?
    }

    companion object {
        val ROUTING = setOf("from", "at", "expireAt", "system", "who", "whom", "deleted", "edited", "streaming", "together", "e2e")

        fun of(chatId: String, s: DataSnapshot): MsgNode {
            @Suppress("UNCHECKED_CAST")
            val raw = s.value as? Map<String, Any?> ?: return MsgNode(s.key, s.value)
            val sealed = raw["e2e"] as? String ?: return MsgNode(s.key, raw)
            val content = E2eEngine.instance?.openContent(chatId, sealed)
            val merged = HashMap<String, Any?>(raw)
            merged["_e2e"] = true
            if (content != null) {
                content.forEach { (k, v) -> if (k !in ROUTING) merged[k] = v }
            } else {
                merged["_locked"] = true
            }
            return MsgNode(s.key, merged)
        }
    }
}

/** The media of encrypted messages: link → its key (learnt when the message is opened). */
object E2eMedia {
    private val keys = HashMap<String, String>()

    fun remember(url: String, key: String) {
        if (key.isNotEmpty()) keys[url] = key
    }

    fun keyOf(url: String): String? = keys[url]

    fun isSealed(url: String) = keys.containsKey(url)
}

/** The page's clock, as the phone's code asks for it. */
internal object System {
    fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}

internal object MediaUpload {
    /** The media server: this page's own address (on the computer while developing, the dev server passes it on). */
    val BASE: String = pageOrigin()
    const val MAX_BYTES = 100L * 1024 * 1024
}

@JsFun("() => location.hostname === 'localhost' ? location.origin : 'https://qeyf.159.195.147.140.sslip.io'")
private external fun pageOrigin(): String
