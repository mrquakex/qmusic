package com.qmusic.ui.chat

import com.qmusic.ui.motion.pressScale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

// ==================================================================== the map itself

/** Someone (or a place) on the live map. [trail]: the way they came; [bearing]: where they head (an arrow). */
class LiveMapPin(
    val key: String,
    val lat: Double,
    val lng: Double,
    val user: ChatUser?,
    val live: Boolean,
    val accuracy: Double? = null,
    val bearing: Float? = null,
    val trail: List<Pair<Double, Double>> = emptyList(),
    val me: Boolean = false,
)

/** Moves a [LiveMap]'s camera from outside; asked before the map is ready, it happens once it is. */
class LiveMapControl {
    interface Camera {
        fun move(lat: Double, lng: Double, zoom: Double?, animate: Boolean)
        fun fit(points: List<Pair<Double, Double>>)
    }

    private var pending: ((Camera) -> Unit)? = null

    internal var camera: Camera? = null
        set(value) {
            field = value
            if (value != null) pending?.let { action -> pending = null; action(value) }
        }

    fun moveTo(lat: Double, lng: Double, zoom: Double? = null, animate: Boolean = true) = act { it.move(lat, lng, zoom, animate) }
    fun fit(points: List<Pair<Double, Double>>) = act { it.fit(points) }

    private fun act(action: (Camera) -> Unit) {
        val c = camera
        if (c != null) action(c) else pending = action
    }
}

/**
 * A real map (pinch to zoom, turn, fling) with [pins] on it: each glides to its new place, with its accuracy halo, its
 * heading and the way it came. [onGesture]: the person moved the map by hand (stop following someone); [onCenter]: where
 * the middle of the map is once it settles. [fitBottom]: room kept free at the foot when everyone is fitted in.
 */
@Composable
internal expect fun LiveMap(
    pins: List<LiveMapPin>,
    control: LiveMapControl,
    start: Pair<Double, Double>,
    startZoom: Double,
    modifier: Modifier,
    fitBottom: Dp,
    onGesture: () -> Unit,
    onPinTap: (String) -> Unit,
    onCenter: (Double, Double) -> Unit,
)

/** A still, sharp picture of the map around a place (a chat's card); [zoom] as [LiveMap]'s. */
@Composable
internal expect fun MapPreview(lat: Double, lng: Double, zoom: Double, modifier: Modifier)

internal val LiveGreen = Color(0xFF1DAA61)
internal val MeBlue = Color(0xFF2F80ED)

/**
 * One person on the map: their picture in a white ring, a pulse while live, an arrow on the ring where they head; it
 * pops in when it first shows. [mapBearing]: how far the map is turned (the arrow keeps pointing the true way).
 */
@Composable
internal fun LivePinView(pin: LiveMapPin, mapBearing: Float, modifier: Modifier = Modifier) {
    val color = if (pin.me) MeBlue else LiveGreen
    val popped = remember { Animatable(0f) }
    LaunchedEffect(Unit) { popped.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }
    Box(
        modifier.size(PIN_SIZE).graphicsLayer {
            scaleX = popped.value
            scaleY = popped.value
        },
        contentAlignment = Alignment.Center,
    ) {
        if (pin.live) {
            val pulse by rememberInfiniteTransition(label = "pinPulse").animateFloat(
                0.55f, 1.2f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "pinPulseScale",
            )
            Box(
                Modifier.size(PIN_SIZE).graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                    alpha = (1.25f - pulse).coerceIn(0f, 0.55f)
                }.clip(CircleShape).background(color),
            )
        }
        // The way they head: a small arrow riding the ring.
        val bearing = pin.bearing
        if (bearing != null && pin.live) {
            Canvas(Modifier.size(PIN_SIZE).rotate(bearing - mapBearing)) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w / 2, 0f)
                    lineTo(w / 2 - w * 0.13f, w * 0.2f)
                    lineTo(w / 2 + w * 0.13f, w * 0.2f)
                    close()
                }
                drawPath(path, color)
            }
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Color.White).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (pin.user != null) ChatAvatar(pin.user, 36.dp)
            else Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFE53935)))
        }
        if (!pin.live) {
            // Over: a grey dot on the picture.
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(12.dp).clip(CircleShape).background(Color(0xFF9E9E9E)).border(2.dp, Color.White, CircleShape))
        }
    }
}

internal val PIN_SIZE = 64.dp

