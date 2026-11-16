@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.qmusic.ui.chat

import com.qmusic.ui.components.QeyfBarItem
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.PlatformBackHandler
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextMuted
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.datetime.Clock

private val CallRed = Color(0xFFFF5A5F)

/** A status not seen yet rings white; a seen one grey. */
internal val RingUnseen: Color get() = Ink
internal val RingSeen = Color(0xFF5A5A5A)

/** [content] (a picture) with a status ring around it when [ring] is set. */
@Composable
internal fun StatusRinged(ring: Color?, content: @Composable () -> Unit) {
    if (ring == null) {
        content()
    } else {
        Box(Modifier.border(2.dp, ring, CircleShape).padding(3.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The messages section's home: Chats, Status, Groups and Calls with a bar at the bottom, each with its badge (unread
 * chats, new statuses, unread groups, missed calls).
 */
@Composable
internal fun ChatHome(
    controller: ChatController,
    s: ChatStrings,
    me: ChatUser,
    onExit: () -> Unit,
    onCall: ((ChatUser, Boolean) -> Unit)?,
    onGroupCall: ((ChatUser, Boolean) -> Unit)?,
) {
    val tab by controller.tab.collectAsState()
    // A phone that would miss calls: the setup opens by itself.
    SetupWizard(s)
    // Back from another tab goes to Chats first, then out.
    PlatformBackHandler(enabled = tab != ChatTab.CHATS) { controller.selectTab(ChatTab.CHATS) }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        // The tabs side by side: a swipe goes to the next or the previous one (as on WhatsApp), a tap on the bar
        // slides there.
        val tabs = ChatTab.entries
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = tabs.indexOf(tab)) { tabs.size }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        // A tap on the bar slides there; a far tab is not reached through every tab between (one step, then the
        // slide), as WhatsApp does.
        LaunchedEffect(tab) {
            val target = tabs.indexOf(tab)
            if (pager.targetPage == target && !pager.isScrollInProgress) return@LaunchedEffect
            if (kotlin.math.abs(pager.currentPage - target) > 1) pager.scrollToPage(if (target > pager.currentPage) target - 1 else target + 1)
            pager.animateScrollToPage(target, animationSpec = androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        // A swipe that lands on a tab makes it the tab (its badge clears, Back comes home from it).
        LaunchedEffect(pager) {
            androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { page -> if (controller.tab.value != tabs[page]) controller.selectTab(tabs[page]) }
        }
        // Leaving a tab puts its keyboard away (a search typed on it).
        LaunchedEffect(pager) {
            androidx.compose.runtime.snapshotFlow { pager.currentPage }.collect { focusManager.clearFocus() }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            // The tabs beside are ready, so a swipe shows them at once (their effects wait for them to be shown).
            beyondViewportPageCount = 1,
            key = { tabs[it].name },
            // A firm, quick settle: a short swipe goes to the next tab, without bouncing.
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pager,
                pagerSnapDistance = androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
                snapAnimationSpec = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f),
            ),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (tabs[page]) {
                    ChatTab.CHATS -> ChatsTab(controller, s, me, onExit, onCall, onGroupCall)
                    ChatTab.STATUS -> StatusTab(controller, s, me, onExit)
                    ChatTab.GROUPS -> GroupsTab(controller, s, me, onExit, onGroupCall)
                    ChatTab.CALLS -> CallsTab(controller, s, me, onExit, onCall, onGroupCall, shown = pager.settledPage == page)
                }
            }
        }
        HomeBar(controller, s, if (pager.isScrollInProgress) tabs[pager.currentPage] else tab)
    }
}

// ------------------------------------------------------------------ the bar

@Composable
private fun HomeBar(controller: ChatController, s: ChatStrings, tab: ChatTab) {
    val unread by controller.unreadCount.collectAsState()
    val threads by controller.threads.collectAsState()
    val unseen by controller.unseenStatusOwners.collectAsState()
    val missed by controller.missedCalls.collectAsState()
    val groupsUnread = threads.orEmpty().count { it.isGroup && it.unread }
    val chatsUnread = threads.orEmpty().count { !it.isGroup && it.unread }.takeIf { threads != null } ?: unread
    com.qmusic.ui.components.QeyfBar(BgRoot) {
        QeyfBarItem(s.tabChats, QMusicIcons.Chat, tab == ChatTab.CHATS, count = chatsUnread) { controller.selectTab(ChatTab.CHATS) }
        QeyfBarItem(s.tabStatus, tab == ChatTab.STATUS, dot = unseen.isNotEmpty(), icon = { tint -> StatusGlyph(tint, 22.dp) }) { controller.selectTab(ChatTab.STATUS) }
        QeyfBarItem(s.tabGroups, QMusicIcons.Group, tab == ChatTab.GROUPS, count = groupsUnread) { controller.selectTab(ChatTab.GROUPS) }
        QeyfBarItem(s.tabCalls, QMusicIcons.Phone, tab == ChatTab.CALLS, count = missed, badgeColor = CallRed) { controller.selectTab(ChatTab.CALLS) }
        // The music and the videos are one tap away (the same bar is in them, with the messages).
        val w = ChatMusic.words()
        AppHome.openMusic?.let { open -> QeyfBarItem(w.music, QMusicIcons.Headphones, false, onClick = open) }
        AppHome.openVideos?.let { open -> QeyfBarItem(w.video, QMusicIcons.VideoPlay, false, onClick = open) }
    }
}

