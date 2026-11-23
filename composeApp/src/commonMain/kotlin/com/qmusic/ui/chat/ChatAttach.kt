package com.qmusic.ui.chat

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** The phone's latest photos, for the attach panel (asked for once; the system picker stays for the rest). */
class RecentPhotos(
    /** Their addresses, newest first; empty until allowed. */
    val photos: List<String>,
    /** Whether the photos may be shown. */
    val allowed: Boolean,
    /** Asks for the permission. */
    val ask: () -> Unit,
    /** The photo at [uri], shrunk and compressed to send. */
    val prepare: suspend (String) -> PickedImage?,
)

@Composable
expect fun rememberRecentPhotos(enabled: Boolean): RecentPhotos

/** One way to attach: its picture in a coloured mark, its word under it. */
class AttachAction(val label: String, val icon: ImageVector, val tint: Color, val run: () -> Unit)

/** The top of the panels under the message box: rounded, with a handle. */
@Composable
internal fun PanelHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(TextSecondary.copy(alpha = 0.45f)))
    }
}

val PanelShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * What opens from the clip, under the message box (as on WhatsApp): the ways to attach, as coloured marks four to a
 * row, then the latest photos of the phone (a tap opens one to send).
 */
@Composable
internal fun AttachPanel(
    actions: List<AttachAction>,
    onPhoto: (PickedImage) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val english = ChatMusic.english
    val recent = rememberRecentPhotos(enabled = true)
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf<String?>(null) }
    ExpandablePanel(collapsed = 380.dp, onDismiss = onDismiss, modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = if (LocalWideLayout.current) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(actions, key = { it.label }) { a ->
                Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = a.run).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(50)).border(1.dp, TextSecondary.copy(alpha = 0.25f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(a.icon, contentDescription = null, tint = a.tint, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text(a.label, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(2.dp)) }
            if (!recent.allowed) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DarkSurfaceVariant).clickable { recent.ask() }.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(QMusicIcons.Samples, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (english) "Show your latest photos here" else "Son fotoğrafların burada görünsün",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(if (english) "Tap to allow" else "İzin vermek için dokun", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            } else {
                items(recent.photos, key = { it }, span = { androidx.compose.foundation.lazy.grid.GridItemSpan(1) }) { uri ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(DarkSurfaceVariant).clickable(enabled = preparing == null) {
                            preparing = uri
                            scope.launch {
                                recent.prepare(uri)?.let(onPhoto)
                                preparing = null
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (preparing == uri) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/** A sticker's sign: a square with a peeled corner. */
@Composable
internal fun StickerGlyph(tint: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val r = w * 0.22f
        drawPath(
            Path().apply {
                moveTo(w * 0.62f, w * 0.1f)
                lineTo(w * 0.1f + r, w * 0.1f)
                quadraticBezierTo(w * 0.1f, w * 0.1f, w * 0.1f, w * 0.1f + r)
                lineTo(w * 0.1f, w * 0.9f - r)
                quadraticBezierTo(w * 0.1f, w * 0.9f, w * 0.1f + r, w * 0.9f)
                lineTo(w * 0.9f - r, w * 0.9f)
                quadraticBezierTo(w * 0.9f, w * 0.9f, w * 0.9f, w * 0.9f - r)
                lineTo(w * 0.9f, w * 0.38f)
                close()
            },
            tint,
            style = s,
        )
        drawPath(
            Path().apply {
                moveTo(w * 0.62f, w * 0.1f)
                quadraticBezierTo(w * 0.62f, w * 0.38f, w * 0.9f, w * 0.38f)
            },
            tint,
            style = s,
        )
    }
}

/**
 * A panel under the message box that the person sizes by its handle (as on WhatsApp): pulled up it grows to most of
 * the screen, pulled down it comes back, and further down it closes ([onDismiss]); let go, it settles.
 */
@Composable
internal fun ExpandablePanel(
    collapsed: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (LocalWideLayout.current) {
        // A computer: a small window over the writing box (nothing to pull), opening with a quick rise and fade.
        val shown = remember { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(Unit) { shown.animateTo(1f, androidx.compose.animation.core.tween(170, easing = com.qmusic.ui.motion.Motion.EmphasizedDecelerate)) }
        val shape = RoundedCornerShape(18.dp)
        Column(
            modifier
                .padding(start = 12.dp, bottom = 6.dp)
                .width(440.dp)
                .heightIn(max = collapsed.coerceAtMost(420.dp))
                .graphicsLayer {
                    alpha = shown.value
                    translationY = (1f - shown.value) * 12.dp.toPx()
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                    scaleX = 0.97f + 0.03f * shown.value
                    scaleY = scaleX
                }
                .shadow(18.dp, shape)
                .clip(shape)
                .background(DarkSurface)
                .padding(top = 10.dp),
        ) { content() }
        return
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val low = with(density) { collapsed.toPx() }
    // Pulled up: most of a phone's screen (the conversation's top stays in sight).
    val high = with(density) { (collapsed * 1.75f).coerceAtMost(660.dp).toPx() }.coerceAtLeast(low)
    val height = remember { androidx.compose.animation.core.Animatable(low) }
    val scope = rememberCoroutineScope()
    val settle = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 500f)
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        scope.launch { height.snapTo((height.value - delta).coerceIn(low * 0.25f, high)) }
    }
    Column(modifier.fillMaxWidth().height(with(density) { height.value.toDp() }).clip(PanelShape).background(DarkSurface)) {
        Box(
            Modifier.fillMaxWidth().draggable(
                drag,
                androidx.compose.foundation.gestures.Orientation.Vertical,
                onDragStopped = { velocity ->
                    val h = height.value
                    when {
                        // Pulled well down, or flung down from the small size: it closes.
                        h < low * 0.7f || (velocity > 1500f && h <= low * 1.05f) -> onDismiss()
                        velocity < -800f -> height.animateTo(high, settle)
                        velocity > 800f -> height.animateTo(low, settle)
                        else -> height.animateTo(if (h > (low + high) / 2) high else low, settle)
                    }
                },
            ),
        ) { PanelHandle() }
        content()
    }
}

/**
 * A picture full screen, black around it: two fingers zoom it (up to 5x) and move it while zoomed, a double tap zooms
 * in and back, and pulled down while not zoomed it closes; [title] over its top with the back arrow.
 */
@Composable
fun ZoomablePhoto(url: String, title: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEdgeToEdge()
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var pull by remember { mutableStateOf(0f) }
        val fade = (1f - kotlin.math.abs(pull) / 900f).coerceIn(0.3f, 1f)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade))
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            // Zoomed in on where the finger was.
                            scale = 2.5f
                            offset = (androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f) - at) * 1.5f
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2 || scale > 1.01f) {
                                val next = (scale * zoom).coerceIn(1f, 5f)
                                scale = next
                                // Kept within the picture's edges.
                                val maxX = size.width * (next - 1f) / 2f
                                val maxY = size.height * (next - 1f) / 2f
                                offset = androidx.compose.ui.geometry.Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (fingers == 1) {
                                pull += pan.y
                            }
                        } while (event.changes.any { it.pressed })
                        if (kotlin.math.abs(pull) > 220f) onDismiss() else pull = 0f
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                // Google's pictures come small by default: the large one is asked for.
                model = sizedPhoto(url, 1600),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + pull
                },
            )
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = onDismiss) { Icon(QMusicIcons.Back, contentDescription = com.qmusic.ui.A11y.back, tint = Color.White) }
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
