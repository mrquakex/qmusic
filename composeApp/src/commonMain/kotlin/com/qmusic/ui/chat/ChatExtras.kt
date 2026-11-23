package com.qmusic.ui.chat

import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.datetime.Clock

/** Read: two green ticks (like the apps people know); delivered: two grey; sent: one grey. */
internal val TickRead = Color(0xFF1DAA61)

// ------------------------------------------------------------------ emoji

private val EmojiCategories: List<Pair<String, List<String>>> = listOf(
    "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾".split(" "),
    "👍" to "👍 👎 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 🦾 ✍️ 💅 🤳 👀 👁️ 👅 👄 🧠 🫶 🫡 🫠 🫢 🫣 🫤 🥹".split(" "),
    "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💋 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟 💤".split(" "),
    "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦖 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐘 🦒 🐪 🐕 🐈 🌵 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌝 🌚 🌙 ⭐ ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌈 🌊".split(" "),
    "🍔" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🍆 🥔 🥕 🌽 🌶️ 🥒 🥦 🧄 🧅 🍄 🥜 🍞 🥐 🥖 🧀 🥚 🍳 🥞 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍰 🎂 🧁 🍮 🍭 🍬 🍫 🍿 🍩 🍪 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🍸 🍹".split(" "),
    "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🎽 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 🚴 🏆 🥇 🥈 🥉 🎖️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🎯 🎳 🎮 🕹️ 🧩".split(" "),
    "🚗" to "🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚜 🏍️ 🛵 🚲 🛴 🚨 🚆 🚇 ✈️ 🛫 🚀 🛸 🚁 ⛵ 🚤 🛳️ ⚓ 🗺️ 🏔️ 🏕️ 🏖️ 🏝️ 🏠 🏡 🏢 🏥 🏦 🏨 🏫 🕌 ⛪ 🗽 🗼 🏰 🎡 🎢 🌃 🌆 🌉".split(" "),
    "💡" to "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💰 💳 💎 ⚖️ 🔧 🔨 ⚙️ 🔫 💣 🔪 🛡️ 🔮 💊 💉 🧬 🔬 🔭 🧹 🧺 🧻 🚽 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🎁 🎈 🎉 🎊 ✉️ 📦 📝 📚 📖 🔖 📌 📎 ✂️ 🔒 🔓".split(" "),
    "🔣" to "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚫 ⛔ 🔞 📵 ♻️ 🔰 🆗 🆕 🆒 🆓 🆙 🆘 ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ 🔀 🔁 🔂 🎵 🎶 💲 ©️ ®️ ™️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🇹🇷 🏳️ 🏴 🏁 🚩 🏳️‍🌈".split(" "),
)

