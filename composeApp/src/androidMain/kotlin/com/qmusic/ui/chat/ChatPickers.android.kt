package com.qmusic.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import com.qmusic.ui.theme.lightTheme
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ------------------------------------------------------------------ where the phone is

@Composable
actual fun rememberLocationPicker(onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    val latestPicked by rememberUpdatedState(onPicked)
    val latestError by rememberUpdatedState(onError)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) showDialog = true else latestError("permission")
    }
    if (showDialog) {
        StaticLocationPickerDialog(
            context = context,
            onPick = { location ->
                showDialog = false
                latestPicked(location)
            },
            onDismiss = { showDialog = false },
        )
    }
    return {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            showDialog = true
        } else {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

private fun enabledProviders(manager: LocationManager): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
    ) {
        add(LocationManager.FUSED_PROVIDER)
    }
    if (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.GPS_PROVIDER)
    }
    if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.NETWORK_PROVIDER)
    }
}

@SuppressLint("MissingPermission")
private fun bestLastKnown(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val candidates = buildList {
        addAll(enabledProviders(manager))
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }.distinct()
    return candidates
        .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
}

/**
 * A fresh fix from enabled providers; delivers fast if [bestLastKnown] is under 60s old and accurate (<= 100m).
 * On API < 31 (or when FUSED_PROVIDER is unavailable), requests updates from all enabled providers in parallel and
 * cleans up all listeners / [CancellationSignal]s on first fix, 8s timeout, or caller cancellation.
 */
@SuppressLint("MissingPermission")
private fun currentLocation(context: Context, onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit): () -> Unit {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = enabledProviders(manager)
    val lastKnown: () -> Location? = {
        providers.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
            ?: bestLastKnown(context)
    }
    if (providers.isEmpty()) {
        onError("off")
        return {}
    }
    val cached = lastKnown()
    if (cached != null &&
        System.currentTimeMillis() - cached.time < 60_000L &&
        (!cached.hasAccuracy() || cached.accuracy <= 100f)
    ) {
        onPicked(ChatLocation(cached.latitude, cached.longitude))
        return {}
    }

    val main = Handler(Looper.getMainLooper())
    val signals = ArrayList<CancellationSignal>()
    val listeners = ArrayList<LocationListener>()
    var done = false
    lateinit var timeoutRunnable: Runnable

    fun cleanup() {
        main.removeCallbacks(timeoutRunnable)
        signals.forEach { runCatching { it.cancel() } }
        signals.clear()
        listeners.forEach { listener -> runCatching { manager.removeUpdates(listener) } }
        listeners.clear()
    }

    val finish: (Location?) -> Unit = { location ->
        if (!done) {
            done = true
            cleanup()
            val resolved = location ?: lastKnown()
            if (resolved != null) onPicked(ChatLocation(resolved.latitude, resolved.longitude)) else onError("none")
        }
    }

    timeoutRunnable = Runnable { finish(null) }
    main.postDelayed(timeoutRunnable, 8_000L)

    val targetProviders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in providers) {
        listOf(LocationManager.FUSED_PROVIDER)
    } else {
        providers
    }
    var pendingCount = targetProviders.size

    fun oneFailed() {
        pendingCount--
        if (pendingCount <= 0) finish(null)
    }

    val executor = ContextCompat.getMainExecutor(context)
    for (provider in targetProviders) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            signals += signal
            runCatching {
                manager.getCurrentLocation(provider, signal, executor) { location ->
                    main.post {
                        if (location != null) finish(location) else oneFailed()
                    }
                }
            }.onFailure {
                main.post { oneFailed() }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    main.post { finish(location) }
                }

                @Deprecated("old API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listeners += listener
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }.onFailure {
                listeners.remove(listener)
                main.post { oneFailed() }
            }
        }
    }

    return {
        if (!done) {
            done = true
            cleanup()
        }
    }
}

