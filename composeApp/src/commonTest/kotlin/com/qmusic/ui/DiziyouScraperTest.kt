package com.qmusic.ui

import com.qmusic.ui.chat.MovieSource
import com.qmusic.ui.videos.MovieHtmlParser
import com.qmusic.ui.videos.ScraperFetcher
import com.qmusic.ui.videos.ScraperFormPoster
import com.qmusic.ui.videos.ScraperResponse
import com.qmusic.ui.videos.fetchDizidadCatalogOrNull
import com.qmusic.ui.videos.fetchDiziyouCatalogOrNull
import com.qmusic.ui.videos.fetchHdFilmCehennemiCatalogOrNull
import com.qmusic.ui.videos.resolveDizidadStreamOrNull
import com.qmusic.ui.videos.resolveDiziyouStreamOrNull
import com.qmusic.ui.videos.resolveHdFilmCehennemiStreamOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pure in-memory verification of Diziyou catalog, search API, season/episode listing, NP_CONFIG & dual-audio HLS. */
class DiziyouScraperTest {

    private fun html(url: String, body: String) = ScraperResponse(url, body, "text/html; charset=utf-8")
    private fun json(url: String, body: String) = ScraperResponse(url, body, "application/json; charset=utf-8")

    @Test
    fun extractsCatalogAndStripsTemplatePlaceholders() {
        val pageHtml = """
            <html><body>
            <template id="search-box-template">
              <a href="{url}"><img src="{poster}" alt=""></a>
            </template>
            <article class="card">
              <picture class="card__poster">
                <img src="https://diziyou.net/images/t/p/w300/anFx.jpg" alt="Breaking Bad">
              </picture>
              <div class="card__body">
                <h3>Breaking Bad</h3>
                <div class="card__meta"><span class="tmdb">9.0</span><span>2008</span></div>
              </div>
              <a href="https://diziyou.net/dizi/breaking-bad/" title="Breaking Bad izle">Breaking Bad</a>
            </article>
            </body></html>
        """.trimIndent()
        val page = MovieHtmlParser.extractCatalogPage(pageHtml, "https://diziyou.net/diziler/", "Diziyou")
        assertEquals(1, page.items.size)
        assertEquals("Breaking Bad", page.items.first().title)
        assertEquals("https://diziyou.net/dizi/breaking-bad/", page.items.first().pageUrl)
        assertEquals("9.0", page.items.first().rating)
        assertEquals("2008", page.items.first().year)
    }

    @Test
    fun parsesSearchJsonAndUpgradesPosterSize() = runTest {
        val src = MovieSource(id = "diziyou", name = "Diziyou", baseUrl = "https://diziyou.net", catalogPath = "/diziler/")
        val fetch: ScraperFetcher = { url, _, _ ->
            if ("wp-json/wstream/v1/search" in url) {
                json(
                    url,
                    """{"results":[{"id":5435,"title":"Breaking Bad","type":"Dizi","url":"https:\/\/diziyou.net\/dizi\/breaking-bad\/","poster":"https:\/\/diziyou.net\/images\/t\/p\/w92\/poster.jpg"}]}""",
                )
            } else {
                html(url, "<html><body></body></html>")
            }
        }
        val page = fetchDiziyouCatalogOrNull(src, "breaking", null, emptyMap(), fetch)
        assertNotNull(page)
        assertEquals(1, page.items.size)
        assertEquals("Breaking Bad", page.items.first().title)
        assertEquals("https://diziyou.net/images/t/p/w300/poster.jpg", page.items.first().poster)
    }

