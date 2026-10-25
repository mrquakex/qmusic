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
import androidx.compose.foundation.horizontalScroll
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
import com.qmusic.settings.MoreSettings
import com.qmusic.settings.MoreSettingsChange
import com.qmusic.ui.strings.LocalStrings
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
    crashReport: String? = null,
    onBackupExport: (() -> Unit)? = null,
    onBackupImport: (() -> Unit)? = null,
    onShareCrashReport: () -> Unit = {},
    onClearCrashReport: () -> Unit = {},
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
    updateNotifications: Boolean = true,
    autoUpdate: Boolean = true,
    audioQualityWifi: String = "HIGH",
    onAudioQualityWifiChange: (String) -> Unit = {},
    audioQualityMobile: String = "NORMAL",
    onAudioQualityMobileChange: (String) -> Unit = {},
    normalizeVolume: Boolean = true,
    onNormalizeVolumeChange: (Boolean) -> Unit = {},
    onAutoUpdateChange: (Boolean) -> Unit = {},
    onUpdateNotificationsChange: (Boolean) -> Unit = {},
    onAutoCheckUpdatesChange: (Boolean) -> Unit = {},
    language: String = "tr",
    onLanguageChange: (String) -> Unit = {},
    more: MoreSettings = MoreSettings(),
    onMore: (MoreSettingsChange) -> Unit = {},
    accountMode: String = com.qmusic.settings.AccountMode.YOUTUBE,
    spotifySignedIn: Boolean = false,
    onSpotifySignIn: () -> Unit = {},
    onSpotifySignOut: () -> Unit = {},
    onChangeAccount: () -> Unit = {},
    /** Opens the list of ringtones made in the app; null where there are none to make. */
    onOpenRingtones: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    var confirmRemoveDownloads by remember { mutableStateOf(false) }
    var lastFmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = s.settingsTitle,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        var confirmChangeAccount by remember { mutableStateOf(false) }
        if (confirmChangeAccount) {
            AlertDialog(
                onDismissRequest = { confirmChangeAccount = false },
                title = { Text(s.changeAccount) },
                text = { Text(s.changeAccountConfirm) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmChangeAccount = false
                        onChangeAccount()
                    }) { Text(s.changeAccount) }
                },
                dismissButton = { TextButton(onClick = { confirmChangeAccount = false }) { Text(s.cancel) } },
            )
        }
        Section(s.sectionAccount) {
            when (accountMode) {
            com.qmusic.settings.AccountMode.SPOTIFY -> ActionRow(
                icon = QMusicIcons.Account,
                title = "Spotify",
                subtitle = if (spotifySignedIn) s.spotifySignedInSubtitle else s.spotifySignedOutSubtitle,
                action = {
                    if (spotifySignedIn) {
                        OutlinedButton(onClick = onSpotifySignOut) { Text(s.signOut) }
                    } else {
                        Button(onClick = onSpotifySignIn) { Text(s.signIn) }
                    }
                },
            )
            com.qmusic.settings.AccountMode.YOUTUBE -> if (isLoggedInYouTube) {
                ActionRow(
                    icon = QMusicIcons.Account,
                    title = "YouTube Music",
                    subtitle = s.accountSignedInSubtitle,
                    action = { OutlinedButton(onClick = onLogoutYouTubeClick) { Text(s.signOut) } },
                )
            } else {
                ActionRow(
                    icon = QMusicIcons.Account,
                    title = "YouTube Music",
                    subtitle = s.accountSignedOutSubtitle,
                    action = { Button(onClick = onLoginYouTubeClick) { Text(s.signIn) } },
                )
            }
            else -> ActionRow(
                icon = QMusicIcons.Account,
                title = s.connectAccount,
                subtitle = s.connectAccountSubtitle,
                onClick = onChangeAccount,
            )
            }
            if (accountMode == com.qmusic.settings.AccountMode.SPOTIFY || accountMode == com.qmusic.settings.AccountMode.YOUTUBE) {
                RowDivider()
                ActionRow(
                    icon = null,
                    title = s.changeAccount,
                    subtitle = s.changeAccountSubtitle,
                    onClick = { confirmChangeAccount = true },
                )
            }
        }

        Section(s.sectionPlayback) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = s.crossfadeTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (crossfadeSeconds == 0) s.off else "${crossfadeSeconds}s",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = s.crossfadeSubtitle,
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
                title = s.equalizerTitle,
                subtitle = s.equalizerSubtitle,
                onClick = onOpenEqualizerClick,
            )
            RowDivider()
            QualityRow(s.audioQualityTitle + " · " + s.audioQualityWifi, audioQualityWifi, onAudioQualityWifiChange)
            RowDivider()
            QualityRow(s.audioQualityTitle + " · " + s.audioQualityMobile, audioQualityMobile, onAudioQualityMobileChange)
            RowDivider()
            SwitchRow(
                icon = null,
                title = s.normalizeTitle,
                subtitle = s.normalizeSubtitle,
                checked = normalizeVolume,
                onChange = onNormalizeVolumeChange,
            )
            RowDivider()
            SwitchRow(
                icon = QMusicIcons.VideoPlay,
                title = s.showVideosTitle,
                subtitle = s.showVideosSubtitle,
                checked = videoByDefault,
                onChange = onVideoByDefaultChange,
            )
            RowDivider()
            SwitchRow(
                icon = QMusicIcons.Headphones,
                title = s.headsetResumeTitle,
                subtitle = s.headsetResumeSubtitle,
                checked = more.headsetResume,
                onChange = { onMore(MoreSettingsChange.HeadsetResume(it)) },
            )
            RowDivider()
            SwitchRow(
                icon = QMusicIcons.Lyrics,
                title = s.notificationLyricsTitle,
                subtitle = s.notificationLyricsSubtitle,
                checked = more.notificationLyrics,
                onChange = { onMore(MoreSettingsChange.NotificationLyrics(it)) },
            )
        }

        Section(s.sectionDownloads) {
            SwitchRow(
                icon = QMusicIcons.Download,
                title = s.autoDownloadLikedTitle,
                subtitle = s.autoDownloadLikedSubtitle,
                checked = autoDownload,
                onChange = onAutoDownloadChange,
            )
            if (autoDownload) {
                RowDivider()
                SwitchRow(
                    icon = null,
                    title = s.wifiOnlyTitle,
                    subtitle = s.wifiOnlySubtitle,
                    checked = wifiOnly,
                    onChange = onWifiOnlyChange,
                )
            }
            RowDivider()
            SwitchRow(
                icon = QMusicIcons.Samples,
                title = s.smartDownloadsTitle,
                subtitle = s.smartDownloadsSubtitle,
                checked = more.smartDownloads,
                onChange = { onMore(MoreSettingsChange.SmartDownloads(it)) },
            )
            RowDivider()
            QualityRow(s.downloadQualityTitle, more.downloadQuality) { onMore(MoreSettingsChange.DownloadQuality(it)) }
            RowDivider()
            PillRow(
                title = s.cacheLimitTitle,
                subtitle = s.cacheLimitSubtitle,
                options = CACHE_CHOICES,
                value = more.cacheLimitMb,
                onChange = { onMore(MoreSettingsChange.CacheLimit(it)) },
            )
            RowDivider()
            ActionRow(
                icon = null,
                title = s.clearCacheTitle,
                subtitle = s.cacheUsed(settingsSize(more.cacheBytes)),
                onClick = { onMore(MoreSettingsChange.ClearCache) },
            )
            RowDivider()
            ActionRow(
                icon = QMusicIcons.Trash,
                title = s.removeAllDownloadsTitle,
                subtitle = s.settingsDownloadedSize(downloadedBytes),
                onClick = if (downloadedBytes > 0) ({ confirmRemoveDownloads = true }) else null,
            )
        }

        Section(s.sectionLibrary) {
            if (onOpenRingtones != null) {
                ActionRow(
                    icon = QMusicIcons.Bell,
                    title = s.myRingtones,
                    subtitle = s.myRingtonesSubtitle,
                    onClick = onOpenRingtones,
                )
                RowDivider()
            }
            if (accountMode == com.qmusic.settings.AccountMode.NONE) {
                ActionRow(
                    icon = QMusicIcons.PlaylistAdd,
                    title = s.importSpotifyTitle,
                    subtitle = s.importSpotifySubtitle,
                    onClick = onOpenSpotifyImport,
                )
                RowDivider()
            }
            ActionRow(
                icon = QMusicIcons.Stats,
                title = "Last.fm",
                subtitle = when {
                    !more.lastFmAvailable -> s.lastFmNotConfigured
                    more.lastFmUser != null -> s.lastFmSignedIn(more.lastFmUser)
                    else -> s.lastFmSubtitle
                },
                action = when {
                    !more.lastFmAvailable -> null
                    more.lastFmUser != null -> {
                        { OutlinedButton(onClick = { onMore(MoreSettingsChange.LastFmSignOut) }) { Text(s.signOut) } }
                    }
                    else -> {
                        { Button(onClick = { lastFmDialog = true }) { Text(s.signIn) } }
                    }
                },
            )
        }

        Section(s.sectionAppearance) {
            SwitchRow(
                icon = null,
                title = s.amoledTitle,
                subtitle = s.amoledSubtitle,
                checked = more.amoled,
                onChange = { onMore(MoreSettingsChange.Amoled(it)) },
            )
        }

        Section(s.sectionUpdates) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.versionLabel(currentAppVersion), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
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
                            Text(s.checkUpdate)
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
                            Text(s.updateToVersion(updateInfo.versionName))
                        }
                    }
                }
            }
            RowDivider()
            SwitchRow(
                icon = null,
                title = s.checkOnLaunchTitle,
                subtitle = s.checkOnLaunchSubtitle,
                checked = autoCheckUpdates,
                onChange = onAutoCheckUpdatesChange,
            )
            RowDivider()
            SwitchRow(
                icon = null,
                title = s.autoUpdateTitle,
                subtitle = s.autoUpdateSubtitle,
                checked = autoUpdate,
                onChange = onAutoUpdateChange,
            )
            RowDivider()
            SwitchRow(
                icon = null,
                title = s.updateNotificationsTitle,
                subtitle = s.updateNotificationsSubtitle,
                checked = updateNotifications,
                onChange = onUpdateNotificationsChange,
            )
        }

        Section(s.sectionLanguage) {
            ActionRow(
                icon = null,
                title = s.languageOptionTurkish,
                subtitle = if (language == "tr") s.selectedLanguage else "",
                action = if (language == "tr") {
                    {
                        Icon(
                            imageVector = QMusicIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else null,
                onClick = { onLanguageChange("tr") },
            )
            RowDivider()
            ActionRow(
                icon = null,
                title = s.languageOptionEnglish,
                subtitle = if (language == "en") s.selectedLanguage else "",
                action = if (language == "en") {
                    {
                        Icon(
                            imageVector = QMusicIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else null,
                onClick = { onLanguageChange("en") },
            )
        }

        if (onBackupExport != null && onBackupImport != null) {
            Section(s.backupSection) {
                ActionRow(icon = QMusicIcons.Download, title = s.backupExport, subtitle = s.backupExportSubtitle, onClick = onBackupExport)
                RowDivider()
                ActionRow(icon = QMusicIcons.History, title = s.backupImport, subtitle = s.backupImportSubtitle, onClick = onBackupImport)
            }
        }

        Section(s.sectionAbout) {
            ActionRow(
                icon = QMusicIcons.Headphones,
                title = "QMusic",
                subtitle = s.madeBy,
            )
            RowDivider()
            ActionRow(
                icon = null,
                title = s.sourceCodeTitle,
                subtitle = "github.com/mrquakex/qmusic",
                onClick = { runCatching { uriHandler.openUri(REPOSITORY_URL) } },
            )
            if (crashReport != null) {
                RowDivider()
                ActionRow(
                    icon = null,
                    title = s.crashReport,
                    subtitle = s.crashReportHint,
                    action = {
                        Column {
                            TextButton(onClick = onShareCrashReport) { Text(s.share) }
                            TextButton(onClick = onClearCrashReport) { Text(s.clearReport) }
                        }
                    },
                )
            }
            RowDivider()
            Text(
                text = s.aboutLicense,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    if (lastFmDialog) {
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { lastFmDialog = false },
            title = { Text("Last.fm") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        singleLine = true,
                        label = { Text(s.lastFmUsername) },
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(s.lastFmPassword) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = user.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        lastFmDialog = false
                        onMore(MoreSettingsChange.LastFmSignIn(user, password))
                    },
                ) { Text(s.signIn) }
            },
            dismissButton = { TextButton(onClick = { lastFmDialog = false }) { Text(s.cancel) } },
        )
    }

    if (confirmRemoveDownloads) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownloads = false },
            title = { Text(s.removeAllDownloadsTitle) },
            text = { Text(s.removeAllDownloadsConfirmMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveDownloads = false
                    onRemoveAllDownloads()
                }) { Text(s.remove) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDownloads = false }) { Text(s.cancel) } },
        )
    }
}

private val CACHE_CHOICES = listOf("256" to "256 MB", "512" to "512 MB", "1024" to "1 GB", "2048" to "2 GB", "4096" to "4 GB")

/** A setting with a few choices, as pills that scroll sideways when they do not fit. */
@Composable
private fun PillRow(title: String, subtitle: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(null)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .padding(start = 40.dp)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (key, label) ->
                val selected = key == value
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White else DarkSurfaceVariant)
                        .clickable { onChange(key) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A setting with three choices of stream quality, as pills. */
@Composable
private fun QualityRow(title: String, value: String, onChange: (String) -> Unit) {
    val s = LocalStrings.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(null)
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.padding(start = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("HIGH" to s.qualityHigh, "NORMAL" to s.qualityNormal, "LOW" to s.qualityLow).forEach { (key, label) ->
                val selected = key == value
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White else DarkSurfaceVariant)
                        .clickable { onChange(key) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
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