// ==================================================================== a conversation's live map

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/** "34 km/sa" from metres a second; nothing while (nearly) still. */
private fun speedLabel(mps: Float?, english: Boolean): String? {
    if (mps == null || mps < 1.4f) return null
    val kmh = (mps * 3.6f).roundToInt()
    return if (english) "$kmh km/h" else "$kmh km/sa"
}

private fun batteryLabel(pct: Int?, english: Boolean): String? = pct?.let { if (english) "battery $it%" else "pil %$it" }

private fun agoShort(ms: Long, english: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> if (english) "just now" else "şimdi"
        s < 60 -> if (english) "${s}s ago" else "$s sn önce"
        s < 3600 -> if (english) "${s / 60} min ago" else "${s / 60} dk önce"
        else -> if (english) "${s / 3600} h ago" else "${s / 3600} sa önce"
    }
}

private fun leftShort(ms: Long, english: Boolean): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return if (english) (if (h > 0) "$h h " else "") + (if (m > 0 || h == 0L) "$m min " else "") + "left"
    else (if (h > 0) "$h sa " else "") + (if (m > 0 || h == 0L) "$m dk " else "") + "kaldı"
}

/**
 * Everyone sharing in a conversation, full screen on a real map. It opens with the map growing in and the panel
 * sliding up, and leaves the same way. A tap on someone (on the map or in the panel) flies to them and follows them as
 * they move, until the map is moved by hand. The panel says for each how long it goes on, when it was last heard, how
 * far, how fast and their battery; directions to them in one tap; the sharer can stop.
 */
@Composable
internal fun LiveMapScreen(
    chatId: String?,
    focus: Pair<Double, Double>,
    people: List<Pair<ChatUser, LiveLocation>>,
    myUid: String,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val english = ChatMusic.english
    var closing by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { closing = true },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogEdgeToEdge()
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        LaunchedEffect(closing) {
            if (closing) {
                shown = false
                delay(260)
                onDismiss()
            }
        }
        val appear by animateFloatAsState(if (shown) 1f else 0f, tween(300, easing = FastOutSlowInEasing), label = "liveMapAppear")
        var clock by remember { mutableStateOf(nowMs()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                clock = nowMs()
            }
        }
        val control = remember { LiveMapControl() }
        // Whom the camera follows: who was tapped on (the one opened at, first).
        var following by remember {
            mutableStateOf(people.minByOrNull { (_, l) -> distanceMeters(focus.first, focus.second, l.lat, l.lng) }?.first?.uid)
        }
        var center by remember { mutableStateOf(focus) }
        val followed = people.firstOrNull { it.first.uid == following }?.second
        LaunchedEffect(following, followed?.lat, followed?.lng) {
            followed?.let { control.moveTo(it.lat, it.lng, null, animate = true) }
        }
        val pins = people.map { (u, l) ->
            val live = l.active(clock)
            LiveMapPin(u.uid, l.lat, l.lng, u, live, l.accuracy, l.bearing, if (live) l.trail else emptyList(), me = u.uid == myUid)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f * appear))) {
            LiveMap(
                pins = pins,
                control = control,
                start = focus,
                startZoom = 15.5,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = appear
                    val s = 0.92f + 0.08f * appear
                    scaleX = s
                    scaleY = s
                },
                fitBottom = 300.dp,
                onGesture = { following = null },
                onPinTap = { uid ->
                    following = uid
                    people.firstOrNull { it.first.uid == uid }?.second?.let { control.moveTo(it.lat, it.lng, 16.5, animate = true) }
                },
                onCenter = { la, ln -> center = la to ln },
            )
            // Top: back, and what this is.
            Row(
                Modifier.statusBarsPadding().padding(12.dp).graphicsLayer { alpha = appear; translationY = (1f - appear) * -40f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapRoundButton({ Icon(QMusicIcons.Back, contentDescription = null, tint = Color.Black) }) { closing = true }
                Spacer(Modifier.width(10.dp))
                val sharingNow = people.count { it.second.active(clock) }
                Column(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(if (english) "Live location" else "Canlı konum", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (english) "$sharingNow sharing" else "$sharingNow kişi paylaşıyor",
                        color = Color.Black.copy(alpha = 0.6f), fontSize = 12.sp,
                    )
                }
            }
            // Right: show everyone, and me.
            Column(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).graphicsLayer { alpha = appear },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (people.size > 1) {
                    MapRoundButton({ Text(if (english) "All" else "Hepsi", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }) {
                        following = null
                        control.fit(people.map { it.second.lat to it.second.lng })
                    }
                }
                val mine = people.firstOrNull { it.first.uid == myUid }?.second
                if (mine != null) {
                    MapRoundButton({ Icon(QMusicIcons.Location, contentDescription = null, tint = MeBlue, modifier = Modifier.size(22.dp)) }) {
                        following = myUid
                        control.moveTo(mine.lat, mine.lng, 16.5, animate = true)
                    }
                }
            }
            // The panel: slides up.
            LivePanel(
                people = people,
                myUid = myUid,
                clock = clock,
                following = following,
                chatId = chatId,
                center = center,
                onPick = { uid ->
                    following = uid
                    people.firstOrNull { it.first.uid == uid }?.second?.let { control.moveTo(it.lat, it.lng, 16.5, animate = true) }
                },
                onStop = onStop,
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = (1f - appear) * size.height },
            )
        }
    }
}

