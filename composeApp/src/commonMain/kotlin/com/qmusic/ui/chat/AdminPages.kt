package com.qmusic.ui.chat

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val Danger = Color(0xFFE5484D)
private val Warn = Color(0xFFF5A524)
private val Good = Color(0xFF30A46C)

private fun t(tr: String, en: String) = if (ChatMusic.english) en else tr

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/** "5 dk önce", "3 sa önce", "2 gün önce"; "—" when not known. */
private fun ago(ms: Long): String {
    if (ms <= 0L) return "—"
    val m = (nowMs() - ms) / 60_000
    return when {
        m < 1 -> t("az önce", "just now")
        m < 60 -> t("$m dk önce", "$m min ago")
        m < 1440 -> t("${m / 60} sa önce", "${m / 60} h ago")
        else -> t("${m / 1440} gün önce", "${m / 1440} d ago")
    }
}

/** "19.11.2026 14:05". */
private fun dateTime(ms: Long): String {
    if (ms <= 0L) return "—"
    val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(d.dayOfMonth)}.${two(d.monthNumber)}.${d.year} ${two(d.hour)}:${two(d.minute)}"
}

private fun untilLabel(ban: AdminBan): String = if (ban.forever) t("süresiz", "for good") else t("${dateTime(ban.until)} tarihine kadar", "until ${dateTime(ban.until)}")

private fun gb(bytes: Long): String = ((bytes * 10 / (1024L * 1024 * 1024)) / 10.0).toString().replace('.', ',') + " GB"

private enum class AdminTab(val tr: String, val en: String) {
    SUMMARY("Özet", "Summary"),
    PEOPLE("Kişiler", "People"),
    REPORTS("Şikâyetler", "Reports"),
    GROUPS("Gruplar", "Groups"),
    CHARTS("Grafikler", "Charts"),
    STORAGE("Depolama", "Storage"),
    CRASHES("Çökmeler", "Crashes"),
    ANNOUNCE("Duyuru", "Announce"),
    SETTINGS("Ayarlar", "Settings"),
    LOG("Kayıt", "Log"),
}

/** The admin panel. Only an admin reaches it (the entry shows only for them, and the server refuses others anyway). */
@Composable
internal fun AdminPage(controller: ChatController, me: ChatUser) {
    val console = controller.admin
    val isAdmin by (console?.isAdmin ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    if (console == null || !isAdmin) {
        Column(Modifier.fillMaxSize()) {
            TopBar(t("Yönetim", "Admin"), { controller.pop() })
            Centered { Text(t("Bu sayfa yalnızca yöneticiler için.", "This page is for admins only."), color = TextSecondary) }
        }
        return
    }
    val users by remember(console) { console.users() }.collectAsState(initial = emptyList())
    val reports by remember(console) { console.reports() }.collectAsState(initial = emptyList())
    val groups by remember(console) { console.groups() }.collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(AdminTab.SUMMARY) }
    var openUser by remember { mutableStateOf<String?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    val names = remember(users, groups) {
        users.associate { it.uid to it.name } + groups.associate { it.id to t("grup: ", "group: ") + it.name } + (CLAUDE_BOT_UID to "Claude")
    }
    // The counts (sign-ups, conversations, the disk) are made fresh each time the panel opens.
    LaunchedEffect(console) { console.request("stats") }

    PlatformBackHandler(enabled = openUser != null || openGroup != null) {
        if (openUser != null) openUser = null else openGroup = null
    }
    openUser?.let { uid ->
        val user = users.firstOrNull { it.uid == uid }
        if (user != null) {
            AdminUserPage(controller, console, me, user, groups.filter { g -> g.members.any { it.first == uid } }) { openUser = null }
            return
        }
    }
    openGroup?.let { id ->
        val group = groups.firstOrNull { it.id == id }
        if (group != null) {
            AdminGroupPage(controller, console, group, names, onUser = { openUser = it }) { openGroup = null }
            return
        }
    }

    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(t("Yönetim", "Admin"), { controller.pop() })
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdminTab.entries.forEach { each ->
                val open = reports.count { it.handled == null }
                val label = (if (ChatMusic.english) each.en else each.tr) + if (each == AdminTab.REPORTS && open > 0) " ($open)" else ""
                Pill(label, primary = tab == each) { tab = each }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                AdminTab.SUMMARY -> AdminSummary(console, users, reports, groups) { tab = it }
                AdminTab.PEOPLE -> AdminPeople(users) { openUser = it }
                AdminTab.REPORTS -> AdminReports(controller, console, reports, names, onUser = { openUser = it }, onGroup = { openGroup = it })
                AdminTab.GROUPS -> AdminGroups(groups, names) { openGroup = it }
                AdminTab.CHARTS -> AdminCharts(console)
                AdminTab.STORAGE -> AdminStorage(controller, console, users, groups)
                AdminTab.CRASHES -> AdminCrashes(console, users)
                AdminTab.ANNOUNCE -> AdminAnnounce(controller, console)
                AdminTab.SETTINGS -> AdminSettings(controller, console)
                AdminTab.LOG -> AdminLog(console, names)
            }
        }
    }
}

