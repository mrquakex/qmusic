package com.qmusic.ui

import com.qmusic.ui.videos.ScraperFetcher
import com.qmusic.ui.videos.ScraperResponse
import com.qmusic.ui.videos.resolveGenericStreams
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generic HLS + iframe resolver against a fake in-memory HTTP layer (no network). */
class GenericStreamResolverTest {

    private class FakeWeb(private val pages: Map<String, ScraperResponse>) {
        val requests = mutableListOf<Pair<String, String?>>()
        val fetch: ScraperFetcher = { url, _, referer ->
            requests += url to referer
            pages[url]
        }
    }

    private fun html(url: String, body: String) = ScraperResponse(url, body, "text/html; charset=utf-8")

    @Test
    fun followsNestedIframesAndExpandsHlsMaster() = runTest {
        val page = "https://site.example.test/film/ornek"
        val embed1 = "https://player.example.test/e/abc"
        val embed2 = "https://vid.example.test/embed/xyz"
        val master = "https://cdn.example.test/hls/master.m3u8"
        val web = FakeWeb(
            mapOf(
                embed1 to html(embed1, """<html><iframe src="$embed2"></iframe></html>"""),
                embed2 to html(
                    embed2,
                    """<script>player.setup({file: "$master"}); </script><track src="/tr.vtt" srclang="tr" label="Türkçe">""",
                ),
                master to ScraperResponse(
                    master,
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                    1080/index.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
                    720/index.m3u8
                    """.trimIndent(),
                    "application/vnd.apple.mpegurl",
                ),
            ),
        )
        val pageHtml = """<html><a href="https://site.example.test/fragman.mp4">fragman</a><iframe data-src="$embed1"></iframe></html>"""

        val result = resolveGenericStreams(page, pageHtml, emptyMap(), web.fetch)

        val heights = result.streams.map { it.height }
        assertTrue(1080 in heights && 720 in heights, "variants: $heights")
        assertTrue(result.streams.none { it.videoUrl.endsWith("fragman.mp4") }, "iframe streams must win over page links")
        assertEquals("https://vid.example.test/", result.streams.first { it.height == 1080 }.headers["Referer"])
        assertTrue(result.captions.any { it.url == "https://vid.example.test/tr.vtt" })
        assertTrue((embed1 to page) in web.requests && (embed2 to embed1) in web.requests)
    }

    @Test
    fun embedRedirectingToMediaUsesParentReferer() = runTest {
        val page = "https://site.example.test/film/b"
        val embed = "https://go.example.test/r/1"
        val web = FakeWeb(mapOf(embed to ScraperResponse("https://cdn.example.test/file_720p.mp4", "", "video/mp4")))

        val result = resolveGenericStreams(page, """<iframe src="$embed"></iframe>""", emptyMap(), web.fetch)

        val s = result.streams.single()
        assertEquals("https://cdn.example.test/file_720p.mp4", s.videoUrl)
        assertEquals(720, s.height)
        assertEquals("https://site.example.test/", s.headers["Referer"])
    }

    @Test
    fun fallsBackToPageStreamsWhenNoIframePlays() = runTest {
        val page = "https://site.example.test/film/c"
        val web = FakeWeb(emptyMap())
        val pageHtml = """<iframe src="https://dead.example.test/x"></iframe><video><source src="/media/c.mp4"></video>"""

        val result = resolveGenericStreams(page, pageHtml, emptyMap(), web.fetch, expandHls = false)

        assertEquals(listOf("https://site.example.test/media/c.mp4"), result.streams.map { it.videoUrl })
    }
}