@Composable
private fun StaticLocationPickerDialog(
    context: Context,
    onPick: (ChatLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    val english = ChatMusic.english
    val initialKnown = remember { bestLastKnown(context) }
    val initialCenter = remember {
        initialKnown?.let { it.latitude to it.longitude } ?: (41.0082 to 28.9784)
    }
    val initialZoom = remember { if (initialKnown != null) 15.5 else 11.0 }
    var center by remember { mutableStateOf(initialCenter) }
    var gpsFix by remember { mutableStateOf(initialKnown?.let { ChatLocation(it.latitude, it.longitude) }) }
    var userDragged by remember { mutableStateOf(false) }
    var gpsRequestTick by remember { mutableIntStateOf(0) }
    val control = remember { LiveMapControl() }

    DisposableEffect(gpsRequestTick) {
        val cancel = currentLocation(
            context = context,
            onPicked = { fix ->
                gpsFix = fix
                if (!userDragged) {
                    center = fix.lat to fix.lng
                    control.moveTo(fix.lat, fix.lng, 16.0, animate = true)
                }
            },
            onError = {},
        )
        onDispose { cancel() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogEdgeToEdge()
        Column(Modifier.fillMaxSize().background(BgRoot)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LiveMap(
                    pins = emptyList(),
                    control = control,
                    start = initialCenter,
                    startZoom = initialZoom,
                    modifier = Modifier.fillMaxSize(),
                    fitBottom = 24.dp,
                    onGesture = { userDragged = true },
                    onPinTap = {},
                    onCenter = { lat, lng -> center = lat to lng },
                )

                // Monochrome center crosshair + pin marker anchored directly at the map camera's target.
                MonochromeCenterMarker(Modifier.align(Alignment.Center))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mapButtonTint = if (lightTheme) Ink else InkInverse
                    MapRoundButton(
                        content = { Icon(QMusicIcons.Back, contentDescription = null, tint = mapButtonTint) },
                        onClick = onDismiss,
                    )
                    MapRoundButton(
                        content = { Icon(QMusicIcons.Location, contentDescription = null, tint = mapButtonTint, modifier = Modifier.size(22.dp)) },
                        onClick = {
                            userDragged = false
                            gpsFix?.let { fix ->
                                center = fix.lat to fix.lng
                                control.moveTo(fix.lat, fix.lng, 16.0, animate = true)
                            }
                            gpsRequestTick++
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextSecondary.copy(alpha = 0.4f)),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (english) "Choose location" else "Konum seç",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f", center.first, center.second),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))

                // Primary action: send selected map center.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink)
                        .clickable { onPick(ChatLocation(center.first, center.second)) }
                        .padding(vertical = 13.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QMusicIcons.Location, contentDescription = null, tint = InkInverse, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (english) "Send this location" else "Bu konumu gönder",
                        color = InkInverse,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                val currentFix = gpsFix
                if (currentFix != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(BgRoot)
                            .border(1.dp, TextSecondary.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                            .clickable { onPick(ChatLocation(currentFix.lat, currentFix.lng)) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (english) "Send current location" else "Mevcut konumumu gönder",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonochromeCenterMarker(modifier: Modifier = Modifier) {
    val fg = Ink
    val bg = InkInverse
    Canvas(modifier.size(48.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val ringR = 13.dp.toPx()
        val arm = 19.dp.toPx()
        val gap = 7.dp.toPx()
        // Outer contrast halo so the crosshair stays crisp on both dark and light map tiles.
        drawCircle(bg.copy(alpha = 0.75f), radius = ringR, center = c, style = Stroke(width = 4.dp.toPx()))
        drawLine(bg.copy(alpha = 0.75f), Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(bg.copy(alpha = 0.75f), Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(bg.copy(alpha = 0.75f), Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(bg.copy(alpha = 0.75f), Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)

        drawCircle(fg, radius = ringR, center = c, style = Stroke(width = 2.dp.toPx()))
        drawLine(fg, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(fg, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(fg, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(fg, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

        drawCircle(bg, radius = 4.5.dp.toPx(), center = c)
        drawCircle(fg, radius = 3.dp.toPx(), center = c)
    }
}

// ------------------------------------------------------------------ contacts

@Composable
actual fun rememberContactPicker(onPicked: (ChatContact) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Picking from the phone numbers gives this app the right to read that one entry, without the contacts permission.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0).orEmpty()
                    val phone = c.getString(1).orEmpty()
                    if (phone.isNotBlank()) onPicked(ChatContact(name, phone))
                }
            }
        }
    }
    return { launcher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
}

@Composable
actual fun rememberContactSaver(): (ChatContact) -> Unit {
    val context = LocalContext.current
    return { contact ->
        runCatching {
            context.startActivity(
                Intent(ContactsContract.Intents.Insert.ACTION)
                    .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, contact.phone)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

// ------------------------------------------------------------------ the lock

private class AndroidAppLock(private val activity: FragmentActivity) : AppLock {
    private val prefs = activity.getSharedPreferences("qmusic_settings", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // Fingerprint or face, or the screen lock code/pattern: whatever the phone has.
    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override val available: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    override fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    override fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onResult(false) }
    }

    companion object {
        const val KEY = "chat_lock"
    }
}

private fun Context.fragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

@Composable
actual fun rememberAppLock(): AppLock? {
    val context = LocalContext.current
    return remember(context) { context.fragmentActivity()?.let { AndroidAppLock(it) } }
}
