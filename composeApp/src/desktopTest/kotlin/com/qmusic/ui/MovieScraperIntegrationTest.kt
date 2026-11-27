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
}
