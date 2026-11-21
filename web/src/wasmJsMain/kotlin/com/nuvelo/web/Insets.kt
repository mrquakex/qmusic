package com.nuvelo.web

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp

/**
 * The phone's notch and home bar (an app on the home screen draws under them), in CSS pixels, which are the app's dp.
 * The bottom one is 0 while the keyboard is up (it covers the home bar). index.html measures them and calls in.
 */
object SafeArea {
    var top by mutableStateOf(0f)
    var bottom by mutableStateOf(0f)

    init {
        listenSafeArea { t, b ->
            top = t.toFloat()
            bottom = b.toFloat()
        }
    }
}

@JsFun("(f) => { window.nuveloSafeArea = f; if (window.nuveloSafeAreaNow) window.nuveloSafeAreaNow(); }")
private external fun listenSafeArea(f: (Double, Double) -> Unit)

fun Modifier.safeTop(): Modifier = composed { padding(top = SafeArea.top.dp) }

fun Modifier.safeBottom(): Modifier = composed { padding(bottom = SafeArea.bottom.dp) }
