package com.qmusic.ui.videos

import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.BoxWithConstraints
import com.qmusic.engine.video.VideoComment
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.engine.video.VideoEntry
import com.qmusic.engine.video.VideoQuality
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged

/** The page of one video: the picture on top, then what it is, what the account can do with it, comments, what's next. */
@Composable
fun WatchPage(
    controller: VideosController,
    playback: VideoPlayback?,
    s: VideoStrings,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watch by controller.watch.collectAsState()
    val fullscreen by controller.fullscreen.collectAsState()
    val pip = rememberInPictureInPicture()
    val w = watch ?: return
    val seek: (Long) -> Unit = { playback?.seekTo(it) }
    when {
        // The floating window shows the picture and nothing else.
        pip -> Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            if (playback != null) VideoPlaybackSurface(playback, Modifier.fillMaxSize())
        }
        fullscreen -> {
            // Leaving and more, as on YouTube. Down: the picture follows the finger and gets a little smaller, the
            // black goes grey; let go far enough and it flies to where the page holds it, the page appearing (the
            // full screen ends), else it springs back. Up (upright): the details of the page come up from the bottom
            // as a sheet while the picture stays; let go far enough and the sheet stays, a tap outside closes it.
            // Sideways full screen: a drag either way leaves it.
            val drag = remember { Animatable(0f) }
            val exit = remember { Animatable(0f) }
            val sheet = remember { Animatable(0f) }
            val upright by controller.fullscreenUpright.collectAsState()
            // Coming in upright (the page pulled down): the picture travels from its place on the page to the middle
            // while the page fades into black, the same way as leaving, backwards.
            val enter = remember { Animatable(if (upright) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (enter.value > 0f) enter.animateTo(0f, tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
            val raw = remember { floatArrayOf(0f, 0f) }
            val dragScope = rememberCoroutineScope()
            var screenHeight by remember { mutableStateOf(1f) }
            var screenWidth by remember { mutableStateOf(1f) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density).toFloat()
            val exitAt = with(density) { 110.dp.toPx() }
            val sheetPx = { screenHeight * 0.6f }
            // 0 (full screen) to 1 (far enough to leave).
            val dragged = { (kotlin.math.abs(drag.value) / (screenHeight * 0.3f)).coerceIn(0f, 1f) }
            // Where the page holds the picture's centre, from the screen's centre (under the status bar).
            val slotShift = { (statusTop + screenWidth * 9f / 16f / 2f) - screenHeight / 2f }
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .drawBehind {
                        val grey = Color(0xFF151515)
                        val base = if (upright) androidx.compose.ui.graphics.lerp(Color.Black, grey, dragged())
                        else androidx.compose.ui.graphics.lerp(Color.Black, VideoColors.Background, dragged())
                        drawRect(base.copy(alpha = 1f - maxOf(exit.value, enter.value)))
                    }
                    .onSizeChanged {
                        screenHeight = it.height.toFloat().coerceAtLeast(1f)
                        screenWidth = it.width.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(upright) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragScope.launch { drag.stop(); sheet.stop() }
                                raw[0] = drag.value
                                raw[1] = sheet.value
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                // The details sheet open: the drag moves it; otherwise the picture follows the finger,
                                // down or up (both leave upright, as both leave sideways).
                                if (upright && raw[1] > 0f) {
                                    raw[1] = (raw[1] - dy / sheetPx()).coerceIn(0f, 1f)
                                } else {
                                    raw[0] += dy
                                }
                                val d = raw[0]
                                val sh = raw[1]
                                dragScope.launch { drag.snapTo(d); sheet.snapTo(sh) }
                            },
                            onDragEnd = {
                                dragScope.launch {
                                    if (upright) {
                                        when {
                                            kotlin.math.abs(raw[0]) > exitAt -> {
                                                // To its place on the page, the page coming up around it.
                                                exit.animateTo(1f, tween(300, easing = ease))
                                                controller.setFullscreen(false)
                                            }
                                            raw[1] > 0f -> sheet.animateTo(if (raw[1] > 0.5f) 1f else 0f, tween(200, easing = ease))
                                            else -> drag.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f))
                                        }
                                    } else if (kotlin.math.abs(raw[0]) > exitAt) {
                                        val way = if (raw[0] < 0f) -1f else 1f
                                        drag.animateTo(way * screenHeight * 0.3f, tween(180, easing = ease))
                                        controller.setFullscreen(false)
                                    } else {
                                        drag.animateTo(0f, tween(220, easing = ease))
                                    }
                                }
                            },
                            onDragCancel = {
                                dragScope.launch {
                                    launch { sheet.animateTo(if (sheet.value > 0.3f) 1f else 0f, tween(200)) }
                                    drag.animateTo(0f, tween(220))
                                }
                            },
                        )
                    },
            ) {
                // The page the picture is flying to (or from): it shows more the further the picture is dragged, and
                // in full as it lands; with its light (the video's colours) as on the page itself.
                val pageShown = { maxOf(exit.value, enter.value, dragged() * 0.55f) }
                if (upright && (exit.value > 0f || enter.value > 0f || drag.value != 0f)) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageShown() }.background(VideoColors.Background)) {
                        if (!com.qmusic.ui.theme.lightTheme) AmbientGlow(w.video.thumbnail, Modifier.fillMaxWidth().statusBarsPadding())
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f))
                            WatchDetails(controller, w, s, onShare, onSignIn, seek, showRelated = true)
                        }
                    }
                }
                // Upright, the black around the picture holds its colours, softly (gone as it leaves).
                if (upright && !com.qmusic.ui.theme.lightTheme) {
                    UprightGlow(w.video.thumbnail, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - pageShown() })
                }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = dragged()
                        if (upright) {
                            // Leaving or coming in: between the middle (0) and its place on the page (1).
                            val e = maxOf(exit.value, enter.value)
                            val scale = (1f - 0.06f * p) * (1f - e) + e
                            val follow = drag.value - sheet.value * sheetPx() * 0.18f
                            translationY = follow * (1f - e) + slotShift() * e
                            scaleX = scale
                            scaleY = scale
                        } else {
                            val scale = 1f - 0.22f * p
                            translationY = drag.value
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.25f * p
                        }
                    },
                ) {
                    PlayerBox(w, playback, s, controller, true, Modifier.fillMaxSize(), plain = true)
                }
                // The details sheet (upright): over the picture, from the bottom.
                if (upright && sheet.value > 0f) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f * sheet.value))
                            .pointerInput(Unit) { detectTapGestures { dragScope.launch { sheet.animateTo(0f, tween(200, easing = ease)) } } },
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f)
                            .graphicsLayer { translationY = (1f - sheet.value) * size.height }
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                            .background(VideoColors.Background),
                    ) {
                        WatchDetails(controller, w, s, onShare, onSignIn, seek, showRelated = false)
                    }
                }
            }
        }
        else -> BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            // Dragging the picture down shrinks the video into the small player, as on YouTube: it gets smaller and
            // slides to the bottom corner, what is about it fades away and the page under it shows; let go far
            // enough and it lands just where the small player is.
            val offset = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            var height by remember { mutableStateOf(1f) }
            var total by remember { mutableStateOf(0f) }
            val wide = maxWidth >= WIDE_WATCH && maxHeight >= 600.dp
            val density = androidx.compose.ui.platform.LocalDensity.current
            val margin = with(density) { 12.dp.toPx() }
            val width = with(density) { maxWidth.toPx() }
            val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density).toFloat()
            // The small player: half the width (300 dp at most), 12 dp from the bottom right corner.
            val miniScale = (minOf(width * 0.5f, with(density) { 300.dp.toPx() }) / width).coerceIn(0.2f, 1f)
            val travel = (height - margin - (statusTop + width * 9f / 16f) * miniScale).coerceAtLeast(1f)
            val progress = { (offset.value / travel).coerceIn(0f, 1f) }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { progress() }.collect { controller.watchDrag.value = it }
            }
            // Pulling the page down from its top (the title, not the picture), as on YouTube: the picture slides
            // down a little, what is about it slides down more and darkens, black opens between them; let go far
            // enough and the video goes full screen, else it all settles back.
            var pull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            val pullFull = with(density) { PULL_FULLSCREEN.toPx() }
            var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val settle: () -> Unit = {
                val from = pull
                settleJob?.cancel()
                settleJob = scope.launch { androidx.compose.animation.core.animate(from, 0f, animationSpec = tween(220)) { v, _ -> pull = v } }
            }
            // The finger lifted (with or without a fling): far enough, full screen upright (the phone is not
            // turned: the video fills the screen as it is held, centred on black); else back in place.
            val release: () -> Unit = {
                if (pull >= pullFull) {
                    settleJob?.cancel()
                    pull = 0f
                    controller.setFullscreen(true, upright = true)
                } else if (pull > 0f) {
                    settle()
                }
            }
            val pullConnection = remember(pullFull) {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                        // Pulled and now pushed back up: the pull gives way first, then the page scrolls.
                        if (pull > 0f && available.y < 0f) {
                            val take = maxOf(available.y, -pull)
                            pull += take
                            return androidx.compose.ui.geometry.Offset(0f, take)
                        }
                        return androidx.compose.ui.geometry.Offset.Zero
                    }

                    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                        // At the top and pulled further down by a finger: the page follows it (more slowly).
                        if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0f) {
                            pull = (pull + available.y * 0.55f).coerceAtMost(pullFull * 2.2f)
                            return androidx.compose.ui.geometry.Offset(0f, available.y)
                        }
                        return androidx.compose.ui.geometry.Offset.Zero
                    }

                    override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                        if (pull <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                        release()
                        return available
                    }
                }
            }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { controller.watchDrag.value = 0f } }
            // Ambient light: the video's colours glow softly around it, on the dark theme (as on YouTube).
            val ambient = !com.qmusic.ui.theme.lightTheme
            val player: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (ambient) Color.Transparent else Color.Black)
                        .statusBarsPadding()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { total = 0f },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    total += dy
                                    scope.launch { offset.snapTo(total.coerceAtLeast(0f)) }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        // Up opens full screen (sideways), down shrinks the video into the small player.
                                        if (total < -SWIPE_PX) {
                                            offset.snapTo(0f)
                                            controller.setFullscreen(true)
                                        } else if (!wide && progress() > 0.16f) {
                                            offset.animateTo(travel, tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                            controller.pop()
                                        } else if (wide && offset.value > height * 0.18f) {
                                            offset.animateTo(height, tween(150))
                                            controller.pop()
                                        } else {
                                            offset.animateTo(0f, tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                                        }
                                    }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f, tween(220)) } },
                            )
                        },
                ) {
                    PlayerBox(w, playback, s, controller, false, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
            }
            val layer = Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer {
                    if (wide) {
                        translationY = offset.value
                        alpha = 1f - (offset.value / height).coerceIn(0f, 1f) * 0.6f
                    } else {
                        // Smaller from the top right corner, down and a little in from the edge.
                        val p = progress()
                        val scale = 1f - (1f - miniScale) * p
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = -margin * p
                        translationY = offset.value
                    }
                }
                // The page's background goes as the video is pulled, so the page under it shows through.
                .drawBehind { drawRect(VideoColors.Background.copy(alpha = 1f - progress())) }
            // What is about the video (and its glow) fades out early in the pull, the picture alone travels.
            val fade = Modifier.graphicsLayer { alpha = (1f - progress() * 2.5f).coerceIn(0f, 1f) }
            if (wide) {
                // A tablet: the video and what is about it on the left, what comes next down the right.
                Row(modifier = layer) {
                    Box(Modifier.weight(1.7f)) {
                        if (ambient) AmbientGlow(w.video.thumbnail, Modifier.fillMaxWidth())
                        Column {
                            player()
                            WatchDetails(controller, w, s, onShare, onSignIn, seek, showRelated = false)
                        }
                    }
                    RelatedColumn(controller, w, s, Modifier.weight(1f).statusBarsPadding())
                }
            } else {
                Box(modifier = layer) {
                    if (ambient) AmbientGlow(w.video.thumbnail, Modifier.fillMaxWidth().then(fade))
                    // Pulled down: black opens between the picture and what is about it, darker the further it goes
                    // (the light around the video sinks into it, never cut off at once).
                    Column(Modifier.drawBehind { if (pull > 0f) drawRect(Color.Black.copy(alpha = (pull / pullFull).coerceIn(0f, 1f))) }) {
                        Box(
                            Modifier
                                .drawBehind { if (progress() > 0f) drawRect(Color.Black.copy(alpha = (progress() * 4f).coerceIn(0f, 1f))) }
                                .graphicsLayer { translationY = pull * 0.45f },
                        ) { player() }
                        Box(
                            fade
                                .graphicsLayer { translationY = pull }
                                .nestedScroll(pullConnection)
                                // A slow lift sends no fling: the lift itself is watched too (nothing is consumed).
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                                        } while (event.changes.any { it.pressed })
                                        if (pull > 0f) release()
                                    }
                                }
                                // Darker the further it is pulled.
                                .drawWithContent {
                                    drawContent()
                                    if (pull > 0f) drawRect(Color.Black.copy(alpha = (pull / pullFull).coerceIn(0f, 1f) * 0.55f))
                                },
                        ) { WatchDetails(controller, w, s, onShare, onSignIn, seek, showRelated = true) }
                    }
                }
            }
        }
    }
}

