package com.qmusic.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.qmusic.player.ringtone.ClipRange
import com.qmusic.ringtone.RingtoneDraft
import com.qmusic.ringtone.RingtonePhase
import com.qmusic.ringtone.RingtoneStep
import com.qmusic.ringtone.RingtoneStudio
import com.qmusic.ringtone.RingtoneTarget
import com.qmusic.ui.strings.LocalStrings
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkBackground
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import kotlin.math.abs
import kotlin.math.max

/**
 * Making a ringtone from a song: the song drawn as a wave with the part to keep marked on it (drag the ends, or the
 * whole part), a preview that loops it, fine steps, fades and loudness, and a name. Once it is made, the sounds it can
 * be set as.
 */
@Composable
fun RingtoneEditorScreen(
    studio: RingtoneStudio,
    onBack: () -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft by studio.draft.collectAsState()
    val playhead by studio.previewPosition.collectAsState()
    val s = LocalStrings.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(QMusicIcons.Back, contentDescription = s.back, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = s.makeRingtone,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        val d = draft ?: return@Column
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SongHeader(d)
            when (d.phase) {
                RingtonePhase.LOADING -> Loading(d)
                RingtonePhase.FAILED -> Failed(d.error, onRetry = studio::retry)
                RingtonePhase.READY, RingtonePhase.SAVING -> Editor(d, playhead, studio)
                RingtonePhase.SAVED -> Saved(d, studio, onOpenList)
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun SongHeader(d: RingtoneDraft) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceVariant),
        ) {
            val thumb = d.song.thumbnail ?: d.song.thumbnails.lastOrNull()?.url
            if (thumb != null) {
                AsyncImage(thumb, d.song.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = d.song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = d.song.artists.joinToString { it.name }.ifEmpty { s.unknownArtist },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Loading(d: RingtoneDraft) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Fetching is the first part of the bar, reading the sound the rest.
        val overall = if (d.step == RingtoneStep.FETCH) d.progress * 0.7f else 0.7f + d.progress * 0.3f
        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.18f),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (d.step == RingtoneStep.FETCH) s.ringtoneFetching else s.ringtoneAnalyzing,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Failed(error: String?, onRetry: () -> Unit) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = error ?: s.ringtoneErrorGeneric,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(12.dp)) { Text(s.ringtoneRetry) }
    }
}

@Composable
private fun Editor(d: RingtoneDraft, playhead: Long?, studio: RingtoneStudio) {
    val s = LocalStrings.current
    val saving = d.phase == RingtonePhase.SAVING

    RingtoneWave(
        bars = d.bars,
        durationMs = d.durationMs,
        range = d.range,
        playheadMs = playhead,
        enabled = !saving,
        onStart = studio::setStart,
        onEnd = studio::setEnd,
        onMove = studio::moveClip,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = s.ringtonePart,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = s.ringtoneLength(((d.range.lengthMs + 500) / 1000).toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }

    Stepper(s.ringtoneStart, d.range.startMs, enabled = !saving, onMinus = { studio.nudgeStart(-STEP_MS) }, onPlus = { studio.nudgeStart(STEP_MS) })
    Stepper(s.ringtoneEnd, d.range.endMs, enabled = !saving, onMinus = { studio.nudgeEnd(-STEP_MS) }, onPlus = { studio.nudgeEnd(STEP_MS) })

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(enabled = !saving, onClick = studio::togglePreview),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (d.previewing) QMusicIcons.Pause else QMusicIcons.Play,
                contentDescription = if (d.previewing) s.ringtoneStopPreview else s.ringtonePreview,
                tint = BgRoot,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = if (d.previewing) s.ringtoneStopPreview else s.ringtonePreview,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = studio::suggest, enabled = !saving) {
            Icon(QMusicIcons.Waveform, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.ringtoneSuggest)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface),
    ) {
        OptionRow(s.ringtoneFadeIn, s.ringtoneFadeInSubtitle, d.fadeIn, !saving, studio::setFadeIn)
        OptionRow(s.ringtoneFadeOut, s.ringtoneFadeOutSubtitle, d.fadeOut, !saving, studio::setFadeOut)
        OptionRow(s.ringtoneLouder, s.ringtoneLouderSubtitle, d.louder, !saving, studio::setLouder)
    }

    OutlinedTextField(
        value = d.name,
        onValueChange = studio::setName,
        enabled = !saving,
        singleLine = true,
        label = { Text(s.ringtoneName) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DarkSurfaceVariant,
            unfocusedContainerColor = DarkSurfaceVariant,
            disabledContainerColor = DarkSurfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    if (saving) {
        LinearProgressIndicator(
            progress = { d.progress },
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.18f),
        )
        Text(s.ringtoneCreating, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Button(
            onClick = studio::save,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(QMusicIcons.Bell, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.ringtoneCreate, style = MaterialTheme.typography.titleMedium, color = BgRoot)
        }
    }
}

/** The ringtone is made: which of the phone's sounds to set it as. */
@Composable
private fun Saved(d: RingtoneDraft, studio: RingtoneStudio, onOpenList: () -> Unit) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(QMusicIcons.Check, contentDescription = null, tint = BgRoot, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(s.ringtoneReady, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = d.made?.title.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.ringtoneSavedNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface),
    ) {
        TargetRow(QMusicIcons.Bell, s.ringtoneSetPhone, RingtoneTarget.PHONE in d.applied) { studio.apply(RingtoneTarget.PHONE) }
        TargetRow(QMusicIcons.Queue, s.ringtoneSetNotification, RingtoneTarget.NOTIFICATION in d.applied) { studio.apply(RingtoneTarget.NOTIFICATION) }
        TargetRow(QMusicIcons.Moon, s.ringtoneSetAlarm, RingtoneTarget.ALARM in d.applied) { studio.apply(RingtoneTarget.ALARM) }
        TargetRow(QMusicIcons.Account, s.ringtoneSetContact, d.contactName != null, detail = d.contactName?.takeIf { it != "·" }) {
            studio.applyToContact()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = studio::editAgain, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
            Text(s.ringtoneEditAgain)
        }
        OutlinedButton(onClick = onOpenList, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f)) {
            Text(s.myRingtones)
        }
    }
}

