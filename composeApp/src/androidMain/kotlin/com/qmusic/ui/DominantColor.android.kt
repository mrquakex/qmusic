package com.qmusic.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberDominantColor(url: String?): Color? {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(48) // a thumbnail is plenty to judge the colour, and cheap
            .allowHardware(false) // the pixels have to be readable
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@LaunchedEffect
        color = withContext(Dispatchers.Default) { tint(bitmap) }
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
            val pixel = bitmap.getPixel(x, y)
            android.graphics.Color.colorToHSV(pixel, hsv)
            val w = 0.05 + hsv[1] * hsv[2] // saturated and bright pixels decide the colour
            r += android.graphics.Color.red(pixel) * w
            g += android.graphics.Color.green(pixel) * w
            b += android.graphics.Color.blue(pixel) * w
            weight += w
        }
    }
    if (weight == 0.0) return Color(0xFF2A2233)
    val average = android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
    android.graphics.Color.colorToHSV(average, hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.65f)
    hsv[2] = 0.42f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
