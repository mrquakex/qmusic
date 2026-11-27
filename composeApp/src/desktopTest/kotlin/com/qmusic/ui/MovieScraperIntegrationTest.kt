package com.qmusic.ui

import com.qmusic.ui.chat.MovieSource
import com.qmusic.ui.videos.fetchMovieCatalog
import com.qmusic.ui.videos.resolveExternalMovie
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real end-to-end HTTP integration test for Nuvelo's pure-Kotlin Movie Scraper:
 * spins up a real local HTTP server simulating a full cinema site (catalog + search + genre + pagination
 * + film page with JSON-LD metadata + nested iframe embed with Dean Edwards packed JS + Referer-protected HLS stream)
 * and verifies that fetchMovieCatalog -> resolveExternalMovie -> actual stream byte download works end-to-end!
 */
class MovieScraperIntegrationTest {

    @Test
    fun endToEndCatalogEmbedPackedJsAndRefererStreamWork() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        val base = "http://127.0.0.1:$port"

        // 1. Cinema homepage catalog
        server.createContext("/filmler") { ex ->
            val html = """
                <html><body>
                  <nav><a href="/tur/bilim-kurgu">Bilim Kurgu</a></nav>
                  <div class="movies">
                    <a href="/izle/ornek-film" title="Örnek Sinema Filmi">
                      <img data-src="/poster/ornek.jpg" alt="Örnek Sinema Filmi" />
                      <span>8.7</span><span>2025</span><span>1080p</span>
                    </a>
                  </div>
                  <a rel="next" href="/filmler?p=2">Sonraki</a>
                </body></html>
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }

        // 2. Movie detail page containing JSON-LD metadata + nested player iframe
        server.createContext("/izle/ornek-film") { ex ->
            val html = """
                <html>
                  <head>
                    <meta property="og:title" content="Örnek Sinema Filmi (2025)" />
                    <meta property="og:image" content="$base/poster/ornek.jpg" />
                    <script type="application/ld+json">
                      {"@type":"Movie","name":"Örnek Sinema Filmi","datePublished":"2025-05-01","description":"Tamamen yerli HTTP scraper testi için örnek film açıklaması.","aggregateRating":{"ratingValue":"8.7"}}
                    </script>
                  </head>
                  <body>
                    <iframe src="$base/embed/player-42"></iframe>
                  </body>
                </html>
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }

