package com.nuvelo.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.nuvelo.web.res.Res
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.QMusicTheme
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("app") { NuveloWeb() }
}

/** Writes [message] to the browser's console (for finding what went wrong). */
@JsFun("(m) => console.log('[nuvelo] ' + m)")
external fun logJs(message: String)

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun NuveloWeb() {
    val resolver = LocalFontFamilyResolver.current
    var text by remember { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(Unit) { try { loadFonts(resolver) { text = it } } catch (e: Throwable) { logJs("fonts: " + e.toString()) } }
    QMusicThemeHost(text)
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun loadFonts(resolver: androidx.compose.ui.text.font.FontFamily.Resolver, done: (FontFamily) -> Unit) {
    run {
        // The letters first (the page shows as soon as they are in), the emoji after them (they are bigger).
        var roboto: ByteArray? = null
        while (roboto == null) {
            roboto = runCatching { Res.readBytes("files/roboto.ttf") }.getOrNull()
            if (roboto == null) kotlinx.coroutines.delay(1_000)
        }
        val letters: ByteArray = roboto
        val family = FontFamily(
            listOf(400, 500, 600, 700, 800, 900).map { w ->
                Font("roboto-$w", letters, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            },
        )
        // Preloaded fonts are also what any text falls back to (Turkish letters in a style without a family).
        resolver.preload(family)
        done(family)
        hidePageSplash()
        // The emoji are not needed to start: a failed load is tried again a little later.
        repeat(3) { attempt ->
            val ok = runCatching { resolver.preload(FontFamily(Font("emoji", Res.readBytes("files/emoji.ttf")))) }.isSuccess
            if (ok) return
            kotlinx.coroutines.delay(2_000L * (attempt + 1))
        }
    }
}

@Composable
private fun QMusicThemeHost(text: FontFamily?) {
    QMusicTheme {
        Box(Modifier.fillMaxSize().background(BgRoot)) {
            val family = text ?: return@Box
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = family)) {
                WebChat()
            }
        }
    }
}

/** The page's own splash (in index.html, shown while the app loads) goes once the app draws. */
@JsFun("() => { const s = document.getElementById('boot'); if (s) { s.style.opacity = '0'; setTimeout(() => s.remove(), 300); } }")
private external fun hidePageSplash()

@JsFun("(f) => window.addEventListener('popstate', () => f())")
external fun onHistoryBack(f: () -> Unit)
