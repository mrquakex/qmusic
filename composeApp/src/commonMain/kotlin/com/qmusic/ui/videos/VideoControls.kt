package com.qmusic.ui.videos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The few icons the section needs that the music app's set does not have. */
object VideoIcons {
    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Fullscreen: ImageVector = stroked("Fullscreen", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5")
    val FullscreenExit: ImageVector = stroked("FullscreenExit", "M9 4v5H4M15 4v5h5M20 15h-5v5M4 15h5v5")
    val Comment: ImageVector = stroked("Comment", "M5 4h14a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-4 4v-4H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Shorts: ImageVector = stroked("Shorts", "M8 3h8a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z", "M10.5 9.5v5l4-2.5z")
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val HIDE_CONTROLS_MS = 3_000L
private const val LONG_PRESS_MS = 400L
private const val SEEK_STEP_MS = 10_000L

internal fun speedLabel(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

/**
 * What lies over the picture, laid out as YouTube lays it: shrink on the top left and settings on the top right, the
 * previous / play / next buttons in the middle, time and full screen under them, and a thin seek bar along the bottom.
 * Press and hold plays at double speed until let go; double tap on a side jumps ten seconds.
 */
@Composable
internal fun PlayerControls(playback: VideoPlayback, w: WatchState, s: VideoStrings, controller: VideosController, fullscreen: Boolean) {
    var visible by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var fast by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isPlaying by playback.isPlaying.collectAsState()
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    val ended by playback.ended.collectAsState()
    val live = w.isLive()
    val device = rememberDeviceControls()
    // The brightness or volume being changed, and how far: shown for a moment.
    var hud by remember { mutableStateOf<Pair<String, Float>?>(null) }
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }

    // The controls fade away while playing, and stay while paused, while a menu is open or while seeking.
    LaunchedEffect(visible, isPlaying, menu, scrubbing) {
        if (visible && isPlaying && !menu && scrubbing == null) {
            delay(HIDE_CONTROLS_MS)
            visible = false
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Full screen only: drag up and down on the left third for brightness, on the right third for volume.
            .pointerInput(fullscreen, device) {
                if (!fullscreen || device == null) return@pointerInput
                var area = 0
                var value = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        area = when {
                            o.x < size.width / 3f -> 1
                            o.x > size.width * 2f / 3f -> 2
                            else -> 0
                        }
                        value = if (area == 1) device.brightness else if (area == 2) device.volume else 0f
                    },
                    onVerticalDrag = { change, dy ->
                        if (area != 0) {
                            change.consume()
                            value = (value - dy / size.height * 1.3f).coerceIn(0f, 1f)
                            if (area == 1) device.brightness = value else device.volume = value
                            hud = (if (area == 1) s.brightness else s.volumeLabel) to value
                        }
                    },
                )
            }
            .pointerInput(w.video.id, live) {
                detectTapGestures(
                    onPress = {
                        val hold = scope.launch {
                            delay(LONG_PRESS_MS)
                            if (!live) {
                                fast = true
                                playback.setSpeed(2f)
                            }
                        }
                        tryAwaitRelease()
                        hold.cancel()
                        if (fast) {
                            fast = false
                            playback.setSpeed(speed)
                        }
                    },
                    onTap = { visible = !visible },
                    onDoubleTap = { at ->
                        when {
                            live -> playback.togglePlay()
                            at.x < size.width / 3f -> {
                                playback.seekTo((playback.positionMs.value - SEEK_STEP_MS).coerceAtLeast(0))
                                seekHint = "-10"
                            }
                            at.x > size.width * 2f / 3f -> {
                                playback.seekTo(playback.positionMs.value + SEEK_STEP_MS)
                                seekHint = "+10"
                            }
                            else -> playback.togglePlay()
                        }
                    },
                )
            },
    ) {
        seekHint?.let { hint ->
            Pill(hint, Modifier.align(if (hint.startsWith("-")) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 40.dp))
        }
        if (fast) Pill("2x  ▶▶", Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        hud?.let { (label, value) -> Pill("$label  ${(value * 100).toInt()}%", Modifier.align(Alignment.Center)) }

        if (visible || ended) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))

            // Top: shrink (or leave full screen) on the left, settings on the right.
            Row(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (fullscreen) controller.setFullscreen(false) else controller.pop() }) {
                    Icon(QMusicIcons.ChevronDown, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Box(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(QMusicIcons.Settings, s.quality, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (w.qualities.size > 1) {
                            DropdownMenuItem(text = { Text(s.quality, fontWeight = FontWeight.Bold) }, onClick = {}, enabled = false)
                            w.qualities.forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(if (q == w.quality) "${q.label}  ✓" else q.label) },
                                    onClick = {
                                        menu = false
                                        controller.selectQuality(q)
                                    },
                                )
                            }
                        }
                        if (w.captions.isNotEmpty()) {
                            DropdownMenuItem(text = { Text(s.captions, fontWeight = FontWeight.Bold) }, onClick = {}, enabled = false)
                            DropdownMenuItem(
                                text = { Text(if (w.caption == null) "${s.captionsOff}  ✓" else s.captionsOff) },
                                onClick = {
                                    menu = false
                                    controller.selectCaption(null)
                                },
                            )
                            // The person's own words first, then the machine's, a handful of each.
                            (w.captions.filter { !it.auto }.take(6) + w.captions.filter { it.auto }.take(2)).forEach { track ->
                                val name = if (track.auto) "${track.label} (auto)" else track.label
                                DropdownMenuItem(
                                    text = { Text(if (track == w.caption) "$name  ✓" else name) },
                                    onClick = {
                                        menu = false
                                        controller.selectCaption(track)
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(text = { Text(s.speed, fontWeight = FontWeight.Bold) }, onClick = {}, enabled = false)
                        SPEEDS.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(if (value == speed) "${speedLabel(value)}  ✓" else speedLabel(value)) },
                                onClick = {
                                    speed = value
                                    playback.setSpeed(value)
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }

            // Middle: previous, play / pause, next.
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                IconButton(onClick = { if (!controller.playPrevious()) playback.seekTo(0) }, enabled = !live, modifier = Modifier.size(48.dp)) {
                    Icon(QMusicIcons.Previous, null, tint = Color.White.copy(alpha = if (live) 0.35f else 1f), modifier = Modifier.size(30.dp))
                }
                IconButton(
                    onClick = { playback.togglePlay() },
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(if (isPlaying) QMusicIcons.Pause else QMusicIcons.Play, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                val hasNext = w.related.isNotEmpty()
                IconButton(onClick = { controller.playNext() }, enabled = hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(QMusicIcons.Next, null, tint = Color.White.copy(alpha = if (hasNext) 1f else 0.35f), modifier = Modifier.size(30.dp))
                }
            }

            // Bottom: time and full screen, with the seek bar under them.
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val chapters = remember(w.details?.description) { chaptersOf(w.details?.description) }
                    val chapter = currentChapter(chapters, scrubbing?.toLong() ?: position)
                    Text(
                        if (live) s.live else "${clock(scrubbing?.toLong() ?: position)} / ${clock(duration)}" + (chapter?.let { "  •  ${it.title}" } ?: ""),
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { controller.setFullscreen(!fullscreen) }, modifier = Modifier.size(40.dp)) {
                        Icon(if (fullscreen) VideoIcons.FullscreenExit else VideoIcons.Fullscreen, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                if (!live) {
                    SeekBar(
                        position = position,
                        duration = duration,
                        scrubbing = scrubbing,
                        onScrub = { scrubbing = it },
                        onSeek = { playback.seekTo(it) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        } else if (!live && duration > 0) {
            // Hidden controls leave a hairline of progress, as YouTube does.
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(VideoColors.Red))
            }
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A thin red seek bar with a small round handle: tap anywhere on it, or drag the handle. */
@Composable
internal fun SeekBar(
    position: Long,
    duration: Long,
    scrubbing: Float?,
    onScrub: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = duration.coerceAtLeast(1L).toFloat()
    val fraction = ((scrubbing ?: position.toFloat()) / total).coerceIn(0f, 1f)
    val current by rememberUpdatedState(scrubbing)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(total) { detectTapGestures { o -> onSeek(((o.x / size.width) * total).toLong()) } }
            .pointerInput(total) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onScrub((o.x / size.width * total).coerceIn(0f, total)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onScrub((change.position.x / size.width * total).coerceIn(0f, total))
                    },
                    onDragEnd = {
                        current?.let { onSeek(it.toLong()) }
                        onScrub(null)
                    },
                    onDragCancel = { onScrub(null) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val h = (if (scrubbing != null) 5.dp else 3.dp).toPx()
            val r = CornerRadius(h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y - h / 2), Size(size.width, h), r)
            drawRoundRect(VideoColors.Red, Offset(0f, y - h / 2), Size(size.width * fraction, h), r)
            drawCircle(VideoColors.Red, radius = (if (scrubbing != null) 8.dp else 6.dp).toPx(), center = Offset(size.width * fraction, y))
        }
    }
}