        // 3. Embedded player page with Dean Edwards p,a,c,k,e,d JS pointing to our HLS stream
        server.createContext("/embed/player-42") { ex ->
            val referer = ex.requestHeaders.getFirst("Referer")
            assertNotNull(referer, "Embed request must carry Referer header")
            val packed = """
                <html><body>
                  <script>
                    eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1={2:"3://4.5.6.7:$port/8/9.a"};',11,11,'var|player|file|http|127|0|0|1|hls|master|m3u8'.split('|'),0,{}))
                  </script>
                </body></html>
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, packed.size.toLong())
            ex.responseBody.use { it.write(packed) }
        }

        // 4. Referer-protected HLS manifest endpoint
        server.createContext("/hls/master.m3u8") { ex ->
            val referer = ex.requestHeaders.getFirst("Referer")
            if (referer == null || !referer.startsWith(base)) {
                ex.sendResponseHeaders(403, -1)
                ex.close()
                return@createContext
            }
            val m3u8 = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                seg0.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            ex.sendResponseHeaders(200, m3u8.size.toLong())
            ex.responseBody.use { it.write(m3u8) }
        }

        server.start()
        try {
            val source = MovieSource(id = "test", name = "Test Sinema", baseUrl = base, catalogPath = "/filmler")
            val catalog = fetchMovieCatalog(source).getOrThrow()
            assertEquals(1, catalog.items.size)
            assertEquals("Örnek Sinema Filmi", catalog.items.first().title)
            assertEquals("8.7", catalog.items.first().rating)
            assertEquals("2025", catalog.items.first().year)
            assertEquals("$base/filmler?p=2", catalog.nextPageUrl)
            assertEquals(listOf("Bilim Kurgu"), catalog.genres.map { it.first })

            val resolved = resolveExternalMovie(catalog.items.first().pageUrl, catalog.items.first().headers).getOrThrow()
            assertEquals("Örnek Sinema Filmi (2025)", resolved.title)
            assertEquals("2025", resolved.year)
            assertEquals("8.7", resolved.rating)
            assertTrue(resolved.synopsis!!.contains("scraper testi"))
            assertEquals(1, resolved.streams.size)
            val stream = resolved.streams.first()
            assertEquals("$base/hls/master.m3u8", stream.videoUrl)

            // Verify the extracted stream URL + extracted Referer header ACTUALLY downloads playable HLS bytes (200 OK)!
            val client = OkHttpClient()
            val req = Request.Builder().url(stream.videoUrl).apply {
                stream.headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body?.string().orEmpty()
                assertTrue(body.contains("#EXTM3U"), "Stream must return valid HLS playlist bytes")
            }

            // Verify Admin Panel's 1-tap "Kaynağı Test Et" diagnostic returns ok = true end-to-end!
            val diag = com.qmusic.ui.videos.testMovieSource(source, english = false)
            assertTrue(diag.ok, "Expected diagnostic OK, got: ${diag.summary}")
            assertEquals(1, diag.catalogCount)
            assertEquals(1, diag.streamCount)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun articleSiblingCardsSrcsetDataBgAndDataEmbedWork() {
        val html = """
            <html><body>
              <article class="movie-card">
                <div class="poster" data-bg="/posters/blade.jpg"></div>
                <div class="info"><a href="/film/blade-runner"><h2>Blade Runner 2049</h2></a><span>8.0</span><span>2017</span></div>
              </article>
              <a href="/film/arrival">
                <img srcset="/img/arr-sm.jpg 200w, /img/arr-lg.jpg 800w" alt="Arrival" />
              </a>
              <button data-embed="https://player.example.com/v/999">Server 1</button>
            </body></html>
        """.trimIndent()
        val cards = com.qmusic.ui.videos.MovieHtmlParser.extractCatalog(html, "https://site.example.com/", "Sinema")
        assertEquals(2, cards.size)
        assertEquals("Blade Runner 2049", cards[0].title)
        assertEquals("https://site.example.com/posters/blade.jpg", cards[0].poster)
        assertEquals("2017", cards[0].year)
        assertEquals("Arrival", cards[1].title)
        assertEquals("https://site.example.com/img/arr-lg.jpg", cards[1].poster)
        assertEquals(listOf("https://player.example.com/v/999"), com.qmusic.ui.videos.MovieHtmlParser.extractEmbedIframes(html, "https://site.example.com/"))
    }

    @Test
    fun cryptoJsAesDecryptionExtractsStreamPayload() {
        val hash = "RkwyZjRjTm9TQ2Y3YnZyYm5PWU9CZz09"
        val payload = """{"ct":"cLW+ch5fHx4Zm3lkKkLVeCgyMAoAc4o2De1A01wrQnyR2rKCtsMVz4bKK3VqUVnAKszxezt+1TaTh72ecRl+Qs9igN0bcpY2D/nIOdNe+oRoOc93d5bRO5vuZGpRy/prqlwOyH3yB4lkntysOuFgAfAHZggbEEgW2L0gQwJYjmdBHj88QZpa44ehLVpb3ARVvy7z5YUWsKkm2OaFMmbwPlJltjh5SBXZOyZslGYjU18z4/QvJMuPV3wD9pdYmt3Mxm5s1baB7uK/oin/PZ8Af3rgWwCNCrDU8cpTo15RtvC7k97ESjIeqDaeTlxjwiayFH4rqfpjYxefhtbrSZmYAKFwTXl4TPbs8G13UFEnpmL7BGWSLu6etb9lEP4BXMtf2QQJ9fFAcVoO2haMFmILl9hic9/xQkWrT2pzCab3VPQkuhI15OsQgpa/DBclQdl7tEO8RtymNpGLpOd/4S+0lj2FrTRQecLMMXO4CVVM2J8B6Ko3lO49RR+jWeynlfRHt5HGkGh1QUsR3qHxoGg3Mwdknksa8tQ+0Qu4ggU9jVWAcuY8yHsU9KHwEJdP8VdQnHY0zapHzqDr1iKC6U9sVUA4lrujILwJoJf9s3FsAfGmF5FRrFFV+rVvv8fq7q42fl0fdrKGAQDye9QQM1f6ixYGNdmkcFs+6O/x16vKS1O3iRyLzlSLRZgNx4qpMlske89J3zA3ZwdHp91JdAujQRnR/5U9whIC1tMVLW3QNOWLCE8IfAFz9lFa5EpozgmP5QVKEcBbfE4+2lseO0lPVHjjWyn88L2DRJ0zz9cgg15RUi1Kf72XSUrVuxjhhN8a20aFu+Dhg3UfaMdPmbwEynhMj7c2lXciWN12o1ErhL4pzxpqbPLg4bBvR7jWU3giREbiFpGOHe/YSAYc5zhbc9YKUhLMEEDrfVTiUyw4oGVVjcK5j1FHN76sk/m1sSMnbX/LBRV5GJP2u7ZFUBvWGmguhu8FP+KTiELRdtja7cvbycTuEKEe7bLC4dFvttBxz+TfHxJ07hXcb4TX5yQirWdeosTLg4+iu3pGeN6b34U91NViq6vztLj8hYTKQUneE9HetxhZ8OtSOTqZr+MELBeTPxmrPZwtDx3k76Efc+9V+SfIL0PwtJO4D7YIzLxmBohqbdwl5cTI8B3jKKkkTtz1OtVRyBjrq21XhVBObxanD8ulTlePZ+O7cJhLGaj5CCyO+OWPWasu1CnBFdabn+SrCZdfuU2JRTtJO1y3JRH1+jlv0c8YT/SP+s/Tt8jg3tbK75EqFIM4C1OIex77SK6JX3TI572MxCr6CBElp1ubXPVvN+aMhQGCKgJZwBW+37OHv7v/BNRNp8yMleGQfLNCwjcgNnaboMn3Jb+kW4KPE5e7HZ3Ykpr/Es8BM5KW7ipMOxg3XCJ5oP/KWp+aIdlxjWTgSL+YpaKMaj7+TVtBf+rfKBQjBzIrPnqjE1pmM7cpsqDw2hLKCJFddT6T03zBY0cMSxKQcuBU7fWH7q+3hJUDxA1dQSVVu9pAS4Q9xQ3mrqkvNDeOaorjiWo5BDqti1mz5YeDSP/DniKXK/6v3RcTaTM9JOSNwqebdiwGyFTi1lJFGVntAMn5pAf42Rdr39K10pamecQpz4hVtFn5zlsqkT0KvM7YJdlkpQaAgksD25m1+9qxripIgmpNwYtQrzCihxQeZxOzhfcX6XyvRriiWp5j7rhToU+1Xs0iyvx+pAThxxVlk9pDfLNsuscTcTVkpJi2D6Su9753eJB+B96lGA9NwDWz64v1k9Ud","iv":"57489ff55bd86c0f9f0b11c0a095baeb","s":"b09bf2e9691f3fd3"}"""
        val ct = Regex(""""ct"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
        assertNotNull(ct)
        val sHex = Regex(""""s"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
        assertNotNull(sHex)
        val ivHex = Regex(""""iv"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
        assertNotNull(ivHex)

        fun parseHex(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        val salt = parseHex(sHex)
        val iv = parseHex(ivHex)
        val cipherBytes = java.util.Base64.getDecoder().decode(ct)

        val md = java.security.MessageDigest.getInstance("MD5")
        var d = ByteArray(0)
        var keyAndIv = ByteArray(0)
        val passBytes = hash.toByteArray(Charsets.UTF_8)
        while (keyAndIv.size < 48) {
            md.reset()
            md.update(d)
            md.update(passBytes)
            md.update(salt)
            d = md.digest()
            val next = ByteArray(keyAndIv.size + d.size)
            System.arraycopy(keyAndIv, 0, next, 0, keyAndIv.size)
            System.arraycopy(d, 0, next, keyAndIv.size, d.size)
            keyAndIv = next
        }
        val key = ByteArray(32)
        System.arraycopy(keyAndIv, 0, key, 0, 32)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8).replace("\\/", "/")
        assertTrue(decrypted.contains("video_location"), "Decrypted payload must contain video_location")
        assertTrue(decrypted.contains("https://vidmixi.com/list/"), "Must extract real vidmixi list HLS url")
    }
}
