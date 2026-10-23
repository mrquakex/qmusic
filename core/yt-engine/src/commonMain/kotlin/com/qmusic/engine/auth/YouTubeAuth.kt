package com.qmusic.engine.auth

import kotlin.time.Clock

/**
 * Manages YouTube Music authentication state and SAPISIDHASH computation.
 */
class YouTubeAuth(
    cookieString: String? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() / 1000 },
) {
    var rawCookies: String? = null
        private set

    private var sapisid: String? = null

    init {
        cookieString?.let { setCookies(it) }
    }

    /**
     * Whether the user has a valid authenticated session (SAPISID or __Secure-3PAPISID).
     */
    val isLoggedIn: Boolean
        get() = !sapisid.isNullOrBlank()

    /**
     * The HTTP Authorization header value (e.g. `SAPISIDHASH <hash>`) or null if not logged in.
     */
    val authorizationHeader: String?
        get() {
            val hash = getSapisidHash() ?: return null
            return "SAPISIDHASH $hash"
        }

    /**
     * Parse cookies and update authentication state.
     */
    fun setCookies(cookieString: String) {
        rawCookies = cookieString
        sapisid = parseSapisid(cookieString)
    }

    /**
     * Computes the SAPISIDHASH for InnerTube requests.
     * Formula: `timestamp + "_" + sha1Hex(timestamp + " " + sapisid + " " + origin)`
     */
    fun getSapisidHash(origin: String = "https://music.youtube.com"): String? {
        val currentSapisid = sapisid ?: return null
        val timestamp = clock()
        val payload = "$timestamp $currentSapisid $origin"
        val hash = sha1Hex(payload)
        return "${timestamp}_$hash"
    }

    companion object {
        /**
         * Parses a cookie header string to extract `SAPISID` or `__Secure-3PAPISID`.
         */
        fun parseSapisid(cookieString: String): String? {
            if (cookieString.isBlank()) return null
            val cookies = cookieString.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var sapisidVal: String? = null
            var secureSapisidVal: String? = null

            for (cookie in cookies) {
                val parts = cookie.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key == "SAPISID" && value.isNotEmpty()) {
                        sapisidVal = value
                    } else if (key == "__Secure-3PAPISID" && value.isNotEmpty()) {
                        secureSapisidVal = value
                    }
                }
            }

            return sapisidVal ?: secureSapisidVal
        }

        /**
         * Pure Kotlin SHA-1 computation returning lowercase hexadecimal string.
         */
        internal fun sha1Hex(input: String): String {
            val bytes = input.encodeToByteArray()
            val bitLen = bytes.size.toLong() * 8L

            // Pad to multiple of 64 bytes with 8 bytes reserved for length at the end
            val padLen = (64 - ((bytes.size + 1 + 8) % 64)) % 64
            val totalLen = bytes.size + 1 + padLen + 8
            val padded = ByteArray(totalLen)
            bytes.copyInto(padded)
            padded[bytes.size] = 0x80.toByte()

            for (i in 0 until 8) {
                padded[totalLen - 8 + i] = ((bitLen ushr (56 - i * 8)) and 0xFFL).toByte()
            }

            var h0 = 0x67452301
            var h1 = 0xEFCDAB89.toInt()
            var h2 = 0x98BADCFE.toInt()
            var h3 = 0x10325476
            var h4 = 0xC3D2E1F0.toInt()

            val w = IntArray(80)

            for (chunkStart in 0 until totalLen step 64) {
                for (t in 0 until 16) {
                    val idx = chunkStart + t * 4
                    w[t] = ((padded[idx].toInt() and 0xFF) shl 24) or
                           ((padded[idx + 1].toInt() and 0xFF) shl 16) or
                           ((padded[idx + 2].toInt() and 0xFF) shl 8) or
                           (padded[idx + 3].toInt() and 0xFF)
                }
                for (t in 16 until 80) {
                    w[t] = (w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]).rotateLeft(1)
                }

                var a = h0
                var b = h1
                var c = h2
                var d = h3
                var e = h4

                for (t in 0 until 80) {
                    val f: Int
                    val k: Int
                    when (t) {
                        in 0..19 -> {
                            f = (b and c) or (b.inv() and d)
                            k = 0x5A827999
                        }
                        in 20..39 -> {
                            f = b xor c xor d
                            k = 0x6ED9EBA1
                        }
                        in 40..59 -> {
                            f = (b and c) or (b and d) or (c and d)
                            k = 0x8F1BBCDC.toInt()
                        }
                        else -> {
                            f = b xor c xor d
                            k = 0xCA62C1D6.toInt()
                        }
                    }
                    val temp = a.rotateLeft(5) + f + e + k + w[t]
                    e = d
                    d = c
                    c = b.rotateLeft(30)
                    b = a
                    a = temp
                }

                h0 += a
                h1 += b
                h2 += c
                h3 += d
                h4 += e
            }

            fun Int.toHex8(): String {
                val hexChars = "0123456789abcdef"
                val chars = CharArray(8)
                for (i in 0 until 8) {
                    val shift = (7 - i) * 4
                    chars[i] = hexChars[(this ushr shift) and 0x0F]
                }
                return chars.concatToString()
            }

            return "${h0.toHex8()}${h1.toHex8()}${h2.toHex8()}${h3.toHex8()}${h4.toHex8()}"
        }
    }
}
