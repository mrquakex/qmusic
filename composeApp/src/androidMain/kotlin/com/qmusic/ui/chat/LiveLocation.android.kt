package com.qmusic.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * The phone's part of [LiveShare]: the sharings kept (so they survive the app being closed), the service that sends
 * the place while any goes on, and everyone's live places read.
 *
 *   liveLocations/{chatId}/{uid} = {lat, lng, acc, brg, spd, bat, trail, at, until}   (members read, each writes their
 *   own; until <= 8 h). brg: the way it moves (degrees), spd: m/s, bat: the battery %, trail: "lat,lng;lat,lng;..." the
 *   way come since the sharing began (thinned). Sealed (an encrypted chat): e = "lat,lng,acc,brg,spd,bat|trail".
 */
object LiveLocations {
    private const val PREFS = "qeyf_live_location"
    private const val TAG = "QeyfLive"
    private const val DATABASE_URL = "https://qmusic-2552b-default-rtdb.europe-west1.firebasedatabase.app"

    private var app: Context? = null

    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        LiveShare.sharing.value = load(c)
        LiveShare.start = { chatId, until ->
            Trail.clear(c, chatId)
            save(c, load(c) + (chatId to until))
            LiveLocationService.start(c)
        }
        // Directions to someone: the phone's maps app, driving there (or just the place, when it cannot route).
        LiveShare.directions = { lat, lng, label ->
            val route = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=$lat,$lng")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val place = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + android.net.Uri.encode(label) + ")"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { c.startActivity(route) }.onFailure { runCatching { c.startActivity(place) } }
        }
        LiveShare.stop = { chatId -> stop(c, chatId) }
        LiveShare.watch = { chatId -> watch(c, chatId) }
        // Sharings that were going on when the app was closed carry on (the service picks them up).
        if (load(c).isNotEmpty()) LiveLocationService.start(c)
    }

    /** The sharings still going on (the ended ones dropped). Their ends are server time; the phone's clock is near it. */
    fun load(context: Context): Map<String, Long> {
        val now = System.currentTimeMillis() + (FirebaseChatBackend.peek()?.serverOffset?.value ?: 0L)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? Long)?.takeIf { it > now }?.let { k to it } }
            .toMap()
    }

    fun save(context: Context, sharing: Map<String, Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply {
            sharing.forEach { (k, v) -> putLong(k, v) }
        }.apply()
        LiveShare.sharing.value = sharing
    }

    /** Ends the sharing in [chatId]: the last place stays, marked over (its time is now). */
    fun stop(context: Context, chatId: String) {
        save(context, load(context) - chatId)
        Trail.clear(context, chatId)
        mine(context, chatId)?.child("ended")?.setValue(true)
        if (load(context).isEmpty()) context.stopService(Intent(context, LiveLocationService::class.java))
    }

    fun stopAll(context: Context) {
        load(context).keys.forEach { chatId ->
            mine(context, chatId)?.child("ended")?.setValue(true)
            Trail.clear(context, chatId)
        }
        save(context, emptyMap())
        context.stopService(Intent(context, LiveLocationService::class.java))
    }

    private fun db(context: Context): FirebaseDatabase? = runCatching {
        FirebaseChatBackend.get(context) ?: return null
        val configured = FirebaseApp.getInstance().options.databaseUrl
        if (configured.isNullOrBlank()) FirebaseDatabase.getInstance(DATABASE_URL) else FirebaseDatabase.getInstance()
    }.getOrNull()

    fun mine(context: Context, chatId: String): DatabaseReference? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return db(context)?.getReference("liveLocations")?.child(chatId)?.child(uid)
    }

    /** Writes this phone's place to every sharing going on. */
    fun send(context: Context, location: Location) {
        val now = System.currentTimeMillis() + (FirebaseChatBackend.peek()?.serverOffset?.value ?: 0L)
        val battery = runCatching {
            (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()
        val bearing = if (location.hasBearing() && location.hasSpeed() && location.speed > 0.7f) location.bearing.toDouble() else null
        val speed = if (location.hasSpeed()) location.speed.toDouble() else null
        load(context).forEach { (chatId, until) ->
            if (until <= now) return@forEach
            val trail = Trail.add(context, chatId, location.latitude, location.longitude)
            work.launch {
                // An encrypted conversation: the place goes sealed with its key (the server cannot see it).
                val engine = E2eEngine.instance
                val version = engine?.versionNow(chatId)
                val place: Map<String, Any?> = if (version != null) {
                    engine.loadKey(chatId, version) ?: return@launch
                    val plain = listOf(location.latitude, location.longitude, location.accuracy.toDouble(), bearing ?: "", speed ?: "", battery ?: "")
                        .joinToString(",") + "|" + trail
                    val sealed = engine.sealText(chatId, version, plain) ?: return@launch
                    mapOf("e" to sealed, "at" to ServerValue.TIMESTAMP, "until" to until)
                } else {
                    mapOf(
                        "lat" to location.latitude,
                        "lng" to location.longitude,
                        "acc" to location.accuracy.toDouble(),
                        "brg" to bearing,
                        "spd" to speed,
                        "bat" to battery,
                        "trail" to trail.ifEmpty { null },
                        "at" to ServerValue.TIMESTAMP,
                        "until" to until,
                    )
                }
                mine(context, chatId)?.setValue(place)
                ArrivalWatch.check(context, chatId, location)
            }
        }
    }

    private val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun watch(context: Context, chatId: String) = callbackFlow {
        val ref = db(context)?.getReference("liveLocations")?.child(chatId)
        if (ref == null) {
            trySend(emptyMap())
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                trySend(
                    snap.children.mapNotNull { c ->
                        val uid = c.key ?: return@mapNotNull null
                        val openedText = c.child("e").getValue(String::class.java)?.let { sealed ->
                            E2eEngine.instance?.openText(chatId, sealed)?.takeIf { it != E2e.UNREADABLE }
                        }
                        val opened = openedText?.substringBefore('|')?.split(',')
                        fun num(index: Int, key: String): Double? =
                            opened?.getOrNull(index)?.toDoubleOrNull() ?: runCatching { c.child(key).getValue(Double::class.java) }.getOrNull()
                        val lat = num(0, "lat") ?: return@mapNotNull null
                        val lng = num(1, "lng") ?: return@mapNotNull null
                        val trailText = if (openedText != null) openedText.substringAfter('|', "") else c.child("trail").getValue(String::class.java).orEmpty()
                        uid to LiveLocation(
                            uid,
                            lat,
                            lng,
                            c.child("at").getValue(Long::class.java) ?: 0L,
                            c.child("until").getValue(Long::class.java) ?: 0L,
                            ended = c.child("ended").getValue(Boolean::class.java) == true,
                            accuracy = num(2, "acc"),
                            bearing = num(3, "brg")?.toFloat(),
                            speed = num(4, "spd")?.toFloat(),
                            battery = num(5, "bat")?.toInt(),
                            trail = Trail.parse(trailText),
                        )
                    }.toMap(),
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "watch: ${error.message}")
                trySend(emptyMap())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}

/**
 * Keeps sending the phone's place while a live location is shared: a notification says so and stops it, updates come
 * every 30 seconds or 25 metres on the move (the system holds them back while still), and it ends by itself when
 * every sharing has.
 */
class LiveLocationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var manager: LocationManager? = null
    private var fused: com.google.android.gms.location.FusedLocationProviderClient? = null
    private val fusedCallback = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            result.lastLocation?.let { onPlace(it) }
        }
    }
    private var lastSent = 0L

    /** A new place: sent (a few seconds apart at most, so a quick run of them does not flood the database). */
    private fun onPlace(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastSent < MIN_GAP_MS) return
        lastSent = now
        LiveLocations.send(this, location)
        refresh()
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onPlace(location)
        }

        @Deprecated("old API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LiveLocations.stopAll(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (LiveLocations.load(this).isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        channel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
            .onFailure {
                Log.w("QeyfLive", "foreground: ${it.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        listen()
        main.removeCallbacks(tick)
        main.post(tick)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            stopSelf()
            return
        }
        // The phone's fused location (GPS, Wi-Fi and the cell towers together): every few seconds while moving, and
        // little while still (it waits for a few metres of movement), so the pin glides instead of jumping.
        val play = runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)
        if (play) {
            val client = fused ?: com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this).also { fused = it }
            val request = com.google.android.gms.location.LocationRequest.Builder(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                .setMinUpdateIntervalMillis(MIN_GAP_MS)
                .setMinUpdateDistanceMeters(4f)
                .setWaitForAccurateLocation(false)
                .build()
            val started = runCatching {
                client.removeLocationUpdates(fusedCallback)
                client.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                client.lastLocation.addOnSuccessListener { it?.let { l -> onPlace(l) } }
            }.isSuccess
            if (started) return
        }
        val m = manager ?: (getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { manager = it }
        runCatching { m.removeUpdates(listener) }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && m.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        // The best one on, and the network one beside GPS (indoors GPS may give nothing).
        providers.take(2).forEach { p ->
            runCatching { m.requestLocationUpdates(p, 5_000L, 5f, listener, Looper.getMainLooper()) }
        }
        providers.mapNotNull { p -> runCatching { m.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
            ?.let { LiveLocations.send(this, it) }
    }

    /** Ends what has ended, updates the notification, stops when nothing goes on. */
    private fun refresh() {
        val sharing = LiveLocations.load(this)
        LiveShare.sharing.value = sharing
        if (sharing.isEmpty()) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        runCatching { manager?.removeUpdates(listener) }
        runCatching { fused?.removeLocationUpdates(fusedCallback) }
        super.onDestroy()
    }

    private fun tr() = getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, if (tr()) "Canlı konum" else "Live location", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val sharing = LiveLocations.load(this)
        val now = System.currentTimeMillis() + (FirebaseChatBackend.peek()?.serverOffset?.value ?: 0L)
        val until = sharing.values.maxOrNull() ?: now
        val minutes = ((until - now) / 60_000).coerceAtLeast(1)
        val left = if (minutes >= 60) "${minutes / 60} ${if (tr()) "sa" else "h"} ${minutes % 60} ${if (tr()) "dk" else "min"}" else "$minutes ${if (tr()) "dk" else "min"}"
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.qmusic.player.R.drawable.ic_qmusic_notification)
            .setContentTitle(if (tr()) "Konumun paylaşılıyor" else "Sharing your location")
            .setContentText(
                (if (sharing.size > 1) (if (tr()) "${sharing.size} sohbette · " else "In ${sharing.size} chats · ") else "") +
                    (if (tr()) "$left kaldı" else "$left left"),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (tr()) "Durdur" else "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "qeyf_live_location"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP = "com.qmusic.live.STOP"
        private const val MIN_GAP_MS = 3_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java))
        }
    }
}

