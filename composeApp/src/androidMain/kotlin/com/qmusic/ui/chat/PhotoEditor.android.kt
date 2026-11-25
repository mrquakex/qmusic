package com.qmusic.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** A line drawn on the photo: its points (0-1 of the turned photo), colour and thickness (of the shorter side). */
private class Line(val points: MutableList<Offset>, val color: Color, val width: Float)

/** A text label placed on the photo (0-1 of the turned photo). */
private data class TextOverlay(val text: String, val x: Float, val y: Float, val color: Color)

private enum class EditMode { CROP, DRAW, TEXT }

private enum class UndoKind { LINE, TEXT }

private val PenColors = listOf(Color.White, Color.Black, Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE))
private val PenWidths = listOf(0.005f, 0.009f, 0.016f)

@Composable
actual fun PhotoEditor(image: PickedImage, onDone: (PickedImage) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val tr = remember {
        !ChatMusic.english && context.getSharedPreferences("qmusic_settings", android.content.Context.MODE_PRIVATE).getString("language", "tr") != "en"
    }
    val original = remember(image) { BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size) }
    if (original == null || original.width <= 0 || original.height <= 0) {
        LaunchedEffect(image) { onCancel() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val rotations = remember(original) { arrayOfNulls<Bitmap>(4).apply { this[0] = original } }
    var turns by remember(original) { mutableIntStateOf(0) }
    val shown = remember(original, turns) {
        val idx = turns % 4
        rotations[idx] ?: Bitmap.createBitmap(
            original,
            0,
            0,
            original.width,
            original.height,
            Matrix().apply { postRotate(90f * idx) },
            true,
        ).also { rotations[idx] = it }
    }
    // The crop as parts of the turned photo: left, top, right, bottom.
    var crop by remember(original) { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    val lines = remember(original) { mutableStateListOf<Line>() }
    val texts = remember(original) { mutableStateListOf<TextOverlay>() }
    val undoStack = remember(original) { mutableStateListOf<UndoKind>() }
    var mode by remember { mutableStateOf(EditMode.DRAW) }
    var color by remember { mutableStateOf(PenColors[0]) }
    var strokeWidth by remember { mutableFloatStateOf(PenWidths[1]) }
    var busy by remember { mutableStateOf(false) }
    var redraw by remember { mutableIntStateOf(0) }
    var editingTextIndex by remember { mutableStateOf<Int?>(null) }
    var writingText by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var textColor by remember { mutableStateOf(Color.White) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    PlatformBackHandler(enabled = true) {
        if (writingText) writingText = false else if (!busy) onCancel()
    }

    fun openTextDialog(index: Int?) {
        editingTextIndex = index
        if (index != null && index in texts.indices) {
            textDraft = texts[index].text
            textColor = texts[index].color
        } else {
            textDraft = ""
            textColor = Color.White
        }
        writingText = true
    }

    fun turn() {
        if (busy) return
        // A quarter turn clockwise: (x, y) -> (1 - y, x) for lines, texts, and crop alike.
        lines.forEach { line -> line.points.replaceAll { Offset(1f - it.y, it.x) } }
        for (i in texts.indices) {
            val t = texts[i]
            texts[i] = t.copy(x = (1f - t.y).coerceIn(0.05f, 0.95f), y = t.x.coerceIn(0.08f, 0.92f))
        }
        crop = Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        turns = (turns + 1) % 4
        redraw++
    }

    fun undo() {
        if (busy || undoStack.isEmpty()) return
        when (undoStack.removeAt(undoStack.lastIndex)) {
            UndoKind.LINE -> if (lines.isNotEmpty()) lines.removeAt(lines.lastIndex)
            UndoKind.TEXT -> if (texts.isNotEmpty()) texts.removeAt(texts.lastIndex)
        }
        redraw++
    }

    Box(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !busy, onClick = onCancel) { Icon(QMusicIcons.Close, contentDescription = null, tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (undoStack.isNotEmpty() && !busy) {
                    Text(
                        if (tr) "Geri al" else "Undo",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { undo() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Box(
                    Modifier.padding(end = 8.dp).size(44.dp).clip(CircleShape).background(Color.White).clickable(enabled = !busy) {
                        busy = true
                        val cropSnapshot = crop
                        val linesSnapshot = lines.map { Line(it.points.toMutableList(), it.color, it.width) }
                        val textsSnapshot = texts.toList()
                        scope.launch {
                            val edited = withContext(Dispatchers.Default) {
                                runCatching { render(shown, cropSnapshot, linesSnapshot, textsSnapshot) }.getOrNull()
                            }
                            busy = false
                            if (edited != null) onDone(edited) else onCancel()
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Icon(QMusicIcons.Check, contentDescription = null, tint = Color.Black)
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val validLayout = boxW > 0f && boxH > 0f && shown.width > 0 && shown.height > 0
                val scale = if (validLayout) minOf(boxW / shown.width, boxH / shown.height) else 1f
                val w = if (validLayout) shown.width * scale else 0f
                val h = if (validLayout) shown.height * scale else 0f
                val left = (boxW - w) / 2f
                val top = (boxH - h) / 2f
                val bitmap = remember(shown) { shown.asImageBitmap() }
                val handle = with(density) { 32.dp.toPx() }
                val latestMode by rememberUpdatedState(mode)
                val latestColor by rememberUpdatedState(color)
                val latestWidth by rememberUpdatedState(strokeWidth)
                val latestBusy by rememberUpdatedState(busy)
                val latestW by rememberUpdatedState(w)
                val latestH by rememberUpdatedState(h)
                val latestLeft by rememberUpdatedState(left)
                val latestTop by rememberUpdatedState(top)
                var dragging by remember { mutableIntStateOf(-1) }

                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(shown) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val cw = latestW
                                    val ch = latestH
                                    if (latestBusy || cw <= 0f || ch <= 0f) return@detectDragGestures
                                    val cl = latestLeft
                                    val ct = latestTop
                                    val p = Offset(((start.x - cl) / cw).coerceIn(0f, 1f), ((start.y - ct) / ch).coerceIn(0f, 1f))
                                    when (latestMode) {
                                        EditMode.DRAW -> {
                                            val nearImage = start.x in (cl - handle * 0.5f)..(cl + cw + handle * 0.5f) &&
                                                start.y in (ct - handle * 0.5f)..(ct + ch + handle * 0.5f)
                                            if (nearImage) {
                                                lines += Line(mutableListOf(p), latestColor, latestWidth)
                                                undoStack += UndoKind.LINE
                                                dragging = 99
                                            }
                                        }
                                        EditMode.CROP -> {
                                            // 0..3: corners; 4..7: left/top/right/bottom edges; 8: interior move.
                                            val corners = listOf(
                                                Offset(crop.left, crop.top),
                                                Offset(crop.right, crop.top),
                                                Offset(crop.left, crop.bottom),
                                                Offset(crop.right, crop.bottom),
                                            )
                                            val nearCorner = corners.withIndex()
                                                .map { (i, c) -> i to hypot((c.x - p.x) * cw, (c.y - p.y) * ch) }
                                                .filter { it.second < handle }
                                                .minByOrNull { it.second }?.first
                                            if (nearCorner != null) {
                                                dragging = nearCorner
                                            } else {
                                                val edges = listOfNotNull(
                                                    if (p.y in crop.top..crop.bottom) 4 to abs((crop.left - p.x) * cw) else null,
                                                    if (p.x in crop.left..crop.right) 5 to abs((crop.top - p.y) * ch) else null,
                                                    if (p.y in crop.top..crop.bottom) 6 to abs((crop.right - p.x) * cw) else null,
                                                    if (p.x in crop.left..crop.right) 7 to abs((crop.bottom - p.y) * ch) else null,
                                                )
                                                val nearEdge = edges.filter { it.second < handle * 0.75f }.minByOrNull { it.second }?.first
                                                val inside = p.x in crop.left..crop.right && p.y in crop.top..crop.bottom
                                                dragging = nearEdge ?: if (inside) 8 else -1
                                            }
                                        }
                                        EditMode.TEXT -> Unit
                                    }
                                    redraw++
                                },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val cw = latestW
                                    val ch = latestH
                                    if (latestBusy || cw <= 0f || ch <= 0f || dragging < 0) return@detectDragGestures
                                    val p = Offset(((change.position.x - latestLeft) / cw).coerceIn(0f, 1f), ((change.position.y - latestTop) / ch).coerceIn(0f, 1f))
                                    if (latestMode == EditMode.DRAW && dragging == 99) {
                                        lines.lastOrNull()?.points?.add(p)
                                    } else if (latestMode == EditMode.CROP) {
                                        val min = 0.08f
                                        crop = when (dragging) {
                                            0 -> crop.copy(left = p.x.coerceAtMost(crop.right - min), top = p.y.coerceAtMost(crop.bottom - min))
                                            1 -> crop.copy(right = p.x.coerceAtLeast(crop.left + min), top = p.y.coerceAtMost(crop.bottom - min))
                                            2 -> crop.copy(left = p.x.coerceAtMost(crop.right - min), bottom = p.y.coerceAtLeast(crop.top + min))
                                            3 -> crop.copy(right = p.x.coerceAtLeast(crop.left + min), bottom = p.y.coerceAtLeast(crop.top + min))
                                            4 -> crop.copy(left = p.x.coerceAtMost(crop.right - min))
                                            5 -> crop.copy(top = p.y.coerceAtMost(crop.bottom - min))
                                            6 -> crop.copy(right = p.x.coerceAtLeast(crop.left + min))
                                            7 -> crop.copy(bottom = p.y.coerceAtLeast(crop.top + min))
                                            8 -> {
                                                val dx = (amount.x / cw).coerceIn(-crop.left, 1f - crop.right)
                                                val dy = (amount.y / ch).coerceIn(-crop.top, 1f - crop.bottom)
                                                crop.translate(dx, dy)
                                            }
                                            else -> crop
                                        }
                                    }
                                    redraw++
                                },
                            )
                        },
                    ) {
                        // Read so that every stroke and drag draws again.
                        if (redraw < 0 || w <= 0f || h <= 0f) return@Canvas
                        val side = minOf(w, h)
                        lines.forEach { line ->
                            if (line.points.isEmpty()) return@forEach
                            if (line.points.size == 1) {
                                val p = line.points[0]
                                drawCircle(line.color, radius = line.width * side / 2f, center = Offset(left + p.x * w, top + p.y * h))
                            } else {
                                val path = androidx.compose.ui.graphics.Path()
                                line.points.forEachIndexed { i, p ->
                                    val x = left + p.x * w
                                    val y = top + p.y * h
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, line.color, style = Stroke(width = line.width * side, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        // Outside the crop is dimmed; in CROP mode, frame, thirds, corners, and edge ticks are drawn.
                        val cl = left + crop.left * w
                        val ct = top + crop.top * h
                        val cr = left + crop.right * w
                        val cb = top + crop.bottom * h
                        val dim = Color.Black.copy(alpha = 0.6f)
                        drawRect(dim, Offset(left, top), Size(w, (ct - top).coerceAtLeast(0f)))
                        drawRect(dim, Offset(left, cb), Size(w, (top + h - cb).coerceAtLeast(0f)))
                        drawRect(dim, Offset(left, ct), Size((cl - left).coerceAtLeast(0f), (cb - ct).coerceAtLeast(0f)))
                        drawRect(dim, Offset(cr, ct), Size((left + w - cr).coerceAtLeast(0f), (cb - ct).coerceAtLeast(0f)))
                        if (mode == EditMode.CROP) {
                            drawRect(Color.White, Offset(cl, ct), Size(cr - cl, cb - ct), style = Stroke(2.dp.toPx()))
                            // Thirds, as cameras show them.
                            for (i in 1..2) {
                                drawLine(Color.White.copy(alpha = 0.4f), Offset(cl + (cr - cl) * i / 3f, ct), Offset(cl + (cr - cl) * i / 3f, cb), 1f)
                                drawLine(Color.White.copy(alpha = 0.4f), Offset(cl, ct + (cb - ct) * i / 3f), Offset(cr, ct + (cb - ct) * i / 3f), 1f)
                            }
                            val l = 20.dp.toPx()
                            val sw = 4.dp.toPx()
                            listOf(
                                Offset(cl, ct) to (1f to 1f),
                                Offset(cr, ct) to (-1f to 1f),
                                Offset(cl, cb) to (1f to -1f),
                                Offset(cr, cb) to (-1f to -1f),
                            ).forEach { (c, d) ->
                                drawLine(Color.White, c, Offset(c.x + l * d.first, c.y), sw, StrokeCap.Round)
                                drawLine(Color.White, c, Offset(c.x, c.y + l * d.second), sw, StrokeCap.Round)
                            }
                            // Subtle midpoint handles on each edge.
                            val el = 12.dp.toPx()
                            val mx = (cl + cr) / 2f
                            val my = (ct + cb) / 2f
                            drawLine(Color.White, Offset(mx - el, ct), Offset(mx + el, ct), sw, StrokeCap.Round)
                            drawLine(Color.White, Offset(mx - el, cb), Offset(mx + el, cb), sw, StrokeCap.Round)
                            drawLine(Color.White, Offset(cl, my - el), Offset(cl, my + el), sw, StrokeCap.Round)
                            drawLine(Color.White, Offset(cr, my - el), Offset(cr, my + el), sw, StrokeCap.Round)
                        }
                    }

                    // Draggable text overlays on top of the image.
                    if (w > 0f && h > 0f) {
                        val side = minOf(w, h)
                        val textSp = with(density) { (side * 0.065f).coerceAtLeast(14f).toSp() }
                        val lineHeightSp = with(density) { (side * 0.078f).coerceAtLeast(17f).toSp() }
                        val maxTextDp = with(density) { (w * 0.86f).toDp() }
                        texts.forEachIndexed { idx, item ->
                            var itemSize by remember(idx) { mutableStateOf(IntSize.Zero) }
                            val dark = item.color == Color.Black
                            val currentItem by rememberUpdatedState(item)
                            Text(
                                item.text,
                                color = item.color,
                                fontSize = textSp,
                                lineHeight = lineHeightSp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.7f),
                                        blurRadius = 8f,
                                    ),
                                ),
                                modifier = Modifier
                                    .widthIn(max = maxTextDp)
                                    .onSizeChanged { itemSize = it }
                                    .offset {
                                        val minX = left.roundToInt()
                                        val maxX = (left + w - itemSize.width).roundToInt().coerceAtLeast(minX)
                                        val minY = top.roundToInt()
                                        val maxY = (top + h - itemSize.height).roundToInt().coerceAtLeast(minY)
                                        IntOffset(
                                            (left + item.x * w - itemSize.width / 2f).roundToInt().coerceIn(minX, maxX),
                                            (top + item.y * h - itemSize.height / 2f).roundToInt().coerceIn(minY, maxY),
                                        )
                                    }
                                    .then(
                                        if (mode == EditMode.TEXT && !busy) {
                                            Modifier
                                                .clickable { openTextDialog(idx) }
                                                .pointerInput(idx, w, h) {
                                                    detectDragGestures { change, drag ->
                                                        change.consume()
                                                        if (idx !in texts.indices) return@detectDragGestures
                                                        val cur = currentItem
                                                        texts[idx] = cur.copy(
                                                            x = (cur.x + drag.x / w).coerceIn(0.05f, 0.95f),
                                                            y = (cur.y + drag.y / h).coerceIn(0.08f, 0.92f),
                                                        )
                                                    }
                                                }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        }
                    }
                }
            }

            if (mode == EditMode.DRAW) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PenWidths.forEachIndexed { idx, widthVal ->
                        val dotSize = (8 + idx * 5).dp
                        val selected = strokeWidth == widthVal
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable(enabled = !busy) { strokeWidth = widthVal },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(dotSize).clip(CircleShape).background(Color.White))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.25f)))
                    Spacer(Modifier.width(8.dp))
                    PenColors.forEach { c ->
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (c == color) 32.dp else 26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(2.dp, if (c == color) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                                .clickable(enabled = !busy) { color = c },
                        )
                    }
                }
            } else if (mode == EditMode.TEXT) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    EditorButton(if (tr) "+ Metin ekle" else "+ Add text", false) { openTextDialog(null) }
                }
            }

            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                EditorButton(if (tr) "Çiz" else "Draw", mode == EditMode.DRAW) { mode = EditMode.DRAW }
                EditorButton(if (tr) "Metin" else "Text", mode == EditMode.TEXT) {
                    mode = EditMode.TEXT
                    if (texts.isEmpty()) openTextDialog(null)
                }
                EditorButton(if (tr) "Kırp" else "Crop", mode == EditMode.CROP) { mode = EditMode.CROP }
                EditorButton(if (tr) "Döndür" else "Rotate", false) { turn() }
                if (crop != Rect(0f, 0f, 1f, 1f)) {
                    EditorButton(if (tr) "Sıfırla" else "Reset", false) {
                        if (!busy) {
                            crop = Rect(0f, 0f, 1f, 1f)
                            redraw++
                        }
                    }
                }
            }
        }

        if (writingText) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(enabled = false) {}) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { writingText = false }) { Icon(QMusicIcons.Close, contentDescription = null, tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    if (editingTextIndex != null) {
                        IconButton(onClick = {
                            val idx = editingTextIndex
                            if (idx != null && idx in texts.indices) {
                                texts.removeAt(idx)
                                undoStack.lastIndexOf(UndoKind.TEXT).takeIf { it >= 0 }?.let { undoStack.removeAt(it) }
                            }
                            writingText = false
                        }) { Icon(QMusicIcons.Trash, contentDescription = null, tint = Color.White) }
                    }
                    Text(
                        if (tr) "Tamam" else "Done",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White)
                            .clickable {
                                val trimmed = textDraft.trim()
                                val idx = editingTextIndex
                                if (trimmed.isEmpty()) {
                                    if (idx != null && idx in texts.indices) {
                                        texts.removeAt(idx)
                                        undoStack.lastIndexOf(UndoKind.TEXT).takeIf { it >= 0 }?.let { undoStack.removeAt(it) }
                                    }
                                } else if (idx != null && idx in texts.indices) {
                                    texts[idx] = texts[idx].copy(text = trimmed, color = textColor)
                                } else {
                                    texts += TextOverlay(trimmed, 0.5f, 0.45f, textColor)
                                    undoStack += UndoKind.TEXT
                                }
                                writingText = false
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    if (textDraft.isEmpty()) {
                        Text(
                            if (tr) "Bir şey yaz" else "Write something",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    BasicTextField(
                        value = textDraft,
                        onValueChange = { textDraft = it.take(200) },
                        textStyle = TextStyle(
                            color = textColor,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PenColors.forEach { c ->
                        Box(
                            Modifier
                                .size(if (c == textColor) 32.dp else 26.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable { textColor = c },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.Black else Color.White,
        fontSize = 14.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** The edited photo: lines and text drawn onto the turned photo, then cropped, scaled and compressed. */
private fun render(shown: Bitmap, crop: Rect, lines: List<Line>, texts: List<TextOverlay>): PickedImage {
    val canvasBitmap = shown.copy(Bitmap.Config.ARGB_8888, true)
    var cropped: Bitmap? = null
    var scaled: Bitmap? = null
    var tiny: Bitmap? = null
    try {
        val canvas = android.graphics.Canvas(canvasBitmap)
        val side = minOf(canvasBitmap.width, canvasBitmap.height).toFloat()
        lines.forEach { line ->
            if (line.points.isEmpty()) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = line.color.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = line.width * side
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            if (line.points.size == 1) {
                paint.style = Paint.Style.FILL
                val p = line.points[0]
                canvas.drawCircle(p.x * canvasBitmap.width, p.y * canvasBitmap.height, line.width * side / 2f, paint)
            } else {
                val path = Path()
                line.points.forEachIndexed { i, p ->
                    val x = p.x * canvasBitmap.width
                    val y = p.y * canvasBitmap.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }
        val maxTextW = (canvasBitmap.width * 0.86f).roundToInt().coerceAtLeast(1)
        texts.forEach { item ->
            if (item.text.isBlank()) return@forEach
            val dark = item.color == Color.Black
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = item.color.toArgb()
                textSize = (side * 0.065f).coerceAtLeast(14f)
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(
                    8f,
                    0f,
                    0f,
                    if (dark) android.graphics.Color.argb(153, 255, 255, 255) else android.graphics.Color.argb(179, 0, 0, 0),
                )
            }
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, maxTextW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            val textW = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: layout.width.toFloat()
            val cx = (item.x * canvasBitmap.width).coerceIn(textW / 2f, (canvasBitmap.width - textW / 2f).coerceAtLeast(textW / 2f))
            val cy = (item.y * canvasBitmap.height).coerceIn(layout.height / 2f, (canvasBitmap.height - layout.height / 2f).coerceAtLeast(layout.height / 2f))
            canvas.save()
            canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        val x = (crop.left * canvasBitmap.width).roundToInt().coerceIn(0, (canvasBitmap.width - 1).coerceAtLeast(0))
        val y = (crop.top * canvasBitmap.height).roundToInt().coerceIn(0, (canvasBitmap.height - 1).coerceAtLeast(0))
        val cw = ((crop.right - crop.left) * canvasBitmap.width).roundToInt().coerceIn(1, (canvasBitmap.width - x).coerceAtLeast(1))
        val ch = ((crop.bottom - crop.top) * canvasBitmap.height).roundToInt().coerceIn(1, (canvasBitmap.height - y).coerceAtLeast(1))
        cropped = Bitmap.createBitmap(canvasBitmap, x, y, cw, ch)
        var out: Bitmap = cropped
        val longest = max(out.width, out.height).coerceAtLeast(1)
        if (longest > 1600) {
            val f = 1600f / longest
            scaled = Bitmap.createScaledBitmap(out, (out.width * f).roundToInt().coerceAtLeast(1), (out.height * f).roundToInt().coerceAtLeast(1), true)
            out = scaled
        }
        var quality = 88
        var bytes: ByteArray
        do {
            val stream = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bytes = stream.toByteArray()
            quality -= 8
        } while (bytes.size > 1_000_000 && quality > 40)
        val tf = 24f / max(out.width, out.height).coerceAtLeast(1)
        tiny = Bitmap.createScaledBitmap(out, (out.width * tf).roundToInt().coerceAtLeast(1), (out.height * tf).roundToInt().coerceAtLeast(1), true)
        val ts = ByteArrayOutputStream()
        tiny.compress(Bitmap.CompressFormat.JPEG, 60, ts)
        return PickedImage(bytes, out.width, out.height, android.util.Base64.encodeToString(ts.toByteArray(), android.util.Base64.NO_WRAP))
    } finally {
        if (tiny != null && tiny !== scaled && tiny !== cropped && tiny !== canvasBitmap && !tiny.isRecycled) tiny.recycle()
        if (scaled != null && scaled !== cropped && scaled !== canvasBitmap && !scaled.isRecycled) scaled.recycle()
        if (cropped != null && cropped !== canvasBitmap && !cropped.isRecycled) cropped.recycle()
        if (canvasBitmap !== shown && !canvasBitmap.isRecycled) canvasBitmap.recycle()
    }
}
