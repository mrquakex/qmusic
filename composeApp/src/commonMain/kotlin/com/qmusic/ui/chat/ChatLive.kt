package com.qmusic.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Clock
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where someone sharing their live location is (liveLocations/{chatId}/{uid}); [until]: when the sharing ends. */
data class LiveLocation(
    val uid: String,
    val lat: Double,
    val lng: Double,
    val at: Long,
    val until: Long,
    val ended: Boolean = false,
    /** How sure the phone is of the place, in metres (the halo around the pin). */
    val accuracy: Double? = null,
    /** The way they move, in degrees from north (null while still). */
    val bearing: Float? = null,
    /** Metres per second. */
    val speed: Float? = null,
    /** Their phone's battery, %. */
    val battery: Int? = null,
    /** The way they came since the sharing began. */
    val trail: List<Pair<Double, Double>> = emptyList(),
) {
    fun active(now: Long) = !ended && until > now
}

/**
 * Live location: this phone shares its place in some conversations for a while (a foreground service updates it),
 * and everyone's shared places are watched. The phone's part fills the hooks.
 */
object LiveShare {
    /** The conversations this phone shares its place in now, with when each sharing ends. */
    val sharing = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Starts sharing in [chatId] until [until] (server time; the same as in its message, which is how a message's card
     * knows the sharing is its own). The location permission is asked before.
     */
    var start: (chatId: String, until: Long) -> Unit = { _, _ -> }

    /** Stops sharing in [chatId] (the last place stays, marked over). */
    var stop: (chatId: String) -> Unit = {}

    /** Everyone's live places in [chatId], by uid. */
    var watch: (chatId: String) -> Flow<Map<String, LiveLocation>> = { flowOf(emptyMap()) }

    /** "Let them know when I arrive": the place per conversation (its label), and the home kept for it. */
    val arrivals = MutableStateFlow<Map<String, String>>(emptyMap())
    var arriveAt: (chatId: String, lat: Double, lng: Double, label: String) -> Unit = { _, _, _, _ -> }
    var cancelArrival: (chatId: String) -> Unit = {}
    var home: () -> Pair<Double, Double>? = { null }

    /** Directions to a place, in the phone's maps app. */
    var directions: (lat: Double, lng: Double, label: String) -> Unit = { _, _, _ -> }
    var saveHome: (Double, Double) -> Unit = { _, _ -> }

    val durations = listOf(15 * 60_000L, 60 * 60_000L, 8 * 60 * 60_000L)

    fun durationLabel(ms: Long, english: Boolean): String = when (ms) {
        15 * 60_000L -> if (english) "15 minutes" else "15 dakika"
        60 * 60_000L -> if (english) "1 hour" else "1 saat"
        else -> if (english) "8 hours" else "8 saat"
    }
}

private fun now() = Clock.System.now().toEpochMilliseconds()

/** "12 min left", "1 h 5 min left". */
private fun leftLabel(ms: Long, english: Boolean): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return if (english) {
        (if (h > 0) "$h h " else "") + (if (m > 0 || h == 0L) "$m min " else "") + "left"
    } else {
        (if (h > 0) "$h sa " else "") + (if (m > 0 || h == 0L) "$m dk " else "") + "kaldı"
    }
}

/** "20 s ago", "3 min ago". */
private fun agoLabel(ms: Long, english: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> if (english) "${s}s ago" else "$s sn önce"
        s < 3600 -> if (english) "${s / 60} min ago" else "${s / 60} dk önce"
        else -> if (english) "${s / 3600} h ago" else "${s / 3600} sa önce"
    }
}

/** The distance between two places on the earth, in metres. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180
    val dLng = (lng2 - lng1) * PI / 180
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
}

fun distanceLabel(meters: Double, english: Boolean): String = when {
    meters < 1000 -> "${meters.roundToInt()} m" + if (english) " away" else " uzakta"
    else -> {
        val km = (meters / 100).roundToInt() / 10.0
        "${km.toString().replace('.', if (english) '.' else ',')} km" + if (english) " away" else " uzakta"
    }
}

// ==================================================================== the map

/** A place on the map, with who is there (their picture), and whether it is live (a pulse around it). */
class MapMarker(val lat: Double, val lng: Double, val user: ChatUser?, val live: Boolean)