@Composable
@Suppress("unused")
private fun BarItem(
    label: String,
    selected: Boolean,
    count: Int,
    dot: Boolean,
    modifier: Modifier,
    badgeColor: Color = TickRead,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The selected tab sits on a pill, like the system's own bars.
            Box(
                Modifier.widthIn(max = 60.dp).fillMaxWidth(0.86f).height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) Ink.copy(alpha = 0.14f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { icon(if (selected) Ink else TextSecondary) }
            if (count > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-2).dp).height(18.dp).widthIn(min = 18.dp).clip(CircleShape).background(badgeColor).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (count > 99) "99+" else count.toString(), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            } else if (dot) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = 2.dp).size(9.dp).clip(CircleShape).background(TickRead))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** The status sign: a broken ring with a dot in it. */
@Composable
internal fun StatusGlyph(tint: Color, size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.1f
        val r = this.size.minDimension / 2 - stroke
        for (i in 0 until 4) {
            drawArc(
                color = tint,
                startAngle = i * 90f + 12f,
                sweepAngle = 66f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        drawCircle(tint, radius = r * 0.38f, center = center)
    }
}

// ------------------------------------------------------------------ shared pieces

/** The big title bar of a tab: the title on the left, the actions on the right. */
@Composable
internal fun HomeTitle(title: String, onExit: () -> Unit, logo: Boolean = false, actions: @Composable () -> Unit = {}) {
    Row(
        // It stays at the top while the list scrolls under it (on the page's colour, so nothing shows through).
        Modifier.fillMaxWidth().background(BgRoot).statusBarsPadding().height(60.dp).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // At home there is nowhere to go back to.
        val home by AppHome.messagesFirst.collectAsState()
        if (home) {
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = onExit) { Icon(QMusicIcons.Back, contentDescription = null, tint = Ink) }
        }
        if (logo) {
            // The chats' page carries the app's name, as WhatsApp does.
            Box(Modifier.weight(1f).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                com.qmusic.ui.theme.NuveloWordmark(letterHeight = 18.dp, color = TextPrimary)
            }
        } else {
            Text(title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        actions()
    }
}

/** The round search box that is always there at the top of a list. */
@Composable
internal fun SearchPill(value: String, onValue: (String) -> Unit, hint: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(DarkSurfaceVariant).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QMusicIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = TextMuted, fontSize = 16.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(QMusicIcons.Close, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onValue("") })
        }
    }
}

