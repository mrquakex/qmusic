package com.qmusic.ui.chat

import android.content.Context
import com.google.firebase.database.DataSnapshot
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A message as read: the snapshot's own values, and for an encrypted one its sealed content opened and laid over them,
 * read with the same calls as a DataSnapshot (child, getValue, children, exists).
 */
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
    fun <T> getValue(type: Class<T>): T? {
        val v = value ?: return null
        return when (type) {
            String::class.java, java.lang.String::class.java -> v as? String
            Long::class.java, java.lang.Long::class.java -> (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull()
            Int::class.java, java.lang.Integer::class.java -> (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull()
            Double::class.java, java.lang.Double::class.java -> (v as? Number)?.toDouble()
            Boolean::class.java, java.lang.Boolean::class.java -> v as? Boolean
            else -> v
        } as T?
    }

    companion object {
        /** Content keys that stay readable on the servers: who, when, and what they act on. */
        val ROUTING = setOf("from", "at", "expireAt", "system", "who", "whom", "deleted", "edited", "streaming", "together", "e2e", "pollClosed")

        /**
         * The message at [s] in [chatId]: an encrypted one opened (marked "_e2e"), or marked "_locked" when its key is
         * not on this phone.
         */
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

/**
 * Files sealed in 64 KB pieces (AES-256-GCM each, the piece's number and whether it is the last bound to it: STREAM),
 * so a large video is sealed and opened without holding it all in memory.
 *
 *   [8-byte nonce prefix] then pieces of (up to 64 KB + 16-byte tag)
 */
internal object E2eStream {
    private const val PIECE = 64 * 1024

    private fun nonce(prefix: ByteArray, index: Int) = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
    private fun aad(index: Int, last: Boolean) = ByteBuffer.allocate(5).putInt(index).put(if (last) 1 else 0).array()

    fun sealedSize(plain: Long): Long = 8 + plain + 16 * maxOf(1, (plain + PIECE - 1) / PIECE)

    fun seal(key: ByteArray, input: InputStream, output: OutputStream) {
        val prefix = E2eCrypto.bytes(8)
        output.write(prefix)
        val k = SecretKeySpec(key, "AES")
        var index = 0
        var current = readPiece(input)
        while (true) {
            val next = if (current.size == PIECE) readPiece(input) else ByteArray(0)
            val last = next.isEmpty()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(prefix, index)))
            c.updateAAD(aad(index, last))
            output.write(c.doFinal(current))
            if (last) break
            current = next
            index++
        }
    }

    fun open(key: ByteArray, input: InputStream, output: OutputStream) {
        val prefix = ByteArray(8)
        require(readFully(input, prefix) == 8) { "short" }
        val k = SecretKeySpec(key, "AES")
        var index = 0
        var current = readPiece(input, PIECE + 16)
        while (true) {
            val next = if (current.size == PIECE + 16) readPiece(input, PIECE + 16) else ByteArray(0)
            val last = next.isEmpty()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(prefix, index)))
            c.updateAAD(aad(index, last))
            output.write(c.doFinal(current))
            if (last) break
            current = next
            index++
        }
    }

    private fun readPiece(input: InputStream, size: Int = PIECE): ByteArray {
        val b = ByteArray(size)
        val n = readFully(input, b)
        return if (n == size) b else b.copyOf(n)
    }

    private fun readFully(input: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}

/**
 * The media of encrypted messages: link → its key (learnt when the message is opened), and the opened copy on the
 * phone, made when it is first played, watched or saved.
 */
object E2eMedia {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun remember(url: String, key: String) {
        if (key.isNotEmpty()) keys[url] = key
    }

    fun keyOf(url: String): String? = keys[url]

    fun isSealed(url: String) = keys.containsKey(url)

    /** The opened copy of [url] on the phone (made now if needed); null when it cannot be had. */
    internal fun openedFile(context: Context, url: String, name: String = ""): File? {
        val key = keys[url] ?: return null
        val dir = File(context.cacheDir, "e2e_media").apply { mkdirs() }
        val ext = name.substringAfterLast('.', url.substringAfterLast('.', "bin")).take(8)
        val out = File(dir, E2eCrypto.hex(E2eCrypto.sha256(url.toByteArray())).take(24) + "." + ext)
        if (out.exists() && out.length() > 0) return out
        val tmp = File(dir, out.name + ".part")
        return runCatching {
            MediaUpload.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful) { "http ${r.code}" }
                val body = r.body ?: error("empty")
                tmp.outputStream().use { o -> E2eStream.open(E2eCrypto.unb64(key), body.byteStream(), o) }
            }
            tmp.renameTo(out)
            out
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    /** Seals the file at [open] into a new file on the phone (to be sent); returns it and its key. */
    internal fun sealToFile(context: Context, open: () -> InputStream): Pair<File, String> {
        val key = E2eCrypto.bytes(32)
        val dir = File(context.cacheDir, "e2e_out").apply { mkdirs() }
        val out = File.createTempFile("seal", ".bin", dir)
        open().use { i -> out.outputStream().use { o -> E2eStream.seal(key, i, o) } }
        return out to E2eCrypto.b64(key)
    }
}
