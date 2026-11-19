package com.qmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.InkInverse
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow

/** A new version downloaded and waiting (set by the app's updater), and the way to put it in now. */
object UpdateReady {
    val version = MutableStateFlow<String?>(null)
    var install: (() -> Unit)? = null

    /**
     * The phone can update without a question: the update is fetched in the background and goes in when the app is not
     * in use (true), instead of at once over the app being used. Null or false: the usual way.
     */
    var quiet: (() -> Boolean)? = null
}

/** Hidden for the rest of the time the app is open ("Later" was tapped). */
private object UpdateBannerState {
    var hiddenFor: String? = null
}

/**
 * "New version ready, restart": over whatever is on screen while the app is open. A tap puts it in now (the app
 * closes and is opened again); otherwise it goes in by itself after the app is left.
 */
@Composable
fun UpdateReadyBanner(english: Boolean) {
    val version by UpdateReady.version.collectAsState()
    var hidden by remember(version) { mutableStateOf(version != null && UpdateBannerState.hiddenFor == version) }
    val v = version ?: return
    if (hidden) return
    Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(DarkSurfaceVariant).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QMusicIcons.Download, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (english) "Nuvelo $v is ready" else "Nuvelo $v hazır",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (english) "Later" else "Sonra",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { UpdateBannerState.hiddenFor = v; hidden = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Text(
                if (english) "Restart" else "Yeniden başlat",
                color = InkInverse,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Ink).clickable { UpdateReady.install?.invoke() }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