/**
 * The way this phone came while sharing in a conversation: a point each 15 metres or so, the last [MAX] kept, as
 * "lat,lng;lat,lng;...". It starts again with each new sharing and goes when the sharing stops.
 */
object Trail {
    private const val PREFS = "nuvelo_live_trail"
    private const val MAX = 80
    private const val STEP_M = 15.0

    fun add(context: Context, chatId: String, lat: Double, lng: Double): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val points = parse(prefs.getString(chatId, null).orEmpty()).toMutableList()
        val last = points.lastOrNull()
        if (last == null || distanceMeters(last.first, last.second, lat, lng) >= STEP_M) points += lat to lng
        val kept = points.takeLast(MAX)
        val text = kept.joinToString(";") { (a, b) -> "${round(a)},${round(b)}" }
        prefs.edit().putString(chatId, text).apply()
        return text
    }

    fun clear(context: Context, chatId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(chatId).apply()
    }

    fun parse(text: String): List<Pair<Double, Double>> =
        text.split(';').mapNotNull { p ->
            val parts = p.split(',')
            val a = parts.getOrNull(0)?.toDoubleOrNull()
            val b = parts.getOrNull(1)?.toDoubleOrNull()
            if (a != null && b != null) a to b else null
        }

    /** Five decimals: about a metre, and short. */
    private fun round(v: Double) = kotlin.math.round(v * 100_000) / 100_000
}

