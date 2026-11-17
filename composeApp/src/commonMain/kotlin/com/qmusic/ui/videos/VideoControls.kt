package com.qmusic.ui.videos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.engine.models.PreviewFrames
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val Lock: ImageVector = stroked("Lock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val Unlock: ImageVector = stroked("Unlock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 6.8-1.2")
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val HIDE_CONTROLS_MS = 3_000L
private const val LONG_PRESS_MS = 400L
private const val SEEK_STEP_MS = 10_000L

/** How long the double-tap seeking stays "on" after a tap: taps in that time keep adding ten seconds. */
private const val SEEK_CHAIN_MS = 750L

/** The "up next" card shows this long before the end. */
private const val UP_NEXT_MS = 10_000L

/** A swipe across the whole picture moves this far (shorter for short videos). */
private const val SWIPE_SPAN_MS = 120_000L

internal fun speedLabel(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

/** Qeyf's warm colours across [width]: the played part of the seek bar. */
private fun qeyfBrush(width: Float) = Brush.horizontalGradient(listOf(VideoColors.QeyfOrange, VideoColors.QeyfPink), startX = 0f, endX = width.coerceAtLeast(1f))

/**
 * What lies over the picture. Its parts, as YouTube lays them out and some of its own:
 * - Top: shrink (or leave full screen), the title and the channel in full screen, a lock, the settings sheet.
 * - Middle: previous, play / pause, next.
 * - Bottom: time and chapter, full screen, and the seek bar (Qeyf's colours, chapters cut into it, SponsorBlock's parts
 *   marked, a picture of the moment over the finger while seeking).
 * - Gestures: tap shows and hides; double tap on a side jumps ten seconds and every tap after it ten more; press and
 *   hold plays at double speed; a sideways swipe seeks; in full screen up and down on the sides set brightness and
 *   volume, and two fingers spread or pinch fill the screen or fit the picture.
 * - Ten seconds before the end, the next video's card counts down (autoplay), and can be dismissed.
 * - The lock ignores every touch (in a pocket, for a child) until it is unlocked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerControls(playback: VideoPlayback, w: WatchState, s: VideoStrings, controller: VideosController, fullscreen: Boolean, starting: Boolean = false) {
    var visible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var fast by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var seekSide by remember { mutableStateOf(0) }
    var seekTotal by remember { mutableStateOf(0) }
    var seekTick by remember { mutableStateOf(0) }
    var swipeFrom by remember { mutableStateOf(0L) }
    var swipeTarget by remember { mutableStateOf<Long?>(null) }
    var zoomNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isPlaying by playback.isPlaying.collectAsState()
    val position by playback.positionMs.collectAsState()
    val duration by playback.durationMs.collectAsState()
    val ended by playback.ended.collectAsState()
    val live = w.isLive()
    val x = s.extras()
    val autoplay by controller.autoplay.collectAsState()
    val sleep by controller.sleep.collectAsState()
    val skipTick by controller.skipTick.collectAsState()
    val preview by controller.preview.collectAsState()
    // The preview pictures over the seek bar: the sheets of where it is now and the next ones are fetched ahead, so
    // a held bar shows its picture at once rather than a black box while the sheet comes.
    val imageContext = coil3.compose.LocalPlatformContext.current
    val sheetNow = remember(preview, position / 60_000) { preview?.frameAt(position)?.sheetUrl }
    LaunchedEffect(preview, sheetNow) {
        val frames = preview ?: return@LaunchedEffect
        val loader = coil3.SingletonImageLoader.get(imageContext)
        val from = frames.sheetUrls.indexOf(sheetNow).coerceAtLeast(0)
        (listOf(0) + (from until from + 3)).distinct().mapNotNull { frames.sheetUrls.getOrNull(it) }.forEach { url ->
            loader.enqueue(coil3.request.ImageRequest.Builder(imageContext).data(url).size(coil3.size.Size.ORIGINAL).build())
        }
    }
    val segments by controller.segments.collectAsState()
    val cancelled by controller.autoplayCancelled.collectAsState()
    val chapters = remember(w.details?.description) { chaptersOf(w.details?.description) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(skipTick) {
        if (skipTick > 0) {
            note = x.sponsorSkipped
            delay(1_800)
            note = null
        }
    }
    val device = rememberDeviceControls()
    // The brightness or volume being changed, and how far: shown for a moment.
    var hud by remember { mutableStateOf<Pair<String, Float>?>(null) }
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }
    LaunchedEffect(zoomNote) {
        if (zoomNote != null) {
            delay(900)
            zoomNote = null
        }
    }
    LaunchedEffect(seekTick) {
        if (seekTick > 0) {
            delay(SEEK_CHAIN_MS)
            seekSide = 0
            seekTotal = 0
        }
    }
    LaunchedEffect(showUnlock) {
        if (showUnlock) {
            delay(2_500)
            showUnlock = false
        }
    }
    // The controls fade away while playing, and stay while paused, while the sheet is open or while seeking.
    LaunchedEffect(visible, isPlaying, sheet, scrubbing) {
        if (visible && isPlaying && !sheet && scrubbing == null) {
            delay(HIDE_CONTROLS_MS)
            visible = false
        }
    }

    // Locked: every touch is held here (nothing reaches the page or the player) and only brings the unlock button.
    if (locked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        showUnlock = true
                    }
                },
        ) {
            if (showUnlock) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable {
                            locked = false
                            visible = true
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(VideoIcons.Unlock, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(x.unlock, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        return
    }

    /** Ten seconds more to [side] (-1 back, 1 ahead), added to the taps just before it. */
    fun chainSeek(side: Int) {
        if (seekSide != side) {
            seekSide = side
            seekTotal = 0
        }
        seekTotal += (SEEK_STEP_MS / 1000).toInt()
        val end = playback.durationMs.value.takeIf { it > 0 } ?: Long.MAX_VALUE
        playback.seekTo((playback.positionMs.value + side * SEEK_STEP_MS).coerceIn(0, end))
        seekTick++
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
            // A sideways swipe anywhere on the picture seeks; the target time shows in the middle until let go.
            .pointerInput(w.video.id, live) {
                if (live) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        swipeFrom = playback.positionMs.value
                        swipeTarget = swipeFrom
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val total = playback.durationMs.value
                        if (total > 0) {
                            val span = minOf(total, SWIPE_SPAN_MS).toFloat()
                            swipeTarget = ((swipeTarget ?: swipeFrom) + (dx / size.width * span).toLong()).coerceIn(0, total)
                        }
                    },
                    onDragEnd = {
                        swipeTarget?.let { playback.seekTo(it) }
                        swipeTarget = null
                    },
                    onDragCancel = { swipeTarget = null },
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
                    // A hold (for 2x) is not a tap: the controls do not come up when it is let go.
                    onLongPress = {},
                    onTap = { at ->
                        // Right after a double tap, single taps keep seeking (as on YouTube).
                        if (seekSide != 0 && !live) chainSeek(if (at.x < size.width / 2f) -1 else 1)
                        else visible = !visible
                    },
                    onDoubleTap = { at ->
                        when {
                            live -> playback.togglePlay()
                            at.x < size.width / 3f -> chainSeek(-1)
                            at.x > size.width * 2f / 3f -> chainSeek(1)
                            else -> playback.togglePlay()
                        }
                    },
                )
            }
            // Full screen: two fingers spread to fill the screen with the picture, pinch to fit it again. Only touches of
            // two fingers are taken; one finger goes on to the gestures above.
            .pointerInput(fullscreen) {
                if (!fullscreen) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var two = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            two = true
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (two && zoom > 1.12f && !controller.fill.value) {
                        controller.setFill(true)
                        zoomNote = x.zoomFill
                    } else if (two && zoom < 0.89f && controller.fill.value) {
                        controller.setFill(false)
                        zoomNote = x.zoomFit
                    }
                }
            },
    ) {
        if (seekSide != 0) SeekRipple(seekSide, "$seekTotal ${x.seconds}", seekTick)
        swipeTarget?.let { target ->
            val delta = target - swipeFrom
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clock(target), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    (if (delta >= 0) "+" else "-") + clock(abs(delta)),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (fast) Pill("2x  ▶▶", Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        else (zoomNote ?: note)?.let { Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 12.dp), small = true) }
        hud?.let { (label, value) -> Pill("$label  ${(value * 100).toInt()}%", Modifier.align(Alignment.Center)) }

        if ((visible || ended) && swipeTarget == null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))

            // Top: shrink (or leave full screen), the title in full screen, the lock, the settings.
            Row(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 4.dp, vertical = if (fullscreen) 6.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (fullscreen) controller.setFullscreen(false) else controller.pop() }) {
                    Icon(QMusicIcons.ChevronDown, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                if (fullscreen) {
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(w.details?.title ?: w.video.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (w.details?.channelName ?: w.video.channelName)?.let {
                            Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = {
                        locked = true
                        showUnlock = true
                    }) { Icon(VideoIcons.Lock, x.lock, tint = Color.White, modifier = Modifier.size(22.dp)) }
                } else {
                    Box(Modifier.weight(1f))
                }
                com.qmusic.ui.tv.TvButton(modifier = Modifier.size(44.dp))
                IconButton(onClick = { sheet = true }) { Icon(QMusicIcons.Settings, x.playerSettings, tint = Color.White, modifier = Modifier.size(22.dp)) }
            }

            // Middle: previous, play / pause, next (not while it starts: the spinner is there then).
            if (!starting) Row(
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
                val hasNext = w.related.isNotEmpty() || w.queue.size > w.queueIndex + 1
                IconButton(onClick = { controller.playNext() }, enabled = hasNext, modifier = Modifier.size(48.dp)) {
                    Icon(QMusicIcons.Next, null, tint = Color.White.copy(alpha = if (hasNext) 1f else 0.35f), modifier = Modifier.size(30.dp))
                }
            }

            // Bottom: time and full screen, with the seek bar under them.
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = if (fullscreen) 8.dp else 0.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val chapter = currentChapter(chapters, scrubbing?.toLong() ?: position)
                    Text(
                        if (starting) "" else if (live) s.live else "${clock(scrubbing?.toLong() ?: position)} / ${clock(duration)}" + (chapter?.let { "  •  ${it.title}" } ?: ""),
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { controller.setFullscreen(!fullscreen) }, modifier = Modifier.size(40.dp)) {
                        Icon(if (fullscreen) VideoIcons.FullscreenExit else VideoIcons.Fullscreen, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                if (!live && !starting) {
                    SeekBar(
                        position = position,
                        duration = duration,
                        scrubbing = scrubbing,
                        onScrub = { scrubbing = it },
                        onSeek = { playback.seekTo(it) },
                        chapters = chapters.map { it.timeMs },
                        segments = segments,
                        preview = preview,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        } else if (!live && duration > 0 && !fullscreen && swipeTarget == null) {
            // Hidden controls leave a hairline of progress, as YouTube does, in Qeyf's colours.
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)) {
                drawRect(Color.White.copy(alpha = 0.25f))
                drawRect(qeyfBrush(size.width), size = Size(size.width * fraction, size.height))
            }
        }

        // Ten seconds before the end: the next video, counting down (autoplay), whether the controls show or not.
        val next = remember(w.video.id, w.related, w.queue) { controller.nextUp() }
        val left = duration - position
        if (next != null && !live && autoplay != "0" && cancelled != w.video.id && sleep.mode != SleepMode.VIDEO_END &&
            duration > UP_NEXT_MS * 2 && left in 1..UP_NEXT_MS && scrubbing == null && swipeTarget == null
        ) {
            UpNextCard(
                next = next,
                label = "${x.upNext} · ${(left / 1000).coerceAtLeast(1)}",
                onPlay = { controller.playNext() },
                onCancel = { controller.cancelAutoplay() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = if (fullscreen) 72.dp else 56.dp),
            )
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = VideoColors.Surface) {
            Column(modifier = Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text(x.playerSettings, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    SwitchRow(x.autoplay, null, autoplay != "0") { controller.setAutoplay(it) }
                }
                if (w.qualities.size > 1) {
                    SheetSection(s.quality) {
                        items(w.qualities) { q -> LabelChip(q.label, q == w.quality) { controller.selectQuality(q) } }
                    }
                }
                if (!live) {
                    SheetSection(s.speed) {
                        items(SPEEDS) { value ->
                            LabelChip(speedLabel(value), value == speed) {
                                speed = value
                                playback.setSpeed(value)
                            }
                        }
                    }
                }
                if (w.captions.isNotEmpty()) {
                    SheetSection(s.captions) {
                        item { LabelChip(s.captionsOff, w.caption == null) { controller.selectCaption(null) } }
                        // The person's own words first, then the machine's, a handful of each.
                        items(w.captions.filter { !it.auto }.take(6) + w.captions.filter { it.auto }.take(2)) { track ->
                            LabelChip(if (track.auto) "${track.label} (auto)" else track.label, track == w.caption) { controller.selectCaption(track) }
                        }
                    }
                }
                SheetSection(x.sleepTimer) {
                    item { LabelChip(x.sleepOffLabel, sleep.mode == SleepMode.OFF) { controller.clearSleep() } }
                    items(listOf(15, 30, 45, 60)) { minutes ->
                        LabelChip(x.sleepMinutes(minutes), sleep.mode == SleepMode.TIMER && sleep.minutes == minutes) { controller.setSleepMinutes(minutes) }
                    }
                    if (!live) item { LabelChip(x.sleepVideoEnd, sleep.mode == SleepMode.VIDEO_END) { controller.setSleepAtVideoEnd() } }
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Text(title, color = VideoColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The side of the picture that was double tapped lights up, with the seconds jumped so far. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SeekRipple(side: Int, label: String, tick: Int) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(SEEK_CHAIN_MS.toInt()))
    }
    val left = side < 0
    Box(
        modifier = Modifier
            .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(0.4f)
            .clip(
                if (left) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
            )
            .background(Color.White.copy(alpha = 0.10f + 0.14f * flash.value)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (left) "◀◀" else "▶▶", color = Color.White.copy(alpha = 0.6f + 0.4f * flash.value), fontSize = 18.sp)
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The next video near the end: tap plays it now, the cross keeps this one from going on. */
@Composable
private fun UpNextCard(next: com.qmusic.engine.video.VideoItem, label: String, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(VideoColors.Surface)) {
            if (next.thumbnail != null) {
                coil3.compose.AsyncImage(model = next.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp)) {
            Text(label, color = VideoColors.QeyfOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(next.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(QMusicIcons.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier, small: Boolean = false) {
    Text(
        text,
        color = Color.White,
        fontSize = if (small) 13.sp else 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private val PREVIEW_WIDTH = 168.dp

/**
 * Qeyf's seek bar: the played part runs from orange to pink, chapters cut small gaps into it, the parts SponsorBlock
 * skips are marked, and it thickens under the finger. While seeking, the picture of that moment floats over the finger.
 * Tap anywhere on it, or drag.
 */
@Composable
internal fun SeekBar(
    position: Long,
    duration: Long,
    scrubbing: Float?,
    onScrub: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Long> = emptyList(),
    segments: List<SkipSegment> = emptyList(),
    preview: PreviewFrames? = null,
) {
    val total = duration.coerceAtLeast(1L).toFloat()
    val fraction = ((scrubbing ?: position.toFloat()) / total).coerceIn(0f, 1f)
    val current by rememberUpdatedState(scrubbing)
    val thickness by animateDpAsState(if (scrubbing != null) 6.dp else 3.dp, label = "seekThickness")
    val knob by animateDpAsState(if (scrubbing != null) 9.dp else 6.dp, label = "seekKnob")
    var widthPx by remember { mutableStateOf(0f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
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
        if (scrubbing != null && preview != null && widthPx > 0f) {
            preview.frameAt(scrubbing.toLong())?.let { frame -> SeekPreview(preview, frame, clock(scrubbing.toLong()), fraction, widthPx) }
        }
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val h = thickness.toPx()
            val r = CornerRadius(h / 2)
            val gap = 2.dp.toPx()
            val played = size.width * fraction
            val brush = qeyfBrush(size.width)
            // The track in pieces, one per chapter, with a small gap between them.
            val cuts = chapters.filter { it in 1 until duration }.sorted().map { it / total * size.width }
            var start = 0f
            (cuts + size.width).forEach { end ->
                val pieceEnd = if (end < size.width) end - gap / 2 else end
                val pieceStart = if (start > 0f) start + gap / 2 else start
                if (pieceEnd > pieceStart) {
                    drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(pieceStart, y - h / 2), Size(pieceEnd - pieceStart, h), r)
                    val filledEnd = minOf(pieceEnd, played)
                    if (filledEnd > pieceStart) drawRoundRect(brush, Offset(pieceStart, y - h / 2), Size(filledEnd - pieceStart, h), r)
                }
                start = end
            }
            // What SponsorBlock will skip.
            segments.forEach { seg ->
                val a = seg.startMs / total * size.width
                val b = seg.endMs / total * size.width
                if (b > a) drawRect(VideoColors.Sponsor.copy(alpha = 0.9f), Offset(a, y - h / 2), Size(b - a, h))
            }
            drawCircle(brush, radius = knob.toPx(), center = Offset(played, y))
        }
    }
}

/**
 * The picture of the held moment with its time, centred over the finger and kept on screen. It is cut out of YouTube's
 * sheet of small pictures, and placed over the page without taking room in it.
 */
@Composable
private fun SeekPreview(preview: PreviewFrames, frame: PreviewFrames.Frame, time: String, fraction: Float, barWidthPx: Float) {
    val density = LocalDensity.current
    val widthPx = with(density) { PREVIEW_WIDTH.toPx() }
    val scale = widthPx / preview.frameWidth
    val heightPx = preview.frameHeight * scale
    val gapPx = with(density) { 10.dp.toPx() }
    val x = (barWidthPx * fraction - widthPx / 2).coerceIn(0f, (barWidthPx - widthPx).coerceAtLeast(0f))
    val y = -gapPx - heightPx
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(widthPx.toInt(), heightPx.toInt()))
                layout(0, 0) { placeable.place(x.toInt(), y.toInt()) }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        coil3.compose.AsyncImage(
            model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                .data(frame.sheetUrl)
                .size(coil3.size.Size.ORIGINAL)
                .build(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.FixedScale(scale),
            alignment = Alignment.TopStart,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset((-frame.left * scale).toInt(), (-frame.top * scale).toInt()) }
                .requiredSize(
                    with(density) { (preview.frameWidth * preview.framesPerRow * scale).toDp() },
                    with(density) { (preview.frameHeight * preview.framesPerColumn * scale).toDp() },
                ),
        )
        Text(
            time,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
