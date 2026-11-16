package com.qmusic.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val EventRed = Color(0xFFE53958)

private val MonthsTr = listOf("Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara")
private val MonthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DaysTr = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
private val DaysEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun local(ms: Long): LocalDateTime = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
private fun two(n: Int) = n.toString().padStart(2, '0')

/** "Mon 16 Nov · 20:00" in the app's language. */
private fun whenLabel(ms: Long, english: Boolean): String {
    val t = local(ms)
    val day = (if (english) DaysEn else DaysTr)[t.dayOfWeek.ordinal]
    val month = (if (english) MonthsEn else MonthsTr)[t.monthNumber - 1]
    return "$day ${t.dayOfMonth} $month · ${two(t.hour)}:${two(t.minute)}"
}

/**
 * An event in a conversation: a small calendar page (month and day), what, when and where, and the three answers with
 * how many gave each (the person's own is filled); a tap answers (again: takes it back).
 */
@Composable
internal fun EventCard(event: ChatEvent, votes: Map<String, List<Int>>, myUid: String, mine: Boolean, onVote: (List<Int>) -> Unit) {
    val english = ChatMusic.english
    val fg = if (mine) InkInverse else TextPrimary
    val soft = if (mine) InkInverse.copy(alpha = 0.6f) else TextSecondary
    val t = local(event.at)
    val over = event.at < Clock.System.now().toEpochMilliseconds()
    val myAnswer = votes[myUid]?.firstOrNull()
    Column(Modifier.width(270.dp).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The calendar page.
            Column(
                Modifier.size(width = 52.dp, height = 58.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(18.dp).background(EventRed), contentAlignment = Alignment.Center) {
                    Text((if (english) MonthsEn else MonthsTr)[t.monthNumber - 1].uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    Text(t.dayOfMonth.toString(), color = Color(0xFF111114), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(whenLabel(event.at, english) + if (over) (if (english) " · over" else " · geçti") else "", color = soft, fontSize = 13.sp)
                if (event.place.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(QMusicIcons.Location, contentDescription = null, tint = soft, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(event.place, color = soft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val labels = if (english) listOf("Going", "Maybe", "Can't") else listOf("Katılıyorum", "Belki", "Katılmıyorum")
            labels.forEachIndexed { i, label ->
                val count = votes.values.count { it.firstOrNull() == i }
                val on = myAnswer == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(if (on) (if (mine) InkInverse else Ink) else (if (mine) InkInverse.copy(alpha = 0.08f) else Ink.copy(alpha = 0.08f)))
                        .clickable { onVote(if (on) emptyList() else listOf(i)) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (count > 0) "$label $count" else label,
                        color = if (on) (if (mine) Ink else InkInverse) else fg,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Making an event: what, the day, the hour and (if any) where. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventDialog(onSend: (ChatEvent) -> Unit, onDismiss: () -> Unit) {
    val english = ChatMusic.english
    val zone = TimeZone.currentSystemDefault()
    val now = local(Clock.System.now().toEpochMilliseconds())
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    // By default: tomorrow, at the next round hour of today.
    var day by remember { mutableStateOf(LocalDate.fromEpochDays(now.date.toEpochDays() + 1)) }
    var time by remember { mutableStateOf(LocalTime((now.hour + 1).coerceAtMost(23), 0)) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val at = LocalDateTime(day, time).toInstant(zone).toEpochMilliseconds()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(if (english) "New event" else "Yeni etkinlik", color = TextPrimary) },
        text = {
            Column {
                Field(title, { title = it.take(100) }, if (english) "What (e.g. Dinner)" else "Ne (ör. Akşam yemeği)")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EventChoice(Modifier.weight(1f), if (english) "Day" else "Gün", whenLabel(at, english).substringBefore(" ·")) { pickingDay = true }
                    EventChoice(Modifier.weight(1f), if (english) "Time" else "Saat", "${two(time.hour)}:${two(time.minute)}") { pickingTime = true }
                }
                Spacer(Modifier.height(10.dp))
                Field(place, { place = it.take(100) }, if (english) "Where (optional)" else "Nerede (isteğe bağlı)")
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSend(ChatEvent(title.trim(), at, place.trim()))
                onDismiss()
            }) { Text(if (english) "Send" else "Gönder", color = if (title.isNotBlank()) Ink else TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (english) "Cancel" else "Vazgeç", color = TextSecondary) } },
    )
    if (pickingDay) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day.toEpochDays() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = LocalDate.fromEpochDays((it / 86_400_000L).toInt()) }
                    pickingDay = false
                }) { Text("OK", color = Ink) }
            },
        ) { DatePicker(state = state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = DarkSurfaceVariant,
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime(state.hour, state.minute)
                    pickingTime = false
                }) { Text("OK", color = Ink) }
            },
        )
    }
}

@Composable
private fun EventChoice(modifier: Modifier, label: String, value: String, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(DarkSurface).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
