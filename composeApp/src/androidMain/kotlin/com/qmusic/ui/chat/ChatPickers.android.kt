package com.qmusic.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ------------------------------------------------------------------ where the phone is

@Composable
actual fun rememberLocationPicker(onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) currentLocation(context, onPicked, onError) else onError("permission")
    }
    return {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            currentLocation(context, onPicked, onError)
        } else {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

/** A fresh fix from the best provider that is on; the last known one when none comes within a few seconds. */
@SuppressLint("MissingPermission")
private fun currentLocation(context: Context, onPicked: (ChatLocation) -> Unit, onError: (String) -> Unit) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    if (providers.isEmpty()) {
        onError("off")
        return
    }
    val lastKnown: () -> Location? = {
        providers.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
    }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    var done = false
    val finish: (Location?) -> Unit = { location ->
        if (!done) {
            done = true
            val l = location ?: lastKnown()
            if (l != null) onPicked(ChatLocation(l.latitude, l.longitude)) else onError("none")
        }
    }
    val cancel = CancellationSignal()
    // Waits at most 12 seconds for a fresh position.
    main.postDelayed({ cancel.cancel(); finish(null) }, 12_000)
    val provider = providers.first()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { location -> main.post { finish(location) } }
        }.onFailure { finish(null) }
    } else {
        runCatching {
            // Every method spelled out: before Android 11 the listener's other methods have no default.
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    main.post { finish(location) }
                }

                @Deprecated("old API")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
        }.onFailure { finish(null) }
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
