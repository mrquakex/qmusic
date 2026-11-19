package com.qmusic.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun tt(tr: String, en: String) = if (ChatMusic.english) en else tr

private val ToolDanger = Color(0xFFE5484D)
private val ChartBlue = Color(0xFF3B82F6)
private val ChartGreen = Color(0xFF30A46C)
private val ChartViolet = Color(0xFF8B5CF6)

private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> ((bytes * 10 / (1024L * 1024 * 1024)) / 10.0).toString().replace('.', ',') + " GB"
    bytes >= 1024L * 1024 -> ((bytes * 10 / (1024L * 1024)) / 10.0).toString().replace('.', ',') + " MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun whenLabel(ms: Long): String {
    if (ms <= 0L) return "—"
    val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(d.dayOfMonth)}.${two(d.monthNumber)} ${two(d.hour)}:${two(d.minute)}"
}

/** "2026-11-19" → "19.11". */
private fun shortDay(day: String): String = day.split('-').let { if (it.size == 3) "${it[2]}.${it[1]}" else day }

@Composable
private fun ToolCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DarkSurface).padding(14.dp)) { content() }
}

@Composable
private fun ToolNote(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
}

// ==================================================================== charts

/** The last 30 days: who showed up, who joined, how many messages went, and how many accounts there are. */
@Composable
internal fun AdminCharts(console: AdminConsole) {
    val days by remember(console) { console.daily() }.collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (days.isEmpty()) {
            item { ToolNote(tt("Grafikler hazırlanıyor. Sayılar her saat ve paneli her açışında güncellenir.", "The charts are being made. The numbers are updated every hour and each time the panel opens.")) }
        } else {
            item { BarChart(tt("Günlük aktif kişi", "Daily active people"), days.map { it.day to it.active }, ChartBlue) }
            item { BarChart(tt("Yeni katılan", "New sign-ups"), days.map { it.day to it.newUsers }, ChartGreen) }
            item {
                BarChart(
                    tt("Gönderilen mesaj", "Messages sent"),
                    days.map { it.day to it.messages },
                    ChartViolet,
                    note = tt("Sadece sayı tutulur, mesajların içeriği değil. Sayım bu sürümden itibaren başlar.", "Only the number is kept, never what they say. Counting starts with this version."),
                )
            }
            item { BarChart(tt("Toplam hesap", "Accounts in all"), days.map { it.day to it.total }, TextSecondary) }
        }
    }
}

