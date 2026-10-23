package com.qmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.updater.model.UpdateInfo

private const val REPOSITORY_URL = "https://github.com/mrquakex/qmusic"

/**
 * Settings, grouped like YouTube Music's: Account, Playback, Downloads, Library, Updates and About. Each group is
 * one rounded block of plain rows, so the page reads as a list and not as a pile of cards.
 */
@Composable
fun SettingsScreen(
    isLoggedInYouTube: Boolean,
    crossfadeSeconds: Int,
    isCheckingUpdate: Boolean,
    updateInfo: UpdateInfo?,
    updateMessage: String?,
    currentAppVersion: String,
    onLoginYouTubeClick: () -> Unit,
    onLogoutYouTubeClick: () -> Unit,
    onCrossfadeChange: (Int) -> Unit,
    onOpenEqualizerClick: () -> Unit,
    onCheckUpdateClick: () -> Unit,
    onOpenSpotifyImport: () -> Unit,
    modifier: Modifier = Modifier,
    updateProgress: Float? = null,
    onInstallUpdate: () -> Unit = {},
    videoByDefault: Boolean = false,
    onVideoByDefaultChange: (Boolean) -> Unit = {},
    autoDownload: Boolean = false,
    onAutoDownloadChange: (Boolean) -> Unit = {},
    wifiOnly: Boolean = true,
    onWifiOnlyChange: (Boolean) -> Unit = {},
    downloadedBytes: Long = 0L,
    onRemoveAllDownloads: () -> Unit = {},
    autoCheckUpdates: Boolean = true,
    onAutoCheckUpdatesChange: (Boolean) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var confirmRemoveDownloads by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Section("Account") {
            if (isLoggedInYouTube) {
                ActionRow(
                    icon = QMusicIcons.Account,
                    title = "YouTube Music",
                    subtitle = "Signed in. Your home, likes and library are synced.",
                    action = { OutlinedButton(onClick = onLogoutYouTubeClick) { Text("Sign out") } },
                )
            } else {
                ActionRow(
                    icon = QMusicIcons.Account,
                    title = "YouTube Music",
                    subtitle = "Sign in for your own mixes, likes and library.",
                    action = { Button(onClick = onLoginYouTubeClick) { Text("Sign in") } },
                )
            }
        }

        Section("Playback") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Crossfade",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (crossfadeSeconds == 0) "Off" else "${crossfadeSeconds}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Songs blend into each other as one ends. Tapping a song never fades.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = crossfadeSeconds.toFloat(),
                    onValueChange = { onCrossfadeChange(it.toInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = DarkSurfaceVariant,
                    ),
                )
            }
            RowDivider()
            ActionRow(
                icon = QMusicIcons.Tune,
                title = "Equalizer",
                subtitle = "10 bands",
                onClick = onOpenEqualizerClick,
            )
            RowDivider()
            SwitchRow(
                icon = QMusicIcons.VideoPlay,
                title = "Show music videos",
                subtitle = "Open the player on the video when a song has one. Off: the cover comes first.",
                checked = videoByDefault,
                onChange = onVideoByDefaultChange,
            )
        }

        Section("Downloads") {
            SwitchRow(
                icon = QMusicIcons.Download,
                title = "Auto-download liked songs",
                subtitle = "Every song you like is saved on this device.",
                checked = autoDownload,
                onChange = onAutoDownloadChange,
            )
            if (autoDownload) {
                RowDivider()
                SwitchRow(
                    icon = null,
                    title = "Wi-Fi only",
                    subtitle = "Wait for Wi-Fi instead of using mobile data.",
                    checked = wifiOnly,
                    onChange = onWifiOnlyChange,
                )
            }
            RowDivider()
            ActionRow(
                icon = QMusicIcons.Trash,
                title = "Remove all downloads",
                subtitle = if (downloadedBytes > 0) "${settingsSize(downloadedBytes)} on this device" else "Nothing downloaded",
                onClick = if (downloadedBytes > 0) ({ confirmRemoveDownloads = true }) else null,
            )
        }

        Section("Library") {
            ActionRow(
                icon = QMusicIcons.PlaylistAdd,
                title = "Import from Spotify",
                subtitle = "Bring a public Spotify playlist into your library.",
                onClick = onOpenSpotifyImport,
            )
        }

        Section("Updates") {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Version $currentAppVersion", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        if (updateMessage != null) {
                            Text(
                                text = updateMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (updateInfo != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(onClick = onCheckUpdateClick, enabled = !isCheckingUpdate && updateProgress == null) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("Check")
                        }
                    }
                }
                if (updateInfo != null) {
                    if (updateInfo.changelog.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = updateInfo.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (updateProgress != null) {
                        LinearProgressIndicator(
                            progress = { updateProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.18f),
                        )
                    } else {
                        Button(onClick = onInstallUpdate, modifier = Modifier.fillMaxWidth()) {
                            Text("Update to v${updateInfo.versionName}")
                        }
                    }
                }
            }
            RowDivider()
            SwitchRow(
                icon = null,
                title = "Check on launch",
                subtitle = "Look for a new version whenever the app opens.",
                checked = autoCheckUpdates,
                onChange = onAutoCheckUpdatesChange,
            )
        }

        Section("About") {
            ActionRow(
                icon = QMusicIcons.Headphones,
                title = "QMusic",
                subtitle = "Made by Mrquake",
            )
            RowDivider()
            ActionRow(
                icon = null,
                title = "Source code",
                subtitle = "github.com/mrquakex/qmusic",
                onClick = { runCatching { uriHandler.openUri(REPOSITORY_URL) } },
            )
            RowDivider()
            Text(
                text = "Open source under the GNU GPL v3. Streams are resolved with the open-source PipePipe extractor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    if (confirmRemoveDownloads) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            title = { Text("Remove all downloads?") },
            text = { Text("The songs stay in your library; they just need a connection to play again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveDownloads = false
                    onRemoveAllDownloads()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDownloads = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DarkSurface),
    ) { content() }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = Color.White.copy(alpha = 0.06f))
}

/** A row with an icon, a title and a caption; tappable when [onClick] is given, with an optional button at the end. */
@Composable
private fun ActionRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BgRoot,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = DarkSurfaceVariant,
                uncheckedBorderColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

/** Every row keeps the same left gutter, with or without an icon, so titles line up down the page. */
@Composable
private fun RowIcon(icon: ImageVector?) {
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    } else {
        Spacer(Modifier.size(24.dp))
    }
    Spacer(Modifier.width(16.dp))
}

private fun settingsSize(bytes: Long): String {
    val mb = bytes / 1_048_576
    return if (mb >= 1024) "${mb / 1024}.${(mb % 1024) * 10 / 1024} GB" else "$mb MB"
}
