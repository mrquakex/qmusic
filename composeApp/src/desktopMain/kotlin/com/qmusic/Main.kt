package com.qmusic

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import com.qmusic.ui.theme.QMusicLogoSymbol
import com.qmusic.ui.viewmodel.MainViewModel
import com.qmusic.ui.viewmodel.SpotifyLinker
import java.awt.Dimension
import java.io.File

fun main() = application {
    val baseDir = remember { File(System.getProperty("user.home"), ".qmusic").apply { mkdirs() } }
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
        width = 430.dp,
        height = 880.dp,
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "QMusic",
        icon = rememberVectorPainter(QMusicLogoSymbol),
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                DesktopBackDispatcher.dispatchBack()
            } else {
                false
            }
        },
    ) {
        window.minimumSize = Dimension(360, 640)
        QMusicApp(viewModel = viewModel)
    }
}
