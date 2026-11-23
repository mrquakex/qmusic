package com.qmusic.ui.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.VideoCast
import com.qmusic.ui.VideoCastState
import com.qmusic.ui.theme.QMusicIcons

/** Words of the TV panel, in the page's language. */
private class CastWords(english: Boolean) {
    val onTv = if (english) "Playing on the TV" else "TV'de oynatılıyor"
    val opening = if (english) "Opening on the TV..." else "TV'de açılıyor..."
    val watchHere = if (english) "Watch on the phone" else "Telefonda izle"
}

/**
 * The player's place while the video plays on the TV: its picture dimmed, what plays where, play and pause, the bar
 * (which seeks the TV), and back to the phone. Drawn over the player instead of its own controls.
 */
@Composable
internal fun CastPanel(w: WatchState, state: VideoCastState?, s: VideoStrings, controller: VideosController) {
    val words = remember(s) { CastWords(s == VideoStrings.English) }
    val segments by controller.segments.collectAsState()
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        w.video.thumbnail?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.35f, modifier = Modifier.fillMaxSize().blur(16.dp))
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.pop() }) { Icon(QMusicIcons.ChevronDown, contentDescription = com.qmusic.ui.A11y.close, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Box(Modifier.weight(1f))
            com.qmusic.ui.tv.TvButton(modifier = Modifier.size(44.dp))
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state == null) words.opening else words.onTv, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                w.details?.title ?: w.video.title,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = { state?.let { VideoCast.seekTo?.invoke((it.positionMs - 10_000).coerceAtLeast(0)) } }) {
                    Text("-10", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(enabled = state != null) { VideoCast.playPause?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == null) {
                        Loading(Modifier.size(32.dp))
                    } else {
                        Icon(if (state.playing) QMusicIcons.Pause else QMusicIcons.Play, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                IconButton(onClick = { state?.let { VideoCast.seekTo?.invoke(it.positionMs + 10_000) } }) {
                    Text("+10", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pos = state?.positionMs ?: 0L
                val dur = state?.durationMs ?: 0L
                Text("${clock(scrubbing?.let { (it * dur).toLong() } ?: pos)} / ${clock(dur)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    words.watchHere,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)).clickable { VideoCast.stop?.invoke() }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            val dur = state?.durationMs ?: 0L
            Slider(
                value = scrubbing ?: if (dur > 0) ((state?.positionMs ?: 0L).toFloat() / dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { f -> if (dur > 0) VideoCast.seekTo?.invoke((f * dur).toLong()) }
                    scrubbing = null
                },
                enabled = state != null && dur > 0,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF4E6A), inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            )
        }
    }
    // SponsorBlock on the TV: a marked part is jumped over once, by seeking the TV.
    LaunchedEffect(segments, w.video.id) {
        if (segments.isEmpty()) return@LaunchedEffect
        val skipped = HashSet<SkipSegment>()
        VideoCast.state.collect { st ->
            val pos = st?.takeIf { it.videoId == w.video.id && it.playing }?.positionMs ?: return@collect
            val hit = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 1_500 && it !in skipped } ?: return@collect
            skipped += hit
            VideoCast.seekTo?.invoke(hit.endMs)
            controller.noteSkipped()
        }
    }
}

/**
 * Keeps the watch page and the TV together: the open video is what a new TV connection plays, a video opened while a
 * TV is connected goes to it, the phone stays quiet while the TV plays it, and it goes on here where the TV left off.
 */
@Composable
internal fun VideoCastEffect(controller: VideosController, playback: VideoPlayback?) {
    val watch by controller.watch.collectAsState()
    val connected by VideoCast.connected.collectAsState()
    val state by VideoCast.state.collectAsState()
    val onPhone by VideoCast.onPhone.collectAsState()
    val id = watch?.video?.id
    LaunchedEffect(id, watch?.details?.title) {
        val w = watch
        VideoCast.watching.value = w?.let { Triple(it.video.id, it.details?.title ?: it.video.title, it.video.thumbnail) }
    }
    VideoCast.phonePosition = { playback?.positionMs?.value ?: 0L }
    // A video opened while a TV is connected plays there.
    LaunchedEffect(id, connected) {
        val w = watch ?: return@LaunchedEffect
        if (!connected || VideoCast.state.value?.videoId == w.video.id) return@LaunchedEffect
        if (VideoCast.onPhone.value == w.video.id) return@LaunchedEffect // kept on the phone
        VideoCast.cast?.invoke(w.video.id, w.details?.title ?: w.video.title, w.video.thumbnail, playback?.positionMs?.value ?: 0L)
    }
    val onTv = id != null && connected && onPhone != id && (state == null || state?.videoId == id)
    // The phone stays quiet while the TV plays the video.
    LaunchedEffect(onTv, playback) {
        val p = playback ?: return@LaunchedEffect
        if (!onTv) return@LaunchedEffect
        p.pause()
        p.isPlaying.collect { if (it) p.pause() }
    }
    // Back from the TV: on from where it was there.
    val backAt by VideoCast.backAt.collectAsState()
    LaunchedEffect(backAt, id, connected, state) {
        val (vid, at) = backAt ?: return@LaunchedEffect
        if (vid != id || (connected && state?.videoId == id)) return@LaunchedEffect
        val p = playback ?: return@LaunchedEffect
        VideoCast.backAt.value = null
        p.seekTo(at)
        if (!p.isPlaying.value) p.togglePlay()
    }
}

/** Whether the open video is (or is being put) on the TV, which takes the player's place. */
@Composable
internal fun castingHere(w: WatchState): Pair<Boolean, VideoCastState?> {
    val connected by VideoCast.connected.collectAsState()
    val state by VideoCast.state.collectAsState()
    val onPhone by VideoCast.onPhone.collectAsState()
    val here = connected && onPhone != w.video.id && (state == null || state?.videoId == w.video.id)
    return here to state?.takeIf { it.videoId == w.video.id }
}
