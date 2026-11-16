package com.qmusic.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** A song on a conversation's shared playlist: who put it there and when. */
data class SharedListEntry(val key: String, val item: SharedItem, val by: String, val byName: String, val at: Long)

/** A reaction sent while watching together: an emoji or a few words, and whose. */
data class LiveReaction(val key: String, val text: String, val by: String, val name: String)

/**
 * Music and videos in the messages beyond sending them: a person's profile song, the song someone is playing now,
 * a conversation's shared playlist, reactions while watching together, and sending a song (at a second, or with a
 * line of its lyrics) or a video (at a second) from the music and video sides. The phone's part fills the hooks.
 */
object ChatMusic {
    var english = false

    fun words() = ChatMusicWords(english)

    // ---------------------------------------------------------------- what someone is playing now

    /** Whether this account tells its friends the song it plays. */
    val shareListening = MutableStateFlow(true)
    var setShareListening: (Boolean) -> Unit = {}

    /** What plays here now (null: nothing, or paused). */
    var publishListening: (SharedItem?) -> Unit = {}

    /** What the people being watched play now, by uid. */
    val listening = MutableStateFlow<Map<String, SharedItem>>(emptyMap())
    var listenTo: (Set<String>) -> Unit = {}
    private val watchers = HashMap<String, Set<String>>()

    /** The screen [key] wants to see what [uids] play (empty: no longer). */
    fun watch(key: String, uids: Set<String>) {
        if (uids.isEmpty()) watchers.remove(key) else watchers[key] = uids
        listenTo(watchers.values.flatten().toSet())
    }

    // ---------------------------------------------------------------- profile song

    var profileSong: (String) -> Flow<SharedItem?> = { flowOf(null) }
    var setProfileSong: suspend (SharedItem?) -> Boolean = { false }

    // ---------------------------------------------------------------- shared playlist

    var sharedList: (String) -> Flow<List<SharedListEntry>> = { flowOf(emptyList()) }
    var addToList: suspend (String, SharedItem) -> Boolean = { _, _ -> false }
    var removeFromList: suspend (String, String) -> Unit = { _, _ -> }

    /** Plays [items] from [index] in the music player. */
    var playList: (List<SharedItem>, Int) -> Unit = { _, _ -> }

    // ---------------------------------------------------------------- reactions while watching together

    /** The listening / watching together this phone takes part in (set by the app). */
    val together = MutableStateFlow<TogetherState?>(null)
    var reactions: (String) -> Flow<LiveReaction> = { emptyFlow() }
    var react: (String, String) -> Unit = { _, _ -> }

    // ---------------------------------------------------------------- sending from the music and video sides

    /** A song or a video to send to conversations (the app shows where to). */
    val sending = MutableStateFlow<SharedItem?>(null)

    /** Sends what plays in the music player now (set by the app). */
    var offerNowPlaying: (() -> Unit)? = null

    fun offer(item: SharedItem) {
        sending.value = item
    }

    // ---------------------------------------------------------------- statuses' songs

    /** Whether statuses play their songs (the speaker button of the status viewer). */
    val statusSound = MutableStateFlow(true)

    /** The address of a song's sound ([fresh]: not the one kept, which stopped working); set by the app. */
    var resolveSongUrl: suspend (String, Boolean) -> String? = { _, _ -> null }

    private val songUrls = HashMap<String, Pair<String, Long>>()
    private val songLookups = HashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /**
     * The address of [id]'s sound, kept for a while (an address works for some hours), so a status's song is
     * there at once the second time, and the next statuses' songs are looked up while the first one plays. Lookups
     * of the same song at the same time are one.
     */
    suspend fun songUrl(id: String, fresh: Boolean = false): String? {
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        if (fresh) songUrls.remove(id) else songUrls[id]?.takeIf { now - it.second < SONG_URL_KEEP_MS }?.let { return it.first }
        val running = songLookups[id]?.takeIf { it.isActive && !fresh }
        // Not tied to who asked first: a lookup started ahead goes on when its screen moves on.
        val lookup = running ?: lookupScope.async { runCatching { resolveSongUrl(id, fresh) }.getOrNull() }.also { songLookups[id] = it }
        val url = runCatching { lookup.await() }.getOrNull()
        if (songLookups[id] === lookup) songLookups.remove(id)
        return url?.also { songUrls[id] = it to now }
    }