private const val TILE = 256.0

/** One OpenStreetMap tile, through Nuvelo's server (which keeps them, and can change where they come from). */
internal fun mapTile(zoom: Int, x: Int, y: Int) = "https://qeyf.159.195.147.140.sslip.io/tiles/$zoom/$x/$y.png"

private fun worldX(lng: Double, zoom: Int) = (lng + 180.0) / 360.0 * TILE * (1 shl zoom)
private fun worldY(lat: Double, zoom: Int): Double {
    val r = lat.coerceIn(-85.0, 85.0) * PI / 180.0
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * TILE * (1 shl zoom)
}
private fun latOf(y: Double, zoom: Int): Double {
    val n = PI - 2.0 * PI * y / (TILE * (1 shl zoom))
    return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
}
private fun lngOf(x: Double, zoom: Int) = x / (TILE * (1 shl zoom)) * 360.0 - 180.0

/**
 * A map (OpenStreetMap) centred on [lat], [lng], with [markers] on it; [draggable]: moved by a
 * finger ([onMoved] gets the new centre).
 */
@Composable
fun OsmMap(
    lat: Double,
    lng: Double,
    zoom: Int,
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
    onMoved: (Double, Double) -> Unit = { _, _ -> },
) {
    // Two world pixels (at [zoom]) to a dp.
    val perDp = 2.0
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.background(Color(0xFFE5E3DF)).then(
            if (draggable) {
                Modifier.pointerInput(zoom) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val dx = drag.x / density.density * perDp
                        val dy = drag.y / density.density * perDp
                        onMoved(latOf(worldY(lat, zoom) - dy, zoom), lngOf(worldX(lng, zoom) - dx, zoom))
                    }
                }
            } else {
                Modifier
            },
        ),
    ) {
        val w = maxWidth.value.toDouble()
        val h = maxHeight.value.toDouble()
        val cx = worldX(lng, zoom)
        val cy = worldY(lat, zoom)
        // The tiles one zoom out, each drawn 256 dp wide: the same ground, with the map's writing at its own size.
        val tileZoom = (zoom - 1).coerceAtLeast(0)
        val span = TILE * (1 shl (zoom - tileZoom))
        val n = 1 shl tileZoom
        val firstX = floor((cx - w / 2 * perDp) / span).toInt()
        val lastX = floor((cx + w / 2 * perDp) / span).toInt()
        val firstY = floor((cy - h / 2 * perDp) / span).toInt().coerceAtLeast(0)
        val lastY = floor((cy + h / 2 * perDp) / span).toInt().coerceAtMost(n - 1)
        for (tx in firstX..lastX) for (ty in firstY..lastY) {
            val left = w / 2 + (tx * span - cx) / perDp
            val top = h / 2 + (ty * span - cy) / perDp
            AsyncImage(
                model = mapTile(tileZoom, ((tx % n) + n) % n, ty),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset(left.dp, top.dp).size((span / perDp).dp),
            )
        }
        markers.forEach { m ->
            val x = w / 2 + (worldX(m.lng, zoom) - cx) / perDp
            val y = h / 2 + (worldY(m.lat, zoom) - cy) / perDp
            MapPin(m, Modifier.offset((x - 22).dp, (y - 22).dp))
        }
        Text(
            "© OpenStreetMap",
            color = Color.Black.copy(alpha = 0.6f),
            fontSize = 8.sp,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 3.dp),
        )
    }
}

