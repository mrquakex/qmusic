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

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
actual fun StatusAudio(url: String, playing: Boolean, startMs: Long, loopMs: Long, onReady: (Long) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val ready = rememberUpdatedState(onReady)
    val failed = rememberUpdatedState(onFailed)
    val start = rememberUpdatedState(startMs)
    val player = remember(url) {
        // YouTube holds a request with no end to about real-time speed, so the song came slowly or not at all; asked
        // for in bounded pieces (as the music player does) it comes at the line's full speed.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(com.qmusic.player.source.ChunkedDataSource.Factory(http, STATUS_CHUNK_BYTES))
        // It starts as soon as half a second is there (a status is short; waiting for 2.5 s felt broken).
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 20_000, 400, 1_000).build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            // It takes the sound: music playing in the app pauses while a status sings.
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                seekTo(startMs)
                prepare()
            }
    }
    /** [ms] moved back so that a whole part still fits in a shorter song. */
    fun fit(ms: Long): Long {
        val length = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: return ms
        return ms.coerceAtMost((length - STATUS_SONG_LENGTH).coerceAtLeast(0)).coerceAtLeast(0)
    }
    DisposableEffect(player) {
        var announced = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY || announced) return
                announced = true
                val fitted = fit(start.value)
                if (kotlin.math.abs(fitted - player.currentPosition) > 1_500) player.seekTo(fitted)
                ready.value(player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed.value()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // A part chosen again (while picking it): it plays from there.
    LaunchedEffect(player, startMs) {
        if (player.playbackState == Player.STATE_READY) player.seekTo(fit(startMs))
    }
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
        if (!playing) return@LaunchedEffect
        // It fades in once it really plays, instead of starting with a jolt.
        while (!player.isPlaying) kotlinx.coroutines.delay(40)
        if (player.volume < 1f) {
            for (step in 1..12) {
                player.volume = step / 12f
                kotlinx.coroutines.delay(45)
            }
        }
        while (loopMs > 0) {
            kotlinx.coroutines.delay(200)
            val from = fit(start.value)
            if (player.currentPosition >= from + loopMs || player.currentPosition < from - 1_000) player.seekTo(from)
        }
    }
}

private const val STATUS_CHUNK_BYTES = 1L * 1024 * 1024

@Composable
actual fun DialogEdgeToEdge() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }
}
