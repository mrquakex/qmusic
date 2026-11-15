package com.qmusic.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

@Composable
actual fun QrCode(text: String, modifier: Modifier) {
    val bitmap = remember(text) {
        // One pixel per module (the image is scaled up without smoothing, so it stays sharp).
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier, filterQuality = FilterQuality.None)
}

@Composable
actual fun rememberQrScanner(onResult: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    return remember(context) {
        {
            // Google's scanner runs in Play services: no camera permission, nothing kept.
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let { latest.value(it) } }
        }
    }
}

@Composable
actual fun StatusVideo(url: String, paused: Boolean, modifier: Modifier, onReady: (Long) -> Unit) {
    val context = LocalContext.current
    val ready = rememberUpdatedState(onReady)
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(if (url.startsWith("/")) Uri.fromFile(File(url)) else Uri.parse(url)))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready.value(player.duration.takeIf { it > 0 } ?: 0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier,
    )
}

@Composable
actual fun rememberTextFileShare(): (fileName: String, text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { fileName, text ->
            runCatching {
                // In the cache, shared through the app's file provider (only this file, read once by the app picked).
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val safe = fileName.replace(Regex("[\\/:*?\"<>|]"), "_")
                val file = File(dir, safe).apply { writeText(text) }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, safe.removeSuffix(".txt"))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