@Composable
private fun TargetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, done: Boolean, detail: String? = null, onClick: () -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (done) {
                Text(
                    text = listOfNotNull(s.ringtoneIsSet, detail).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (done) Icon(QMusicIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** One end of the clip with its time and − / + buttons for fine steps. */
@Composable
private fun Stepper(label: String, ms: Long, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(clock(ms), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        StepButton("−", enabled, onMinus)
        StepButton("+", enabled, onPlus)
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgRoot,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = DarkSurfaceVariant,
                uncheckedBorderColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

/**
 * The song drawn as bars, the kept part bright between two handles. Drag a handle to move that end, drag between them
 * to move the whole part, tap anywhere to put the part there; a line follows the preview while it plays.
 */
@Composable
private fun RingtoneWave(
    bars: List<Float>,
    durationMs: Long,
    range: ClipRange,
    playheadMs: Long?,
    enabled: Boolean,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onMove: (Long) -> Unit,
) {
    val current by rememberUpdatedState(range)
    val moveStart by rememberUpdatedState(onStart)
    val moveEnd by rememberUpdatedState(onEnd)
    val moveClip by rememberUpdatedState(onMove)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(136.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .pointerInput(durationMs, enabled) {
                if (!enabled || durationMs <= 0) return@pointerInput
                var mode = DRAG_CLIP
                var grab = 0L
                detectDragGestures(
                    onDragStart = { offset ->
                        val width = size.width.toFloat()
                        val r = current
                        val xs = r.startMs.toFloat() / durationMs * width
                        val xe = r.endMs.toFloat() / durationMs * width
                        val slop = HANDLE_TOUCH.toPx()
                        val ds = abs(offset.x - xs)
                        val de = abs(offset.x - xe)
                        mode = when {
                            ds <= slop && ds <= de -> DRAG_START
                            de <= slop -> DRAG_END
                            else -> DRAG_CLIP
                        }
                        // Grabbing inside the part keeps the spot under the finger; outside, the part centres on it.
                        grab = if (offset.x in xs..xe) (offset.x / width * durationMs).toLong() - r.startMs else r.lengthMs / 2
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val ms = (change.position.x / size.width * durationMs).toLong()
                        when (mode) {
                            DRAG_START -> moveStart(ms)
                            DRAG_END -> moveEnd(ms)
                            else -> moveClip(ms - grab)
                        }
                    },
                )
            }
            .pointerInput(durationMs, enabled) {
                if (!enabled || durationMs <= 0) return@pointerInput
                detectTapGestures { offset ->
                    moveClip((offset.x / size.width * durationMs).toLong() - current.lengthMs / 2)
                }
            },
    ) {
        if (durationMs <= 0 || bars.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val xs = range.startMs.toFloat() / durationMs * w
        val xe = range.endMs.toFloat() / durationMs * w

        drawRect(Color.White.copy(alpha = 0.07f), Offset(xs, 0f), Size(xe - xs, h))

        val slot = w / bars.size
        val barWidth = slot * 0.6f
        bars.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val bh = max(4.dp.toPx(), v * h * 0.76f)
            drawRoundRect(
                color = if (cx in xs..xe) Color.White else Color.White.copy(alpha = 0.3f),
                topLeft = Offset(cx - barWidth / 2, (h - bh) / 2),
                size = Size(barWidth, bh),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }

        val line = 2.dp.toPx()
        listOf(xs, xe).forEach { x ->
            drawRect(Color.White, Offset(x - line / 2, 0f), Size(line, h))
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x - 6.dp.toPx(), h / 2 - 17.dp.toPx()),
                size = Size(12.dp.toPx(), 34.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            for (dx in listOf(-2f, 2f)) {
                drawLine(
                    color = Color(0xFF0B0B0F),
                    start = Offset(x + dx.dp.toPx(), h / 2 - 7.dp.toPx()),
                    end = Offset(x + dx.dp.toPx(), h / 2 + 7.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }

        playheadMs?.let { ms ->
            val x = ms.toFloat() / durationMs * w
            drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5.dp.toPx())
            drawCircle(Color.White, 4.dp.toPx(), Offset(x, 6.dp.toPx()))
        }
    }
}

/** m:ss.t, to a tenth of a second (the ends of the part are placed that finely). */
private fun clock(ms: Long): String {
    val tenths = (ms / 100).coerceAtLeast(0)
    val seconds = tenths / 10
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}.${tenths % 10}"
}

private const val STEP_MS = 500L
private const val DRAG_START = 1
private const val DRAG_END = 2
private const val DRAG_CLIP = 3
private val HANDLE_TOUCH = 36.dp
