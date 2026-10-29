package com.qmusic

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qmusic.database.createJvmDatabase
import com.qmusic.database.repository.MusicRepositoryImpl
import com.qmusic.engine.YouTube
import com.qmusic.engine.auth.YouTubeAuth
import com.qmusic.player.DesktopPlayerController
import com.qmusic.spotify.SpotifyImporter
import com.qmusic.ui.DesktopBackDispatcher
import com.qmusic.ui.QMusicApp
import com.qmusic.ui.components.DesktopWindowActions
import com.qmusic.ui.components.LocalDesktopWindowActions
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.SpotifyLinker
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File

fun main(args: Array<String>) = application {
    val baseDir = remember { File(System.getProperty("user.home"), ".qmusic").apply { mkdirs() } }
    val windowIcons = remember { DesktopIcon.createWindowIcons() }
    val primaryPainter = remember(windowIcons) { windowIcons.last().toPainter() }

    val forceInstaller = remember { args.any { it.equals("--install", ignoreCase = true) || it.equals("--setup", ignoreCase = true) } }
    var showInstaller by remember {
        mutableStateOf(forceInstaller || !DesktopInstallManager.isInstalled(baseDir))
    }

    if (showInstaller) {
        val installerWindowState = rememberWindowState(
            width = 780.dp,
            height = 540.dp,
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = installerWindowState,
            title = "QMusic Kurulum Sihirbazı",
            icon = primaryPainter,
            undecorated = true,
            transparent = true,
            resizable = false,
        ) {
            LaunchedEffect(windowIcons) {
                window.iconImages = windowIcons
            }
            QMusicInstallerScreen(
                baseDir = baseDir,
                onInstallFinished = { showInstaller = false },
                onCloseRequest = ::exitApplication,
            )
        }
    } else {
        LaunchedEffect(Unit) {
            DesktopInstallManager.ensureIconAndDesktopShortcut(baseDir)
        }

        val viewModel = remember {
            val database = createJvmDatabase(File(baseDir, "qmusic.db"))
            val repository = MusicRepositoryImpl(database)
            val settings = DesktopAppSettings(baseDir)
            val sessionStore = DesktopYouTubeSessionStore(baseDir)
            val youTube = YouTube()
            val youTubeAuth = YouTubeAuth(cookieString = sessionStore.load())
            val spotifyImporter = SpotifyImporter.create()
            val spotifyLinker = SpotifyLinker(youTube, spotifyImporter, settings)
            val playerController = DesktopPlayerController(
                youTube = youTube,
                spotifyMatcher = { song -> spotifyLinker.youtubeId(song) },
                baseDir = baseDir,
            )

            MainViewModel(
                youTube = youTube,
                playerController = playerController,
                repository = repository,
                spotifyImporter = spotifyImporter,
                youTubeAuth = youTubeAuth,
                onSessionChanged = sessionStore::save,
                downloads = playerController.downloads,
                settings = settings,
                spotifyLinker = spotifyLinker,
            )
        }

        val windowState = rememberWindowState(
            width = 1360.dp,
            height = 860.dp,
            position = WindowPosition(Alignment.Center),
        )
        var isMaximized by remember { mutableStateOf(false) }
        var restoredBounds by remember { mutableStateOf<Rectangle?>(null) }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "QMusic",
            icon = primaryPainter,
            undecorated = true,
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    DesktopBackDispatcher.dispatchBack()
                } else {
                    false
                }
            },
        ) {
            LaunchedEffect(windowIcons) {
                window.background = java.awt.Color(5, 5, 7)
                window.iconImages = windowIcons
            }
            window.minimumSize = Dimension(900, 620)

            val toggleMaximize: () -> Unit = {
                if (!isMaximized) {
                    restoredBounds = window.bounds
                    val maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    window.bounds = maxBounds
                    isMaximized = true
                } else {
                    restoredBounds?.let { window.bounds = it }
                    isMaximized = false
                }
            }

            val windowActions = remember(isMaximized) {
                DesktopWindowActions(
                    isMaximized = isMaximized,
                    onMinimize = { windowState.isMinimized = true },
                    onToggleMaximize = toggleMaximize,
                    onClose = ::exitApplication,
                    dragArea = { content ->
                        WindowDraggableArea(
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { toggleMaximize() })
                            },
                        ) {
                            content()
                        }
                    },
                )
            }

            CompositionLocalProvider(LocalDesktopWindowActions provides windowActions) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!isMaximized) {
                                Modifier.border(1.dp, Color.White.copy(alpha = 0.10f))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    QMusicApp(viewModel = viewModel)
                }
            }
        }
    }
}
