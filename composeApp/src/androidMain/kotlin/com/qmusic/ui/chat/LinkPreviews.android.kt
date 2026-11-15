package com.qmusic.ui.chat

import okhttp3.Request

/** Reads a page's title, description and picture (Open Graph tags, then the plain title) for a link's card. */
internal object LinkPreviews {
    private const val MAX_BYTES = 512 * 1024

    fun fetch(url: String): LinkPreview? = runCatching {
        val request = Request.Builder()
            .url(url)
            // Sites give their cards to link-preview bots; a plain browser name gets them everywhere else.
            .header("User-Agent", "Mozilla/5.0 (compatible; QeyfLinkPreview/1.0; +https://github.com/mrquakex/qmusic)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        MediaUpload.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            val finalUrl = response.request.url.toString()
            if (type.startsWith("image/")) return LinkPreview(url, finalUrl.substringAfterLast('/'), "", finalUrl, host(finalUrl))
            if (!type.contains("html")) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_BYTES.toLong())
            val html = source.buffer.snapshot().utf8().take(MAX_BYTES)
            val head = html.substringBefore("</head>", html)
            fun meta(vararg names: String): String? {
                for (name in names) {
                    val a = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']$name["'][^>]*content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(head)
                    val b = Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']$name["']""", RegexOption.IGNORE_CASE).find(head)
                    val v = (a ?: b)?.groupValues?.get(1)?.let(::unescape)?.trim()
                    if (!v.isNullOrEmpty()) return v
                }
                return null
            }
            val title = meta("og:title", "twitter:title")
                ?: Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.let(::unescape)?.trim()
                ?: return null
            val image = meta("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")?.let { absolute(finalUrl, it) }
            LinkPreview(
                url = url,
                title = title.take(160),
                description = meta("og:description", "twitter:description", "description").orEmpty().take(240),
                image = image,
                site = meta("og:site_name") ?: host(finalUrl),
            )
        }
    }.getOrNull()

    private fun host(url: String) = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrDefault("")

    private fun absolute(base: String, link: String): String = runCatching { java.net.URI(base).resolve(link).toString() }.getOrDefault(link)

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}
