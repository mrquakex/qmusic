package com.qmusic.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.QMusicIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A line drawn on the photo: its points (0-1 of the turned photo), colour and thickness (of the shorter side). */
private class Line(val points: MutableList<Offset>, val color: Color, val width: Float)

private enum class EditMode { CROP, DRAW }

private val PenColors = listOf(Color.White, Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE), Color.Black)

@Composable
actual fun PhotoEditor(image: PickedImage, onDone: (PickedImage) -> Unit, onCancel: () -> Unit) {
    PlatformBackHandler(enabled = true) { onCancel() }
    val context = LocalContext.current
    val tr = remember { context.getSharedPreferences("qmusic_settings", android.content.Context.MODE_PRIVATE).getString("language", "tr") != "en" }
    val original = remember(image) { BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size) }
    var turns by remember { mutableStateOf(0) }
    val shown = remember(original, turns) {
        if (turns % 4 == 0) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(90f * turns) }, true)
    }
    // The crop as parts of the turned photo: left, top, right, bottom.
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    val lines = remember { mutableStateListOf<Line>() }
    var mode by remember { mutableStateOf(EditMode.DRAW) }
    var color by remember { mutableStateOf(PenColors[1]) }
    var busy by remember { mutableStateOf(false) }
    var redraw by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    fun turn() {
        // A quarter turn clockwise: (x, y) → (1 - y, x) for the lines and the crop alike.
        lines.forEach { line -> line.points.replaceAll { Offset(1f - it.y, it.x) } }
        crop = Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        turns = (turns + 1) % 4
        redraw++
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(QMusicIcons.Close, contentDescription = null, tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (lines.isNotEmpty()) {
                Text(
                    if (tr) "Geri al" else "Undo",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { lines.removeAt(lines.lastIndex); redraw++ }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Box(
                Modifier.padding(end = 8.dp).size(44.dp).clip(CircleShape).background(Color.White).clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        val edited = withContext(Dispatchers.Default) { runCatching { render(shown, crop, lines) }.getOrNull() }
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
            val scale = minOf(boxW / shown.width, boxH / shown.height)
            val w = shown.width * scale
            val h = shown.height * scale
            val left = (boxW - w) / 2
            val top = (boxH - h) / 2
            val bitmap = remember(shown) { shown.asImageBitmap() }
            Box(Modifier.fillMaxSize()) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                val handle = with(density) { 36.dp.toPx() }
                var dragging by remember { mutableStateOf(-1) }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(mode, color, shown) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val p = Offset(((start.x - left) / w).coerceIn(0f, 1f), ((start.y - top) / h).coerceIn(0f, 1f))
                                if (mode == EditMode.DRAW) {
                                    lines += Line(mutableListOf(p), color, 0.008f)
                                } else {
                                    // Which corner is nearest (or the middle, to move it whole).
                                    val corners = listOf(Offset(crop.left, crop.top), Offset(crop.right, crop.top), Offset(crop.left, crop.bottom), Offset(crop.right, crop.bottom))
                                    val near = corners.indexOfFirst { c -> abs((c.x - p.x) * w) < handle && abs((c.y - p.y) * h) < handle }
                                    dragging = if (near >= 0) near else if (crop.contains(p)) 4 else -1
                                }
                                redraw++
                            },
                            onDragEnd = { dragging = -1 },
                            onDrag = { change, amount ->
                                change.consume()
                                val p = Offset(((change.position.x - left) / w).coerceIn(0f, 1f), ((change.position.y - top) / h).coerceIn(0f, 1f))
                                if (mode == EditMode.DRAW) {
                                    lines.lastOrNull()?.points?.add(p)
                                } else {
                                    val min = 0.08f
                                    crop = when (dragging) {
                                        0 -> crop.copy(left = p.x.coerceAtMost(crop.right - min), top = p.y.coerceAtMost(crop.bottom - min))
                                        1 -> crop.copy(right = p.x.coerceAtLeast(crop.left + min), top = p.y.coerceAtMost(crop.bottom - min))
                                        2 -> crop.copy(left = p.x.coerceAtMost(crop.right - min), bottom = p.y.coerceAtLeast(crop.top + min))
                                        3 -> crop.copy(right = p.x.coerceAtLeast(crop.left + min), bottom = p.y.coerceAtLeast(crop.top + min))
                                        4 -> {
                                            val dx = (amount.x / w).coerceIn(-crop.left, 1f - crop.right)
                                            val dy = (amount.y / h).coerceIn(-crop.top, 1f - crop.bottom)
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
                    if (redraw < 0) return@Canvas
                    val side = minOf(w, h)
                    lines.forEach { line ->
                        if (line.points.size == 1) {
                            val p = line.points[0]
                            drawCircle(line.color, radius = line.width * side / 2, center = Offset(left + p.x * w, top + p.y * h))
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
                    // Outside the crop is dimmed; the crop has a frame and corner marks.
                    val cl = left + crop.left * w
                    val ct = top + crop.top * h
                    val cr = left + crop.right * w
                    val cb = top + crop.bottom * h
                    val dim = Color.Black.copy(alpha = 0.6f)
                    drawRect(dim, Offset(left, top), Size(w, ct - top))
                    drawRect(dim, Offset(left, cb), Size(w, top + h - cb))
                    drawRect(dim, Offset(left, ct), Size(cl - left, cb - ct))
                    drawRect(dim, Offset(cr, ct), Size(left + w - cr, cb - ct))
                    if (mode == EditMode.CROP) {
                        drawRect(Color.White, Offset(cl, ct), Size(cr - cl, cb - ct), style = Stroke(2.dp.toPx()))
                        // Thirds, as cameras show them.
                        for (i in 1..2) {
                            drawLine(Color.White.copy(alpha = 0.4f), Offset(cl + (cr - cl) * i / 3, ct), Offset(cl + (cr - cl) * i / 3, cb), 1f)
                            drawLine(Color.White.copy(alpha = 0.4f), Offset(cl, ct + (cb - ct) * i / 3), Offset(cr, ct + (cb - ct) * i / 3), 1f)
                        }
                        val l = 22.dp.toPx()
                        val sw = 4.dp.toPx()
                        listOf(Offset(cl, ct) to (1f to 1f), Offset(cr, ct) to (-1f to 1f), Offset(cl, cb) to (1f to -1f), Offset(cr, cb) to (-1f to -1f)).forEach { (c, d) ->
                            drawLine(Color.White, c, Offset(c.x + l * d.first, c.y), sw, StrokeCap.Round)
                            drawLine(Color.White, c, Offset(c.x, c.y + l * d.second), sw, StrokeCap.Round)
                        }
                    }
                }
            }
        }
        if (mode == EditMode.DRAW) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                PenColors.forEach { c ->
                    Box(
                        Modifier.padding(horizontal = 6.dp).size(if (c == color) 34.dp else 28.dp).clip(CircleShape).background(c)
                            .border(2.dp, if (c == color) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                            .clickable { color = c },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            EditorButton(if (tr) "Çiz" else "Draw", mode == EditMode.DRAW) { mode = EditMode.DRAW }
            EditorButton(if (tr) "Kırp" else "Crop", mode == EditMode.CROP) { mode = EditMode.CROP }
            EditorButton(if (tr) "Döndür" else "Rotate", false) { turn() }
            if (crop != Rect(0f, 0f, 1f, 1f)) EditorButton(if (tr) "Sıfırla" else "Reset", false) { crop = Rect(0f, 0f, 1f, 1f); redraw++ }
        }
    }
}

@Composable
private fun EditorButton(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.Black else Color.White,
        fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** The edited photo: the lines drawn onto the turned photo, then the crop, shrunk and compressed like a picked one. */
private fun render(shown: Bitmap, crop: Rect, lines: List<Line>): PickedImage {
    val canvasBitmap = shown.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(canvasBitmap)
    val side = minOf(canvasBitmap.width, canvasBitmap.height).toFloat()
    lines.forEach { line ->
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
            canvas.drawCircle(p.x * canvasBitmap.width, p.y * canvasBitmap.height, line.width * side / 2, paint)
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
    val x = (crop.left * canvasBitmap.width).roundToInt().coerceIn(0, canvasBitmap.width - 1)
    val y = (crop.top * canvasBitmap.height).roundToInt().coerceIn(0, canvasBitmap.height - 1)
    val cw = ((crop.right - crop.left) * canvasBitmap.width).roundToInt().coerceIn(1, canvasBitmap.width - x)
    val ch = ((crop.bottom - crop.top) * canvasBitmap.height).roundToInt().coerceIn(1, canvasBitmap.height - y)
    var out = Bitmap.createBitmap(canvasBitmap, x, y, cw, ch)
    val longest = max(out.width, out.height)
    if (longest > 1600) {
        val f = 1600f / longest
        out = Bitmap.createScaledBitmap(out, (out.width * f).roundToInt(), (out.height * f).roundToInt(), true)
    }
    var quality = 88
    var bytes: ByteArray
    do {
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        bytes = stream.toByteArray()
        quality -= 8
    } while (bytes.size > 1_000_000 && quality > 40)
    val tf = 24f / max(out.width, out.height)
    val tiny = Bitmap.createScaledBitmap(out, (out.width * tf).roundToInt().coerceAtLeast(1), (out.height * tf).roundToInt().coerceAtLeast(1), true)
    val ts = ByteArrayOutputStream()
    tiny.compress(Bitmap.CompressFormat.JPEG, 60, ts)
    return PickedImage(bytes, out.width, out.height, android.util.Base64.encodeToString(ts.toByteArray(), android.util.Base64.NO_WRAP))
}
