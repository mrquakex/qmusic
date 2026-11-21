package com.qmusic.ui.chat

import com.nuvelo.web.api
import com.nuvelo.web.apiBlob
import com.nuvelo.web.blobDrop
import com.nuvelo.web.bytesToBlob
import com.nuvelo.web.randomB64
import com.nuvelo.web.sealBlob
import com.nuvelo.web.storeGet
import com.nuvelo.web.storeSet
import com.nuvelo.web.text
import com.nuvelo.web.upload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// What the phone's messages code gets from Android, given by the browser.

/** The page's language: Turkish unless the person picked English in the settings. */
internal fun webTurkish(): Boolean = (storeGet("nuvelo.language") ?: "tr") != "en"

/** Random numbers from the browser's cryptographic source (for ids and codes). */
internal object WebRandom {
    fun nextInt(bound: Int): Int {
        val b = E2eCrypto.unb64(randomB64(4))
        val n = ((b[0].toInt() and 0x7f) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        return n % bound
    }
}

/** A small cache that keeps the [max] most recently used entries. */
internal class LruCache<K, V>(private val max: Int) {
    private val map = LinkedHashMap<K, V>()

    fun get(key: K): V? = map.remove(key)?.also { map[key] = it }

    fun put(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > max) map.remove(map.keys.first())
    }

    fun evictAll() = map.clear()

    fun bytes(): Long = map.values.sumOf { (it as? ByteArray)?.size?.toLong() ?: 0L }
}

/** The settings the phone keeps in its preferences, kept in the browser's storage. */
internal object WebPrefs {
    fun getString(key: String, default: String?): String? = storeGet("nuvelo.$key") ?: default

    fun edit() = Editor()

    class Editor {
        fun putString(key: String, value: String?): Editor {
            storeSet("nuvelo.$key", value)
            return this
        }

        fun apply() {}
    }
}

/** Whether the page is on screen (the person is here: "online"). */
object WebPage {
    val visible = MutableStateFlow(true)
}

/** Web push: a token once notifications are allowed on this browser (see WebNotifications). */
internal object WebPush {
    var current: String? = null

    suspend fun token(): String? = current
}

/** The browser asks to save data (a slow or counted connection). */
@JsFun("() => !!(navigator.connection && navigator.connection.saveData)")
internal external fun saveData(): Boolean

@JsFun("(s) => encodeURIComponent(s)")
internal external fun encodeUrl(s: String): String

/** A file picked in the browser is known by a number: its "location" is "blob:<number>". */
internal fun blobIdOf(location: String): Int = location.removePrefix("blob:").toIntOrNull() ?: error("cannot read")

/** Requests to Nuvelo's server with the person's sign-in. */
internal object WebApi {
    suspend fun call(method: String, url: String, token: String, json: String?): Pair<Int, String> =
        parse(api(method, url, token, json).text())

    suspend fun blob(url: String, token: String, id: Int): Pair<Int, String> = parse(apiBlob(url, token, id).text())

    /** A request answered with sound: played from a link of its own. */
    suspend fun audio(url: String, token: String, json: String): String = speakUrl(url, token, json).text()

    private fun parse(r: String): Pair<Int, String> {
        val o = Json.parseToJsonElement(r).jsonObject
        return (o["status"]?.jsonPrimitive?.intOrNull ?: 0) to (o["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }
}

@JsFun(
    """async (url, token, json) => {
        const r = await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, body: json });
        if (!r.ok) throw new Error('speak ' + r.status);
        return URL.createObjectURL(await r.blob());
    }""",
)
private external fun speakUrl(url: String, token: String, json: String): kotlin.js.Promise<kotlin.js.JsString>

/** Files to Nuvelo's media server. */
internal object WebUpload {
    /**
     * Sends the kept file [id] (sealed first when [seal]: then it goes as "bin"); [progress] hears the bytes sent.
     * Returns the link, and the key it was sealed with.
     */
    suspend fun blob(token: String, chatId: String, ext: String, mime: String, id: Int, seal: Boolean, progress: (Long) -> Unit): Pair<String, String?> {
        var sendId = id
        var key: String? = null
        if (seal) {
            val o = Json.parseToJsonElement(sealBlob(id).text()).jsonObject
            sendId = o["id"]!!.jsonPrimitive.intOrNull!!
            key = o["key"]!!.jsonPrimitive.content
        }
        try {
            val url = upload(
                sendId,
                "${MediaUpload.BASE}/media/upload?chat=$chatId&ext=${if (seal) "bin" else ext}",
                token,
                if (seal) "application/octet-stream" else mime,
            ) { done -> progress(done.toLong()) }.text()
            return url to key
        } finally {
            if (sendId != id) blobDrop(sendId)
        }
    }

    suspend fun bytes(token: String, chatId: String, ext: String, mime: String, bytes: ByteArray, progress: (Long) -> Unit): String {
        val id = bytesToBlob(bytes, mime)
        try {
            return blob(token, chatId, ext, mime, id, false, progress).first
        } finally {
            blobDrop(id)
        }
    }
}

/** A link's card, read by Nuvelo's server (the browser may not read other sites). */
internal object WebLinks {
    suspend fun preview(token: String, url: String): LinkPreview? {
        val (code, body) = WebApi.call("GET", "${MediaUpload.BASE}/media/preview?url=${encodeUrl(url)}", token, null)
        if (code != 200) return null
        val o = Json.parseToJsonElement(body) as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val title = str("title")?.takeIf { it.isNotBlank() } ?: return null
        return LinkPreview(url, title, str("description").orEmpty(), str("image")?.takeIf { it.isNotBlank() }, str("site").orEmpty())
    }
}
