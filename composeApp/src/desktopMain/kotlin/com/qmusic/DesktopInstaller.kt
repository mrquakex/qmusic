package com.qmusic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import com.qmusic.ui.theme.BgRoot
import com.qmusic.ui.theme.DarkSurface
import com.qmusic.ui.theme.DarkSurfaceVariant
import com.qmusic.ui.theme.QMusicHeaderLogo
import com.qmusic.ui.theme.QMusicIcons
import com.qmusic.ui.theme.QMusicTheme
import com.qmusic.ui.theme.QMusicWordmark
import com.qmusic.ui.theme.Q_GLYPH_PATH
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class InstallStep {
    CONFIGURE,
    INSTALLING,
    COMPLETED,
}

object DesktopInstallManager {
    private const val INSTALL_MARKER = "installed_v2.0.3.flag"

    fun isInstalled(baseDir: File): Boolean = File(baseDir, INSTALL_MARKER).exists()

    fun defaultInstallDir(): File {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
        return File(localAppData, "Programs\\QMusic")
    }

    /**
     * Ensures `qmusic.ico` exists in `~/.qmusic/qmusic.ico` and creates the Desktop shortcut if missing.
     */
    fun ensureIconAndDesktopShortcut(baseDir: File) {
        val icoFile = File(baseDir, "qmusic.ico")
        if (!icoFile.exists() || icoFile.length() < 1024L) {
            runCatching { DesktopIcon.writeIcoFile(icoFile) }
        }
        val desktopDir = findWindowsDesktopDir()
        val shortcutFile = File(desktopDir, "QMusic.lnk")
        if (!shortcutFile.exists()) {
            val exeTarget = resolveCurrentOrInstalledExe()
            if (exeTarget != null) {
                createWindowsShortcut(
                    shortcutFile = shortcutFile,
                    targetExe = exeTarget,
                    iconFile = icoFile,
                    description = "QMusic - Müziğin Seninle",
                )
            }
        }
    }

    fun findWindowsDesktopDir(): File {
        val shellDesktop = runCatching {
            javax.swing.filechooser.FileSystemView.getFileSystemView().homeDirectory
        }.getOrNull()
        if (shellDesktop != null && shellDesktop.exists()) return shellDesktop

        val userHome = File(System.getProperty("user.home"))
        val oneDrive = System.getenv("OneDrive")
        return listOfNotNull(
            oneDrive?.let { File(it, "Ekler\\Masaüstü") }?.takeIf { it.exists() },
            oneDrive?.let { File(it, "Masaüstü") }?.takeIf { it.exists() },
            oneDrive?.let { File(it, "Desktop") }?.takeIf { it.exists() },
            File(userHome, "Desktop").takeIf { it.exists() },
            File(userHome, "Masaüstü").takeIf { it.exists() },
        ).firstOrNull() ?: File(userHome, "Desktop")
    }

    fun findWindowsStartMenuProgramsDir(): File {
        val appData = System.getenv("APPDATA")
            ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
        return File(appData, "Microsoft\\Windows\\Start Menu\\Programs")
    }