/** Under the map: each person sharing, with what is known of them, and what can be done. */
@Composable
private fun LivePanel(
    people: List<Pair<ChatUser, LiveLocation>>,
    myUid: String,
    clock: Long,
    following: String?,
    chatId: String?,
    center: Pair<Double, Double>,
    onPick: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val english = ChatMusic.english
    val mine = people.firstOrNull { it.first.uid == myUid }?.second
    // The others first, the sharing ones before the ended; me last.
    val ordered = people.sortedWith(compareBy<Pair<ChatUser, LiveLocation>> { it.first.uid == myUid }.thenBy { !it.second.active(clock) })
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(DarkSurfaceVariant)
            .navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(TextSecondary.copy(alpha = 0.4f)))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            ordered.forEach { (user, l) ->
                val active = l.active(clock)
                val isMe = user.uid == myUid
                val selected = following == user.uid
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(user.uid) }
                        .background(if (selected) Ink.copy(alpha = 0.06f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).border(2.dp, if (active) (if (isMe) MeBlue else LiveGreen) else TextSecondary.copy(alpha = 0.4f), CircleShape).padding(3.dp),
                        contentAlignment = Alignment.Center,
                    ) { ChatAvatar(user, 42.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isMe) (if (english) "You" else "Sen") else user.name,
                            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                if (active) leftShort(l.until - clock, english) else (if (english) "ended" else "sona erdi"),
                                agoShort(clock - l.at, english),
                            ).joinToString(" · "),
                            color = if (active) (if (isMe) MeBlue else LiveGreen) else TextSecondary, fontSize = 12.sp,
                        )
                        val more = listOfNotNull(
                            if (mine != null && !isMe) distanceLabel(distanceMeters(mine.lat, mine.lng, l.lat, l.lng), english) else null,
                            if (active) speedLabel(l.speed, english) else null,
                            batteryLabel(l.battery, english),
                        )
                        if (more.isNotEmpty()) Text(more.joinToString(" · "), color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isMe && active) {
                        PanelPill(if (english) "Stop" else "Durdur", Color(0xFFE53935), onStop)
                    } else if (!isMe) {
                        PanelPill(if (english) "Directions" else "Yol tarifi", TextPrimary) { LiveShare.directions(l.lat, l.lng, user.name) }
                    }
                }
            }
        }
        // Sharing here: "let them know when I arrive" (home, or the middle of the map), and keeping a home.
        if (chatId != null && mine != null && mine.active(clock)) {
            val arrivals by LiveShare.arrivals.collectAsState()
            var homeTick by remember { mutableStateOf(0) }
            val home = remember(homeTick) { LiveShare.home() }
            val target = arrivals[chatId]
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (target != null) {
                    item { PanelChip((if (english) "Will tell when you arrive" else "Varınca haber verilecek") + if (target.isNotBlank()) ": $target" else "", strong = true) {} }
                    item { PanelChip(if (english) "Cancel" else "İptal") { LiveShare.cancelArrival(chatId) } }
                } else {
                    if (home != null) item {
                        PanelChip(if (english) "Tell them when I'm home" else "Eve varınca haber ver") {
                            LiveShare.arriveAt(chatId, home.first, home.second, if (english) "home" else "eve")
                        }
                    }
                    item {
                        PanelChip(if (english) "Tell them when I reach the map's middle" else "Haritanın ortasına varınca haber ver") {
                            LiveShare.arriveAt(chatId, center.first, center.second, "")
                        }
                    }
                }
                item {
                    PanelChip(if (english) "Save where I am as home" else "Bulunduğum yeri ev olarak kaydet") {
                        LiveShare.saveHome(mine.lat, mine.lng)
                        homeTick++
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelPill(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(TextSecondary.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun PanelChip(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (strong) InkInverse else TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (strong) Ink else DarkSurfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
internal fun MapRoundButton(content: @Composable () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).pressScale(0.9f).clip(CircleShape).background(Color.White).border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ==================================================================== everyone, from every chat

/**
 * Everyone sharing their live location with this person now, from every conversation, on one map (a family's,
 * friends'): who, from which chat, how far; a tap flies to them and follows them.
 */
@Composable
internal fun LivePeoplePage(controller: ChatController, me: ChatUser) {
    val english = ChatMusic.english
    val open by SecretSpace.unlocked.collectAsState()
    val people by remember(open) { controller.livePeople(open) }.collectAsState(initial = emptyList())
    var clock by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            clock = nowMs()
        }
    }
    val active = people.filter { it.second.active(clock) }
    val mine = active.firstOrNull { it.second.uid == me.uid }?.second
    val others = active.filter { it.second.uid != me.uid }.distinctBy { it.second.uid }
    val all = others.map { it.second } + listOfNotNull(mine)
    Column(Modifier.fillMaxSize().background(com.qmusic.ui.theme.BgRoot)) {
        TopBar(if (english) "Live locations" else "Canlı konumlar", { controller.pop() })
        if (all.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(QMusicIcons.Location, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (english) "No one is sharing their location with you now" else "Şu an kimse seninle konum paylaşmıyor", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (english) "When someone shares their live location in a chat, they show here." else "Biri bir sohbette canlı konumunu paylaşınca burada görünür.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            }
            return@Column
        }
        val control = remember { LiveMapControl() }
        var following by remember { mutableStateOf<String?>(null) }
        // Everyone in view at first.
        val ids = all.map { it.uid }.sorted()
        LaunchedEffect(ids) { control.fit(all.map { it.lat to it.lng }) }
        val followed = all.firstOrNull { it.uid == following }
        LaunchedEffect(following, followed?.lat, followed?.lng) { followed?.let { control.moveTo(it.lat, it.lng, null, animate = true) } }
        fun userOf(l: LiveLocation): ChatUser? =
            if (l.uid == me.uid) me else others.firstOrNull { it.second.uid == l.uid }?.first?.let { t -> if (t.isGroup) ChatUser(l.uid, t.other.name, "", null) else t.other }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LiveMap(
                pins = all.map { l -> LiveMapPin(l.uid, l.lat, l.lng, userOf(l), true, l.accuracy, l.bearing, l.trail, me = l.uid == me.uid) },
                control = control,
                start = all.first().lat to all.first().lng,
                startZoom = 12.0,
                modifier = Modifier.fillMaxSize(),
                fitBottom = 40.dp,
                onGesture = { following = null },
                onPinTap = { uid ->
                    following = uid
                    all.firstOrNull { it.uid == uid }?.let { control.moveTo(it.lat, it.lng, 16.5, animate = true) }
                },
                onCenter = { _, _ -> },
            )
            if (all.size > 1) {
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    MapRoundButton({ Text(if (english) "All" else "Hepsi", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }) {
                        following = null
                        control.fit(all.map { it.lat to it.lng })
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
            others.forEach { (t, l) ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        following = l.uid
                        control.moveTo(l.lat, l.lng, 16.5, animate = true)
                    }.background(if (following == l.uid) Ink.copy(alpha = 0.06f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(46.dp).clip(CircleShape).border(2.dp, LiveGreen, CircleShape).padding(3.dp), contentAlignment = Alignment.Center) {
                        ChatAvatar(t.other, 40.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.other.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                leftShort(l.until - clock, english),
                                agoShort(clock - l.at, english),
                                mine?.let { m -> distanceLabel(distanceMeters(m.lat, m.lng, l.lat, l.lng), english) },
                                speedLabel(l.speed, english),
                                batteryLabel(l.battery, english),
                            ).joinToString(" · "),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                    }
                    PanelPill(if (english) "Chat" else "Sohbet", TextPrimary) { controller.openConversation(t.other) }
                }
            }
        }
    }
}
