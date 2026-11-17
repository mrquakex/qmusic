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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** The system photo picker, up to ten at once (no storage permission needed); they come back shrunk and compressed. */
@Composable
actual fun rememberImagePicker(onPicked: (List<PickedImage>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // Prepared side by side (each on its own core), in the order picked.
            val picked = withContext(Dispatchers.Default) {
                uris.map { uri -> async { runCatching { prepare(context, uri) }.getOrNull() } }.mapNotNull { it.await() }
            }
            onPicked(picked)
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** The camera app takes the photo into the app's cache; it comes back like a picked one. Asks for the camera first. */
@Composable
actual fun rememberCamera(onPicked: (PickedImage?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = androidx.compose.runtime.remember { arrayOfNulls<Uri>(1) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = target[0]
        if (!taken || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onPicked(withContext(Dispatchers.Default) { runCatching { prepare(context, uri) }.getOrNull() })
        }
    }
    fun open() {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        target[0] = uri
        camera.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) open() }
    return {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            open()
        } else {
            permission.launch(android.Manifest.permission.CAMERA)
        }
    }
}

private const val MAX_PICK = 10
private const val MAX_SIDE = 1600
private const val MAX_BYTES = 1_000_000
private const val HD_SIDE = 4096
private const val HD_BYTES = 6_000_000
private const val THUMB_SIDE = 24

/**
 * Decodes (upright, already scaled down while decoding), shrinks to [maxSide], compresses to under [maxBytes]. The
 * standard one carries how to make the HD one (up to [HD_SIDE]) when the photo is bigger than the standard size.
 */
internal fun prepare(context: Context, uri: Uri, maxSide: Int = MAX_SIDE, maxBytes: Int = MAX_BYTES): PickedImage {
    val bitmap = decode(context, uri, maxSide)
    val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true) else bitmap
    // The photo as taken is clearly bigger than the standard size (decoding above already shrank it).
    val bigger = maxSide == MAX_SIDE && originalSide(context, uri) > MAX_SIDE * 11 / 10

    var quality = if (maxSide > MAX_SIDE) 92 else 84
    var jpeg: ByteArray
    do {
        jpeg = ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        quality -= 10
    } while (jpeg.size > maxBytes && quality > 30)

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
    val appContext = context.applicationContext
    return PickedImage(
        jpeg = jpeg,
        width = sized.width,
        height = sized.height,
        thumb = thumb,
        hd = if (bigger) ({ kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { prepare(appContext, uri, HD_SIDE, HD_BYTES) } }) else null,
    )
}

/** The longer side of the photo as stored (0 when it cannot be read). */
private fun originalSide(context: Context, uri: Uri): Int = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    max(bounds.outWidth, bounds.outHeight)
}.getOrDefault(0)

private fun decode(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder turns the photo upright (EXIF) and decodes it at a smaller size directly.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > maxSide) {
                val s = maxSide.toFloat() / side
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("cannot read the photo")
}

/**
 * The phone's latest photos for the attach panel: asked for with the photos permission (on Android 14 and later the
 * person may allow only some; those show then), read newest first.
 */
@Composable
actual fun rememberRecentPhotos(enabled: Boolean): RecentPhotos {
    val context = LocalContext.current
    fun allowed(): Boolean {
        fun has(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> has(android.Manifest.permission.READ_MEDIA_IMAGES) || has(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> has(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> has(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    var granted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(allowed()) }
    var photos by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = allowed() }
    androidx.compose.runtime.LaunchedEffect(granted, enabled) {
        if (!granted || !enabled) return@LaunchedEffect
        photos = withContext(Dispatchers.IO) {
            runCatching {
                val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.Images.Media._ID),
                    null,
                    null,
                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC",
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val out = ArrayList<String>()
                    while (c.moveToNext() && out.size < RECENT_PHOTOS) {
                        out += android.content.ContentUris.withAppendedId(uri, c.getLong(id)).toString()
                    }
                    out
                }.orEmpty()
            }.getOrDefault(emptyList())
        }
    }
    return RecentPhotos(
        photos = photos,
        allowed = granted,
        ask = {
            launcher.launch(
                when {
                    Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
                    else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                },
            )
        },
        prepare = { u -> withContext(Dispatchers.Default) { runCatching { prepare(context, Uri.parse(u)) }.getOrNull() } },
    )
}

private const val RECENT_PHOTOS = 40