    @Test
    fun resolvesSeriesSeasonOneEpisodeOneWithDualAudioAndVttSubtitles() = runTest {
        val seriesUrl = "https://diziyou.net/dizi/breaking-bad/"
        val ep1Url = "https://diziyou.net/bolum/breaking-bad-1-sezon-1-bolum/"
        val epApiUrl = "https://diziyou.net/wp-json/wstream/v1/retrieve-episodes/1396?season=1&id=1396"
        val embedUrl = "https://ksdpictures.site/dzembed.php?id=abc-123"
        val masterUrl = "https://ksdpictures.site/dserv/uploads/master.m3u8"

        val seriesHtml = """
            <html>
            <head><title>Breaking Bad</title></head>
            <body>
              <p class="hro__overview" dir="ltr">Kimya öğretmeni Walter White'ın hikayesi.</p>
              <ul id="seasons-list">
                <li><button type="button" data-season-number="5">Sezon 5</button></li>
                <li><button type="button" data-season-number="1">Sezon 1</button></li>
              </ul>
              <div id="episodes-list" data-tvshow-id="1396"></div>
            </body>
            </html>
        """.trimIndent()
        val epApiJson = """
            {"season":"1","results":[
              {"url":"https:\/\/diziyou.net\/bolum\/breaking-bad-1-sezon-2-bolum\/","name":"Cat's in the Bag","season_number":"1","episode_number":"2","runtime":"48d","image":"https:\/\/diziyou.net\/images\/t\/p\/w92\/ep2.jpg"},
              {"url":"https:\/\/diziyou.net\/bolum\/breaking-bad-1-sezon-1-bolum\/","name":"Pilot","season_number":"1","episode_number":"1","runtime":"59d","image":"https:\/\/diziyou.net\/images\/t\/p\/w92\/ep1.jpg"}
            ]}
        """.trimIndent()
        val epHtml = """<html><body><iframe src="$embedUrl"></iframe></body></html>"""
        val embedHtml = """
            <html><body>
            <script>window.NP_CONFIG = {"sources":[{"title":"Orijinal","master":"$masterUrl"}],"subtitles":[{"label":"Türkçe","lang":"tr","url":"https://ksdpictures.site/sub_tr.vtt"},{"label":"English","lang":"en","url":"https://ksdpictures.site/sub_en.vtt"}]};</script>
            </body></html>
        """.trimIndent()
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="tur",NAME="Turkish",URI="/dserv/uploads/dub.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="eng",NAME="English",URI="/dserv/uploads/orig.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2048000,RESOLUTION=1280x720,AUDIO="audio"
            /dserv/uploads/720p.m3u8
        """.trimIndent()

        val pages = mapOf(
            epApiUrl to json(epApiUrl, epApiJson),
            ep1Url to html(ep1Url, epHtml),
            embedUrl to html(embedUrl, embedHtml),
            masterUrl to ScraperResponse(masterUrl, masterM3u8, "application/vnd.apple.mpegurl"),
        )
        val fetch: ScraperFetcher = { url, _, _ -> pages[url] }

        val resolved = resolveDiziyouStreamOrNull(seriesUrl, seriesHtml, emptyMap(), fetch)
        assertNotNull(resolved)
        assertEquals(listOf(1, 5), resolved.seasons)
        assertEquals("1396", resolved.tvshowId)
        assertEquals(2, resolved.episodes.size)
        assertEquals(1, resolved.episodes.first().episode)
        assertEquals("Pilot", resolved.episodes.first().title)
        assertEquals(ep1Url, resolved.activeEpisodeUrl)
        assertEquals(2, resolved.audioTracks.size)
        assertEquals(2, resolved.captions.size)
        assertEquals("tr", resolved.captions.first().language)
        assertTrue(resolved.streams.any { it.height == 720 })
        assertTrue(resolved.synopsis?.contains("Walter White") == true)
    }

    @Test
    fun cleansDizidadCatalogAndResolvesSeriesSeasonsAndHdstreamableMirror() = runTest {
        val src = MovieSource(id = "dizimom", name = "Dizimom", baseUrl = "https://www.dizidad.com", catalogPath = "/")
        val archiveUrl = "https://www.dizidad.com/1-dizi-arsivi/"
        val prensUrl = "https://www.dizidad.com/diziler/prens-izle-hd5-full/"
        val ep1Url = "https://www.dizidad.com/prens-1-sezon-1-bolum-izle-hd2/"
        val streamUrl = "https://video.twimg.com/amplify_video/123/pl/master.m3u8"

        val archiveHtml = """
            <html><body>
            <div class="single-item">
              <div class="katicdil"><img src="https://www.dizidad.com/wp-content/themes/DiziDAD_V1/images/yerlid.png" alt="Yerli Dizi"></div>
              <div class="cat-img"><a href="$prensUrl"><img src="https://www.dizidad.com/wp-content/uploads/2023/07/prens.jpg" alt="Prens izle"></a></div>
              <div class="cat-container">
                <div class="cat-title"><div class="categorytitle"><a href="$prensUrl">Prens izle</a></div><div class="imdbp">(IMDb: 7.9)</div></div>
                <div><span class="dizimeta">Yapım Yılı : </span>2023</div>
                <div><span class="dizimeta">Tür : </span>Komedi</div>
              </div>
            </div>
            <a href="https://www.dizidad.com/yerli-dizi-izle/">Yerli Diziler</a>
            <a class="next page-numbers" href="https://www.dizidad.com/tum-diziler-hd1/page/2/">»</a>
            </body></html>
        """.trimIndent()
        val prensSeriesHtml = """
            <html><head><title>Prens izle | DiziDAD</title><meta name="description" content="Bongomia kralı Prens hikayesi."></head>
            <body>
            <div class="bolumust"><button class="wl_add">İzledim</button><a href="https://www.dizidad.com/prens-2-sezon-1-bolum-izle-hd1/"><div class="baslik">2.Sezon 1.Bölüm</div></a></div>
            <div class="bolumust"><button class="wl_add">İzledim</button><a href="$ep1Url"><div class="baslik">1.Sezon 1.Bölüm</div></a></div>
            </body></html>
        """.trimIndent()
        val ep1Html = """<html><body><iframe data-src="https://hdstreamable.com/tv/video/30aaf34d6afd4b11cc3b3ac4704c7908"></iframe></body></html>"""
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3500000,RESOLUTION=1920x1080
            1080/index.m3u8
        """.trimIndent()

        val pages = mapOf(
            archiveUrl to html(archiveUrl, archiveHtml),
            ep1Url to html(ep1Url, ep1Html),
            streamUrl to ScraperResponse(streamUrl, masterM3u8, "application/vnd.apple.mpegurl"),
        )
        val fetch: ScraperFetcher = { url, _, _ -> pages[url] }
        val postForm: ScraperFormPoster = { url, _, _ ->
            if ("hdstreamable.com/tv/video/30aaf34d6afd4b11cc3b3ac4704c7908" in url) {
                json(url, """{"videoSources":[{"file":"https:\/\/video.twimg.com\/amplify_video\/123\/pl\/master.m3u8"}]}""")
            } else {
                null
            }
        }

        val catalog = fetchDizidadCatalogOrNull(src, "", null, emptyMap(), fetch)
        assertNotNull(catalog)
        assertEquals(1, catalog.items.size)
        assertEquals("Prens", catalog.items.first().title)
        assertEquals("https://www.dizidad.com/wp-content/uploads/2023/07/prens.jpg", catalog.items.first().poster)
        assertEquals("7.9", catalog.items.first().rating)
        assertEquals("2023", catalog.items.first().year)
        assertEquals("Komedi", catalog.items.first().subtitle)
        assertEquals("YERLİ", catalog.items.first().qualityBadge)

        val resolved = resolveDizidadStreamOrNull(prensUrl, prensSeriesHtml, emptyMap(), fetch, postForm)
        assertNotNull(resolved)
        assertEquals(listOf(1, 2), resolved.seasons)
        assertEquals(2, resolved.episodes.size)
        assertEquals(1, resolved.episodes.first().season)
        assertEquals(ep1Url, resolved.activeEpisodeUrl)
        assertTrue(resolved.streams.any { it.height == 1080 })
    }

