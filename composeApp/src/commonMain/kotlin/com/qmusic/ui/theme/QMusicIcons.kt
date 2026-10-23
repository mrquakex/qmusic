package com.qmusic.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * QMusic monochrome icon set (24x24). Geometry matches the brand design system:
 * rounded ends, 2.2 stroke, same corner language as the "q" logo.
 * All icons are tintable (drawn in white, recolored via Icon tint).
 */
object QMusicIcons {
    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    fill = SolidColor(Color.White),
                )
            }
        }.build()

    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Play: ImageVector = filled(
        "Play",
        "M7.5 4.8a1.8 1.8 0 0 1 2.7-1.5l10.2 6.2a1.8 1.8 0 0 1 0 3l-10.2 6.2a1.8 1.8 0 0 1-2.7-1.5V4.8z",
    )

    val Pause: ImageVector = filled(
        "Pause",
        "M8 4h.5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M15.5 4h.5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-.5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
    )

    val Next: ImageVector = filled(
        "Next",
        "M18 5a1 1 0 0 1 1 1v12a1 1 0 1 1-2 0V6a1 1 0 0 1 1-1zM6.6 6.2a1.5 1.5 0 0 0-2.4 1.2v9.2a1.5 1.5 0 0 0 2.4 1.2l6.2-4.6a1.5 1.5 0 0 0 0-2.4L6.6 6.2z",
    )

    val Previous: ImageVector = filled(
        "Previous",
        "M6 5a1 1 0 0 1 1 1v12a1 1 0 1 1-2 0V6a1 1 0 0 1 1-1zm11.4 1.2a1.5 1.5 0 0 1 2.4 1.2v9.2a1.5 1.5 0 0 1-2.4 1.2l-6.2-4.6a1.5 1.5 0 0 1 0-2.4l6.2-4.6z",
    )

    val Shuffle: ImageVector = stroked(
        "Shuffle",
        "M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5",
    )

    val Repeat: ImageVector = stroked(
        "Repeat",
        "M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3",
    )

    val Queue: ImageVector = stroked(
        "Queue",
        "M4 6H20M4 12H14M4 18H18",
    )

    val Volume: ImageVector = stroked(
        "Volume",
        "M11 5L6 9H2v6h4l5 4V5zM15.54 8.46a5 5 0 0 1 0 7.07M19.07 4.93a10 10 0 0 1 0 14.14",
    )

    val Home: ImageVector = stroked(
        "Home",
        "M3 9.5L12 3l9 6.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1V9.5z",
    )

    val Search: ImageVector = stroked(
        "Search",
        "M18 11a7 7 0 1 1-14 0a7 7 0 0 1 14 0zM20 20L16.2 16.2",
    )

    val Library: ImageVector = stroked(
        "Library",
        "M4.5 4h1A1.5 1.5 0 0 1 7 5.5v13A1.5 1.5 0 0 1 5.5 20h-1A1.5 1.5 0 0 1 3 18.5v-13A1.5 1.5 0 0 1 4.5 4z",
        "M11.5 4h1A1.5 1.5 0 0 1 14 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-1A1.5 1.5 0 0 1 10 18.5v-13A1.5 1.5 0 0 1 11.5 4z",
        "M17 4l4 16",
    )

    private const val HEART_PATH =
        "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"

    val Heart: ImageVector = stroked("Heart", HEART_PATH)

    val HeartFilled: ImageVector = filled("HeartFilled", HEART_PATH)

    // Utility glyphs in the same visual language.
    val Close: ImageVector = stroked("Close", "M6 6L18 18M18 6L6 18")

    val Back: ImageVector = stroked("Back", "M15 5l-7 7 7 7")

    val Add: ImageVector = stroked("Add", "M12 5V19M5 12H19")

    val Settings: ImageVector = stroked(
        "Settings",
        "M4 7H14M18 7H20M4 17H6M10 17H20M16 4V10M8 14V20",
    )

    val Account: ImageVector = stroked(
        "Account",
        "M20 21v-1a5 5 0 0 0-5-5H9a5 5 0 0 0-5 5v1M16 8a4 4 0 1 1-8 0a4 4 0 0 1 8 0z",
    )

    val Check: ImageVector = stroked("Check", "M5 12.5l4.5 4.5L19 7.5")

    val Refresh: ImageVector = stroked(
        "Refresh",
        "M20 11a8 8 0 1 0-2.34 5.66M20 4v7h-7",
    )

    val ChevronDown: ImageVector = stroked("ChevronDown", "M6 9l6 6 6-6")

    val Tune: ImageVector = stroked(
        "Tune",
        "M4 7H14M18 7H20M4 17H6M10 17H20M16 4V10M8 14V20",
    )

    val History: ImageVector = stroked(
        "History",
        "M12 7v5l3 2",
        "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z",
    )

    /** Arrow that points up-left: copies a suggestion into the search box without searching. */
    val FillQuery: ImageVector = stroked("FillQuery", "M17 17L7 7M7 7v8M7 7h8")

    /** Three dots; the round caps of the zero-length segments draw them. */
    val More: ImageVector = stroked("More", "M12 5.5h.01M12 12h.01M12 18.5h.01")

    val Moon: ImageVector = stroked("Moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")

    val Download: ImageVector = stroked("Download", "M12 4v11M7 11l5 5 5-5M5 20h14")

    val DownloadDone: ImageVector = stroked(
        "DownloadDone",
        "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z",
        "M8 12.5l2.7 2.7L16 9.8",
    )

    val Lyrics: ImageVector = stroked("Lyrics", "M4 5h16M4 10h11M4 15h16M4 20h9")

    val CloudOff: ImageVector = stroked(
        "CloudOff",
        "M6.5 19A4.5 4.5 0 0 1 6 10a6 6 0 0 1 11.3-1.5A4 4 0 0 1 18 19z",
        "M4 4l16 16",
    )

    val Dice: ImageVector = stroked(
        "Dice",
        "M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4z",
        "M8 8h.01M16 8h.01M12 12h.01M8 16h.01M16 16h.01",
    )

    val DragHandle: ImageVector = stroked("DragHandle", "M5 9h14M5 15h14")

    val Share: ImageVector = stroked("Share", "M12 3v12M8 7l4-4 4 4M5 12v7h14v-7")

    val Radio: ImageVector = stroked("Radio", "M5 9a10 10 0 0 0 0 6M19 9a10 10 0 0 1 0 6M8.5 7a6 6 0 0 0 0 10M15.5 7a6 6 0 0 1 0 10", "M12 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z")

    val Trash: ImageVector = stroked("Trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6")

    val Edit: ImageVector = stroked("Edit", "M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4")

    val PlaylistAdd: ImageVector = stroked("PlaylistAdd", "M4 6h12M4 11h8M4 16h8M17 12v8M13 16h8")

    val RepeatOne: ImageVector = stroked(
        "RepeatOne",
        "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4",
        "M11 10.5l1.5-1V15",
    )

    val Headphones: ImageVector = stroked(
        "Headphones",
        "M4 15v-3a8 8 0 0 1 16 0v3",
        "M4 15h3v5H5a1 1 0 0 1-1-1v-4zM20 15h-3v5h2a1 1 0 0 0 1-1v-4z",
    )

    val VideoPlay: ImageVector = stroked("VideoPlay", "M4 5h16v14H4z", "M10 9l5 3-5 3z")
}
