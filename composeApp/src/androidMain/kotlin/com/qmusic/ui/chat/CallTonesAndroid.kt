package com.qmusic.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * The phone's part of song ringtones for Qeyf calls (see [CallTones]): the choices kept on the phone, the song a person
 * chose for their callers sent to Qeyf's media server (users/{uid}/tone), and the song of the person being called
 * fetched (kept in a small cache, so it plays at once the next time).
 */
object CallTonesAndroid {
    private const val PREFS = "qmusic_settings"
    private const val KEY_FOR = "ring_for_"
    private const val KEY_CALLER_URI = "caller_tone_uri"
    private const val KEY_CALLER_TITLE = "caller_tone_title"
    private const val KEY_HEAR = "hear_caller_tones"
    private const val TAG = "QeyfTones"

    /** A caller tone bigger than this is not sent (ringtones are well under it). */
    private const val MAX_BYTES = 6L * 1024 * 1024
    private const val CACHE_KEEP = 40

    private var app: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        val p = prefs(c)
        CallTones.ring.value = QeyfSounds.choice(c, SoundKind.RING)
        CallTones.perPerson.value = p.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_FOR) && value is String) key.removePrefix(KEY_FOR) to value else null
        }.toMap()
        CallTones.callerTone.value = p.getString(KEY_CALLER_URI, null)?.let { CallerTone(it, p.getString(KEY_CALLER_TITLE, null).orEmpty()) }
        CallTones.hearOthers.value = p.getBoolean(KEY_HEAR, true)

        CallTones.setRing = { id ->
            QeyfSounds.setChoice(c, SoundKind.RING, id)
            CallTones.ring.value = id
            // Its channel is made now, so the next call already rings with it.
            QeyfSounds.ringChannelId(c)
        }
        CallTones.setRingFor = { uid, id ->
            prefs(c).edit().apply { if (id == null) remove(KEY_FOR + uid) else putString(KEY_FOR + uid, id) }.apply()
            CallTones.perPerson.value = if (id == null) CallTones.perPerson.value - uid else CallTones.perPerson.value + (uid to id)
            QeyfSounds.ringChannelId(c, uid)
        }
        CallTones.setHearOthers = { on ->
            prefs(c).edit().putBoolean(KEY_HEAR, on).apply()
            CallTones.hearOthers.value = on
        }
        CallTones.preview = { id -> QeyfSounds.preview(c, SoundKind.RING, id) }
        CallTones.stopPreview = { QeyfSounds.stopPreview() }
        CallTones.setCallerTone = { tone -> publish(c, tone) }
    }

    /** The ringtone of [uid] when the person gave them one of their own, else null. */
    fun ringFor(context: Context, uid: String?): String? = uid?.let { prefs(context).getString(KEY_FOR + it, null) }

    /** Every person's own ringtone (their channels are kept). */
    fun allPersonal(context: Context): Collection<String> =
        prefs(context).all.filterKeys { it.startsWith(KEY_FOR) }.values.filterIsInstance<String>()

    private fun database(context: Context): FirebaseDatabase? = runCatching {
        // The messages open the database first (keeping its offline settings).
        FirebaseChatBackend.get(context) ?: return null
        val configured = FirebaseApp.getInstance().options.databaseUrl
        if (configured.isNullOrBlank()) FirebaseDatabase.getInstance(DATABASE_URL) else FirebaseDatabase.getInstance()
    }.getOrNull()

    /** Sends [tone] as what this account's callers hear (null: Qeyf's beeps again); false when it could not. */
    private suspend fun publish(context: Context, tone: MadeTone?): Boolean = withContext(Dispatchers.IO) {
        CallTones.publishing.value = true
        try {
            val user = FirebaseAuth.getInstance().currentUser ?: return@withContext false
            val db = database(context) ?: return@withContext false
            val ref = db.getReference("users").child(user.uid).child("tone")
            if (tone == null) {
                withTimeoutOrNull(15_000) { ref.removeValue().await() } ?: return@withContext false
                prefs(context).edit().remove(KEY_CALLER_URI).remove(KEY_CALLER_TITLE).apply()
                CallTones.callerTone.value = null
                return@withContext true
            }
            val bytes = context.contentResolver.openInputStream(Uri.parse(tone.uri))?.use { it.readBytes() } ?: return@withContext false
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext false
            val token = user.getIdToken(false).await().token ?: return@withContext false
            val url = MediaUpload.upload(token, "u_${user.uid}", "m4a", "audio/mp4", bytes.size.toLong(), { bytes.inputStream() }) {}
            withTimeoutOrNull(15_000) {
                ref.setValue(mapOf("url" to url, "title" to tone.title.take(80), "at" to ServerValue.TIMESTAMP)).await()
            } ?: return@withContext false
            prefs(context).edit().putString(KEY_CALLER_URI, tone.uri).putString(KEY_CALLER_TITLE, tone.title).apply()
            CallTones.callerTone.value = CallerTone(tone.uri, tone.title)
            true
        } catch (e: Exception) {
            Log.w(TAG, "caller tone not sent: ${e.message}")
            false
        } finally {
            CallTones.publishing.value = false
        }
    }

    /**
     * The song [uid] chose for their callers, as a file on this phone (fetched now, or kept from before); null when
     * they chose none, the person here turned it off, or it cannot be had in time.
     */
    suspend fun peerTone(context: Context, uid: String): File? = withContext(Dispatchers.IO) {
        if (!prefs(context).getBoolean(KEY_HEAR, true)) return@withContext null
        val db = database(context) ?: return@withContext null
        val url = withTimeoutOrNull(5_000) {
            runCatching { db.getReference("users").child(uid).child("tone").child("url").get().await().getValue(String::class.java) }.getOrNull()
        }?.takeIf { it.startsWith(MediaUpload.BASE + "/") } ?: return@withContext null
        val dir = File(context.cacheDir, "calltones").apply { mkdirs() }
        val file = File(dir, sha1(url) + ".m4a")
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        val bytes = withTimeoutOrNull(12_000) { MediaUpload.download(url) }?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val part = File(dir, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) return@withContext null
        // Only the last few are kept.
        dir.listFiles()?.filter { it.name.endsWith(".m4a") }?.sortedByDescending { it.lastModified() }?.drop(CACHE_KEEP)?.forEach { it.delete() }
        file
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app"
}