    @Test
    fun resolvesParentSeriesContextFromDiziyouEpisodeUrl() = runTest {
        val seriesUrl = "https://diziyou.net/dizi/tulsa-king/"
        val ep2Url = "https://diziyou.net/bolum/tulsa-king-2-sezon-1-bolum/"
        val epApiUrl = "https://diziyou.net/wp-json/wstream/v1/retrieve-episodes/153312?season=2&id=153312"
        val embedUrl = "https://ksdpictures.site/dzembed.php?id=tulsa-201"
        val masterUrl = "https://ksdpictures.site/dserv/tulsa_201.m3u8"

        val ep2Html = """
            <html>
            <head><title>Tulsa King 2. Sezon 1. Bölüm izle | Diziyou</title></head>
            <body>
              <a href="$seriesUrl">Tüm Bölümler</a>
              <iframe src="$embedUrl"></iframe>
            </body>
            </html>
        """.trimIndent()
        val seriesHtml = """
            <html>
            <head><title>Tulsa King - Türkçe Dublaj ve Altyazılı İzle | Diziyou</title></head>
            <body>
              <p class="hro__overview">Dwight Manfredi hapisten çıktıktan sonra Tulsa'da yeni bir imparatorluk kurar.</p>
              <button data-season-number="1">Sezon 1</button>
              <button data-season-number="2">Sezon 2</button>
              <div id="episodes-list" data-tvshow-id="153312"></div>
            </body>
            </html>
        """.trimIndent()
        val s2Json = """
            {"season":"2","results":[
              {"url":"https:\/\/diziyou.net\/bolum\/tulsa-king-2-sezon-1-bolum\/","name":"Back in the Saddle","season_number":"2","episode_number":"1","runtime":"42d"}
            ]}
        """.trimIndent()
        val embedHtml = """<html><body><script>window.NP_CONFIG = {"sources":[{"master":"$masterUrl"}]};</script></body></html>"""
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            1080/index.m3u8
        """.trimIndent()

        val pages = mapOf(
            seriesUrl to html(seriesUrl, seriesHtml),
            epApiUrl to json(epApiUrl, s2Json),
            embedUrl to html(embedUrl, embedHtml),
            masterUrl to ScraperResponse(masterUrl, masterM3u8, "application/vnd.apple.mpegurl"),
        )
        val resolved = resolveDiziyouStreamOrNull(ep2Url, ep2Html, emptyMap()) { url, _, _ -> pages[url] }
        assertNotNull(resolved)
        assertEquals("Tulsa King", resolved.title)
        assertEquals(listOf(1, 2), resolved.seasons)
        assertEquals("153312", resolved.tvshowId)
        assertEquals(seriesUrl, resolved.seriesUrl)
        assertEquals(1, resolved.episodes.size)
        assertEquals(2, resolved.episodes.first().season)
        assertEquals(ep2Url, resolved.activeEpisodeUrl)
        assertTrue(resolved.streams.any { it.height == 1080 })
    }

    @Test
    fun parsesHdFilmCehennemiVipSearchAndResolvesSeriesVidloadStream() = runTest {
        val src = MovieSource(
            id = "hdfilmcehennemi",
            name = "HDFilmCehennemi",
            baseUrl = "https://hdfilmcehennemi.vip",
            catalogPath = "/",
            searchPath = "/api/search?q={query}",
        )
        val searchUrl = "https://hdfilmcehennemi.vip/api/search?q=black"
        val seriesUrl = "https://hdfilmcehennemi.vip/dizi/black-mirror/"
        val ep1Url = "https://hdfilmcehennemi.vip/dizi/black-mirror/sezon-1/bolum-1"
        val vidloadUrl = "https://vidload.top/player/ut1t1ec6o67w71nvx26p"
        val m3u8Url = "https://cdn.vidload.top/hls/ut1t1ec6o67w71nvx26p/master.m3u8"
        val vttUrl = "https://cdn.vidload.top/hls/ut1t1ec6o67w71nvx26p_tur.vtt"

        val searchJson = """
            {"data":[{"id":"21","title":"Black Mirror","slug":"dizi\/black-mirror","posterUrl":"https:\/\/hdfilmcehennemi.vip\/uploads\/posters\/webp\/bm.webp","releaseYear":"2011","imdbRating":8.7}]}
        """.trimIndent()
        val seriesHtml = """
            <html>
            <head><title>Black Mirror izle | hdfilmcehennemi</title></head>
            <body>
              <a href="https://hdfilmcehennemi.vip/dizi/black-mirror/sezon-2/bolum-1">Be Right Back</a>
              <a href="$ep1Url">The National Anthem</a>
            </body>
            </html>
        """.trimIndent()
        // Base64 of: <iframe class="{slug}" data-src="//vidload.top/player/{url}" allowfullscreen="true"></iframe>
        val templateB64 = "PGlmcmFtZSBjbGFzcz0ie3NsdWd9IiBkYXRhLXNyYz0iLy92aWRsb2FkLnRvcC9wbGF5ZXIve3VybH0iIGFsbG93ZnVsbHNjcmVlbj0idHJ1ZSI+PC9pZnJhbWU+"
        val ep1Html = """
            <html><body>
            <a href="https://hdfilmcehennemi.vip/dizi/black-mirror">Black Mirror</a>
            <div data-video-player x-data="videoPlayerData(JSON.parse('{\u0022dual\u0022:[{\u0022link\u0022:\u0022ut1t1ec6o67w71nvx26p\u0022,\u0022service_slug\u0022:\u0022vip\u0022,\u0022service_name\u0022:\u0022VIP\u0022,\u0022template\u0022:\u0022$templateB64\u0022}]}'), 'dual', [])"></div>
            </body></html>
        """.trimIndent()
        val vidloadHtml = """
            <html><body><script>
            const player = jwplayer('player').setup(JSON.parse('{\u0022file\u0022:\u0022https:\/\/cdn.vidload.top\/hls\/ut1t1ec6o67w71nvx26p\/master.m3u8\u0022,\u0022type\u0022:\u0022hls\u0022,\u0022tracks\u0022:[{\u0022file\u0022:\u0022https:\/\/cdn.vidload.top\/hls\/ut1t1ec6o67w71nvx26p_tur.vtt\u0022,\u0022kind\u0022:\u0022captions\u0022,\u0022label\u0022:\u0022T\u00fcrk\u00e7e\u0022}]}'));
            </script></body></html>
        """.trimIndent()
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080
            1080/index.m3u8
        """.trimIndent()

        val pages = mapOf(
            searchUrl to json(searchUrl, searchJson),
            ep1Url to html(ep1Url, ep1Html),
            vidloadUrl to html(vidloadUrl, vidloadHtml),
            m3u8Url to ScraperResponse(m3u8Url, masterM3u8, "application/vnd.apple.mpegurl"),
        )
        val fetch: ScraperFetcher = { url, _, _ -> pages[url] }

        val searchPage = fetchHdFilmCehennemiCatalogOrNull(src, "black", null, emptyMap(), fetch)
        assertNotNull(searchPage)
        assertEquals(1, searchPage.items.size)
        assertEquals("Black Mirror", searchPage.items.first().title)
        assertEquals("https://hdfilmcehennemi.vip/dizi/black-mirror", searchPage.items.first().pageUrl)
        assertEquals("2011", searchPage.items.first().year)
        assertEquals("8.7", searchPage.items.first().rating)

        val resolved = resolveHdFilmCehennemiStreamOrNull(seriesUrl, seriesHtml, emptyMap(), fetch)
        assertNotNull(resolved)
        assertEquals("Black Mirror", resolved.title)
        assertEquals(listOf(1, 2), resolved.seasons)
        assertEquals(2, resolved.episodes.size)
        assertEquals(1, resolved.episodes.first().season)
        assertEquals(ep1Url, resolved.activeEpisodeUrl)
        assertTrue(resolved.streams.any { it.height == 1080 })
        assertEquals(1, resolved.captions.size)
        assertEquals(vttUrl, resolved.captions.first().url)
    }
}
