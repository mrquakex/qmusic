package com.qmusic.ui.chat.games

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Result of validating a Turkish word against TDK Güncel Türkçe Sözlük (sozluk.gov.tr/gts). */
data class TdkLookup(
    val valid: Boolean,
    val meaning: String = "",
    val fromApi: Boolean = false,
)

/**
 * Live official TDK dictionary client (https://sozluk.gov.tr/gts?ara=<word>).
 * Platform injects [httpGet] at startup (Android MainActivity via HttpURLConnection on Dispatchers.IO),
 * keeping commonMain 100% Android/Desktop/Web compatible with zero extra Gradle dependencies.
 */
object TurkishDictApi {
    /** Injected platform HTTP GET returning response body text, or null on network failure. */
    var httpGet: (suspend (String) -> String?)? = null

    private val cache = LinkedHashMap<String, TdkLookup>()

    /** Pure-Kotlin UTF-8 percent encoder for Turkish letters (ç, ğ, ı, ö, ş, ü). */
    internal fun encodeUtf8Query(text: String): String {
        val bytes = text.encodeToByteArray()
        val hex = "0123456789ABCDEF"
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val u = b.toInt() and 0xFF
                val safe = (u in 'a'.code..'z'.code) || (u in 'A'.code..'Z'.code) || (u in '0'.code..'9'.code) || u == '-'.code || u == '_'.code
                if (safe) {
                    append(u.toChar())
                } else {
                    append('%')
                    append(hex[u ushr 4])
                    append(hex[u and 0x0F])
                }
            }
        }
    }

    /** Parses raw TDK GTS JSON response into a [TdkLookup]. Returns null if response is malformed. */
    internal fun parseTdkResponse(body: String): TdkLookup? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val obj = GameJson.parseObject(trimmed)
            if (obj.containsKey("error")) return TdkLookup(valid = false, fromApi = true)
            return null
        }
        if (trimmed.startsWith("[")) {
            val arr = GameJson.parseArray(trimmed)
            if (arr.isEmpty()) return TdkLookup(valid = false, fromApi = true)
            val first = arr.firstOrNull() as? JsonObject ?: return TdkLookup(valid = true, fromApi = true)
            val meanings = first["anlamlarListe"] as? JsonArray
            val firstMeaning = (meanings?.firstOrNull() as? JsonObject)?.get("anlam") as? JsonPrimitive
            val cleanMeaning = firstMeaning?.contentOrNull?.trim().orEmpty().replace('\n', ' ').take(90)
            return TdkLookup(valid = true, meaning = cleanMeaning, fromApi = true)
        }
        return null
    }

    /**
     * Validates [rawWord] against TDK GTS API (cached in memory).
     * Falls back to embedded [TurkishWords] only if no HTTP fetcher is wired (unit tests) or TDK times out.
     */
    suspend fun lookup(rawWord: String): TdkLookup {
        val norm = TurkishText.trNormalize(rawWord)
        if (norm.length < 2 || !TurkishText.isTurkishWord(norm)) {
            return TdkLookup(valid = false)
        }
        cache[norm]?.let { return it }

        val fetcher = httpGet
        if (fetcher != null) {
            val query = encodeUtf8Query(TurkishText.trLower(norm))
            val body = runCatching { fetcher("https://sozluk.gov.tr/gts?ara=$query") }.getOrNull()
            if (body != null) {
                val parsed = parseTdkResponse(body)
                if (parsed != null) {
                    if (cache.size > 600) cache.clear()
                    cache[norm] = parsed
                    return parsed
                }
            }
        }
        val fallback = TurkishWords.isValidChainWord(norm)
        return TdkLookup(valid = fallback, fromApi = false)
    }

    /** Picks a truly random 5-letter Turkish word and verifies it live against TDK API before starting WordDuel. */
    suspend fun pickVerifiedTarget5(): String {
        val list = TurkishWords.wordleTargets
        if (list.isEmpty()) return "KİTAP"
        repeat(6) {
            val seed = Sha256.randomSaltHex()
            val candidate = list[Sha256.deterministicIndex(seed, list.size)]
            val res = lookup(candidate)
            if (res.valid) return candidate
        }
        return list[Sha256.deterministicIndex(Sha256.randomSaltHex(), list.size)]
    }
}