/**
 * The light of the video around it: its picture, much enlarged and blurred, behind the player and a little below it,
 * fading into the page. It changes with each video (its own colours).
 */
@Composable
private fun AmbientGlow(picture: String?, modifier: Modifier) {
    if (picture == null) return
    // It comes up softly as the page opens.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(900, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
    BoxWithConstraints(modifier.graphicsLayer { alpha = shown.value }) {
        // The player's height (16:9) and somewhat more below it, where the light dies away.
        val playerHeight = maxWidth * 9f / 16f
        // A new video: its light fades in over the last one's.
        androidx.compose.animation.Crossfade(targetState = picture, animationSpec = tween(900), label = "ambient") { pic ->
            GlowLayer(pic, Modifier.fillMaxWidth().height(playerHeight * 2.3f), centreAt = 0.28f)
        }
    }
}

/** Upright full screen: the video's light fills the black above and below the picture. */
@Composable
private fun UprightGlow(picture: String?, modifier: Modifier) {
    if (picture == null) return
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(700, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
    androidx.compose.animation.Crossfade(targetState = picture, animationSpec = tween(900), label = "uprightAmbient", modifier = modifier.graphicsLayer { alpha = shown.value * 0.85f }) { pic ->
        GlowLayer(pic, Modifier.fillMaxSize(), centreAt = 0.5f)
    }
}

/**
 * One video's light: its picture enlarged and blurred, with its main colour glowing from [centreAt] (0 top, 1 bottom),
 * fading out smoothly downwards and at the sides into whatever is behind (no band where it ends). Phones that cannot
 * blur (before Android 12) get the colour alone, never a sharp copy of the picture.
 */
@Composable
private fun GlowLayer(picture: String, modifier: Modifier, centreAt: Float) {
    val tint = com.qmusic.ui.rememberDominantColor(picture)
    // The colour comes in gently once it is known.
    val tintShown by androidx.compose.animation.core.animateFloatAsState(if (tint != null) 1f else 0f, tween(800), label = "tint")
    Box(
        modifier
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = Color.Black
                // Strong around the picture, gone well before the edges.
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to edge.copy(alpha = if (centreAt < 0.4f) 1f else 0.15f),
                        centreAt to edge,
                        (centreAt + (1f - centreAt) * 0.55f).coerceAtMost(0.98f) to edge.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to edge.copy(alpha = 0.45f),
                        0.22f to edge,
                        0.78f to edge,
                        1f to edge.copy(alpha = 0.45f),
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
    ) {
        if (com.qmusic.ui.chat.callBlurWorks) {
            AsyncImage(
                model = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.62f,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f }.blur(90.dp),
            )
        }
        tint?.let { c ->
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            0f to c.copy(alpha = 0.5f * tintShown),
                            0.55f to c.copy(alpha = 0.18f * tintShown),
                            1f to Color.Transparent,
                            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * centreAt),
                            radius = size.width * 0.9f,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun PlayerBox(w: WatchState, playback: VideoPlayback?, s: VideoStrings, controller: VideosController, fullscreen: Boolean, modifier: Modifier, plain: Boolean = false) {
    val fill by controller.fill.collectAsState()
    val (onTv, tvState) = castingHere(w)
    if (onTv) {
        Box(modifier = modifier.background(Color.Black)) { CastPanel(w, tvState, s, controller) }
        return
    }
    Box(modifier = if (plain) modifier else modifier.background(Color.Black)) {
        val isPlaying = playback?.isPlaying?.collectAsState()?.value ?: false
        val buffering = playback?.buffering?.collectAsState()?.value ?: false
        val playError = playback?.error?.collectAsState()?.value
        val picture = playback?.showingPicture?.collectAsState()?.value ?: false
        // Starting: nothing of the video is on screen yet (its picture stands in, as on YouTube).
        val starting = playback != null && !w.streamError && playError == null && (w.quality == null || !picture)
        if (playback != null && w.quality != null) {
            VideoPlaybackSurface(playback, Modifier.fillMaxSize(), fill = fullscreen && fill)
        }
        if ((starting || playback == null || w.quality == null) && w.video.thumbnail != null) {
            AsyncImage(model = w.video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }

        when {
            playback == null -> Overlay(s.desktopWatch)
            w.streamError || playError != null -> Overlay(s.streamError, s.retry) { controller.retryStream() }
            starting || w.streamLoading || (buffering && !isPlaying) -> Box(Modifier.fillMaxSize(), Alignment.Center) { PlayerSpinner() }
        }
        if (playback != null && w.quality != null && !w.streamError) {
            PlayerControls(playback, w, s, controller, fullscreen, starting = starting)
            // Watching together: everyone's reactions over the picture.
            com.qmusic.ui.chat.TogetherReactionsLayer(w.video.id)
        }
    }
}

@Composable
private fun Overlay(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp)
        if (action != null && onAction != null) {
            Text(
                action,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

internal fun WatchState.isLive() = quality?.isLive == true || video.isLive || details?.isLive == true

private const val SWIPE_PX = 120f

/** How far the page is pulled down (from its title) for the video to go full screen when let go. */
private val PULL_FULLSCREEN = 150.dp

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    fun two(n: Long) = if (n < 10) "0$n" else "$n"
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

private val WIDE_WATCH = 840.dp

@Composable
private fun WatchDetails(
    controller: VideosController,
    w: WatchState,
    s: VideoStrings,
    onShare: (String) -> Unit,
    onSignIn: () -> Unit,
    onSeek: (Long) -> Unit,
    showRelated: Boolean,
) {
    var descriptionOpen by remember { mutableStateOf(false) }
    var commentsOpen by remember { mutableStateOf(false) }
    var downloadPicker by remember(w.video.id) { mutableStateOf(false) }
    var deleteDownloadConfirm by remember(w.video.id) { mutableStateOf(false) }
    var shareDialogPos by remember(w.video.id) { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val details = w.details
    val title = details?.title ?: w.video.title
    val signedIn by controller.signedIn.collectAsState()
    val x = s.extras()

    if (showRelated) {
        LaunchedEffect(listState, w.relatedContinuation) {
            if (w.relatedContinuation == null) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 4
            }.collect { near -> if (near) controller.loadMoreRelated() }
        }
    }
    LaunchedEffect(commentsOpen) { if (commentsOpen) controller.loadComments() }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "title") {
            Column(modifier = Modifier.fillMaxWidth().clickable { descriptionOpen = !descriptionOpen }.padding(12.dp)) {
                Text(title, color = VideoColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, maxLines = if (descriptionOpen) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(details?.views ?: w.video.views, details?.date ?: w.video.published).joinToString(" · ")
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text(meta, color = VideoColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (descriptionOpen) s.showLess else s.showMore, color = VideoColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (descriptionOpen && !details?.description.isNullOrBlank()) {
            item(key = "description") {
                val description = details!!.description!!
                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    val chapters = remember(description) { chaptersOf(description) }
                    if (chapters.isNotEmpty()) {
                        Text(s.chapters, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        chapters.forEach { chapter ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { onSeek(chapter.timeMs) }.padding(vertical = 5.dp)) {
                                Text(clock(chapter.timeMs), color = VideoColors.Link, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                                Text(chapter.title, color = VideoColors.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    // Every time in the description is a link that jumps there.
                    val linked = remember(description) { linkedDescription(description, VideoColors.Link) }
                    androidx.compose.foundation.text.ClickableText(
                        text = linked,
                        style = androidx.compose.ui.text.TextStyle(color = VideoColors.Text, fontSize = 13.sp, lineHeight = 19.sp),
                        onClick = { offset -> linked.timeAt(offset)?.let(onSeek) },
                    )
                }
            }
        }
        item(key = "channel") {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).clickable(enabled = (details?.channelId ?: w.video.channelId) != null) {
                        (details?.channelId ?: w.video.channelId)?.let { controller.push(VideoRoute.Channel(it, details?.channelName ?: w.video.channelName)) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(w.video.channelAvatar ?: details?.channelAvatar, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(details?.channelName ?: w.video.channelName ?: "", color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        details?.subscribers?.let { Text(it, color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1) }
                    }
                }
                Text(
                    if (w.subscribed) s.subscribed else s.subscribe,
                    color = if (w.subscribed) VideoColors.Text else VideoColors.OnChipSelected,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (w.subscribed) VideoColors.Chip else VideoColors.ChipSelected)
                        .clickable { controller.toggleSubscribe() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPill(
                    label = details?.likes ?: s.like,
                    icon = if (w.liked) QMusicIcons.HeartFilled else QMusicIcons.Heart,
                    active = w.liked,
                ) { controller.toggleLike() }
                ActionPill(label = if (w.saved) s.saved else s.save, icon = QMusicIcons.PlaylistAdd, active = w.saved) {
                    if (!controller.openSaveSheet(w.video.id)) onSignIn()
                }
                ActionPill(label = s.share, icon = QMusicIcons.Share, active = false) {
                    val at = com.qmusic.ui.chat.TogetherVideo.playback?.positionMs?.value ?: 0L
                    if (!w.isLive() && at >= 5_000L) shareDialogPos = at else onShare("https://youtu.be/${w.video.id}")
                }
                // To a conversation, at the second it is at (they open it there).
                ActionPill(label = com.qmusic.ui.chat.ChatMusic.words().sendToChat, icon = QMusicIcons.Chat, active = false) {
                    val at = com.qmusic.ui.chat.TogetherVideo.playback?.positionMs?.value ?: 0L
                    com.qmusic.ui.chat.ChatMusic.offer(
                        com.qmusic.ui.chat.SharedItem(
                            com.qmusic.ui.chat.ShareKind.VIDEO, w.video.id, w.video.title, w.video.channelName.orEmpty(), w.video.thumbnail,
                            startMs = if (at > 5_000 && !w.isLive()) at else 0L,
                        ),
                    )
                }
                if (controller.canDownload && !w.isLive()) {
                    val downloads by controller.downloads.collectAsState()
                    val dl = downloads.firstOrNull { it.item.id == w.video.id }
                    ActionPill(
                        label = when (dl?.state) {
                            DownloadState.DONE -> x.downloaded
                            DownloadState.DOWNLOADING -> "%${dl?.percent ?: 0}"
                            DownloadState.QUEUED -> "…"
                            DownloadState.FAILED -> x.downloadFailed
                            null -> x.download
                        },
                        icon = if (dl?.state == DownloadState.DONE) QMusicIcons.DownloadDone else QMusicIcons.Download,
                        active = dl?.state == DownloadState.DONE,
                    ) {
                        when (dl?.state) {
                            null, DownloadState.FAILED -> {
                                if (w.qualities.count { !it.isLive } > 1) downloadPicker = true else controller.downloadCurrent()
                            }
                            DownloadState.DOWNLOADING, DownloadState.QUEUED -> controller.removeDownload(w.video.id)
                            DownloadState.DONE -> deleteDownloadConfirm = true
                        }
                    }
                }
            }
            if (w.signInPrompt) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(VideoColors.Surface).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.signInToDoThis, color = VideoColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(s.signIn, color = VideoColors.Red, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        controller.dismissSignInPrompt()
                        onSignIn()
                    })
                }
            }
        }
        if (w.isLive() && details?.liveChatToken != null) {
            item(key = "live-chat-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(VideoColors.Surface).clickable { controller.toggleLiveChat() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(VideoColors.Red))
                    Spacer(Modifier.width(8.dp))
                    Text(x.liveChat, color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(if (w.liveChatOpen) QMusicIcons.ChevronUp else QMusicIcons.ChevronDown, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
            if (w.liveChatOpen) {
                item(key = "live-chat-box") {
                    LiveChatPanel(w.liveChatMessages, w.liveChatLoading, x.liveChatEmpty)
                }
            }
        }
        item(key = "comments-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(VideoColors.Surface).clickable { commentsOpen = !commentsOpen }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (w.commentsTotal != null) "${s.comments} · ${w.commentsTotal}" else s.comments,
                    color = VideoColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Icon(if (commentsOpen) QMusicIcons.ChevronUp else QMusicIcons.ChevronDown, null, tint = VideoColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (commentsOpen) {
            item(key = "comment-input") { CommentInput(w, s, signedIn, onSignIn) { controller.postComment(it) } }
            if (w.comments.isEmpty() && w.commentsLoading) item(key = "comments-loading") { Loading() }
            if (w.comments.isEmpty() && w.commentsLoaded && !w.commentsLoading) {
                item(key = "comments-none") { Text(s.noComments, color = VideoColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            }
            items(w.comments, key = { "c" + it.id }) { c -> CommentRow(c, w, controller, s, onSeek = onSeek, small = false) }
            if (w.commentsContinuation != null) {
                item(key = "comments-more") {
                    Text(
                        if (w.commentsLoading) "…" else s.loadMore,
                        color = VideoColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { controller.loadComments() }.padding(16.dp),
                    )
                }
            }
        } else if (showRelated) {
            relatedItems(controller, w, s)
        }
    }
    if (downloadPicker) {
        val choices = w.qualities.filter { !it.isLive }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { downloadPicker = false },
            containerColor = VideoColors.Surface,
            title = { Text(x.downloadQualityTitle, color = VideoColors.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { q ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                downloadPicker = false
                                controller.downloadCurrent(q)
                            }.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(q.label, color = VideoColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (q == w.quality) Icon(QMusicIcons.Check, null, tint = VideoColors.Red, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { downloadPicker = false }) { Text(x.cancelLabel, color = VideoColors.TextSecondary) }
            },
        )
    }
    if (deleteDownloadConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteDownloadConfirm = false },
            containerColor = VideoColors.Surface,
            title = { Text(x.deleteDownloadTitle, color = VideoColors.Text) },
            text = { Text(x.deleteDownloadConfirm, color = VideoColors.TextSecondary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleteDownloadConfirm = false
                    controller.removeDownload(w.video.id)
                }) { Text(x.removeDownload, color = VideoColors.Red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteDownloadConfirm = false }) { Text(x.cancelLabel, color = VideoColors.Text) }
            },
        )
    }
    shareDialogPos?.let { atMs ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shareDialogPos = null },
            containerColor = VideoColors.Surface,
            title = { Text(s.share, color = VideoColors.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        x.shareVideoPlain,
                        color = VideoColors.Text,
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            shareDialogPos = null
                            onShare("https://youtu.be/${w.video.id}")
                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                    Text(
                        x.shareFromTime(clock(atMs)),
                        color = VideoColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            shareDialogPos = null
                            onShare("https://youtu.be/${w.video.id}?t=${atMs / 1000}")
                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { shareDialogPos = null }) { Text(x.cancelLabel, color = VideoColors.TextSecondary) }
            },
        )
    }
}

/** Expandable real-time chat box for live streams. */
@Composable
private fun LiveChatPanel(
    messages: List<com.qmusic.engine.video.VideoLiveChatMessage>,
    loading: Boolean,
    emptyText: String,
) {
    val chatState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) chatState.animateScrollToItem(messages.lastIndex)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VideoColors.Surface),
    ) {
        when {
            loading && messages.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Loading() }
            messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text(emptyText, color = VideoColors.TextSecondary, fontSize = 13.sp)
            }
            else -> LazyColumn(
                state = chatState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        Avatar(m.avatar, 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(m.author, color = VideoColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                m.amount?.let { amt ->
                                    Text(
                                        amt,
                                        color = Color.Black,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(VideoColors.Sponsor).padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            if (m.text.isNotEmpty()) {
                                Text(m.text, color = VideoColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What comes next down the side of a wide screen. */
@Composable
private fun RelatedColumn(controller: VideosController, w: WatchState, s: VideoStrings, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, w.relatedContinuation) {
        if (w.relatedContinuation == null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }.collect { near -> if (near) controller.loadMoreRelated() }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) { relatedItems(controller, w, s) }
}

/** The rest of the list the video was opened from, then what YouTube suggests next. */
private fun LazyListScope.relatedItems(controller: VideosController, w: WatchState, s: VideoStrings) {
    if (w.queue.isNotEmpty()) {
        item(key = "queue-title") {
            Text(
                "${s.queueTitle} · ${w.queueIndex + 1}/${w.queue.size}",
                color = VideoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        items(w.queue.size - (w.queueIndex + 1).coerceAtMost(w.queue.size), key = { "q" + w.queue[w.queueIndex + 1 + it].id }) { i ->
            val index = w.queueIndex + 1 + i
            VideoRowCard(w.queue[index], s, onClick = { controller.openFrom(w.queue, index) })
        }
    }
    item(key = "related-title") {
        val autoplay by controller.autoplay.collectAsState()
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.related, color = VideoColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(s.extras().autoplay, color = VideoColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            androidx.compose.material3.Switch(
                checked = autoplay != "0",
                onCheckedChange = { controller.setAutoplay(it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = VideoColors.Red, checkedThumbColor = Color.White),
            )
        }
    }
    if (w.details == null && !w.detailsError) items(4, key = { "related-loading-$it" }) { SkeletonRow() }
    items(w.related.size, key = { i -> (w.related[i].ids().firstOrNull() ?: "r") + "#" + i }) { i ->
        when (val e = w.related[i]) {
            is VideoEntry.Video -> VideoRowCard(e.item, s, onClick = { controller.open(e.item) }, onMore = { controller.openMenu(e.item) })
            is VideoEntry.Shorts -> ShortsRow(e.items, s) { items, index -> controller.openShorts(items, index) }
            is VideoEntry.Channel -> ChannelResultRow(e.item) { controller.push(VideoRoute.Channel(e.item.id, e.item.name)) }
            is VideoEntry.Playlist -> PlaylistResultRow(e.item, s) { controller.push(VideoRoute.Playlist(e.item.id, e.item.title)) }
        }
    }
}

/** The box that takes a comment: it asks for a sign-in when there is no account. */
@Composable
private fun CommentInput(w: WatchState, s: VideoStrings, signedIn: Boolean, onSignIn: () -> Unit, onPost: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(VideoColors.Chip).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).then(if (signedIn) Modifier else Modifier.clickable(onClick = onSignIn)),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(s.writeComment, color = VideoColors.TextSecondary, fontSize = 14.sp)
            if (signedIn) {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = VideoColors.Text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(VideoColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (signedIn && text.isNotBlank()) {
            Text(
                if (w.posting) "…" else s.post,
                color = VideoColors.Link, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !w.posting) { if (onPost(text)) text = "" }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** One comment, with clickable timestamps and its replies opened under it when asked for. */
@Composable
private fun CommentRow(c: VideoComment, w: WatchState, controller: VideosController, s: VideoStrings, onSeek: (Long) -> Unit, small: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = if (small) 56.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Avatar(c.avatar, if (small) 24.dp else 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(listOfNotNull(c.author, c.published).joinToString(" · "), color = VideoColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val linked = remember(c.text) { linkedDescription(c.text, VideoColors.Link) }
            @Suppress("DEPRECATION")
            androidx.compose.foundation.text.ClickableText(
                text = linked,
                style = androidx.compose.ui.text.TextStyle(color = VideoColors.Text, fontSize = 14.sp, lineHeight = 19.sp),
                modifier = Modifier.padding(top = 2.dp),
                onClick = { offset -> linked.timeAt(offset)?.let(onSeek) },
            )
            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                c.likes?.let { Text("♥ $it", color = VideoColors.TextSecondary, fontSize = 12.sp) }
                val opened = w.replies.containsKey(c.id)
                if (c.replies != null && c.repliesToken != null) {
                    Text(
                        text = when {
                            c.id in w.repliesLoading -> "…"
                            opened -> s.hideReplies
                            else -> "↳ " + s.repliesLabel(c.replies!!)
                        },
                        color = VideoColors.Link, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { if (opened) controller.hideReplies(c.id) else controller.loadReplies(c) },
                    )
                }
            }
        }
    }
    w.replies[c.id]?.forEach { reply -> CommentRow(reply, w, controller, s, onSeek = onSeek, small = true) }
    if (w.replies.containsKey(c.id) && w.repliesNext[c.id] != null) {
        Text(
            if (c.id in w.repliesLoading) "…" else s.loadMore,
            color = VideoColors.Link, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 56.dp, bottom = 8.dp).clickable { controller.loadReplies(c) },
        )
    }
}

@Composable
private fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(VideoColors.Chip).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) VideoColors.Red else VideoColors.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = VideoColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