/** Someone on the map: their picture in a white ring (pulsing while live), or a red pin for a plain place. */
@Composable
private fun MapPin(m: MapMarker, modifier: Modifier) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (m.live) {
            val pulse by rememberInfiniteTransition(label = "livePin").animateFloat(
                0.6f, 1.25f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "livePulse",
            )
            Box(
                Modifier.size(44.dp).graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                    alpha = (1.3f - pulse).coerceIn(0f, 0.6f)
                }.clip(CircleShape).background(Color(0xFF1DAA61)),
            )
        }
        if (m.user != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White).padding(2.dp), contentAlignment = Alignment.Center) {
                ChatAvatar(m.user, 30.dp)
            }
        } else {
            Canvas(Modifier.size(28.dp, 36.dp).offset(y = (-14).dp)) {
                val r = size.width / 2.4f
                val c = Offset(size.width / 2, r + 2f)
                drawCircle(Color(0xFFE53935), radius = r, center = c)
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x - r * 0.7f, c.y + r * 0.6f)
                        lineTo(size.width / 2, size.height)
                        lineTo(c.x + r * 0.7f, c.y + r * 0.6f)
                        close()
                    },
                    Color(0xFFE53935),
                )
                drawCircle(Color.White, radius = r * 0.4f, center = c)
            }
        }
    }
}

// ==================================================================== the live card

/**
 * A live location in a message: the map where they are now (it moves as they do), how long it goes on, when it was
 * last updated; the sharer can stop it here. Over, it shows the last place.
 */
@Composable
internal fun LiveLocationCard(
    place: ChatLocation,
    sender: ChatUser,
    live: LiveLocation?,
    mine: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
) {
    val english = ChatMusic.english
    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            clock = now()
        }
    }
    val own = live?.takeIf { kotlin.math.abs(it.until - place.until) < 5_000 }
    // A later sharing in the chat (another end) leaves this one over, at its first place.
    val until = own?.until ?: place.until
    val active = if (live != null && own == null) false else (own?.ended != true && until > clock)
    val lat = own?.lat ?: place.lat
    val lng = own?.lng ?: place.lng
    val fg = if (mine) InkInverse else TextPrimary
    val soft = if (mine) InkInverse.copy(alpha = 0.6f) else TextSecondary
    Column(Modifier.padding(4.dp).width(260.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        // A sharp picture of the map there, the person on it (pulsing while live).
        Box(Modifier.width(260.dp).height(150.dp), contentAlignment = Alignment.Center) {
            MapPreview(lat, lng, 15.0, Modifier.matchParentSize())
            LivePinView(LiveMapPin(sender.uid, lat, lng, sender, active, me = mine), 0f)
        }
        Row(
            Modifier.fillMaxWidth().background(if (mine) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) Color(0xFF1DAA61) else soft))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (active) (if (english) "Live location" else "Canlı konum") else (if (english) "Live location ended" else "Canlı konum sona erdi"),
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        if (active) leftLabel(until - clock, english) else null,
                        own?.let { (if (english) "updated " else "güncellendi ") + agoLabel(clock - it.at, english) },
                    ).joinToString(" · ").ifEmpty { place.label },
                    color = soft,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (mine && active) {
                Text(
                    if (english) "Stop" else "Durdur",
                    color = Color(0xFFE53935),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onStop).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Choosing how long to share the live location. */
@Composable
internal fun LiveDurationDialog(onPick: (Long) -> Unit, onPlain: () -> Unit, onDismiss: () -> Unit) {
    val english = ChatMusic.english
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant,
        title = { Text(if (english) "Location" else "Konum", color = TextPrimary) },
        text = {
            Column {
                LocationChoice(QMusicIcons.Location, if (english) "Send my current location" else "Şu anki konumu gönder", null) { onDismiss(); onPlain() }
                Spacer(Modifier.height(6.dp))
                Text(if (english) "Share live location for" else "Canlı konum paylaş", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp))
                LiveShare.durations.forEach { d ->
                    LocationChoice(QMusicIcons.Timer, LiveShare.durationLabel(d, english), null) { onDismiss(); onPick(d) }
                }
                Text(
                    if (english) "While it lasts, the people in this chat see where you are; a notification lets you stop it."
                    else "Süre boyunca bu sohbettekiler nerede olduğunu görür; bildirimden istediğin an durdurabilirsin.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(if (english) "Cancel" else "Vazgeç", color = TextSecondary) } },
    )
}

@Composable
private fun LocationChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, note: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF00B86B)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (note != null) Text(note, color = TextSecondary, fontSize = 12.sp)
        }
    }
}