    fun resolveCurrentOrInstalledExe(): File? {
        val installedExe = File(defaultInstallDir(), "QMusic.exe")
        if (installedExe.exists()) return installedExe

        val jpackageAppPath = System.getProperty("jpackage.app-path")?.let { File(it) }
        if (jpackageAppPath != null && jpackageAppPath.exists()) return jpackageAppPath

        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, "QMusic.exe"),
            File(userDir, "composeApp/build/compose/binaries/main/app/QMusic/QMusic.exe"),
            File(userDir.parentFile ?: userDir, "composeApp/build/compose/binaries/main/app/QMusic/QMusic.exe"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    fun createWindowsShortcut(
        shortcutFile: File,
        targetExe: File,
        iconFile: File,
        description: String,
    ) {
        runCatching {
            shortcutFile.parentFile?.mkdirs()
            val vbsFile = File.createTempFile("qmusic_shortcut_", ".vbs")
            val lnkPath = shortcutFile.absolutePath.replace("\"", "\"\"")
            val targetPath = targetExe.absolutePath.replace("\"", "\"\"")
            val workDir = (targetExe.parentFile?.absolutePath ?: "").replace("\"", "\"\"")
            val icoPath = iconFile.absolutePath.replace("\"", "\"\"")
            val desc = description.replace("\"", "\"\"")
            vbsFile.writeText(
                """
                Set oWS = WScript.CreateObject("WScript.Shell")
                Set oLink = oWS.CreateShortcut("$lnkPath")
                oLink.TargetPath = "$targetPath"
                oLink.WorkingDirectory = "$workDir"
                oLink.IconLocation = "$icoPath, 0"
                oLink.Description = "$desc"
                oLink.Save
                """.trimIndent(),
                Charsets.UTF_16LE,
            )
            val proc = ProcessBuilder("wscript.exe", "//B", "//Nologo", vbsFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            proc.waitFor()
            vbsFile.delete()
        }
    }

    suspend fun performFullInstall(
        baseDir: File,
        installDir: File,
        createDesktopShortcut: Boolean,
        createStartMenuShortcut: Boolean,
        onStepProgress: (stepIndex: Int, progress: Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Step 0: Prepare directories & copy application bundle if available
        onStepProgress(0, 0.12f)
        installDir.mkdirs()
        val sourceExe = resolveCurrentOrInstalledExe()
        val sourceAppDir = sourceExe?.parentFile
        if (sourceAppDir != null && sourceAppDir.exists() && sourceAppDir.canonicalPath != installDir.canonicalPath) {
            runCatching {
                sourceAppDir.copyRecursively(installDir, overwrite = true)
            }
        }
        delay(350)
        onStepProgress(0, 0.35f)

        // Step 1: Generate official multi-resolution qmusic.ico
        onStepProgress(1, 0.45f)
        val baseIco = File(baseDir, "qmusic.ico")
        val installIco = File(installDir, "qmusic.ico")
        runCatching { DesktopIcon.writeIcoFile(baseIco) }
        runCatching { DesktopIcon.writeIcoFile(installIco) }
        delay(320)
        onStepProgress(1, 0.65f)

        // Step 2: Create Desktop and Start Menu shortcuts with our official QMusic icon
        onStepProgress(2, 0.72f)
        val finalExe = File(installDir, "QMusic.exe").takeIf { it.exists() } ?: sourceExe
        val iconToUse = installIco.takeIf { it.exists() } ?: baseIco
        if (finalExe != null) {
            if (createDesktopShortcut) {
                val desktopShortcut = File(findWindowsDesktopDir(), "QMusic.lnk")
                createWindowsShortcut(
                    shortcutFile = desktopShortcut,
                    targetExe = finalExe,
                    iconFile = iconToUse,
                    description = "QMusic - Müziğin Seninle",
                )
            }
            if (createStartMenuShortcut) {
                val startMenuShortcut = File(findWindowsStartMenuProgramsDir(), "QMusic.lnk")
                createWindowsShortcut(
                    shortcutFile = startMenuShortcut,
                    targetExe = finalExe,
                    iconFile = iconToUse,
                    description = "QMusic - Müziğin Seninle",
                )
            }
        }
        delay(320)
        onStepProgress(2, 0.88f)

        // Step 3: Finalize installation marker
        onStepProgress(3, 0.94f)
        delay(280)
        runCatching {
            File(baseDir, INSTALL_MARKER).writeText("2.0.3")
        }
        onStepProgress(3, 1.0f)
        delay(220)
    }
}

/**
 * Custom QMusic-branded Desktop Installer Screen (`QMusic Kurulum Sihirbazı`).
 * Built in QMusic's signature monochrome dark aesthetic with animated equalizer emblem,
 * custom toggle cards, and live step-by-step installation progress.
 */
@Composable
fun WindowScope.QMusicInstallerScreen(
    baseDir: File,
    onInstallFinished: () -> Unit,
    onCloseRequest: () -> Unit,
) {
    QMusicTheme {
        var step by remember { mutableStateOf(InstallStep.CONFIGURE) }
        var createDesktopShortcut by remember { mutableStateOf(true) }
        var createStartMenuShortcut by remember { mutableStateOf(true) }
        var autoLaunchAfterInstall by remember { mutableStateOf(true) }
        var activeSubStep by remember { mutableIntStateOf(0) }
        var rawProgress by remember { mutableFloatStateOf(0f) }
        val animatedProgress by animateFloatAsState(
            targetValue = rawProgress,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "installProgress",
        )
        val installDir = remember { DesktopInstallManager.defaultInstallDir() }
        val scope = rememberCoroutineScope()

        val startInstall: () -> Unit = {
            if (step == InstallStep.CONFIGURE) {
                step = InstallStep.INSTALLING
                scope.launch {
                    DesktopInstallManager.performFullInstall(
                        baseDir = baseDir,
                        installDir = installDir,
                        createDesktopShortcut = createDesktopShortcut,
                        createStartMenuShortcut = createStartMenuShortcut,
                        onStepProgress = { idx, prog ->
                            activeSubStep = idx
                            rawProgress = prog
                        },
                    )
                    step = InstallStep.COMPLETED
                    if (autoLaunchAfterInstall) {
                        delay(900)
                        onInstallFinished()
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(BgRoot)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Draggable Custom Window Title Bar
                WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(Color(0xFF09090D))
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            QMusicHeaderLogo()
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "KURULUM SİHİRBAZI",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.1.sp,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }

                        IconButton(
                            onClick = onCloseRequest,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = QMusicIcons.Close,
                                contentDescription = "Kapat",
                                tint = Color.White.copy(alpha = 0.65f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.07f)),
                )

                // Main 2-Column Body
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Left Showcase Column (Brand Emblem + Features)
                    InstallerBrandPanel(
                        isInstalling = step == InstallStep.INSTALLING,
                        modifier = Modifier
                            .width(276.dp)
                            .fillMaxHeight(),
                    )

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.07f)),
                    )

                    // Right Interactive Setup Column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF0C0C12))
                            .padding(horizontal = 32.dp, vertical = 26.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        when (step) {
                            InstallStep.CONFIGURE -> {
                                Column {
                                    Text(
                                        text = "QMusic Masaüstü Kurulumu",
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontSize = 23.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = Color.White,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Uygulama simgesini, masaüstü kısayolunu ve kurulum tercihlerinizi belirleyin.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.62f),
                                    )

                                    Spacer(Modifier.height(20.dp))

                                    // Target Directory Card
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(DarkSurface)
                                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "KURULUM KONUMU",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp,
                                                color = Color.White.copy(alpha = 0.5f),
                                            )
                                            Text(
                                                text = " ~185 MB ",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White.copy(alpha = 0.7f),
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = installDir.absolutePath,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color = Color.White.copy(alpha = 0.92f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    Spacer(Modifier.height(14.dp))

                                    // Custom QMusic Toggle Options
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        InstallerOptionRow(
                                            title = "Masaüstüne QMusic simgesi ekle",
                                            subtitle = "Masaüstünüzde resmi QMusic logolu kısayol oluşturur",
                                            checked = createDesktopShortcut,
                                            onCheckedChange = { createDesktopShortcut = it },
                                        )
                                        InstallerOptionRow(
                                            title = "Başlat Menüsüne ekle",
                                            subtitle = "Windows Başlat Menüsü ve hızlı aramaya ekler",
                                            checked = createStartMenuShortcut,
                                            onCheckedChange = { createStartMenuShortcut = it },
                                        )
                                        InstallerOptionRow(
                                            title = "Kurulum tamamlandığında QMusic'i başlat",
                                            subtitle = "Kurulum biter bitmez QMusic masaüstü arayüzünü açar",
                                            checked = autoLaunchAfterInstall,
                                            onCheckedChange = { autoLaunchAfterInstall = it },
                                        )
                                    }
                                }

                                // Primary CTA Button
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White)
                                        .clickable(onClick = startInstall),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = QMusicIcons.Download,
                                            contentDescription = null,
                                            tint = BgRoot,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = "Kurulumu Başlat",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                            ),
                                            color = BgRoot,
                                        )
                                    }
                                }
                            }

                            InstallStep.INSTALLING -> {
                                val stepLabels = listOf(
                                    "QMusic masaüstü paket dosyaları hazırlanıyor",
                                    "Resmi QMusic uygulama simgesi (qmusic.ico) işleniyor",
                                    "Masaüstü ve Başlat Menüsü kısayolları oluşturuluyor",
                                    "JavaFX ses motoru ve yerel veritabanı doğrulanıyor",
                                )

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom,
                                    ) {
                                        Column {
                                            Text(
                                                text = "QMusic Kuruluyor",
                                                style = MaterialTheme.typography.headlineMedium.copy(
                                                    fontSize = 23.sp,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = Color.White,
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "Lütfen birkaç saniye bekleyin...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.6f),
                                            )
                                        }
                                        Text(
                                            text = "%${(animatedProgress * 100).toInt()}",
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                        )
                                    }

                                    Spacer(Modifier.height(20.dp))

                                    // Custom Glowing Progress Bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color.White.copy(alpha = 0.10f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(animatedProgress.coerceIn(0.02f, 1f))
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(Color.White.copy(alpha = 0.75f), Color.White),
                                                    ),
                                                ),
                                        )
                                    }

                                    Spacer(Modifier.height(26.dp))

                                    // Step-by-step live checklist
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        stepLabels.forEachIndexed { index, label ->
                                            val done = index < activeSubStep || animatedProgress >= 0.99f
                                            val current = index == activeSubStep && !done
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (current) Color.White.copy(alpha = 0.07f)
                                                        else Color.Transparent
                                                    )
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            when {
                                                                done -> Color.White
                                                                current -> Color.White.copy(alpha = 0.18f)
                                                                else -> Color.White.copy(alpha = 0.06f)
                                                            },
                                                        ),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    when {
                                                        done -> Icon(
                                                            imageVector = QMusicIcons.Check,
                                                            contentDescription = null,
                                                            tint = BgRoot,
                                                            modifier = Modifier.size(14.dp),
                                                        )
                                                        current -> CircularProgressIndicator(
                                                            color = Color.White,
                                                            strokeWidth = 2.dp,
                                                            modifier = Modifier.size(13.dp),
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = if (current || done) FontWeight.SemiBold else FontWeight.Normal,
                                                    ),
                                                    color = when {
                                                        done -> Color.White
                                                        current -> Color.White.copy(alpha = 0.95f)
                                                        else -> Color.White.copy(alpha = 0.42f)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = "QMusic v2.0.3 • Resmi Masaüstü Kurulum Paketi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.4f),
                                )
                            }

                            InstallStep.COMPLETED -> {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = QMusicIcons.Check,
                                            contentDescription = null,
                                            tint = BgRoot,
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }

                                    Spacer(Modifier.height(18.dp))

                                    Text(
                                        text = "QMusic Bilgisayarınıza Kuruldu!",
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = Color.White,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Masaüstü simgeniz ve tüm ses bileşenleri hazır. Artık QMusic'i masaüstünüzden tek tıkla açabilirsiniz.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.68f),
                                    )

                                    Spacer(Modifier.height(22.dp))

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(DarkSurface)
                                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        SummaryRow("Masaüstü Simgesi", if (createDesktopShortcut) "Eklendi (QMusic.lnk)" else "Atlandı")
                                        SummaryRow("Başlat Menüsü", if (createStartMenuShortcut) "Eklendi" else "Atlandı")
                                        SummaryRow("Sürüm", "QMusic Desktop v2.0.3")
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White)
                                        .clickable(onClick = onInstallFinished),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = QMusicIcons.Play,
                                            contentDescription = null,
                                            tint = BgRoot,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = "QMusic'i Şimdi Başlat",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                            ),
                                            color = BgRoot,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallerBrandPanel(
    isInstalling: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "installerPulse")
    val eq1 by pulse.animateFloat(
        initialValue = 0.48f,
        targetValue = 1.28f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq1",
    )
    val eq2 by pulse.animateFloat(
        initialValue = 1.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing), RepeatMode.Reverse),
        label = "eq2",
    )
    val glyph = remember { PathParser().parsePathString(Q_GLYPH_PATH).toPath() }

    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF12121A), Color(0xFF07070A)),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Official Squircle App Icon Preview with live pulsing equalizer bars
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1A24), Color(0xFF09090C)),
                        ),
                    )
                    .border(1.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(76.dp)) {
                    val s = size.minDimension / 512f
                    withTransform({
                        scale(s, s, pivot = Offset.Zero)
                    }) {
                        drawPath(glyph, Color.White)
                        val bar1H = 48f * (if (isInstalling) eq1 else 1f)
                        val bar2H = 80f * (if (isInstalling) eq2 else 1f)
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(206f, 248f - bar1H / 2f),
                            size = Size(16f, bar1H),
                            cornerRadius = CornerRadius(8f, 8f),
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(238f, 248f - bar2H / 2f),
                            size = Size(16f, bar2H),
                            cornerRadius = CornerRadius(8f, 8f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            QMusicWordmark(fontSize = 26f, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "MASAÜSTÜ SÜRÜMÜ • v2.0.3",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandFeatureItem(
                icon = QMusicIcons.Headphones,
                title = "YouTube Music Masaüstü",
                subtitle = "Sol kenar çubuğu, tam genişlikte alt bar ve çift sütunlu oynatıcı",
            )
            BrandFeatureItem(
                icon = QMusicIcons.Tune,
                title = "Stüdyo Ses Motoru",
                subtitle = "10 bant ekolayzır, yumuşak geçiş (Crossfade) ve ses eşitleme",
            )
            BrandFeatureItem(
                icon = QMusicIcons.DownloadDone,
                title = "Çevrimdışı & Dinamik Renk",
                subtitle = "4 kanallı hızlı indirme ve kapak rengine duyarlı ambiyans",
            )
        }

        Text(
            text = "Mrquake tarafından tasarlandı",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun BrandFeatureItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                ),
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun InstallerOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (checked) DarkSurfaceVariant else DarkSurface,
        animationSpec = tween(200),
        label = "optionBg",
    )
    val border by animateColorAsState(
        targetValue = if (checked) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f),
        animationSpec = tween(200),
        label = "optionBorder",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) Color.White else Color.White.copy(alpha = 0.08f))
                .border(1.dp, if (checked) Color.White else Color.White.copy(alpha = 0.24f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = QMusicIcons.Check,
                    contentDescription = null,
                    tint = BgRoot,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                ),
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Color.White.copy(alpha = 0.56f),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
}