/** Bars for each day, the busiest one's number at the top, today's figure big; the first and last day under them. */
@Composable
private fun BarChart(title: String, points: List<Pair<String, Int>>, color: Color, note: String? = null) {
    val max = (points.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val today = points.lastOrNull()?.second ?: 0
    val sum = points.sumOf { it.second }
    ToolCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextSecondary, fontSize = 13.sp)
                Text(today.toString(), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(tt("30 gün: $sum", "30 days: $sum"), color = TextSecondary, fontSize = 12.sp)
                Text(tt("en çok: $max", "peak: $max"), color = TextMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = points.size.coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            // A faint line at the top: the busiest day.
            drawLine(color.copy(alpha = 0.15f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            points.forEachIndexed { i, (_, v) ->
                val h = if (v <= 0) 2.dp.toPx() else (v.toFloat() / max) * size.height
                drawRoundRect(
                    color = if (i == n - 1) color else color.copy(alpha = 0.55f),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(points.firstOrNull()?.first?.let(::shortDay).orEmpty(), color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(tt("bugün", "today"), color = TextMuted, fontSize = 11.sp)
        }
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(note, color = TextMuted, fontSize = 11.sp)
        }
    }
}

// ==================================================================== storage

/** How much each conversation's files take on the server, the largest first; a conversation's files can be deleted. */
@Composable
internal fun AdminStorage(controller: ChatController, console: AdminConsole, users: List<AdminUser>, groups: List<AdminGroup>) {
    val stats by remember(console) { console.stats() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<StorageEntry?>(null) }
    val nameOf = remember(users) { users.associate { it.uid to it.name } }
    fun label(id: String): Pair<String, String> = when {
        id.startsWith("u_") -> (nameOf[id.removePrefix("u_")] ?: tt("Bilinmeyen", "Unknown")) to tt("Profil ve durum dosyaları", "Profile and status files")
        id.startsWith("grp") -> (groups.firstOrNull { it.id == id }?.name ?: tt("Silinmiş grup", "Deleted group")) to tt("Grup", "Group")
        id.contains('_') -> id.split('_').joinToString(" ↔ ") { nameOf[it] ?: if (it == "nuvelo") "Nuvelo" else "?" } to tt("Sohbet", "Chat")
        else -> id to ""
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val s = stats
        item {
            ToolCard {
                if (s == null) {
                    ToolNote(tt("Hesaplanıyor…", "Counting…"))
                } else {
                    val used = (s.diskTotal - s.diskFree).coerceAtLeast(0)
                    Text(tt("Mesaj dosyaları", "Message files"), color = TextSecondary, fontSize = 13.sp)
                    Text(bytesLabel(s.mediaBytes), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (s.diskTotal > 0) {
                        Spacer(Modifier.height(10.dp))
                        val part = (used.toFloat() / s.diskTotal).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(DarkSurfaceVariant)) {
                            Box(Modifier.fillMaxWidth(part).height(8.dp).background(if (part > 0.85f) ToolDanger else ChartBlue))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tt("Disk: ${bytesLabel(used)} dolu, ${bytesLabel(s.diskFree)} boş", "Disk: ${bytesLabel(used)} used, ${bytesLabel(s.diskFree)} free"),
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        item { ToolNote(tt("Sadece boyutlar sayılır, hiçbir dosya açılmaz.", "Only sizes are counted; no file is opened.")) }
        items(s?.storage.orEmpty(), key = { it.id }) { e ->
            val (name, kind) = label(e.id)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSurface).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$kind · ${tt("${e.files} dosya", "${e.files} files")}", color = TextSecondary, fontSize = 12.sp)
                }
                Text(bytesLabel(e.bytes), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (!e.id.startsWith("u_")) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tt("Sil", "Delete"), color = ToolDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { deleting = e }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = DarkSurfaceVariant,
            title = { Text(tt("Dosyalar silinsin mi?", "Delete the files?"), color = TextPrimary) },
            text = {
                Text(
                    tt(
                        "\"${label(e.id).first}\" sohbetindeki ${e.files} dosya (${bytesLabel(e.bytes)}) sunucudan silinir. Mesajlar kalır ama fotoğraf, ses ve videolar açılmaz. Geri alınamaz.",
                        "The ${e.files} files (${bytesLabel(e.bytes)}) of \"${label(e.id).first}\" go from the server. The messages stay, but their photos, voice and videos will not open. This cannot be undone.",
                    ),
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        console.request("deleteMedia", e.id)
                            .onSuccess { controller.reportInfo(tt("Siliniyor", "Deleting")) }
                            .onFailure { controller.reportError(it.message ?: "") }
                    }
                }) { Text(tt("Sil", "Delete"), color = ToolDanger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tt("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
}

// ==================================================================== crashes

/** The phones' crashes, the same crash grouped together: how often, on which versions and phones, and its trace. */
@Composable
internal fun AdminCrashes(console: AdminConsole, users: List<AdminUser>) {
    val crashes by remember(console) { console.crashes() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val nameOf = remember(users) { users.associate { it.uid to it.name } }
    val groups = remember(crashes) { crashes.groupBy { it.headline }.entries.sortedByDescending { e -> e.value.maxOf { it.at } } }
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ToolNote(
                tt(
                    "Uygulama çökünce bir sonraki açılışta buraya gelir (2.13.9 ve sonrası, giriş yapmış kişiler). Aynı hata bir arada gösterilir.",
                    "When the app crashes, it comes here at the next start (2.13.9 on, signed-in people). The same error is shown together.",
                ),
            )
        }
        if (groups.isEmpty()) item { Text(tt("Çökme yok.", "No crashes."), color = TextSecondary, modifier = Modifier.padding(top = 20.dp)) }
        items(groups, key = { it.key }) { (headline, list) ->
            val expanded = open == headline
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSurface).clickable { open = if (expanded) null else headline }
                    .padding(14.dp).animateContentSize(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${list.size}×", color = InkInverse, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(ToolDanger).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(whenLabel(list.maxOf { it.at }), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(list.map { it.version }.distinct().joinToString(", ") { "v$it" }, color = TextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(headline.ifEmpty { tt("(açıklamasız)", "(no message)") }, color = TextPrimary, fontSize = 14.sp, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                Text(
                    list.map { it.device }.distinct().take(3).joinToString(" · ") + tt(" · ${list.map { it.uid }.distinct().size} kişi", " · ${list.map { it.uid }.distinct().size} people"),
                    color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(tt("Kimde: ", "Who: ") + list.map { nameOf[it.uid] ?: "?" }.distinct().joinToString(", "), color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BgRoot).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                        Text(list.first().trace.take(4_000), color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tt("Hepsini sil", "Delete all of these"), color = ToolDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { list.forEach { console.deleteCrash(it.id) } } }.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// ==================================================================== settings: maintenance and spam

@Composable
internal fun MaintenanceAndSpam(controller: ChatController, console: AdminConsole) {
    val maintenance by console.maintenance.collectAsState()
    val spam by console.spam.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember(maintenance.text) { mutableStateOf(maintenance.text) }
    var confirmOn by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tt("Bakım modu", "Maintenance"), color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (maintenance.on) tt("Açık: mesajlar kapalı", "On: messages are closed") else tt("Kapalı", "Off"),
                        color = if (maintenance.on) ToolDanger else TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                    Text(tt("Açıkken yöneticiler dışında herkes mesajlarda bakım ekranı görür. Müzik ve video çalışır.", "While on, everyone but the admins sees a maintenance page in the messages. Music and videos work."), color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = maintenance.on,
                    onCheckedChange = { on ->
                        if (on) confirmOn = true else scope.launch { console.setMaintenance(false, text) }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = InkInverse, checkedTrackColor = ToolDanger),
                )
            }
            Spacer(Modifier.height(10.dp))
            Field(text, { text = it.take(300) }, tt("Ekranda yazacak (boşsa: Kısa bir bakımdayız)", "What it says (empty: We are down for a short while)"))
            if (maintenance.on && text != maintenance.text) {
                Spacer(Modifier.height(8.dp))
                Pill(tt("Yazıyı güncelle", "Update the text"), primary = true) { scope.launch { console.setMaintenance(true, text) } }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(tt("Spam koruması", "Spam guard"), color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (spam.on) tt("Açık", "On") else tt("Kapalı", "Off"), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        tt("Bir dakikada ${spam.limit} mesajdan fazla gönderen 1 saat otomatik susturulur, sana Claude sohbetinden haber gelir.", "Anyone sending more than ${spam.limit} messages a minute is muted for an hour by itself; you hear of it in your Claude chat."),
                        color = TextSecondary, fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = spam.on,
                    onCheckedChange = { on -> scope.launch { console.setSpam(on, spam.limit) } },
                    colors = SwitchDefaults.colors(checkedThumbColor = InkInverse, checkedTrackColor = Ink),
                )
            }
            if (spam.on) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20, 30, 40, 60, 100).forEach { n ->
                        Pill(tt("$n/dk", "$n/min"), primary = spam.limit == n) { scope.launch { console.setSpam(true, n) } }
                    }
                }
            }
        }
    }
    if (confirmOn) {
        AlertDialog(
            onDismissRequest = { confirmOn = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(tt("Bakım modu açılsın mı?", "Turn maintenance on?"), color = TextPrimary) },
            text = { Text(tt("Herkesin mesajları sen kapatana kadar kapanır.", "Everyone's messages close until you turn it off."), color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOn = false
                    scope.launch {
                        console.setMaintenance(true, text)
                            .onSuccess { controller.reportInfo(tt("Bakım modu açık", "Maintenance is on")) }
                            .onFailure { controller.reportError(it.message ?: "") }
                    }
                }) { Text(tt("Aç", "Turn on"), color = ToolDanger) }
            },
            dismissButton = { TextButton(onClick = { confirmOn = false }) { Text(tt("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
}

/** In place of the messages while maintenance is on (not for admins). */
@Composable
internal fun MaintenancePage(text: String, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(BgRoot).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tt("Kısa bir bakımdayız", "We are down for a short while"), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text.ifBlank { tt("Mesajlar birazdan geri gelecek. Müzik ve videoları kullanmaya devam edebilirsin.", "Messages will be back soon. You can keep using music and videos.") },
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        WhiteButton(tt("Müziğe dön", "Back to music")) { onExit() }
    }
}

// ==================================================================== a person: more

/** On a person's page: the badge and Claude switches, a message from Nuvelo, deleting the account. */
@Composable
internal fun AdminUserExtras(controller: ChatController, console: AdminConsole, user: AdminUser, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var writing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun done(result: Result<Unit>, ok: String) {
        result.onSuccess { controller.reportInfo(ok) }.onFailure { controller.reportError(tt("Olmadı: ", "Failed: ") + (it.message ?: "")) }
    }
    Column {
        SectionTitle(tt("Yetkiler", "Rights"))
        SwitchLine(tt("Geliştirici rozeti", "Developer badge"), tt("Adının yanında rozet görünür.", "A badge shows by their name."), user.developer) { on ->
            scope.launch { done(console.setBadge(user.uid, on), tt("Kaydedildi", "Saved")) }
        }
        SwitchLine(tt("Claude erişimi", "Claude access"), tt("Claude ile sohbet edebilir.", "They can chat with Claude."), user.claude) { on ->
            scope.launch { done(console.setClaude(user.uid, on), tt("Kaydedildi", "Saved")) }
        }
        SectionTitle(tt("İletişim", "Contact"))
        Column(Modifier.fillMaxWidth().clickable { writing = true }.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(tt("Nuvelo adına mesaj gönder", "Message from Nuvelo"), color = TextPrimary, fontSize = 15.sp)
            Text(tt("\"Nuvelo\" hesabından sohbetine gelir. Cevap verirse sana Claude sohbetinden iletilir.", "Comes into their chats from the \"Nuvelo\" account. If they answer, it reaches you in your Claude chat."), color = TextSecondary, fontSize = 12.sp)
        }
        SectionTitle(tt("Tehlikeli", "Danger"))
        Column(Modifier.fillMaxWidth().clickable { deleting = true }.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(tt("Hesabı sil", "Delete the account"), color = ToolDanger, fontSize = 15.sp)
            Text(tt("Profili, mesajları, durumları ve dosyaları kalıcı olarak silinir.", "Their profile, messages, statuses and files go for good."), color = TextSecondary, fontSize = 12.sp)
        }
    }
    if (writing) {
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(tt("${user.name} kişisine Nuvelo'dan", "From Nuvelo to ${user.name}"), color = TextPrimary) },
            text = { Box(Modifier.imePadding()) { Field(body, { body = it.take(2000) }, tt("Mesaj", "Message")) } },
            confirmButton = {
                TextButton(enabled = body.isNotBlank(), onClick = {
                    writing = false
                    val text = body.trim()
                    scope.launch { done(console.request("message", user.uid, text), tt("Gönderildi", "Sent")) }
                }) { Text(tt("Gönder", "Send"), color = if (body.isNotBlank()) Ink else TextMuted) }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text(tt("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
    if (deleting) {
        var typed by remember { mutableStateOf("") }
        val sure = typed.trim().equals(user.username, ignoreCase = true)
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(tt("Hesap kalıcı olarak silinsin mi?", "Delete the account for good?"), color = ToolDanger) },
            text = {
                Column {
                    Text(
                        tt(
                            "${user.name} adlı kişinin profili, gönderdiği bütün mesajlar, durumları, arkadaşlıkları ve dosyaları silinir; giriş hesabı da kapanır. Geri alınamaz.\n\nOnaylamak için kullanıcı adını yaz: ${user.username}",
                            "${user.name}'s profile, every message they sent, their statuses, friends and files go, and their sign-in is closed. This cannot be undone.\n\nTo confirm, type their user name: ${user.username}",
                        ),
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Field(typed, { typed = it.take(30) }, user.username, capitalize = false)
                }
            },
            confirmButton = {
                TextButton(enabled = sure, onClick = {
                    deleting = false
                    scope.launch {
                        console.request("deleteAccount", user.uid)
                            .onSuccess {
                                controller.reportInfo(tt("Hesap siliniyor", "Deleting the account"))
                                onDeleted()
                            }
                            .onFailure { controller.reportError(it.message ?: "") }
                    }
                }) { Text(tt("Kalıcı olarak sil", "Delete for good"), color = if (sure) ToolDanger else TextMuted) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(tt("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
}

@Composable
private fun SwitchLine(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(note, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = InkInverse, checkedTrackColor = Ink))
    }
}