/** The round white button at the bottom right of a tab. */
@Composable
internal fun HomeFab(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(16.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(Ink).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = InkInverse, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) InkInverse else TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ink else DarkSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

/** The clock, moved on every couple of seconds ("typing…" fades by itself). */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

// ------------------------------------------------------------------ chats

@Composable
private fun ChatsTab(
    controller: ChatController,
    s: ChatStrings,
    me: ChatUser,
    onExit: () -> Unit,
    onCall: ((ChatUser, Boolean) -> Unit)? = null,
    onGroupCall: ((ChatUser, Boolean) -> Unit)? = null,
) {
    // A picture tapped: the person (or group) in a small window, with message, call and info under it.
    var peek by remember { mutableStateOf<ChatUser?>(null) }
    peek?.let { who ->
        AvatarPeek(
            user = who,
            s = s,
            hasStatus = !who.isGroup && controller.friendStatuses.value.any { it.owner.uid == who.uid },
            onMessage = { controller.openConversation(who) },
            onCall = if (who.isGroup) onGroupCall?.let { f -> { video: Boolean -> f(who, video) } } else onCall?.let { f -> { video: Boolean -> f(who, video) } },
            onInfo = { if (who.isGroup) controller.openGroupInfo(who) else controller.openProfile(who) },
            onStatus = { controller.push(ChatRoute.StatusViewer(who.uid, mine = false)) },
        ) { peek = null }
    }
    val all by controller.threads.collectAsState()
    val archived by controller.archived.collectAsState()
    val filter by controller.filter.collectAsState()
    val unseen by controller.unseenStatusOwners.collectAsState()
    val statusGroups by controller.friendStatuses.collectAsState()
    val togetherState by (controller.together?.active ?: remember { kotlinx.coroutines.flow.MutableStateFlow<TogetherState?>(null) }).collectAsState()
    var search by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var muteFor by remember { mutableStateOf<ChatThread?>(null) }
    var cameraShot by remember { mutableStateOf<PickedImage?>(null) }
    val camera = rememberCamera { shot -> cameraShot = shot }
    val scan = rememberQrScanner { text -> controller.openQeyfCode(text, s) }
    val now = rememberNow()
    val withStatus = remember(statusGroups) { statusGroups.map { it.owner.uid }.toSet() }
    val source = if (filter == ChatFilter.ARCHIVED) archived else all
    val list = remember(source, filter, search) {
        val q = search.trim()
        source?.filter { t ->
            when (filter) {
                ChatFilter.ALL, ChatFilter.ARCHIVED -> true
                ChatFilter.UNREAD -> t.unread
                ChatFilter.FAVORITES -> t.favorite
                ChatFilter.GROUPS -> t.isGroup
            } && (q.isEmpty() || t.other.name.contains(q, ignoreCase = true) || t.lastText.contains(q, ignoreCase = true) || t.other.username.contains(q, ignoreCase = true))
        }
    }
    val unreadTotal = all.orEmpty().count { it.unread }
    // What the people of the newest conversations play now.
    val listeningUids = remember(all) { all.orEmpty().filter { !it.isGroup && it.other.uid != CLAUDE_BOT_UID }.take(40).map { it.other.uid }.toSet() }
    val listeningNow = rememberListening("home", listeningUids)
    var found by remember { mutableStateOf<List<ChatController.FoundMessage>>(emptyList()) }
    var foundBusy by remember { mutableStateOf(false) }
    LaunchedEffect(search) {
        val q = search.trim()
        if (q.length < 2) {
            found = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350)
        foundBusy = true
        found = runCatching { controller.searchMessages(q) }.getOrDefault(emptyList())
        foundBusy = false
    }
    val lockedCount = controller.lockedThreads.collectAsState().value.size
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            stickyHeader(key = "title") {
                HomeTitle(s.title, onExit, logo = true) {
                    val incoming by controller.friendRequests.collectAsState()
                    IconButton(onClick = { camera() }) { Icon(QMusicIcons.Camera, contentDescription = s.camera, tint = Ink) }
                    IconButton(onClick = { controller.openFriends() }) {
                        Box {
                            Icon(QMusicIcons.Account, contentDescription = s.friends, tint = Ink)
                            if (incoming.isNotEmpty()) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(16.dp).clip(CircleShape).background(TickRead),
                                    contentAlignment = Alignment.Center,
                                ) { Text(incoming.size.coerceAtMost(9).toString(), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Ink) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                            DropdownMenuItem(text = { Text(s.newGroup, color = TextPrimary) }, onClick = { menu = false; controller.openNewGroup() })
                            DropdownMenuItem(text = { Text(s.broadcastsTitle, color = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.Broadcasts) })
                            DropdownMenuItem(text = { Text(s.starredShort, color = TextPrimary) }, onClick = { menu = false; controller.openStarred() })
                            DropdownMenuItem(text = { Text(s.lockedChats, color = TextPrimary) }, onClick = { menu = false; controller.push(ChatRoute.LockedFolder) })
                            if (scan != null) DropdownMenuItem(text = { Text(s.scanQr, color = TextPrimary) }, onClick = { menu = false; scan() })
                            DropdownMenuItem(text = { Text(s.settings, color = TextPrimary) }, onClick = { menu = false; controller.openSettings() })
                            DropdownMenuItem(text = { Text(s.signOut, color = TextPrimary) }, onClick = { menu = false; controller.signOut() })
                            DropdownMenuItem(text = { Text(s.deleteAccount, color = TextPrimary) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                }
            }
            item(key = "search") { SearchPill(search, { search = it }, s.searchChats) }
            item(key = "chips") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(s.filterAll, filter == ChatFilter.ALL) { controller.setFilter(ChatFilter.ALL) }
                    FilterChip(if (unreadTotal > 0) "${s.filterUnread} $unreadTotal" else s.filterUnread, filter == ChatFilter.UNREAD) { controller.setFilter(ChatFilter.UNREAD) }
                    FilterChip(s.filterFavorites, filter == ChatFilter.FAVORITES) { controller.setFilter(ChatFilter.FAVORITES) }
                    val groupsUnread = all.orEmpty().count { it.isGroup && it.unread }
                    FilterChip(if (groupsUnread > 0) "${s.filterGroups} $groupsUnread" else s.filterGroups, filter == ChatFilter.GROUPS) { controller.setFilter(ChatFilter.GROUPS) }
                    FilterChip(if (archived.isNotEmpty()) "${s.filterArchived} ${archived.size}" else s.filterArchived, filter == ChatFilter.ARCHIVED) { controller.setFilter(ChatFilter.ARCHIVED) }
                }
            }
            item(key = "readiness") { ReadinessCard(s) }
            if (lockedCount > 0 && filter == ChatFilter.ALL && search.isBlank()) {
                item(key = "locked") {
                    Row(
                        Modifier.fillMaxWidth().clickable { controller.push(ChatRoute.LockedFolder) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Icon(QMusicIcons.Archive, null, tint = TextSecondary) }
                        Spacer(Modifier.width(14.dp))
                        Text(s.lockedChats, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(lockedCount.toString(), color = TextMuted, fontSize = 13.sp)
                    }
                }
            }
            togetherState?.let { state ->
                item(key = "together") {
                    val thread = all?.firstOrNull { it.id == state.chatId }
                    TogetherBanner(state, s, onOpen = { thread?.let { controller.openConversation(it.other) } }, onLeave = { controller.together?.leave() })
                }
            }
            // Messages saying what was searched, in every conversation.
            if (search.trim().length >= 2) {
                item(key = "msgHead") { SectionTitle(s.searchMessagesTitle) }
                when {
                    foundBusy && found.isEmpty() -> item(key = "msgBusy") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
                    }
                    found.isEmpty() -> item(key = "msgNone") { Text(s.noResults, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    else -> items(found, key = { "m_" + it.thread.id + it.message.id }) { f -> FoundRow(f, search.trim(), me, s) { controller.openAt(f) } }
                }
            }
            when {
                list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
                }
                list.isEmpty() -> item(key = "empty") {
                    EmptyNote(
                        when {
                            search.isNotBlank() -> s.noResults
                            filter == ChatFilter.UNREAD -> s.noUnread
                            filter == ChatFilter.FAVORITES -> s.noFavorites
                            filter == ChatFilter.GROUPS -> s.noGroups
                            filter == ChatFilter.ARCHIVED -> s.noArchived
                            else -> s.noChats
                        },
                    )
                }
                else -> items(list, key = { it.id }) { t ->
                    val hasStatus = !t.isGroup && t.other.uid in withStatus
                    ThreadRow(
                        t, me, s,
                        onAvatar = { if (t.other.uid == CLAUDE_BOT_UID) controller.openProfile(t.other) else peek = t.other },
                        actions = threadActions(controller, s, t, now) { muteFor = t },
                        now = now,
                        ring = if (hasStatus) (if (t.other.uid in unseen) RingUnseen else RingSeen) else null,
                        listening = if (t.isGroup) null else listeningNow[t.other.uid],
                        onListening = { listeningNow[t.other.uid]?.let { controller.openShare(it) } },
                        tick = rememberListTick(controller, t, me),
                    ) { controller.openConversation(t.other) }
                }
            }
        }
        HomeFab(QMusicIcons.Edit, s.newChat, Modifier.align(Alignment.BottomEnd)) { controller.openNewChat() }
    }
    muteFor?.let { t -> MuteChoice(controller, s, t, now) { muteFor = null } }
    cameraShot?.let { shot ->
        StatusMediaComposer(s, image = shot, video = null, songs = controller.shareables, onCancel = { cameraShot = null }) { caption, edited, extras ->
            controller.postPhotoStatus(edited ?: shot, caption, extras)
            cameraShot = null
            controller.selectTab(ChatTab.STATUS)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.deleteAccount, color = TextPrimary) },
            text = { Text(s.deleteAccountConfirm, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; controller.deleteAccount() }) { Text(s.delete, color = Ink) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

/** A message found by searching: who, where, when, with the words searched in bold. */
@Composable
private fun FoundRow(f: ChatController.FoundMessage, query: String, me: ChatUser, s: ChatStrings, onClick: () -> Unit) {
    val text = f.message.text
    val at = text.indexOf(query, ignoreCase = true)
    // A little before the words found, so they show even in a long message.
    val from = if (at > 30) at - 20 else 0
    val shown = (if (from > 0) "…" else "") + text.substring(from).replace('\n', ' ')
    val start = if (at >= 0) at - from + (if (from > 0) 1 else 0) else -1
    val mine = if (f.message.from == me.uid) s.you + ": " else ""
    val styled = androidx.compose.ui.text.buildAnnotatedString {
        append(mine)
        append(shown)
        if (start >= 0 && start + query.length <= shown.length) {
            addStyle(androidx.compose.ui.text.SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold), mine.length + start, mine.length + start + query.length)
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(f.thread.other, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.thread.other.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(listTime(f.message.at, s), color = TextMuted, fontSize = 12.sp)
            }
            Text(styled, color = TextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A conversation's hold menu: pin, favourite, mute, archive. */
private fun threadActions(controller: ChatController, s: ChatStrings, t: ChatThread, now: Long, onMute: () -> Unit): List<Pair<String, () -> Unit>> = listOf(
    (if (t.pinned > 0) s.unpin else s.pin) to { controller.setPinned(t, t.pinned == 0L) },
    (if (t.favorite) s.removeFavorite else s.addFavorite) to { controller.setFavorite(t, !t.favorite) },
    (if (t.muted(now)) s.unmute else s.mute) to { if (t.muted(now)) controller.setMuted(t.other, 0L) else onMute() },
    (if (t.archived) s.unarchive else s.archive) to { controller.setArchived(t, !t.archived) },
    s.lockChat to { controller.setLocked(t, true) },
)

@Composable
private fun MuteChoice(controller: ChatController, s: ChatStrings, t: ChatThread, now: Long, onDone: () -> Unit) {
    ChoiceDialog(
        s.mute,
        listOf(
            s.mute8h to { controller.setMuted(t.other, now + 8 * 3600_000L) },
            s.mute1w to { controller.setMuted(t.other, now + 7 * 24 * 3600_000L) },
            s.muteAlways to { controller.setMuted(t.other, -1L) },
        ),
        s,
    ) { onDone() }
}

// ------------------------------------------------------------------ groups

@Composable
private fun GroupsTab(controller: ChatController, s: ChatStrings, me: ChatUser, onExit: () -> Unit, onGroupCall: ((ChatUser, Boolean) -> Unit)? = null) {
    var peek by remember { mutableStateOf<ChatUser?>(null) }
    peek?.let { who ->
        AvatarPeek(
            user = who,
            s = s,
            hasStatus = false,
            onMessage = { controller.openConversation(who) },
            onCall = onGroupCall?.let { f -> { video: Boolean -> f(who, video) } },
            onInfo = { controller.openGroupInfo(who) },
            onStatus = {},
        ) { peek = null }
    }
    val all by controller.threads.collectAsState()
    val archived by controller.archived.collectAsState()
    var search by remember { mutableStateOf("") }
    var muteFor by remember { mutableStateOf<ChatThread?>(null) }
    val now = rememberNow()
    val groups = remember(all, archived, search) {
        val q = search.trim()
        (all.orEmpty() + archived).filter { it.isGroup && (q.isEmpty() || it.other.name.contains(q, ignoreCase = true)) }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            stickyHeader(key = "title") { HomeTitle(s.tabGroups, onExit) }
            item(key = "search") { SearchPill(search, { search = it }, s.search) }
            item(key = "create") {
                Row(
                    Modifier.fillMaxWidth().clickable { controller.openNewGroup() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Ink), contentAlignment = Alignment.Center) {
                        Icon(QMusicIcons.Add, contentDescription = null, tint = InkInverse, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.createGroup, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.createGroupNote, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            if (groups.isNotEmpty()) item(key = "head") { SectionTitle(s.yourGroups) }
            if (all != null && groups.isEmpty()) item(key = "empty") { EmptyNote(if (search.isNotBlank()) s.noResults else s.noGroups) }
            items(groups, key = { it.id }) { t ->
                ThreadRow(
                    t, me, s,
                    onAvatar = { peek = t.other },
                    actions = threadActions(controller, s, t, now) { muteFor = t },
                    now = now,
                    tick = rememberListTick(controller, t, me),
                ) { controller.openConversation(t.other) }
            }
        }
        HomeFab(QMusicIcons.Group, s.newGroup, Modifier.align(Alignment.BottomEnd)) { controller.openNewGroup() }
    }
    muteFor?.let { t -> MuteChoice(controller, s, t, now) { muteFor = null } }
}

// ------------------------------------------------------------------ calls

/** Calls in a row with the same person, way and outcome on the same day show as one line: "Name (3)". */
private data class CallRun(val entries: List<CallLogEntry>) {
    val first: CallLogEntry get() = entries.first()
}

private fun runsOf(calls: List<CallLogEntry>): List<CallRun> {
    val out = mutableListOf<MutableList<CallLogEntry>>()
    for (c in calls) {
        val last = out.lastOrNull()?.firstOrNull()
        val same = last != null && last.peer.uid == c.peer.uid && last.direction == c.direction &&
            (last.outcome == CallOutcome.MISSED) == (c.outcome == CallOutcome.MISSED) && listTime(last.at, ChatStrings.Turkish) == listTime(c.at, ChatStrings.Turkish)
        if (same) out.last().add(c) else out.add(mutableListOf(c))
    }
    return out.map { CallRun(it) }
}

internal fun callWay(c: CallLogEntry, s: ChatStrings): String = when {
    c.direction == CallDirection.GROUP -> s.callGroupShort
    c.outcome == CallOutcome.MISSED -> s.callMissedShort
    c.outcome == CallOutcome.DECLINED -> s.callDeclinedShort
    c.outcome == CallOutcome.NOT_ANSWERED -> s.callNotAnswered
    c.direction == CallDirection.IN -> s.callIn
    else -> s.callOut
}

/** ↙ came in, ↗ went out; red when missed. */
@Composable
internal fun CallArrow(c: CallLogEntry) {
    if (c.direction == CallDirection.GROUP) {
        Icon(QMusicIcons.Group, contentDescription = null, tint = TextSecondary, modifier = Modifier.padding(end = 4.dp).size(14.dp))
    } else {
        DirectionArrow(incoming = c.direction == CallDirection.IN, colour = if (c.outcome == CallOutcome.MISSED) CallRed else TickRead)
    }
}

/** A small drawn arrow: down-left for a call that came in, up-right for one that went out. */
@Composable
internal fun DirectionArrow(incoming: Boolean, colour: Color) {
    androidx.compose.foundation.Canvas(Modifier.padding(end = 5.dp).size(12.dp)) {
        val w = size.width
        val stroke = w * 0.15f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val tip = if (incoming) androidx.compose.ui.geometry.Offset(w * 0.18f, w * 0.82f) else androidx.compose.ui.geometry.Offset(w * 0.82f, w * 0.18f)
        val tail = if (incoming) androidx.compose.ui.geometry.Offset(w * 0.82f, w * 0.18f) else androidx.compose.ui.geometry.Offset(w * 0.18f, w * 0.82f)
        drawLine(colour, tail, tip, strokeWidth = stroke, cap = cap)
        // The head: two short strokes along the sides of the square from the tip.
        val dx = if (incoming) w * 0.42f else -w * 0.42f
        drawLine(colour, tip, androidx.compose.ui.geometry.Offset(tip.x + dx, tip.y), strokeWidth = stroke, cap = cap)
        drawLine(colour, tip, androidx.compose.ui.geometry.Offset(tip.x, tip.y - dx), strokeWidth = stroke, cap = cap)
    }
}

@Composable
private fun CallsTab(
    controller: ChatController,
    s: ChatStrings,
    me: ChatUser,
    onExit: () -> Unit,
    onCall: ((ChatUser, Boolean) -> Unit)?,
    onGroupCall: ((ChatUser, Boolean) -> Unit)?,
    /** On screen (not only ready beside the one shown). */
    shown: Boolean = true,
) {
    val calls by controller.callLog.collectAsState()
    val threads by controller.threads.collectAsState()
    var search by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    val share = com.qmusic.ui.rememberShare()
    val runs = remember(calls, search) {
        val q = search.trim()
        runsOf(if (q.isEmpty()) calls else calls.filter { it.peer.name.contains(q, ignoreCase = true) || it.peer.username.contains(q, ignoreCase = true) })
    }
    val favorites = remember(threads) { threads.orEmpty().filter { it.favorite && !it.isGroup } }
    // Looking at the tab clears its badge; new missed calls while on it too. (Ready beside another tab, it did
    // this too and made itself the tab: the chats jumped to the calls.)
    LaunchedEffect(shown, calls.firstOrNull()?.id) { if (shown) controller.markCallsSeen() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            stickyHeader(key = "title") {
                HomeTitle(s.tabCalls, onExit) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(QMusicIcons.More, contentDescription = null, tint = Ink) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = DarkSurfaceVariant) {
                            DropdownMenuItem(text = { Text(s.callLink, color = TextPrimary) }, onClick = { menu = false; share(s.callLinkText + ": " + qeyfCallLink(me.username)) })
                            DropdownMenuItem(text = { Text(s.clearCalls, color = TextPrimary) }, enabled = calls.isNotEmpty(), onClick = { menu = false; confirmClear = true })
                            DropdownMenuItem(text = { Text(s.settings, color = TextPrimary) }, onClick = { menu = false; controller.openSettings() })
                        }
                    }
                }
            }
            item(key = "search") { SearchPill(search, { search = it }, s.search) }
            item(key = "quick") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(s.quickCall, QMusicIcons.Phone) { controller.push(ChatRoute.NewCall(group = false)) }
                    QuickAction(s.quickGroupCall, QMusicIcons.Group) { controller.push(ChatRoute.NewCall(group = true)) }
                    QuickAction(s.quickFavorites, QMusicIcons.Heart, selected = showFavorites) { showFavorites = !showFavorites }
                }
            }
            if (showFavorites) {
                if (favorites.isEmpty()) {
                    item(key = "favEmpty") { EmptyNote(s.noFavorites) }
                } else {
                    items(favorites, key = { "fav_" + it.id }) { t -> CallPersonRow(t.other, s, onCall, onOpen = { controller.push(ChatRoute.CallDetails(t.other)) }) }
                }
            }
            if (runs.isNotEmpty()) item(key = "recent") { SectionTitle(s.recentCalls) }
            if (calls.isEmpty()) item(key = "empty") { EmptyNote(s.callsEmpty) }
            items(runs, key = { it.first.id }) { run ->
                val c = run.first
                val missed = c.outcome == CallOutcome.MISSED
                val group = c.direction == CallDirection.GROUP
                Row(
                    Modifier.fillMaxWidth().clickable { controller.push(ChatRoute.CallDetails(c.peer)) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatAvatar(c.peer, 50.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.peer.name + if (run.entries.size > 1) " (${run.entries.size})" else "",
                            color = if (missed) CallRed else TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CallArrow(c)
                            Text(listTime(c.at, s) + " " + clock(c.at), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    IconButton(onClick = {
                        when {
                            group -> onGroupCall?.invoke(c.peer, c.video) ?: controller.openConversation(c.peer)
                            onCall != null -> onCall(c.peer, c.video)
                            else -> controller.openConversation(c.peer)
                        }
                    }) { Icon(if (c.video) QMusicIcons.Camera else QMusicIcons.Phone, contentDescription = if (c.video) s.videoCall else s.voiceCall, tint = Ink, modifier = Modifier.size(22.dp)) }
                }
            }
        }
        HomeFab(QMusicIcons.Phone, s.quickCall, Modifier.align(Alignment.BottomEnd)) { controller.push(ChatRoute.NewCall(group = false)) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = DarkSurfaceVariant,
            title = { Text(s.clearCalls, color = TextPrimary) },
            text = { Text(s.clearCallsConfirm, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmClear = false; controller.clearCallLog() }) { Text(s.delete, color = CallRed) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(s.cancel, color = TextSecondary) } },
        )
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (selected) Ink else DarkSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (selected) InkInverse else Ink, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
}

/** A person with a voice and a video button. */
@Composable
private fun CallPersonRow(user: ChatUser, s: ChatStrings, onCall: ((ChatUser, Boolean) -> Unit)?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(user, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(user.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (user.username.isNotEmpty()) Text("@" + user.username, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        if (onCall != null && user.uid != CLAUDE_BOT_UID) {
            IconButton(onClick = { onCall(user, false) }) { Icon(QMusicIcons.Phone, contentDescription = s.voiceCall, tint = Ink, modifier = Modifier.size(22.dp)) }
            IconButton(onClick = { onCall(user, true) }) { Icon(QMusicIcons.Camera, contentDescription = s.videoCall, tint = Ink, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** Who to call: friends (voice or video), or a group. */
@Composable
internal fun NewCallPage(
    controller: ChatController,
    s: ChatStrings,
    group: Boolean,
    onCall: ((ChatUser, Boolean) -> Unit)?,
    onGroupCall: ((ChatUser, Boolean) -> Unit)?,
) {
    val friends by controller.friends.collectAsState()
    val threads by controller.threads.collectAsState()
    var search by remember { mutableStateOf("") }
    val people = remember(friends, threads, group, search) {
        val q = search.trim()
        val base = if (group) threads.orEmpty().filter { it.isGroup }.map { it.other } else friends.filter { it.uid != CLAUDE_BOT_UID }
        base.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }.sortedBy { it.name.lowercase() }
    }
    Column(Modifier.fillMaxSize().background(BgRoot)) {
        TopBar(if (group) s.pickGroupToCall else s.pickToCall, { controller.pop() })
        SearchPill(search, { search = it }, s.search)
        if (people.isEmpty()) {
            EmptyNote(if (search.isNotBlank()) s.noResults else if (group) s.noGroups else s.noFriends)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(people, key = { it.uid }) { user ->
                    val call: ((ChatUser, Boolean) -> Unit)? = if (group) onGroupCall else onCall
                    CallPersonRow(user, s, call?.let { c -> { u: ChatUser, v: Boolean -> controller.pop(); c(u, v) } }, onOpen = {
                        if (group) controller.openConversation(user) else controller.push(ChatRoute.CallDetails(user))
                    })
                }
            }
        }
    }
}

/** One person's calls: their picture, message / voice / video, and each call with how long it lasted. */
@Composable
internal fun CallDetailsPage(controller: ChatController, s: ChatStrings, peer: ChatUser, onCall: ((ChatUser, Boolean) -> Unit)?, onGroupCall: ((ChatUser, Boolean) -> Unit)?) {
    val calls by controller.callLog.collectAsState()
    val theirs = remember(calls, peer.uid) { calls.filter { it.peer.uid == peer.uid } }
    val call: ((ChatUser, Boolean) -> Unit)? = if (peer.isGroup) onGroupCall else onCall
    LazyColumn(Modifier.fillMaxSize().background(BgRoot), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar(s.callDetails, { controller.pop() }) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(CircleShape).clickable { if (peer.isGroup) controller.openGroupInfo(peer) else controller.openProfile(peer) }) { ChatAvatar(peer, 104.dp) }
                Spacer(Modifier.height(12.dp))
                Text(peer.name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (peer.username.isNotEmpty()) Text("@" + peer.username, color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailAction(s.message, QMusicIcons.Chat) { controller.openConversation(peer) }
                    if (call != null && peer.uid != CLAUDE_BOT_UID) {
                        DetailAction(s.voiceCall, QMusicIcons.Phone) { call(peer, false) }
                        DetailAction(s.videoCall, QMusicIcons.Camera) { call(peer, true) }
                    }
                }
            }
        }
        if (theirs.isNotEmpty()) item { SectionTitle(s.recentCalls) }
        items(theirs, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CallArrow(c)
                Column(Modifier.weight(1f)) {
                    Text(callWay(c, s) + " · " + (if (c.video) s.videoCall else s.voiceCall), color = if (c.outcome == CallOutcome.MISSED) CallRed else TextPrimary, fontSize = 15.sp)
                    Text(listTime(c.at, s) + " " + clock(c.at), color = TextSecondary, fontSize = 13.sp)
                }
                if (c.seconds > 0) Text(duration(c.seconds), color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DetailAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(DarkSurfaceVariant).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The ticks of our last message in a one-to-one conversation of the list: read (green), on their phone (two grey),
 * or only sent (one); null when the last message is not ours (or it is a group, or Claude).
 */
@Composable
private fun rememberListTick(controller: ChatController, t: ChatThread, me: ChatUser): TickState? {
    if (t.lastFrom != me.uid || t.other.uid == CLAUDE_BOT_UID) return null
    if (t.isGroup) {
        // A group: green once every other member read it, two grey once it is on all their phones.
        val group by remember(t.id) { controller.group(t.other) }.collectAsState(initial = null)
        val read by remember(t.id) { controller.readMarks(t.other) }.collectAsState(initial = emptyMap())
        val delivered by remember(t.id) { controller.deliveredMarks(t.other) }.collectAsState(initial = emptyMap())
        val others = group?.members?.map { it.user.uid }?.filter { it != me.uid && it != CLAUDE_BOT_UID }.orEmpty()
        if (others.isEmpty()) return TickState.SENT
        return when {
            others.all { (read[it] ?: 0L) >= t.lastAt } -> TickState.READ
            others.all { (read[it] ?: 0L) >= t.lastAt || (delivered[it] ?: 0L) >= t.lastAt } -> TickState.DELIVERED
            else -> TickState.SENT
        }
    }
    val read by remember(t.id) { controller.otherReadAt(t.other) }.collectAsState(initial = 0L)
    val delivered by remember(t.id) { controller.otherDeliveredAt(t.other) }.collectAsState(initial = 0L)
    return when {
        read >= t.lastAt -> TickState.READ
        delivered >= t.lastAt -> TickState.DELIVERED
        else -> TickState.SENT
    }
}

/**
 * A person's (or a group's) picture, opened from the list (as on WhatsApp): the picture large with the name over it,
 * and under it message, voice and video call, and "i" for their profile. A tap on the picture opens their status
 * when they have one.
 */
@Composable
private fun AvatarPeek(
    user: ChatUser,
    s: ChatStrings,
    hasStatus: Boolean,
    onMessage: () -> Unit,
    onCall: ((Boolean) -> Unit)?,
    onInfo: () -> Unit,
    onStatus: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The picture tapped: full screen, to zoom with two fingers.
    var full by remember { mutableStateOf(false) }
    if (full && user.photo != null) {
        ZoomablePhoto(user.photo, user.name) { full = false }
        return
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(280.dp).clip(RoundedCornerShape(18.dp)).background(DarkSurfaceVariant)) {
            Box(
                Modifier.size(280.dp).background(DarkSurface).clickable(enabled = user.photo != null || hasStatus) {
                    if (user.photo != null) {
                        full = true
                    } else {
                        onDismiss()
                        onStatus()
                    }
                },
            ) {
                if (user.photo != null) {
                    coil3.compose.AsyncImage(
                        model = user.photo,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChatAvatar(user, 160.dp) }
                }
                // The name, over a dark fade at the top of the picture.
                Box(
                    Modifier.fillMaxWidth().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                    ).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 22.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(user.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (!user.isGroup) UserBadge(user.uid, compact = true, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (hasStatus) {
                    // Their status, from the corner of the picture.
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp).size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable {
                            onDismiss()
                            onStatus()
                        },
                        contentAlignment = Alignment.Center,
                    ) { StatusGlyph(Color.White, 22.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                PeekButton({ Icon(QMusicIcons.Chat, contentDescription = s.message, tint = TickRead, modifier = Modifier.size(24.dp)) }) { onDismiss(); onMessage() }
                if (onCall != null) {
                    PeekButton({ Icon(QMusicIcons.Phone, contentDescription = s.callVoice, tint = TickRead, modifier = Modifier.size(24.dp)) }) { onDismiss(); onCall(false) }
                    PeekButton({ Icon(QMusicIcons.Camera, contentDescription = s.callVideo, tint = TickRead, modifier = Modifier.size(24.dp)) }) { onDismiss(); onCall(true) }
                }
                PeekButton({
                    Box(Modifier.size(22.dp).border(2.dp, TickRead, CircleShape), contentAlignment = Alignment.Center) {
                        Text("i", color = TickRead, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                }) { onDismiss(); onInfo() }
            }
        }
    }
}

@Composable
private fun PeekButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { icon() }
}
