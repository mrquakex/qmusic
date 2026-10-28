package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap

private val dominantColorCache = ConcurrentHashMap<String, Color>()

@Composable
actual fun rememberDominantColor(url: String?): Color? {
    val context = LocalPlatformContext.current
    var color by remember(url) { mutableStateOf(url?.let { dominantColorCache[it] }) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        dominantColorCache[url]?.let {
            color = it
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(48) // a thumbnail is plenty to judge the colour, and cheap
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@LaunchedEffect
        val computed = withContext(Dispatchers.Default) { tint(bitmap) }
        dominantColorCache[url] = computed
        color = computed
    }
    return color
}

/** Average of the colourful pixels (greys and near-black/white count little), then set to a deep, rich shade. */
private fun tint(bitmap: Bitmap): Color {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var weight = 0.0
    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getColor(x, y)
            val pr = (pixel shr 16) and 0xFF
            val pg = (pixel shr 8) and 0xFF
            val pb = pixel and 0xFF
            java.awt.Color.RGBtoHSB(pr, pg, pb, hsv)
            val w = 0.05 + hsv[1] * hsv[2] // saturated and bright pixels decide the colour
            r += pr * w
            g += pg * w
            b += pb * w
            weight += w
        }
    }
    if (weight == 0.0) return Color(0xFF2A2233)
    java.awt.Color.RGBtoHSB((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt(), hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.65f)
    hsv[2] = 0.42f
    val rgb = java.awt.Color.HSBtoRGB(hsv[0], hsv[1], hsv[2])
    return Color((rgb and 0x00FFFFFF) or (0xFF shl 24))
}