// ------------------------------------------------------------------ summary

@Composable
private fun AdminSummary(console: AdminConsole, users: List<AdminUser>, reports: List<AdminReport>, groups: List<AdminGroup>, go: (AdminTab) -> Unit) {
    val stats by remember(console) { console.stats() }.collectAsState(initial = null)
    val now = nowMs()
    val people = users.filter { it.uid != CLAUDE_BOT_UID && it.uid != "nuvelo" }
    val day = people.count { it.online || now - it.lastActive < 24 * 3600_000L }
    val week = people.count { it.online || now - it.lastActive < 7 * 24 * 3600_000L }
    val newWeek = people.count { it.created > 0 && now - it.created < 7 * 24 * 3600_000L }
    val banned = people.count { it.ban?.isBan == true && it.ban.activeAt(now) }
    val muted = people.count { it.ban?.isBan == false && it.ban.activeAt(now) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(t("Hesap", "Accounts"), people.size.toString(), Modifier.weight(1f)) { go(AdminTab.PEOPLE) }
                StatTile(t("Şu an açık", "Online now"), people.count { it.online }.toString(), Modifier.weight(1f)) { go(AdminTab.PEOPLE) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(t("Son 24 saat", "Last 24 h"), day.toString(), Modifier.weight(1f))
                StatTile(t("Son 7 gün", "Last 7 days"), week.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(t("Bu hafta katılan", "Joined this week"), newWeek.toString(), Modifier.weight(1f))
                StatTile(t("Grup", "Groups"), groups.size.toString(), Modifier.weight(1f)) { go(AdminTab.GROUPS) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(t("Açık şikâyet", "Open reports"), reports.count { it.handled == null }.toString(), Modifier.weight(1f), alert = reports.any { it.handled == null }) { go(AdminTab.REPORTS) }
                StatTile(t("Banlı / susturulmuş", "Banned / muted"), "$banned / $muted", Modifier.weight(1f))
            }
        }
        item { AdminSection(t("Sürümler", "Versions")) }
        item {
            val versions = people.groupBy { it.version ?: t("bilinmiyor", "unknown") }.mapValues { it.value.size }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key }.thenByDescending { it.value })
            Card {
                versions.forEach { (v, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(v, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(t("$n kişi", "$n people"), color = TextSecondary, fontSize = 14.sp)
                    }
                }
                Text(
                    t("Sürüm, kişi uygulamayı 2.13.8 veya sonrasıyla açınca görünür.", "A version shows once the person opens 2.13.8 or later."),
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { AdminSection(t("Sunucu", "Server")) }
        item {
            Card {
                val s = stats
                if (s == null) {
                    Text(t("Sayılar hazırlanıyor…", "Making the counts…"), color = TextSecondary, fontSize = 14.sp)
                } else {
                    InfoLine(t("Mesaj dosyaları", "Message files"), gb(s.mediaBytes))
                    if (s.diskTotal > 0) InfoLine(t("Boş alan", "Free space"), gb(s.diskFree) + " / " + gb(s.diskTotal))
                    InfoLine(t("Güncellendi", "Updated"), ago(s.at))
                }
                Spacer(Modifier.height(8.dp))
                Pill(t("Yenile", "Refresh"), primary = false) { scope.launch { console.request("stats") } }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, alert: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) {
        Text(value, color = if (alert) Warn else TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DarkSurface).padding(14.dp)) { content() }
}

@Composable
private fun AdminSection(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun InfoLine(label: String, value: String, color: Color = TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 14.sp, textAlign = TextAlign.End)
    }
}

// ------------------------------------------------------------------ people

private enum class PeopleSort { ACTIVE, NEW, NAME }

@Composable
private fun AdminPeople(users: List<AdminUser>, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(PeopleSort.ACTIVE) }
    val now = nowMs()
    val shown = remember(users, query, sort) {
        val q = query.trim().lowercase().removePrefix("@")
        users.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.username.contains(q) }
            .let { list ->
                when (sort) {
                    PeopleSort.ACTIVE -> list.sortedWith(compareByDescending<AdminUser> { it.online }.thenByDescending { it.lastActive })
                    PeopleSort.NEW -> list.sortedByDescending { it.created }
                    PeopleSort.NAME -> list.sortedBy { it.name.lowercase() }
                }
            }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Field(query, { query = it.take(40) }, t("İsim veya kullanıcı adı ara", "Search a name or user name"), capitalize = false)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(t("Son aktif", "Last active"), primary = sort == PeopleSort.ACTIVE) { sort = PeopleSort.ACTIVE }
                    Pill(t("Yeni katılan", "Newest"), primary = sort == PeopleSort.NEW) { sort = PeopleSort.NEW }
                    Pill(t("İsim", "Name"), primary = sort == PeopleSort.NAME) { sort = PeopleSort.NAME }
                }
            }
        }
        items(shown, key = { it.uid }) { u ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(u.uid) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatAvatar(u.name, u.photo, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        val ban = u.ban?.takeIf { it.activeAt(now) }
                        if (ban != null) {
                            Spacer(Modifier.width(6.dp))
                            Tag(if (ban.isBan) t("BANLI", "BANNED") else t("SUSTURULDU", "MUTED"), if (ban.isBan) Danger else Warn)
                        }
                    }
                    Text(
                        "@" + u.username + "  ·  " + (u.version?.let { "v$it" } ?: "v?"),
                        color = TextSecondary, fontSize = 12.sp, maxLines = 1,
                    )
                }
                Text(
                    when {
                        u.online -> t("çevrimiçi", "online")
                        else -> ago(u.lastActive)
                    },
                    color = if (u.online) Good else TextSecondary, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** What can be done to one person, in a list of plain choices; each one that cannot be undone asks first. */
@Composable
private fun AdminUserPage(
    controller: ChatController,
    console: AdminConsole,
    me: ChatUser,
    user: AdminUser,
    groups: List<AdminGroup>,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val now = nowMs()
    val ban = user.ban?.takeIf { it.activeAt(now) }
    val untouchable = user.uid == me.uid || isFounder(user.uid) || user.uid == CLAUDE_BOT_UID
    var banning by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Result<Unit>>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    fun ask(question: String, action: suspend () -> Result<Unit>) {
        confirm = question to action
    }
    fun done(result: Result<Unit>, ok: String) {
        result.onSuccess { controller.reportInfo(ok) }.onFailure { controller.reportError(t("Olmadı: ", "Failed: ") + (it.message ?: "")) }
    }
    LazyColumn(Modifier.fillMaxSize().background(BgRoot), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar(user.name, onBack) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ChatAvatar(user.name, user.photo, 96.dp)
                Spacer(Modifier.height(10.dp))
                Text(user.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("@" + user.username, color = TextSecondary, fontSize = 14.sp)
                if (ban != null) {
                    Spacer(Modifier.height(8.dp))
                    Tag((if (ban.isBan) t("BANLI ", "BANNED ") else t("SUSTURULDU ", "MUTED ")) + "· " + untilLabel(ban), if (ban.isBan) Danger else Warn)
                    if (ban.reason.isNotBlank()) Text(t("Sebep: ", "Reason: ") + ban.reason, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Card {
                    InfoLine(t("Durum", "Status"), if (user.online) t("çevrimiçi", "online") else if (user.hidden) t("son görülmeyi gizliyor", "hides last seen") else ago(user.lastSeen), if (user.online) Good else TextPrimary)
                    InfoLine(t("Son açılış", "Last opened"), ago(user.lastOpen))
                    InfoLine(t("Son giriş", "Last sign-in"), ago(user.lastSignIn))
                    InfoLine(t("Sürüm", "Version"), user.version ?: t("bilinmiyor", "unknown"))
                    InfoLine(t("Katıldı", "Joined"), dateTime(user.created))
                    InfoLine(t("Sohbet sayısı", "Conversations"), user.chats.toString())
                    InfoLine(t("Grupları", "Groups"), groups.size.toString())
                }
                if (groups.isNotEmpty()) {
                    Text(groups.joinToString(", ") { it.name }, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                }
            }
        }
        if (untouchable) {
            item {
                Text(
                    t("Bu hesaba yönetim işlemi yapılamaz.", "Admin actions are not available for this account."),
                    color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
            return@LazyColumn
        }
        item { SectionTitle(t("Erişim", "Access")) }
        item {
            if (ban != null) {
                ActionRow(t("Banı / susturmayı kaldır", "Lift the ban / mute"), null, Good) {
                    scope.launch { done(console.unban(user.uid), t("Kaldırıldı", "Lifted")) }
                }
            }
            ActionRow(t("Banla", "Ban"), t("Mesaj, arama, durum, dosya; hiçbiri olmaz. Sohbetler ekranı kapanır.", "No messages, calls, statuses or files. The messages screen closes."), Danger) { banning = "ban" }
            ActionRow(t("Sustur", "Mute"), t("Uygulamayı kullanır, okur ama kimseye yazamaz.", "They can use the app and read, but not write to anyone."), Warn) { banning = "mute" }
        }
        item { SectionTitle(t("Profil", "Profile")) }
        item {
            ActionRow(t("Profil fotoğrafını kaldır", "Remove the profile photo"), null) {
                ask(t("${user.name} adlı kişinin profil fotoğrafı kaldırılsın mı?", "Remove ${user.name}'s profile photo?")) { console.request("reset", user.uid, "photo") }
            }
            ActionRow(t("Adını sıfırla", "Reset the name"), t("\"Nuvelo kullanıcısı\" olur; kendisi yeniden değiştirebilir.", "Becomes \"Nuvelo user\"; they can change it again.")) {
                ask(t("${user.name} adı sıfırlansın mı?", "Reset ${user.name}'s name?")) { console.request("reset", user.uid, "name") }
            }
            ActionRow(t("Hakkında yazısını kaldır", "Remove the about line"), null) {
                ask(t("Hakkında yazısı kaldırılsın mı?", "Remove the about line?")) { console.request("reset", user.uid, "about") }
            }
            ActionRow(t("Durumlarını sil", "Delete their statuses"), null) {
                ask(t("${user.name} adlı kişinin bütün durumları silinsin mi?", "Delete all of ${user.name}'s statuses?")) { console.request("reset", user.uid, "status") }
            }
            ActionRow(t("Kullanıcı adını değiştir", "Change the user name"), "@" + user.username) { renaming = true }
        }
        item { AdminUserExtras(controller, console, user, onDeleted = onBack) }
    }

    banning?.let { kind ->
        BanDialog(kind, user.name, onDismiss = { banning = null }) { until, reason ->
            banning = null
            scope.launch {
                done(console.ban(user.uid, kind, until, reason), if (kind == "ban") t("Banlandı", "Banned") else t("Susturuldu", "Muted"))
            }
        }
    }
    confirm?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = DarkSurfaceVariant,
            text = { Text(question, color = TextPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { done(action(), t("İstendi; birkaç saniyede olur", "Asked; it takes a few seconds")) }
                }) { Text(t("Evet", "Yes"), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(user.username) }
        val valid = Regex("^[a-z0-9._]{3,20}$").matches(name)
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(t("Yeni kullanıcı adı", "New user name"), color = TextPrimary) },
            text = {
                Column {
                    Field(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }.take(20) }, "kullanici.adi", prefix = "@", capitalize = false)
                    Text(t("3-20 harf: a-z, 0-9, nokta, alt çizgi.", "3-20 of a-z, 0-9, dot, underscore."), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = valid && name != user.username, onClick = {
                    renaming = false
                    scope.launch { done(console.request("rename", user.uid, name), t("İstendi; birkaç saniyede olur", "Asked; it takes a few seconds")) }
                }) { Text(t("Değiştir", "Change"), color = if (valid) Ink else TextMuted) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(t("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
}

@Composable
private fun ActionRow(title: String, note: String?, color: Color = TextPrimary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(title, color = color, fontSize = 15.sp)
        if (note != null) Text(note, color = TextSecondary, fontSize = 12.sp)
    }
}

/** How long and why: 1 day, 7 days, 30 days or for good, with a reason the person will see. */
@Composable
private fun BanDialog(kind: String, name: String, onDismiss: () -> Unit, onConfirm: (until: Long, reason: String) -> Unit) {
    val choices = listOf(
        t("1 saat", "1 hour") to 3600_000L,
        t("1 gün", "1 day") to 24 * 3600_000L,
        t("7 gün", "7 days") to 7 * 24 * 3600_000L,
        t("30 gün", "30 days") to 30 * 24 * 3600_000L,
        t("Süresiz", "For good") to -1L,
    )
    var pick by remember { mutableStateOf(1) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(if (kind == "ban") t("$name banlansın", "Ban $name") else t("$name susturulsun", "Mute $name"), color = TextPrimary) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEachIndexed { i, (label, _) -> Pill(label, primary = pick == i) { pick = i } }
                }
                Spacer(Modifier.height(12.dp))
                Field(reason, { reason = it.take(200) }, t("Sebep (kişi görür)", "Reason (they see it)"))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val span = choices[pick].second
                onConfirm(if (span < 0) AdminBan.FOREVER else nowMs() + span, reason)
            }) { Text(if (kind == "ban") t("Banla", "Ban") else t("Sustur", "Mute"), color = Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Vazgeç", "Cancel"), color = TextPrimary) } },
    )
}

// ------------------------------------------------------------------ reports

private fun reasonLabel(reason: String): String = when (reason) {
    "spam" -> "Spam"
    "abuse", "harassment" -> t("Taciz / hakaret", "Abuse")
    "inappropriate" -> t("Uygunsuz içerik", "Inappropriate")
    "fake" -> t("Sahte hesap", "Fake account")
    else -> reason
}

@Composable
private fun AdminReports(
    controller: ChatController,
    console: AdminConsole,
    reports: List<AdminReport>,
    names: Map<String, String>,
    onUser: (String) -> Unit,
    onGroup: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showDone by remember { mutableStateOf(false) }
    val shown = reports.filter { showDone || it.handled == null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t("Açık", "Open"), primary = !showDone) { showDone = false }
                Pill(t("Hepsi", "All"), primary = showDone) { showDone = true }
            }
        }
        if (shown.isEmpty()) item { Text(t("Şikâyet yok.", "No reports."), color = TextSecondary, modifier = Modifier.padding(top = 24.dp)) }
        items(shown, key = { it.id }) { r ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(reasonLabel(r.reason), if (r.handled == null) Warn else TextMuted)
                    Spacer(Modifier.width(8.dp))
                    Text(ago(r.at), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (r.handled != null) Text(t("çözüldü", "handled"), color = Good, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(t("Şikâyet eden: ", "By: ") + (names[r.by] ?: r.by), color = TextSecondary, fontSize = 13.sp)
                Text(t("Şikâyet edilen: ", "About: ") + (names[r.target] ?: r.target), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (r.recent.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(t("Şikâyetle gönderilen mesajlar:", "Messages sent with the report:"), color = TextMuted, fontSize = 12.sp)
                    r.recent.takeLast(10).forEach { (from, text) ->
                        Text((names[from] ?: from) + ": " + text, color = TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.target.startsWith("grp")) Pill(t("Grubu aç", "Open group"), primary = true) { onGroup(r.target) }
                    else Pill(t("Kişiyi aç", "Open person"), primary = true) { onUser(r.target) }
                    if (r.handled == null) {
                        Pill(t("Çözüldü", "Handled"), primary = false) { scope.launch { console.markReport(r.id, "ok").onFailure { controller.reportError(it.message ?: "") } } }
                    } else {
                        Pill(t("Yeniden aç", "Reopen"), primary = false) { scope.launch { console.markReport(r.id, null) } }
                    }
                    Pill(t("Sil", "Delete"), primary = false) { scope.launch { console.deleteReport(r.id).onFailure { controller.reportError(it.message ?: "") } } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ groups

@Composable
private fun AdminGroups(groups: List<AdminGroup>, names: Map<String, String>, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (groups.isEmpty()) item { Text(t("Grup yok.", "No groups."), color = TextSecondary, modifier = Modifier.padding(16.dp)) }
        items(groups, key = { it.id }) { g ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(g.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(g.name, g.photo, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        t("${g.members.size} üye", "${g.members.size} members") + "  ·  " + t("kuran: ", "by ") + (names[g.createdBy] ?: "?") + if (g.secret) t("  ·  gizli", "  ·  secret") else "",
                        color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(dateTime(g.createdAt).substringBefore(' '), color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AdminGroupPage(
    controller: ChatController,
    console: AdminConsole,
    group: AdminGroup,
    names: Map<String, String>,
    onUser: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().background(BgRoot), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar(group.name, onBack) }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Card {
                    InfoLine(t("Üye", "Members"), group.members.size.toString())
                    InfoLine(t("Kuran", "Made by"), names[group.createdBy] ?: "?")
                    InfoLine(t("Kuruldu", "Made"), dateTime(group.createdAt))
                    if (group.secret) InfoLine(t("Tür", "Kind"), t("gizli grup", "secret group"))
                }
            }
        }
        item { SectionTitle(t("Üyeler", "Members")) }
        items(group.members, key = { it.first }) { (uid, name) ->
            Row(Modifier.fillMaxWidth().clickable { onUser(uid) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(names[uid] ?: name, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (uid in group.admins) Text(t("yönetici", "admin"), color = TextSecondary, fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            ActionRow(t("Grubu kapat", "Close the group"), t("Grup, içindeki mesajlar ve dosyalar herkesten silinir. Geri alınamaz.", "The group, its messages and files go for everyone. This cannot be undone."), Danger) { closing = true }
        }
    }
    if (closing) {
        AlertDialog(
            onDismissRequest = { closing = false },
            containerColor = DarkSurfaceVariant,
            text = { Text(t("\"${group.name}\" kapatılsın mı? Geri alınamaz.", "Close \"${group.name}\"? This cannot be undone."), color = TextPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    closing = false
                    scope.launch {
                        console.request("closeGroup", group.id)
                            .onSuccess { controller.reportInfo(t("Grup kapatılıyor", "Closing the group")); onBack() }
                            .onFailure { controller.reportError(it.message ?: "") }
                    }
                }) { Text(t("Kapat", "Close"), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { closing = false }) { Text(t("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
}

// ------------------------------------------------------------------ announcements

@Composable
private fun AdminAnnounce(controller: ChatController, console: AdminConsole) {
    val past by remember(console) { console.announcements() }.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                t("Herkese bildirim gider; uygulamayı açınca bir kez de pencere olarak çıkar.", "Everyone gets a notification; it also shows once as a window when they open the app."),
                color = TextSecondary, fontSize = 13.sp,
            )
        }
        item { Field(text, { text = it.take(1000) }, t("Duyuru yazısı", "Announcement text")) }
        item { WhiteButton(t("Herkese gönder", "Send to everyone"), enabled = text.isNotBlank()) { asking = true } }
        if (past.isNotEmpty()) item { AdminSection(t("Önceki duyurular", "Earlier announcements")) }
        items(past, key = { it.id }) { a ->
            Card {
                Text(a.text, color = TextPrimary, fontSize = 14.sp)
                Text(dateTime(a.at), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = DarkSurfaceVariant,
            text = { Text(t("Bu duyuru herkese bildirim olarak gitsin mi?", "Send this announcement to everyone?"), color = TextPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    val body = text.trim()
                    scope.launch {
                        console.request("announce", "", body)
                            .onSuccess { text = ""; controller.reportInfo(t("Gönderiliyor", "Sending")) }
                            .onFailure { controller.reportError(it.message ?: "") }
                    }
                }) { Text(t("Gönder", "Send"), color = Ink) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(t("Vazgeç", "Cancel"), color = TextPrimary) } },
        )
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun AdminSettings(controller: ChatController, console: AdminConsole) {
    val least by console.minVersion.collectAsState()
    val barred by remember(console) { console.bannedNames() }.collectAsState(initial = emptyList())
    var version by remember(least) { mutableStateOf(least.orEmpty()) }
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val versionOk = version.isEmpty() || Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){0,3}$").matches(version)
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MaintenanceAndSpam(controller, console) }
        item { AdminSection(t("Zorunlu güncelleme", "Required update")) }
        item {
            Text(
                t(
                    "Bu sürümden eski olanlar sohbetleri açınca \"güncelle\" ekranı görür, güncellemeden devam edemez. Boş bırakırsan herkes girebilir. Şu anki: ${com.qmusic.ui.AppVersion.NAME}",
                    "Anyone older than this sees an \"update\" page in the messages and cannot go on without updating. Empty: everyone may. This one: ${com.qmusic.ui.AppVersion.NAME}",
                ),
                color = TextSecondary, fontSize = 13.sp,
            )
        }
        item { Field(version, { version = it.filter { c -> c.isDigit() || c == '.' }.take(15) }, t("örnek: 2.13.8", "e.g. 2.13.8"), capitalize = false) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t("Kaydet", "Save"), primary = true) {
                    if (!versionOk) {
                        controller.reportError(t("Sürüm 2.13.8 gibi yazılmalı", "Write the version like 2.13.8"))
                    } else if (version.isNotEmpty() && versionOlder(com.qmusic.ui.AppVersion.NAME, version)) {
                        // The admin's own app would lock itself out.
                        controller.reportError(t("Bu senin sürümünden yeni; önce kendin güncelle", "That is newer than yours; update first"))
                    } else {
                        scope.launch {
                            console.setMinVersion(version.ifEmpty { null })
                                .onSuccess { controller.reportInfo(t("Kaydedildi", "Saved")) }
                                .onFailure { controller.reportError(it.message ?: "") }
                        }
                    }
                }
                if (least != null) Pill(t("Kaldır", "Remove"), primary = false) { scope.launch { console.setMinVersion(null) } }
            }
        }
        item { AdminSection(t("Yasaklı kullanıcı adları", "Barred user names")) }
        item {
            Text(t("Bu adları kimse yeni alamaz (şu an kullanan varsa ona dokunulmaz).", "No one can newly take these (whoever has one now keeps it)."), color = TextSecondary, fontSize = 13.sp)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }.take(20) }, "ad", prefix = "@", modifier = Modifier.weight(1f), capitalize = false)
                Spacer(Modifier.width(8.dp))
                Pill(t("Ekle", "Add"), primary = true) {
                    val n = name
                    scope.launch {
                        console.banName(n, true)
                            .onSuccess { name = "" }
                            .onFailure { controller.reportError(t("Ad 3-20 harf olmalı", "3-20 characters")) }
                    }
                }
            }
        }
        items(barred, key = { it }) { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("@$n", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Pill(t("Kaldır", "Remove"), primary = false) { scope.launch { console.banName(n, false) } }
            }
        }
    }
}

// ------------------------------------------------------------------ log

private fun actionLabel(kind: String, value: String): String = when (kind) {
    "ban" -> t("Banladı", "Banned")
    "mute" -> t("Susturdu", "Muted")
    "unban" -> t("Banı kaldırdı", "Lifted a ban")
    "closeGroup" -> t("Grubu kapattı", "Closed a group")
    "rename" -> t("Kullanıcı adını değiştirdi → @$value", "Renamed → @$value")
    "reset" -> when (value) {
        "photo" -> t("Fotoğrafı kaldırdı", "Removed a photo")
        "name" -> t("Adı sıfırladı", "Reset a name")
        "about" -> t("Hakkındayı kaldırdı", "Removed an about line")
        else -> t("Durumları sildi", "Deleted statuses")
    }
    "announce" -> t("Duyuru: ", "Announced: ") + value
    "stats" -> t("Sayıları yeniledi", "Refreshed the counts")
    "maintenance" -> if (value == "on") t("Bakım modunu açtı", "Turned maintenance on") else t("Bakım modunu kapattı", "Turned maintenance off")
    "spam" -> if (value == "off") t("Spam korumasını kapattı", "Turned the spam guard off") else t("Spam sınırı: $value/dk", "Spam limit: $value/min")
    "autoMute" -> t("Spam koruması susturdu ($value mesaj/dk)", "Spam guard muted ($value messages/min)")
    "badge" -> if (value == "on") t("Geliştirici rozeti verdi", "Gave the developer badge") else t("Rozeti aldı", "Took the badge")
    "claude" -> if (value == "on") t("Claude erişimi verdi", "Gave Claude access") else t("Claude erişimini aldı", "Took Claude access")
    "message" -> t("Nuvelo adına yazdı: ", "Wrote as Nuvelo: ") + value
    "deleteMedia" -> t("Sohbet dosyalarını sildi", "Deleted a chat's files")
    "deleteAccount" -> t("Hesap sildi", "Deleted an account")
    "minVersion" -> if (value.isEmpty()) t("Zorunlu güncellemeyi kaldırdı", "Removed the required update") else t("En az sürüm: $value", "Least version: $value")
    "banName" -> t("Kullanıcı adı yasağı", "User name bar")
    "report" -> t("Şikâyeti çözdü", "Handled a report")
    else -> kind
}

@Composable
private fun AdminLog(console: AdminConsole, names: Map<String, String>) {
    val all by remember(console) { console.actions() }.collectAsState(initial = emptyList())
    // The counts are refreshed each time the panel opens: not worth a line each.
    val shown = all.filter { it.kind != "stats" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shown.isEmpty()) item { Text(t("Henüz bir işlem yok.", "Nothing done yet."), color = TextSecondary) }
        items(shown, key = { it.id }) { a ->
            Column(Modifier.fillMaxWidth()) {
                Text(actionLabel(a.kind, a.value), color = TextPrimary, fontSize = 14.sp)
                val who = names[a.target] ?: a.target.takeIf { it.isNotEmpty() && a.kind != "report" }
                Text(
                    listOfNotNull(who, names[a.by]?.let { t("yapan: $it", "by $it") }, dateTime(a.at)).joinToString("  ·  "),
                    color = TextSecondary, fontSize = 12.sp,
                )
                if (a.error != null) Text(t("Hata: ", "Error: ") + a.error, color = Danger, fontSize = 12.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ what everyone sees

/** In place of the messages while the account is banned: why, and until when. */
@Composable
internal fun BannedPage(ban: AdminBan, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(BgRoot).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t("Hesabın askıya alındı", "Your account is suspended"), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            if (ban.forever) t("Mesajlaşma bu hesap için kapatıldı.", "Messaging is closed for this account.")
            else t("Mesajlaşma ${dateTime(ban.until)} tarihine kadar kapalı.", "Messaging is closed until ${dateTime(ban.until)}."),
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        if (ban.reason.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(t("Sebep: ", "Reason: ") + ban.reason, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Text(t("Müzik ve videoları kullanmaya devam edebilirsin.", "You can still use music and videos."), color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        WhiteButton(t("Müziğe dön", "Back to music")) { onExit() }
    }
}

/** In place of the messages while this version is older than the least one allowed. */
@Composable
internal fun UpdateRequiredPage(least: String) {
    Column(
        Modifier.fillMaxSize().background(BgRoot).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t("Güncelleme gerekli", "Update required"), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            t(
                "Bu sürüm (${com.qmusic.ui.AppVersion.NAME}) artık desteklenmiyor. Devam etmek için en az $least sürümüne güncelle.",
                "This version (${com.qmusic.ui.AppVersion.NAME}) is no longer supported. Update to $least or later to go on.",
            ),
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        WhiteButton(t("Güncelle", "Update")) { ForcedUpdate.requests.tryEmit(Unit) }
    }
}

/** Over the messages: the bans, the least version and the newest announcement, for everyone. */
@Composable
internal fun AdminGate(console: AdminConsole?, onExit: () -> Unit, content: @Composable () -> Unit) {
    if (console == null) {
        content()
        return
    }
    val ban by console.myBan.collectAsState()
    val least by console.minVersion.collectAsState()
    val news by console.announcement.collectAsState()
    val maintenance by console.maintenance.collectAsState()
    val isAdmin by console.isAdmin.collectAsState()
    // A ban that ends while the app is open: the page goes by itself at that moment.
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(ban) {
        val b = ban ?: return@LaunchedEffect
        while (b.activeAt(now)) {
            delay((b.until - now).coerceIn(1_000L, 60_000L))
            now = nowMs()
        }
    }
    val active = ban?.takeIf { it.activeAt(now) }
    val oldVersion = least?.let { versionOlder(com.qmusic.ui.AppVersion.NAME, it) } == true
    when {
        oldVersion -> UpdateRequiredPage(least.orEmpty())
        maintenance.on && !isAdmin -> MaintenancePage(maintenance.text, onExit)
        active != null && active.isBan -> BannedPage(active, onExit)
        else -> content()
    }
    // Muted: said once (per mute), and every send fails while it lasts.
    if (active != null && !active.isBan && !oldVersion) {
        var told by remember(active.at, active.until) { mutableStateOf(false) }
        if (!told) {
            AlertDialog(
                onDismissRequest = { told = true },
                containerColor = DarkSurfaceVariant,
                title = { Text(t("Susturuldun", "You are muted"), color = TextPrimary) },
                text = {
                    Text(
                        t("${untilLabel(active)} kimseye mesaj gönderemezsin; mesajlarını okuyabilirsin.", "You cannot send messages ${untilLabel(active)}; you can still read.") +
                            if (active.reason.isNotBlank()) t("\nSebep: ", "\nReason: ") + active.reason else "",
                        color = TextSecondary,
                    )
                },
                confirmButton = { TextButton(onClick = { told = true }) { Text(t("Tamam", "OK"), color = Ink) } },
            )
        }
    }
    val a = news
    if (a != null && !oldVersion && active?.isBan != true) {
        AlertDialog(
            onDismissRequest = { console.dismissAnnouncement(a.id) },
            containerColor = DarkSurfaceVariant,
            title = { Text("Nuvelo", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(a.text, color = TextPrimary) },
            confirmButton = { TextButton(onClick = { console.dismissAnnouncement(a.id) }) { Text(t("Tamam", "OK"), color = Ink) } },
        )
    }
}