    private val lookupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private const val SONG_URL_KEEP_MS = 3 * 3600_000L
}

/**
 * Messages as Qeyf's home: the app opens on them, and their bar goes to the music and the videos (from where Back
 * comes home). Set by the app.
 */
object AppHome {
    val messagesFirst = MutableStateFlow(true)
    var setMessagesFirst: (Boolean) -> Unit = {}
    var openMusic: (() -> Unit)? = null
    var openVideos: (() -> Unit)? = null
}

/** "2:35" (or "1:02:35"). */
fun secondsLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    fun two(n: Long) = if (n < 10) "0$n" else "$n"
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

class ChatMusicWords(private val english: Boolean) {
    val sendToChat = if (english) "Send in chat" else "Sohbete gönder"
    val sendTo = if (english) "Send to" else "Kime gönderilsin"
    fun startHere(at: String) = if (english) "Start at $at" else "Bu saniyeden başlasın ($at)"
    fun startsAt(at: String) = if (english) "Starts at $at" else "Başlangıç $at"
    val sent = if (english) "Sent" else "Gönderildi"
    val signInFirst = if (english) "Sign in to messages first" else "Önce sohbete giriş yap"
    val noChats = if (english) "No conversations yet" else "Henüz sohbet yok"
    val lyricLine = if (english) "Lyric" else "Şarkı sözü"
    val quoteHint = if (english) "Hold a line to send it in chat" else "Bir satıra basılı tut, sohbete gönder"

    val listeningTo = if (english) "Listening" else "Dinliyor"
    val showListening = if (english) "Show what I listen to" else "Dinlediğimi göster"
    val showListeningNote = if (english) "Your friends see the song you are playing, in their chat list" else "Arkadaşların, o an çaldığın şarkıyı sohbet listesinde görür"

    val profileSong = if (english) "Profile song" else "Profil şarkısı"
    val profileSongNote = if (english) "Whoever opens your profile sees it and can play it" else "Profilini açan görür ve dinleyebilir"
    val pickSong = if (english) "Pick a song" else "Şarkı seç"
    val change = if (english) "Change" else "Değiştir"
    val remove = if (english) "Remove" else "Kaldır"
    val failed = if (english) "That did not work, try again" else "Olmadı, tekrar dene"

    val sharedList = if (english) "Shared playlist" else "Ortak çalma listesi"
    val sharedListEmpty = if (english) "No songs yet. Everyone here can add some." else "Henüz şarkı yok. Buradaki herkes ekleyebilir."
    val addSong = if (english) "Add a song" else "Şarkı ekle"
    val playAll = if (english) "Play all" else "Hepsini çal"
    fun addedBy(name: String) = if (english) "Added by $name" else "$name ekledi"
    fun songs(n: Int) = if (english) "$n songs" else "$n şarkı"
    val alreadyThere = if (english) "Already on the list" else "Zaten listede"
    val added = if (english) "Added to the list" else "Listeye eklendi"