/** The emoji keyboard: categories along the top, the emoji in a grid; a tap types it. */
@Composable
internal fun EmojiPanel(onEmoji: (String) -> Unit, modifier: Modifier = Modifier) {
    var category by remember { mutableStateOf(0) }
    Column(modifier.fillMaxWidth().heightIn(min = 240.dp).background(DarkSurface)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
            EmojiCategories.forEachIndexed { i, (icon, _) ->
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(if (i == category) DarkSurfaceVariant else Color.Transparent).clickable { category = i },
                    contentAlignment = Alignment.Center,
                ) { Text(icon, fontSize = 22.sp) }
            }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(6.dp)) {
            items(EmojiCategories[category].second.filter { it.isNotBlank() }) { e ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { onEmoji(e) }, contentAlignment = Alignment.Center) {
                    Text(e, fontSize = 26.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ link cards

/** The card of the first link in a message: picture, site, title and a line; a tap opens it. */
@Composable
internal fun LinkPreviewCard(url: String, mine: Boolean, load: suspend (String) -> LinkPreview?) {
    var card by remember(url) { mutableStateOf<LinkPreview?>(null) }
    LaunchedEffect(url) { card = load(url) }
    val c = card ?: return
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(
        Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).width(260.dp).clip(RoundedCornerShape(12.dp))
            .background(if (mine) InkInverse.copy(alpha = 0.06f) else Ink.copy(alpha = 0.06f))
            .clickable { runCatching { uriHandler.openUri(c.url) } },
    ) {
        if (c.image != null) {
            AsyncImage(model = c.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1.91f))
        }
        Column(Modifier.padding(10.dp)) {
            val soft = if (mine) InkInverse.copy(alpha = 0.5f) else TextSecondary
            if (c.site.isNotBlank()) Text(c.site, color = soft, fontSize = 11.sp, maxLines = 1)
            Text(c.title, color = if (mine) InkInverse else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (c.description.isNotBlank()) Text(c.description, color = soft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ starred messages

@Composable
internal fun StarredPage(controller: ChatController, s: ChatStrings) {
    val items by controller.starred.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(s.starredTitle, { controller.pop() })
        if (items.isEmpty()) {
            Centered { Text(s.noStarred, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items, key = { it.chatId + it.messageId }) { m ->
                    Column(Modifier.fillMaxWidth().clickable { controller.openStarredChat(m) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.fromName.ifBlank { "?" }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("  ›  " + m.chatName, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(listTime(m.at, s), color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(QMusicIcons.Star, contentDescription = null, tint = TextSecondary, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                            Text(m.text, color = TextSecondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ profile and privacy

@Composable
internal fun SettingsPage(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val privacy by controller.privacy.collectAsState()
    var name by remember(me.uid) { mutableStateOf(me.name) }
    var about by remember(me.uid) { mutableStateOf(me.about) }
    val pickPhoto = rememberImagePicker { photos -> photos.firstOrNull()?.let { controller.setProfilePhoto(it) } }
    val changed = name.trim() != me.name || about.trim() != me.about
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            TopBar(s.settings, { controller.pop() }, actions = {
                IconButton(onClick = { controller.push(ChatRoute.MyQr) }) { QrGlyph(Ink) }
            })
        }
        item {
            // The big picture with a camera on it, like a profile card.
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(CircleShape).clickable { pickPhoto() }) {
                    ChatAvatar(me, 132.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(38.dp).clip(CircleShape).background(Ink),
                        contentAlignment = Alignment.Center,
                    ) { Icon(QMusicIcons.Camera, contentDescription = s.changePhoto, tint = InkInverse, modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(12.dp))
                Text(me.name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                UserBadge(me.uid, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                Text("@" + me.username, color = TextSecondary, fontSize = 14.sp)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(s.nameLabel, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                Field(name, { name = it.take(40) }, s.nameLabel)
                Spacer(Modifier.height(14.dp))
                Text(s.about, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                Field(about, { about = it.take(140) }, s.aboutHint, capitalize = false)
                if (changed) {
                    Spacer(Modifier.height(12.dp))
                    WhiteButton(s.save, enabled = name.isNotBlank()) { controller.updateProfile(name, about) }
                }
            }
        }
        // A profile song is picked from the music, which the web does not have.
        if (!ChatPlatform.web) item { MyProfileSong(controller, s, me) }
        item { SectionTitle(s.accountSection) }
        item {
            MenuRow(s.myQrTitle, s.myQrNote) { controller.push(ChatRoute.MyQr) }
            MenuRow(s.starredShort, null) { controller.openStarred() }
            MenuRow(s.lockedChats, s.lockedChatsNote) { controller.push(ChatRoute.LockedFolder) }
            E2eWords(ChatMusic.english).let { w -> MenuRow(w.e2eTitle, w.encryptNewNote) { controller.push(ChatRoute.Encryption) } }
            MenuRow(if (ChatMusic.english) "Live locations" else "Canlı konumlar", if (ChatMusic.english) "Everyone sharing with you, on one map" else "Seninle konum paylaşan herkes tek haritada") { controller.push(ChatRoute.LivePeople) }
            MenuRow(s.broadcastsTitle, null) { controller.push(ChatRoute.Broadcasts) }
            MenuRow(s.statusPrivacyTitle, s.statusPrivacyNote) { controller.push(ChatRoute.StatusPrivacyPage) }
            // Only for admins (the server refuses anyone else whatever the app shows).
            val isAdmin by (controller.admin?.isAdmin ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
            if (isAdmin) {
                MenuRow(
                    if (ChatMusic.english) "Admin panel" else "Yönetim paneli",
                    if (ChatMusic.english) "People, bans, reports, groups, announcements" else "Kişiler, ban, şikâyetler, gruplar, duyurular",
                ) { controller.push(ChatRoute.Admin) }
            }
        }
        if (!ChatPlatform.web) {
            item { SectionTitle(s.soundsTitle) }
            item { SoundSettings(s) }
            item { SectionTitle(s.notifTitle) }
            item {
                val banner by NotificationPrefs.inAppBanner.collectAsState()
                val hide by NotificationPrefs.hideOnLock.collectAsState()
                Column {
                    SettingSwitch(s.notifBanner, s.notifBannerNote, banner) { NotificationPrefs.setInAppBanner(it) }
                    SettingSwitch(s.notifHideLock, s.notifHideLockNote, hide) { NotificationPrefs.setHideOnLock(it) }
                }
            }
        }
        item { SectionTitle(s.mediaTitle) }
        item {
            val mode by controller.autoDownload.collectAsState()
            var choosing by remember { mutableStateOf(false) }
            val label = when (mode) {
                "wifi" -> s.mediaWifi
                "never" -> s.mediaNever
                else -> s.mediaAlways
            }
            Row(Modifier.fillMaxWidth().clickable { choosing = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.mediaAuto, color = TextPrimary, fontSize = 15.sp)
                    Text(s.mediaAutoNote, color = TextSecondary, fontSize = 12.sp)
                }
                Text(label, color = TextSecondary, fontSize = 14.sp)
            }
            if (choosing) {
                ChoiceDialog(
                    s.mediaAuto,
                    listOf(
                        s.mediaAlways to { controller.setAutoDownload("always") },
                        s.mediaWifi to { controller.setAutoDownload("wifi") },
                        s.mediaNever to { controller.setAutoDownload("never") },
                    ),
                    s,
                ) { choosing = false }
            }
            // How much room the messages' photos, voice and videos take, and emptying it.
            var bytes by remember { mutableStateOf<Long?>(null) }
            var clearing by remember { mutableStateOf(false) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(clearing) { if (!clearing) bytes = controller.mediaCacheBytes() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.mediaStorage, color = TextPrimary, fontSize = 15.sp)
                    Text(bytes?.let { sizeLabel(it) } ?: "…", color = TextSecondary, fontSize = 12.sp)
                }
                Pill(s.mediaClear, primary = false) {
                    if (!clearing) {
                        clearing = true
                        scope.launch {
                            controller.clearMediaCache()
                            clearing = false
                        }
                    }
                }
            }
            Text(s.mediaClearNote, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item { SectionTitle(s.wallpaper) }
        item {
            val current by controller.wallpaper.collectAsState()
            Text(s.wallpaperNote, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChatWallpapers.forEachIndexed { i, colour ->
                    Box(
                        Modifier.size(width = 56.dp, height = 84.dp).clip(RoundedCornerShape(12.dp)).background(colour ?: BgRootColor)
                            .border(2.dp, if (i == current) Ink else BorderLine, RoundedCornerShape(12.dp))
                            .clickable { controller.setWallpaper(i) },
                        contentAlignment = Alignment.Center,
                    ) { if (colour == null) Text(s.wallpaperNone, color = TextSecondary, fontSize = 11.sp) }
                }
            }
        }
        item { SectionTitle(s.privacyTitle) }
        item {
            SettingSwitch(s.hideLastSeen, s.hideLastSeenNote, privacy.hideLastSeen) { controller.setPrivacy(privacy.copy(hideLastSeen = it)) }
            SettingSwitch(s.hideReadReceipts, s.hideReadReceiptsNote, privacy.hideReadReceipts) { controller.setPrivacy(privacy.copy(hideReadReceipts = it)) }
            val shareListening by ChatMusic.shareListening.collectAsState()
            val mw = ChatMusic.words()
            val home by AppHome.messagesFirst.collectAsState()
            if (!ChatPlatform.web) {
                SettingSwitch(mw.showListening, mw.showListeningNote, shareListening) { ChatMusic.setShareListening(it) }
                SettingSwitch(mw.messagesFirst, mw.messagesFirstNote, home) { AppHome.setMessagesFirst(it) }
            }
            val all = listOf(Visibility.EVERYONE, Visibility.FRIENDS, Visibility.NOBODY)
            VisibilityRow(s.privacyPhoto, null, privacy.photo, all, s) { controller.setPrivacy(privacy.copy(photo = it)) }
            VisibilityRow(s.privacyAbout, null, privacy.about, all, s) { controller.setPrivacy(privacy.copy(about = it)) }
            VisibilityRow(s.privacyGroups, s.privacyGroupsNote, privacy.groupAdd, listOf(Visibility.EVERYONE, Visibility.FRIENDS), s) { controller.setPrivacy(privacy.copy(groupAdd = it)) }
            val lock = rememberAppLock()
            if (lock != null) {
                val lockOn by lock.enabled.collectAsState()
                SettingSwitch(s.appLock, if (lock.available) s.appLockNote else s.appLockUnavailable, lockOn) { on ->
                    // Turning it on or off asks for the lock itself first.
                    if (lock.available) {
                        lock.authenticate(s.appLock, s.lockBody) { ok ->
                            if (ok) {
                                lock.setEnabled(on)
                                ChatLock.unlocked.value = true
                            }
                        }
                    } else if (!on) {
                        lock.setEnabled(false)
                    }
                }
            }
            Text(s.e2eNote, color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun MenuRow(title: String, note: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().hoverLight().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            if (note != null) Text(note, color = TextSecondary, fontSize = 12.sp)
        }
        Icon(QMusicIcons.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }
}

/** A small QR sign: three corner squares and a dot. */
@Composable
internal fun QrGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val u = size.minDimension / 7f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = u * 0.8f)
        listOf(0f to 0f, 4f to 0f, 0f to 4f).forEach { (x, y) ->
            drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(x * u + u * 0.4f, y * u + u * 0.4f), size = androidx.compose.ui.geometry.Size(u * 2.2f, u * 2.2f), style = stroke)
        }
        drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(4.6f * u, 4.6f * u), size = androidx.compose.ui.geometry.Size(u * 1.8f, u * 1.8f))
    }
}

/** The wallpapers behind a chat's messages: none, then quiet dark tones that keep white and black bubbles readable. */
internal val ChatWallpapers: List<Color?> get() = if (com.qmusic.ui.theme.lightTheme) {
    listOf(null, Color(0xFFE6EEF6), Color(0xFFE7F2EA), Color(0xFFF5E8EC), Color(0xFFEFE9F6), Color(0xFFEEEEEE), Color(0xFFF6F0E4), Color(0xFFE4F2F2))
} else {
    listOf(null, Color(0xFF0E1621), Color(0xFF0F1A14), Color(0xFF1A0F12), Color(0xFF15101C), Color(0xFF161616), Color(0xFF1C1710), Color(0xFF0D1A1A))
}
private val BgRootColor: Color get() = com.qmusic.ui.theme.BgRoot
private val BorderLine: Color get() = com.qmusic.ui.theme.BorderHover

@Composable
internal fun SettingSwitch(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().hoverLight().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(note, color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = InkInverse, checkedTrackColor = Ink, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = DarkSurfaceVariant),
        )
    }
}

// ------------------------------------------------------------------ dialogs

/** A list of choices in a dialog; the one picked runs. */
@Composable
internal fun ChoiceDialog(title: String, choices: List<Pair<String, () -> Unit>>, s: ChatStrings, note: String? = null, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column {
                if (note != null) Text(note, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                choices.forEach { (label, run) ->
                    Text(label, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onDismiss(); run() }.padding(vertical = 12.dp, horizontal = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } },
    )
}

/** Picking where to forward: the conversations, ticked; Send forwards to all of them. */
@Composable
internal fun ForwardDialog(controller: ChatController, s: ChatStrings, onSend: (List<ChatUser>) -> Unit, onDismiss: () -> Unit) {
    val targets by controller.forwardTargets.collectAsState()
    var picked by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    val shown = targets.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.forwardTo, color = TextPrimary) },
        text = {
            Column {
                Field(query, { query = it }, s.search, capitalize = false)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.uid }) { user ->
                        val on = user.uid in picked
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = if (on) picked - user.uid else picked + user.uid }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChatAvatar(user, 38.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(user.name, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Ink else DarkSurface), contentAlignment = Alignment.Center) {
                                if (on) Icon(QMusicIcons.Check, null, tint = InkInverse, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onDismiss(); onSend(targets.filter { it.uid in picked }) }) {
                Text(s.send + if (picked.isNotEmpty()) " (${picked.size})" else "", color = if (picked.isNotEmpty()) Ink else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel, color = TextSecondary) } },
    )
}

/** Who read a message of ours, who has it, who not yet (in a group, everyone; with one person, them). */
@Composable
internal fun MessageInfoDialog(
    m: ChatMessage,
    people: List<ChatUser>,
    read: Map<String, Long>,
    delivered: Map<String, Long>,
    s: ChatStrings,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(s.messageInfo, color = TextPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(people, key = { it.uid }) { user ->
                    val r = (read[user.uid] ?: 0L) >= m.at
                    val d = r || (delivered[user.uid] ?: 0L) >= m.at
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(user, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(user.name, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        MessageTick(if (r) TickState.READ else if (d) TickState.DELIVERED else TickState.SENT, TextSecondary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                r -> s.readBy
                                d -> s.deliveredTo
                                else -> s.sentOnly
                            },
                            color = if (r) TickRead else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (r) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Ink) } },
    )
}

// ------------------------------------------------------------------ media of a conversation

/** Photos, videos, files and links of a conversation, in tabs (a profile, a group's page). */
@Composable
internal fun MediaTabs(
    messages: List<ChatMessage>,
    s: ChatStrings,
    loadImage: suspend (ChatImage) -> ByteArray?,
    onOpenPhotos: (List<ChatImage>, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenFile: (ChatAttachment) -> Unit,
) {
    val photos = remember(messages) { messages.filter { !it.deleted }.flatMap { it.images }.asReversed() }
    val videos = remember(messages) { messages.mapNotNull { it.attachment?.takeIf { a -> a.kind == AttachmentKind.VIDEO } }.asReversed() }
    val files = remember(messages) { messages.mapNotNull { it.attachment?.takeIf { a -> a.kind == AttachmentKind.FILE } }.asReversed() }
    val linkList = remember(messages) { messages.flatMap { links(it.text) }.distinct().asReversed() }
    var tab by remember { mutableStateOf(0) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(s.photos to photos.size, s.videos to videos.size, s.files to files.size, s.linksTab to linkList.size).forEachIndexed { i, (label, n) ->
                Text(
                    "$label${if (n > 0) " $n" else ""}",
                    color = if (i == tab) InkInverse else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (i == tab) Ink else DarkSurfaceVariant).clickable { tab = i }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        val empty = when (tab) {
            0 -> photos.isEmpty()
            1 -> videos.isEmpty()
            2 -> files.isEmpty()
            else -> linkList.isEmpty()
        }
        if (empty) {
            Text(s.nothingHere, color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            return@Column
        }
        when (tab) {
            0 -> photos.chunked(3).forEachIndexed { row, three ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    three.forEachIndexed { col, image ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) { PhotoCell(image, loadImage, { onOpenPhotos(photos, row * 3 + col) }, false, Modifier.fillMaxSize()) }
                    }
                    repeat(3 - three.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            1 -> videos.chunked(2).forEach { two ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    two.forEach { v -> Box(Modifier.weight(1f)) { VideoTile(v) { onOpenVideo(v.url) } } }
                    if (two.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            2 -> files.forEach { f ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) { FileBubble(f, false, null, s) { onOpenFile(f) } }
            }
            else -> linkList.forEach { link ->
                Text(
                    link,
                    color = Color(0xFF8AB4F8),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { runCatching { uriHandler.openUri(link) } }.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@Composable
private fun VideoTile(a: ChatAttachment, onOpen: () -> Unit) {
    val thumb = remember(a.thumb) { runCatching { kotlin.io.encoding.Base64.decode(a.thumb) }.getOrNull() }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(Color.Black).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        if (thumb != null) AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Icon(QMusicIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        if (a.durationMs > 0) Text(mmss(a.durationMs), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

// ------------------------------------------------------------------ sending photos: a look first, a caption, edits

/** The photos picked, before they go: swipe between them, edit one (crop, turn, draw), write a caption, send. */
@Composable
internal fun SendPhotosPage(
    photos: List<PickedImage>,
    s: ChatStrings,
    caption: String,
    onSend: (List<PickedImage>, String) -> Unit,
    onCancel: () -> Unit,
    viewOnce: Boolean = false,
    onViewOnce: ((Boolean) -> Unit)? = null,
) {
    PlatformBackHandler(enabled = true) { onCancel() }
    var list by remember(photos) { mutableStateOf(photos) }
    var text by remember { mutableStateOf(caption) }
    var editing by remember { mutableStateOf<Int?>(null) }
    // HD, as on WhatsApp: the photos at full quality instead of the lighter standard ones.
    var hd by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val canHd = list.any { it.hd != null }
    val pager = androidx.compose.foundation.pager.rememberPagerState { list.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(QMusicIcons.Close, null, tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (list.size > 1) Text("${pager.currentPage + 1} / ${list.size}", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                if (canHd) {
                    Text(
                        "HD",
                        color = if (hd) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (hd) Color.White else Color.Transparent)
                            .border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
                            .clickable { hd = !hd }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                // The web has no photo editor: the photo goes as it is.
                if (!ChatPlatform.web) {
                    Text(
                        s.editPhoto,
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { editing = pager.currentPage }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                AsyncImage(model = list[page].jpeg, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(DarkSurfaceVariant).padding(horizontal = 16.dp, vertical = 11.dp)) {
                    if (text.isEmpty()) Text(s.addCaption, color = TextMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.take(ChatController.MAX_LENGTH) },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(6.dp))
                // One photo: it can go as view once (the "1" lit up).
                if (onViewOnce != null && list.size == 1) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (viewOnce) Color.White else DarkSurfaceVariant).clickable {
                            onViewOnce(!viewOnce)
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text("1", color = if (viewOnce) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White).clickable(enabled = !preparing) {
                        if (!hd) {
                            onSend(list, text)
                        } else {
                            preparing = true
                            scope.launch {
                                val full = list.map { p -> p.hd?.let { make -> runCatching { make() }.getOrNull() } ?: p }
                                preparing = false
                                onSend(full, text)
                            }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (preparing) androidx.compose.material3.CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Icon(QMusicIcons.Send, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                }
            }
        }
        editing?.let { index ->
            PhotoEditor(
                image = list[index],
                onDone = { edited ->
                    list = list.toMutableList().also { it[index] = edited }
                    editing = null
                },
                onCancel = { editing = null },
            )
        }
    }
}

// ------------------------------------------------------------------ group calls

/** The group's call going on, when we are not in it: who is in it, and Join. */
@Composable
internal fun GroupCallBanner(people: List<ChatUser>, s: ChatStrings, onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(TickRead.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QMusicIcons.Phone, null, tint = TickRead, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.groupCallOngoing, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(people.joinToString(", ") { it.name.substringBefore(' ') } + " · ${people.size} ${s.people}", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            s.groupCallJoin,
            color = Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(TickRead).clickable(onClick = onJoin).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** A group call on screen: everyone's picture (or face) in a grid, ours too, and the buttons. */
@Composable
fun GroupCallScreen(
    service: GroupCallService,
    s: ChatStrings,
    me: ChatUser?,
    modifier: Modifier = Modifier,
    /** Who can be added (friends), in a call made by adding people. */
    people: List<ChatUser> = emptyList(),
) {
    val state by service.state.collectAsState()
    val call = state ?: return
    var addOpen by remember { mutableStateOf(false) }
    val addable = remember(people, call.members, me) {
        val inCall = call.members.map { it.user.uid }.toSet() + listOfNotNull(me?.uid)
        people.filter { it.uid !in inCall }
    }
    PlatformBackHandler(enabled = true) {}
    // Leaving the app: the call goes on in a floating window with the first picture there is.
    val inWindow = CallPictureInPicture(armed = true)
    if (inWindow) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val shown = call.members.firstOrNull { it.connected && it.hasVideo && it.cameraOn }
            if (shown != null) GroupCallVideo(service, shown.user.uid, Modifier.fillMaxSize()) else ChatAvatar(call.group, 72.dp)
        }
        return
    }
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(call.startedAt) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(modifier.fillMaxSize().background(Color(0xFF0B0B0B))) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(call.group.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                call.startedAt?.let { mmss(now - it) } ?: s.groupCallWaiting,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 14.sp,
            )
        }
        // The tiles: two to a row (one big tile when alone with one other).
        val tiles = buildList {
            add(null as GroupCallMember?)
            addAll(call.members)
        }
        val columns = if (tiles.size <= 2) 1 else 2
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { member ->
                        Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF181820)), contentAlignment = Alignment.Center) {
                            val user = member?.user ?: me
                            val showVideo = if (member == null) call.video && call.cameraOn else member.hasVideo && member.cameraOn && member.connected
                            if (showVideo) {
                                GroupCallVideo(service, member?.user?.uid, Modifier.fillMaxSize())
                            } else if (user != null) {
                                ChatAvatar(user, 72.dp)
                            }
                            Text(
                                (if (member == null) s.you else user?.name?.substringBefore(' ').orEmpty()) +
                                    if (member != null && !member.connected) " · …" else "",
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CallRoundButton(if (call.muted) QMusicIcons.MicOff else QMusicIcons.Mic, call.muted) { service.toggleMute() }
            if (call.video) {
                CallRoundButton(if (call.cameraOn) QMusicIcons.Camera else QMusicIcons.CameraOff, !call.cameraOn) { service.toggleCamera() }
                CallRoundButton(QMusicIcons.SwitchCamera, false) { service.switchCamera() }
            }
            CallRoundButton(QMusicIcons.Volume, call.speaker) { service.toggleSpeaker() }
            if (service.canAddPeople && addable.isNotEmpty() && call.members.size + 1 < MAX_GROUP_CALL) {
                CallRoundButton(QMusicIcons.PersonAdd, false) { addOpen = true }
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFFF3B30)).clickable { service.leave() }, contentAlignment = Alignment.Center) {
                Icon(QMusicIcons.PhoneEnd, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
    if (addOpen) {
        AddPersonSheet(
            s = s,
            people = addable,
            onPick = { user ->
                addOpen = false
                service.addPerson(user)
            },
            onClose = { addOpen = false },
        )
    }
}

@Composable
private fun CallRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape).background(if (on) Ink else Ink.copy(alpha = 0.14f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) InkInverse else Ink, modifier = Modifier.size(24.dp))
    }
}

// ------------------------------------------------------------------ sounds

/** Which Qeyf sound plays for what; a pick plays it once. */
@Composable
private fun SoundSettings(s: ChatStrings) {
    val sounds = rememberChatSounds() ?: return
    val choices by sounds.choices.collectAsState()
    val inChat by sounds.inChat.collectAsState()
    val small by sounds.small.collectAsState()
    val english = s.you == "You"
    var picking by remember { mutableStateOf<SoundKind?>(null) }
    val ring by CallTones.ring.collectAsState()
    CallTones.made.collectAsState().value
    val rows = listOf(
        SoundKind.SEND to s.soundSend,
        SoundKind.RECEIVE to s.soundReceive,
        SoundKind.NOTIFY to s.soundNotify,
        SoundKind.RING to s.soundRing,
        SoundKind.RINGBACK to s.soundRingback,
    )
    Column {
        rows.forEach { (kind, label) ->
            Row(Modifier.fillMaxWidth().clickable { picking = kind }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    if (kind == SoundKind.RING && ring.isNotEmpty()) CallTones.name(ring)
                    else ChatSoundCatalog.option(kind, choices[kind] ?: ChatSoundCatalog.defaults.getValue(kind)).name(english),
                    color = TickRead,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                )
            }
        }
        // Songs as Qeyf's sounds: what people calling here hear, and hearing what the called person chose.
        CallerToneSettings(s)
        SettingSwitch(s.soundInChat, s.soundInChatNote, inChat) { sounds.setInChat(it) }
        SettingSwitch(s.soundSmall, s.soundSmallNote, small) { sounds.setSmall(it) }
    }
    if (picking == SoundKind.RING) {
        RingTonePicker(s, s.soundRing, ring.ifEmpty { choices[SoundKind.RING] }, allowDefault = false, onPick = { id -> id?.let { CallTones.setRing?.invoke(it) } }) { picking = null }
        return
    }
    picking?.let { kind ->
        val current = choices[kind]
        AlertDialog(
            onDismissRequest = { picking = null },
            containerColor = DarkSurfaceVariant,
            title = { Text(rows.first { it.first == kind }.second, color = TextPrimary) },
            text = {
                Column {
                    ChatSoundCatalog.options.getValue(kind).forEach { option ->
                        val on = option.id == current
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                sounds.choose(kind, option.id)
                                sounds.preview(kind, option.id)
                            }.padding(vertical = 11.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) TickRead else DarkSurface), contentAlignment = Alignment.Center) {
                                if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Ink))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(option.name(english), color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Icon(QMusicIcons.Play, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("OK", color = Ink) } },
        )
    }
}

// ------------------------------------------------------------------ the list of calls

private val MissedRed = Color(0xFFFF5A5F)

/**
 * In and out, missed (red), declined, group calls; how long each lasted. A tap calls back the same way (voice or
 * video); a group's opens the group.
 */
@Composable
internal fun CallsPage(controller: ChatController, s: ChatStrings, onCall: ((ChatUser, Boolean) -> Unit)?) {
    val calls by controller.callLog.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(s.callsTitle, { controller.pop() }, actions = {
            if (calls.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(s.clearCalls, color = TextSecondary, fontSize = 13.sp) }
        })
        if (calls.isEmpty()) {
            Centered { Text(s.callsEmpty, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(calls, key = { it.id }) { c ->
                    val missed = c.outcome == CallOutcome.MISSED
                    val group = c.direction == CallDirection.GROUP
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (group || onCall == null) controller.openConversation(c.peer) else onCall(c.peer, c.video)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.clip(CircleShape).clickable { controller.openConversation(c.peer) }) { ChatAvatar(c.peer, 48.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.peer.name, color = if (missed) MissedRed else TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (c.direction != CallDirection.GROUP) DirectionArrow(incoming = c.direction == CallDirection.IN, colour = if (missed) MissedRed else TickRead)
                                Text(
                                    listOf(
                                        when {
                                            group -> s.callGroupShort
                                            missed -> s.callMissedShort
                                            c.outcome == CallOutcome.DECLINED -> s.callDeclinedShort
                                            c.outcome == CallOutcome.NOT_ANSWERED -> s.callNotAnswered
                                            c.direction == CallDirection.IN -> s.callIn
                                            else -> s.callOut
                                        },
                                        listTime(c.at, s) + " " + clock(c.at),
                                        if (c.seconds > 0) duration(c.seconds) else null,
                                    ).filterNotNull().joinToString(" · "),
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                        Icon(if (c.video) QMusicIcons.Camera else QMusicIcons.Phone, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.clearCalls, color = TextPrimary) },
            text = { Text(s.clearCallsConfirm, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmClear = false; controller.clearCallLog() }) { Text(s.delete, color = MissedRed) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

