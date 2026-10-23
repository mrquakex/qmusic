package com.qmusic.ui

import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
actual fun CastButton(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // A dark AppCompat context gives the button its white icon and the picker its dark look.
            MediaRouteButton(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)).also { button ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, button) }
            }
        },
    )
}