    val react = if (english) "React" else "Tepki"
    val write = if (english) "Write" else "Yaz"
    val writeHint = if (english) "A few words" else "Kısa bir şey yaz"
    val send = if (english) "Send" else "Gönder"
    val songPart = if (english) "Part of the song" else "Şarkının bölümü"
    val songPartHint = if (english) "Slide to pick the 15 seconds that play" else "Kaydır, çalacak 15 saniyeyi seç"
    val songPreparing = if (english) "Getting the song" else "Şarkı hazırlanıyor"
    val songFailed = if (english) "The song could not be opened" else "Şarkı açılamadı"
    val done = if (english) "Done" else "Tamam"
    val soundOn = if (english) "Sound on" else "Sesi aç"
    val music = if (english) "Music" else "Müzik"
    val messages = if (english) "Messages" else "Mesajlar"
    val findPeople = if (english) "Search a name or @username" else "Ad ya da @kullanıcı adı ara"
    val newGroupNote = if (english) "Talk with several friends at once" else "Birkaç arkadaşınla birlikte konuş"
    val scanCode = if (english) "Scan a code" else "Kod okut"
    val scanCodeNote = if (english) "Add a friend from their Qeyf code" else "Arkadaşını Qeyf kodundan ekle"
    val myCode = if (english) "Share my code" else "Kodumu paylaş"
    val myCodeNote = if (english) "Friends find you with it" else "Arkadaşların seni bununla bulur"
    val yourFriends = if (english) "Your friends" else "Arkadaşların"
    val onQeyf = if (english) "On Qeyf" else "Qeyf'te"
    val typeMore = if (english) "Type at least two letters" else "En az iki harf yaz"
    val noFriendsYet = if (english) "No friends yet" else "Henüz arkadaşın yok"
    val noFriendsNote = if (english) "Search their @username, or scan their code" else "@kullanıcı adını ara ya da kodunu okut"
    fun noOne(q: String) = if (english) "No one named \"$q\"" else "\"$q\" adında kimse yok"
    val noOneNote = if (english) "Check the @username, or ask for their Qeyf code" else "Kullanıcı adını kontrol et ya da Qeyf kodunu iste"
    val video = if (english) "Video" else "Video"
    val messagesFirst = if (english) "Open Qeyf on messages" else "Qeyf mesajlarla açılsın"
    val messagesFirstNote = if (english) "Off: it opens on the music" else "Kapalıyken müzikle açılır"
    val soundOff = if (english) "Sound off" else "Sesi kapat"
    val cancel = if (english) "Cancel" else "Vazgeç"
}

// ==================================================================== sending a song or a video

/** Picking the conversations to send [item] to (from the music or video side); [onDone] gets what to say then. */
@Composable
fun ChatSendDialog(controller: ChatController, s: ChatStrings, item: SharedItem, onDone: (String?) -> Unit) {
    val w = ChatMusic.words()
    val account by controller.account.collectAsState()
    val targets by controller.forwardTargets.collectAsState()
    var picked by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var atSecond by remember { mutableStateOf(item.startMs > 0) }
    val ready = account is ChatAccount.Ready
    val shown = targets.filter { it.uid != CLAUDE_BOT_UID && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = DarkSurfaceVariant,
        title = { Text(w.sendTo, color = TextPrimary) },
        text = {
            Column {
                ShareCard(if (atSecond || item.quote.isNotEmpty()) item else item.copy(startMs = 0), mine = false, s = s)
                if (item.startMs > 0 && item.quote.isEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { atSecond = !atSecond }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tick(atSecond)
                        Spacer(Modifier.width(10.dp))
                        Text(w.startHere(secondsLabel(item.startMs)), color = TextPrimary, fontSize = 14.sp)
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                if (!ready) {
                    Text(w.signInFirst, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                    return@Column
                }
                Field(query, { query = it }, s.search, capitalize = false)
                Spacer(Modifier.height(8.dp))
                if (targets.isEmpty()) Text(w.noChats, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.uid }) { user ->
                        val on = user.uid in picked
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = if (on) picked - user.uid else picked + user.uid }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChatAvatar(user, 38.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(user.name, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Tick(on)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready && picked.isNotEmpty(), onClick = {
                val send = if (atSecond || item.quote.isNotEmpty()) item else item.copy(startMs = 0)
                targets.filter { it.uid in picked }.forEach { controller.send(it, "", send) }
                onDone(w.sent)
            }) {
                Text(s.send + if (picked.isNotEmpty()) " (${picked.size})" else "", color = if (ready && picked.isNotEmpty()) Ink else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(s.cancel, color = TextSecondary) } },
    )
}

@Composable
private fun Tick(on: Boolean) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Ink else DarkSurface), contentAlignment = Alignment.Center) {
        if (on) Icon(QMusicIcons.Check, null, tint = InkInverse, modifier = Modifier.size(14.dp))
    }
}

// ==================================================================== what someone is playing now

/** What [uids] play now, while the screen [key] shows. */
@Composable
fun rememberListening(key: String, uids: Set<String>): Map<String, SharedItem> {
    DisposableEffect(key, uids) {
        ChatMusic.watch(key, uids)
        onDispose { ChatMusic.watch(key, emptySet()) }
    }
    val all by ChatMusic.listening.collectAsState()
    return all
}

/** "Listening: song · artist", small, under a name; a tap plays it here. */
@Composable
internal fun ListeningLine(item: SharedItem, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlay).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QMusicIcons.Headphones, contentDescription = null, tint = TickRead, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            item.title + if (item.subtitle.isNotBlank()) " · " + item.subtitle else "",
            color = TickRead,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ==================================================================== profile song

/** A person's profile song, on their profile: its cover, and a tap plays it. */
@Composable
internal fun ProfileSongCard(uid: String, onPlay: (SharedItem) -> Unit) {
    val song by remember(uid) { ChatMusic.profileSong(uid) }.collectAsState(initial = null)
    val item = song ?: return
    val w = ChatMusic.words()
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DarkSurfaceVariant)
            .clickable { onPlay(item) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(DarkSurface), contentAlignment = Alignment.Center) {
            if (item.thumbnail != null) AsyncImage(model = item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.profileSong, color = TextSecondary, fontSize = 12.sp)
            Text(item.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
            Icon(QMusicIcons.Play, contentDescription = null, tint = InkInverse, modifier = Modifier.size(20.dp))
        }
    }
}

