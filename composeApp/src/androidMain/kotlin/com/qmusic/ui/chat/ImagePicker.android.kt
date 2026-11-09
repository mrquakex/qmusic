package com.qmusic.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** The system photo picker (no storage permission needed); the photo comes back shrunk and compressed. */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.Default) { runCatching { prepare(context, uri) }.getOrNull() }
            onPicked(picked)
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private const val MAX_SIDE = 1600
private const val MAX_BYTES = 1_000_000
private const val THUMB_SIDE = 24

/** Decodes (upright, already scaled down while decoding), shrinks to [MAX_SIDE], compresses to under [MAX_BYTES]. */
private fun prepare(context: Context, uri: Uri): PickedImage {
    val bitmap = decode(context, uri)
    val scale = MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true) else bitmap

    var quality = 84
    var jpeg: ByteArray
    do {
        jpeg = ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        quality -= 10
    } while (jpeg.size > MAX_BYTES && quality > 30)

    val thumbScale = THUMB_SIDE.toFloat() / max(sized.width, sized.height)
    val thumbBitmap = Bitmap.createScaledBitmap(
        sized,
        (sized.width * thumbScale).roundToInt().coerceAtLeast(1),
        (sized.height * thumbScale).roundToInt().coerceAtLeast(1),
        true,
    )
    val thumb = ByteArrayOutputStream().use { out ->
        thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
    return PickedImage(jpeg = jpeg, width = sized.width, height = sized.height, thumb = thumb)
}

private fun decode(context: Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder turns the photo upright (EXIF) and decodes it at a smaller size directly.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("cannot read the photo")
}
