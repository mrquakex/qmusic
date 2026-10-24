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

// Monochrome design system: pure black / white, no colored accents.

/** Settings › Appearance › Pure black: the backgrounds go to #000 (OLED pixels off). Read as state, so it redraws. */
var amoledTheme by mutableStateOf(false)

val BgRoot: Color get() = if (amoledTheme) Color.Black else Color(0xFF050507)
val DarkBackground: Color get() = BgRoot
val BgSurface: Color get() = if (amoledTheme) Color.Black else Color(0xFF0B0B0F)
val DarkSurface: Color get() = if (amoledTheme) Color(0xFF0A0A0A) else Color(0xFF101016)
val DarkSurfaceVariant: Color get() = if (amoledTheme) Color(0xFF151515) else Color(0xFF181820)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color.White.copy(alpha = 0.65f)
val TextMuted = Color.White.copy(alpha = 0.40f)
val BorderSubtle = Color.White.copy(alpha = 0.08f)
val BorderHover = Color.White.copy(alpha = 0.22f)

val QMusicColorScheme get() = darkColorScheme(
    primary = Color.White,
    onPrimary = BgRoot,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = Color.White,
    secondary = Color.White.copy(alpha = 0.75f),
    onSecondary = BgRoot,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = Color.White,
    tertiary = Color.White.copy(alpha = 0.65f),
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
    error = Color.White,
    onError = BgRoot,
)

val QMusicShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val QMusicTypography = Typography(
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