/** The person's own profile song (in the settings), with picking another and taking it off. */
@Composable
internal fun MyProfileSong(controller: ChatController, s: ChatStrings, me: ChatUser) {
    val w = ChatMusic.words()
    val song by remember(me.uid) { ChatMusic.profileSong(me.uid) }.collectAsState(initial = null)
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun set(item: SharedItem?) {
        if (busy) return
        busy = true
        scope.launch {
            if (!ChatMusic.setProfileSong(item)) controller.reportError(w.failed)
            busy = false
        }
    }
    Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant), contentAlignment = Alignment.Center) {
            val pic = song?.thumbnail
            if (pic != null) {
                AsyncImage(model = pic, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(QMusicIcons.Headphones, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(w.profileSong, color = TextPrimary, fontSize = 15.sp)
            Text(song?.let { it.title + if (it.subtitle.isNotBlank()) " · " + it.subtitle else "" } ?: w.profileSongNote, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (song != null) {
            TextButton(onClick = { set(null) }, enabled = !busy) { Text(w.remove, color = TextSecondary) }
        } else {
            Text(w.pickSong, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (picking) {
        StatusSongPicker(s, controller.shareables().filter { it.kind == ShareKind.SONG }, controller.searchSongs, onPick = { set(it.copy(startMs = 0, quote = "")) }) { picking = false }
    }
}

// ==================================================================== shared playlist

/** A conversation's shared playlist: everyone in it adds songs; a tap plays the list from there. */
@Composable
internal fun SharedListPage(controller: ChatController, s: ChatStrings, me: ChatUser, chatId: String, title: String, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        SharedListContent(controller, s, me, chatId, title, onClose)
    }
}

@Composable
private fun SharedListContent(controller: ChatController, s: ChatStrings, me: ChatUser, chatId: String, title: String, onClose: () -> Unit) {
    val w = ChatMusic.words()
    val entries by remember(chatId) { ChatMusic.sharedList(chatId) }.collectAsState(initial = null)
    var picking by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SharedListEntry?>(null) }
    val scope = rememberCoroutineScope()
    val list = entries.orEmpty()
    Column(Modifier.fillMaxSize().background(com.qmusic.ui.theme.BgRoot)) {
        TopBar(w.sharedList, onClose, actions = {
            IconButton(onClick = { picking = true }) { Icon(QMusicIcons.Add, contentDescription = w.addSong, tint = Ink) }
        })
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (list.isNotEmpty()) Text(w.songs(list.size), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isNotEmpty()) Pill(w.playAll, primary = true) { ChatMusic.playList(list.map { it.item }, 0) }
                Pill(w.addSong, primary = list.isEmpty()) { picking = true }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            entries == null -> Unit
            list.isEmpty() -> Text(w.sharedListEmpty, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(list, key = { _, e -> e.key }) { index, e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ChatMusic.playList(list.map { it.item }, index) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(DarkSurfaceVariant)) {
                            if (e.item.thumbnail != null) AsyncImage(model = e.item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.item.title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(e.item.subtitle, w.addedBy(if (e.by == me.uid) s.you else e.byName)).filter { it.isNotBlank() }.joinToString(" · "),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { menuFor = e }) { Icon(QMusicIcons.More, contentDescription = null, tint = TextSecondary) }
                    }
                }
            }
        }
    }
    menuFor?.let { e ->
        ChoiceDialog(
            e.item.title,
            listOf(
                s.forward to { ChatMusic.offer(e.item) },
                w.remove to { scope.launch { ChatMusic.removeFromList(chatId, e.key) } },
            ),
            s,
        ) { menuFor = null }
    }
    if (picking) {
        StatusSongPicker(s, controller.shareables().filter { it.kind == ShareKind.SONG }, controller.searchSongs, onPick = { song ->
            if (list.any { it.item.id == song.id }) {
                controller.reportInfo(w.alreadyThere)
            } else {
                scope.launch {
                    if (ChatMusic.addToList(chatId, song.copy(startMs = 0, quote = ""))) controller.reportInfo(w.added) else controller.reportError(w.failed)
                }
            }
        }) { picking = false }
    }
}

// ==================================================================== reactions while watching together

private val QUICK_REACTIONS = REACTIONS

/**
 * Over the video while watching [videoId] together: the reactions of everyone, rising and fading, and a button to
 * send one (an emoji or a few words).
 */
@Composable
fun TogetherReactionsLayer(videoId: String, modifier: Modifier = Modifier) {
    val state by ChatMusic.together.collectAsState()
    val session = state?.takeIf { it.item.kind == ShareKind.VIDEO && it.item.id == videoId } ?: return
    val chatId = session.chatId
    val w = ChatMusic.words()
    val shown = remember(chatId) { mutableStateListOf<LiveReaction>() }
    LaunchedEffect(chatId) {
        ChatMusic.reactions(chatId).collect { r ->
            if (shown.any { it.key == r.key }) return@collect
            shown.add(r)
            while (shown.size > 14) shown.removeAt(0)
        }
    }
    var open by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = maxHeight
        shown.toList().forEach { r ->
            key(r.key) { Rising(r, height) { shown.removeAll { it.key == r.key } } }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (open) {
                Column(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QUICK_REACTIONS.forEach { e ->
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).clickable { ChatMusic.react(chatId, e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).clickable { writing = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(QMusicIcons.Edit, contentDescription = w.write, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (open) 0.75f else 0.45f)).clickable { open = !open },
                contentAlignment = Alignment.Center,
            ) { Icon(if (open) QMusicIcons.Close else QMusicIcons.Heart, contentDescription = w.react, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
    if (writing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(w.write, color = TextPrimary) },
            text = { Field(text, { text = it.take(60) }, w.writeHint) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    ChatMusic.react(chatId, text.trim())
                    writing = false
                }) { Text(w.send, color = if (text.isNotBlank()) Ink else TextMuted) }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text(w.cancel, color = TextSecondary) } },
        )
    }
}

/** One reaction rising from the bottom and fading; [onGone] once it has. */
@Composable
private fun Rising(r: LiveReaction, height: androidx.compose.ui.unit.Dp, onGone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(r.key) {
        progress.animateTo(1f, tween(durationMillis = 3_800, easing = LinearEasing))
        onGone()
    }
    val p = progress.value
    val lane = (r.key.hashCode() and 0x7fffffff) % 5
    Column(
        Modifier
            .offset(x = (24 + lane * 36).dp, y = height - 56.dp - (height * 0.75f) * p)
            .alpha(if (p < 0.1f) p * 10 else (1f - (p - 0.6f).coerceAtLeast(0f) / 0.4f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val emoji = isBigEmoji(r.text)
        if (emoji) {
            Text(r.text, fontSize = 30.sp)
        } else {
            Text(
                r.text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (r.name.isNotBlank()) {
            Text(r.name, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontStyle = FontStyle.Italic, maxLines = 1)
        }
    }
}

// ==================================================================== a status song's part

/**
 * Picking which 15 seconds of [song] a status plays: the part plays over and over while the slider moves it, and
 * [onPick] gets where it starts.
 */
@Composable
internal fun StatusSongPart(song: SharedItem, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val w = ChatMusic.words()
    var url by remember(song.id) { mutableStateOf<String?>(null) }
    var failed by remember(song.id) { mutableStateOf(false) }
    var retried by remember(song.id) { mutableStateOf(false) }
    var length by remember(song.id) { mutableStateOf(0L) }
    var start by remember(song.id) { mutableStateOf(statusSongStart(song).toFloat()) }
    var playFrom by remember(song.id) { mutableStateOf(statusSongStart(song)) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(song.id) {
        url = ChatMusic.songUrl(song.id)
        if (url == null) failed = true
    }
    val last = (length - STATUS_SONG_LENGTH).coerceAtLeast(0L)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(w.songPart, color = TextPrimary) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(DarkSurface)) {
                        if (song.thumbnail != null) AsyncImage(model = song.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (song.subtitle.isNotBlank()) Text(song.subtitle, color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(18.dp))
                when {
                    failed -> Text(w.songFailed, color = TextSecondary, fontSize = 14.sp)
                    length <= 0L -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(w.songPreparing, color = TextSecondary, fontSize = 14.sp)
                    }
                    else -> {
                        val from = start.toLong().coerceIn(0L, last)
                        Text(
                            secondsLabel(from) + " - " + secondsLabel((from + STATUS_SONG_LENGTH).coerceAtMost(length)),
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        androidx.compose.material3.Slider(
                            value = from.toFloat(),
                            onValueChange = {
                                dragging = true
                                start = it
                            },
                            onValueChangeFinished = {
                                dragging = false
                                playFrom = start.toLong().coerceIn(0L, last)
                            },
                            valueRange = 0f..last.toFloat().coerceAtLeast(1f),
                            enabled = last > 0,
                            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = DarkSurface),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text("0:00", color = TextMuted, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(secondsLabel(length), color = TextMuted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(w.songPartHint, color = TextSecondary, fontSize = 12.sp)
                    }
                }
                url?.let { u ->
                    key(u) {
                        StatusAudio(
                            url = u,
                            playing = !dragging,
                            startMs = playFrom,
                            loopMs = STATUS_SONG_LENGTH,
                            onReady = { ms ->
                                length = ms
                                // A song shorter than where it was to start: the part moves into it.
                                if (start > (ms - STATUS_SONG_LENGTH).coerceAtLeast(0L)) {
                                    start = (ms - STATUS_SONG_LENGTH).coerceAtLeast(0L).toFloat()
                                    playFrom = start.toLong()
                                }
                            },
                            onFailed = {
                                if (retried) {
                                    failed = true
                                } else {
                                    retried = true
                                    scope.launch {
                                        url = null
                                        url = ChatMusic.songUrl(song.id, fresh = true)
                                        if (url == null) failed = true
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = length > 0L && !failed, onClick = {
                // 1 ms stands for the very start (0 means "not chosen").
                onPick(start.toLong().coerceIn(0L, last).coerceAtLeast(1L))
                onDismiss()
            }) { Text(w.done, color = if (length > 0L && !failed) Ink else TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(w.cancel, color = TextSecondary) } },
    )
}
