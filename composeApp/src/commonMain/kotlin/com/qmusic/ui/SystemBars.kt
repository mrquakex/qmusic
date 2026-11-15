package com.qmusic.ui

import androidx.compose.runtime.Composable

/** The phone's status and navigation bars draw dark icons on the light theme, light ones on the dark theme. */
@Composable
expect fun SystemBarsTheme(light: Boolean)