/**
 * "Let them know when I arrive": while a live location is shared, reaching the chosen place (within 150 m) sends
 * "I've arrived" to the conversation and ends the sharing there. A home can be kept for it.
 */
object ArrivalWatch {
    private const val PREFS = "nuvelo_arrival"
    private const val NEAR_M = 150.0

    fun init(context: Context) {
        val c = context.applicationContext
        LiveShare.arrivals.value = targets(c).mapValues { it.value.third }
        LiveShare.arriveAt = { chatId, lat, lng, label ->
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("t_$chatId", "$lat,$lng,$label").apply()
            LiveShare.arrivals.value = targets(c).mapValues { it.value.third }
        }
        LiveShare.cancelArrival = { chatId ->
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("t_$chatId").apply()
            LiveShare.arrivals.value = targets(c).mapValues { it.value.third }
        }
        LiveShare.home = {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("home", null)?.split(',')?.let { p ->
                val lat = p.getOrNull(0)?.toDoubleOrNull()
                val lng = p.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null) lat to lng else null
            }
        }
        LiveShare.saveHome = { lat, lng -> c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("home", "$lat,$lng").apply() }
    }

    private fun targets(context: Context): Map<String, Triple<Double, Double, String>> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            if (!k.startsWith("t_")) return@mapNotNull null
            val p = (v as? String)?.split(',', limit = 3) ?: return@mapNotNull null
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lng = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            k.removePrefix("t_") to Triple(lat, lng, p.getOrNull(2).orEmpty())
        }.toMap()

    /** A new place: arrived somewhere chosen? */
    suspend fun check(context: Context, chatId: String, location: android.location.Location) {
        val (lat, lng, label) = targets(context)[chatId] ?: return
        if (distanceMeters(lat, lng, location.latitude, location.longitude) > NEAR_M) return
        LiveShare.cancelArrival(chatId)
        val backend = FirebaseChatBackend.get(context) ?: return
        val me = backend.awaitMe() ?: return
        val tr = context.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE).getString("language", "tr") != "en"
        val target = if (isGroupId(chatId)) {
            ChatUser(chatId, "", "", null)
        } else {
            val other = chatId.split("_").firstOrNull { it != me.uid } ?: return
            runCatching { backend.user(other) }.getOrNull() ?: return
        }
        val text = when {
            label.isNotBlank() && tr -> "Vardım: $label"
            label.isNotBlank() -> "I've arrived: $label"
            tr -> "Vardım"
            else -> "I've arrived"
        }
        backend.send(target, "📍 $text", null, null)
        LiveLocations.stop(context, chatId)
    }
}
