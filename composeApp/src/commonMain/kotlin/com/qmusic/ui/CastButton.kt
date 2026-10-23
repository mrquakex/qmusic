package com.qmusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The Chromecast button; it shows only while a cast device is on the network. Nothing where casting is not offered. */
@Composable
expect fun CastButton(modifier: Modifier = Modifier)
