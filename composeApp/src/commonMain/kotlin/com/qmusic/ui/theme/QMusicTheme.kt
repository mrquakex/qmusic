package com.qmusic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Monochrome design system: black and white, no colored accents. Dark by default; light turns it over (black ink on
// white paper). Every color here is read as state, so a change of theme redraws everything.

/** Settings › Appearance › Pure black: the backgrounds go to #000 (OLED pixels off). Read as state, so it redraws. */
var amoledTheme by mutableStateOf(false)

/** Settings › Appearance › Theme: light (or the phone's light theme when it follows the system). */
var lightTheme by mutableStateOf(false)

/** The ink: white on the dark theme, black on the light one (text, icons, the main buttons' fill). */
val Ink: Color get() = if (lightTheme) Color.Black else Color.White

/** What stands on the ink: black on the dark theme, white on the light one (a main button's label). */
val InkInverse: Color get() = if (lightTheme) Color.White else Color.Black

val BgRoot: Color get() = when {
    lightTheme -> Color(0xFFFFFFFF)
    amoledTheme -> Color.Black
    else -> Color(0xFF050507)
}
val DarkBackground: Color get() = BgRoot
val BgSurface: Color get() = when {
    lightTheme -> Color(0xFFF7F7F8)
    amoledTheme -> Color.Black
    else -> Color(0xFF0B0B0F)
}
val DarkSurface: Color get() = when {
    lightTheme -> Color(0xFFF2F2F4)
    amoledTheme -> Color(0xFF0A0A0A)
    else -> Color(0xFF101016)
}
val DarkSurfaceVariant: Color get() = when {
    lightTheme -> Color(0xFFEAEAEE)
    amoledTheme -> Color(0xFF151515)
    else -> Color(0xFF181820)
}
val TextPrimary: Color get() = if (lightTheme) Color(0xFF0A0A0C) else Color(0xFFFFFFFF)
val TextSecondary: Color get() = Ink.copy(alpha = if (lightTheme) 0.62f else 0.65f)
val TextMuted: Color get() = Ink.copy(alpha = if (lightTheme) 0.42f else 0.40f)
val BorderSubtle: Color get() = Ink.copy(alpha = if (lightTheme) 0.10f else 0.08f)
val BorderHover: Color get() = Ink.copy(alpha = if (lightTheme) 0.25f else 0.22f)

// The always-dark screens (now playing, calls) keep these whatever the theme: what they show is a picture or a call.
val NightBgRoot: Color get() = if (amoledTheme) Color.Black else Color(0xFF050507)
val NightSurfaceVariant: Color get() = if (amoledTheme) Color(0xFF151515) else Color(0xFF181820)
val NightTextPrimary: Color = Color(0xFFFFFFFF)
val NightTextSecondary: Color = Color.White.copy(alpha = 0.65f)

val QMusicColorScheme get() = (if (lightTheme) androidx.compose.material3.lightColorScheme() else darkColorScheme()).copy(
    primary = Ink,
    onPrimary = BgRoot,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = Ink,
    secondary = Ink.copy(alpha = 0.75f),
    onSecondary = BgRoot,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = Ink,
    tertiary = Ink.copy(alpha = 0.65f),
    onTertiary = BgRoot,
    background = BgRoot,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    outline = BorderHover,
    outlineVariant = BorderSubtle,
    error = Ink,
    onError = BgRoot,
    surfaceContainerLowest = BgRoot,
    surfaceContainerLow = BgSurface,
    surfaceContainerHighest = DarkSurfaceVariant,
    inverseSurface = Ink,
    inverseOnSurface = BgRoot,
)

val QMusicShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val QMusicTypography get() = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        color = TextPrimary,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = TextPrimary,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = TextPrimary,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = TextPrimary,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = TextPrimary,
    ),
    // No fixed color: this is the default text style, and buttons/chips must be able to recolor their label.
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondary,
    ),
    // No fixed color: buttons and chips draw their label in the container's content color.
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondary,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = TextSecondary,
    ),
)

@Composable
fun QMusicTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = QMusicColorScheme,
        typography = QMusicTypography,
        shapes = QMusicShapes,
    ) {
        // Plain Text on the black background reads white unless a container (e.g. a button) says otherwise.
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}
