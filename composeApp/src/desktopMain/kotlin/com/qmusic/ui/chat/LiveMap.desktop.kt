package com.qmusic.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** The computer has no vector map: the OpenStreetMap pictures, moved by hand and by the buttons' camera. */
@Composable
internal actual fun LiveMap(
    pins: List<LiveMapPin>,
    control: LiveMapControl,
    start: Pair<Double, Double>,
    startZoom: Double,
    modifier: Modifier,
    fitBottom: Dp,
    onGesture: () -> Unit,
    onPinTap: (String) -> Unit,
    onCenter: (Double, Double) -> Unit,
) {
    var center by remember { mutableStateOf(start) }
    var level by remember { mutableStateOf(startZoom.toInt() + 1) }
    control.camera = object : LiveMapControl.Camera {
        override fun move(lat: Double, lng: Double, zoom: Double?, animate: Boolean) {
            center = lat to lng
            if (zoom != null) level = zoom.toInt() + 1
        }

        override fun fit(points: List<Pair<Double, Double>>) {
            if (points.isEmpty()) return
            center = points.sumOf { it.first } / points.size to points.sumOf { it.second } / points.size
        }
    }
    OsmMap(
        center.first, center.second, level,
        pins.map { MapMarker(it.lat, it.lng, it.user, it.live) },
        modifier,
        draggable = true,
        onMoved = { la, ln ->
            onGesture()
            center = la to ln
            onCenter(la, ln)
        },
    )
}

@Composable
internal actual fun MapPreview(lat: Double, lng: Double, zoom: Double, modifier: Modifier) {
    OsmMap(lat, lng, zoom.toInt() + 1, emptyList(), modifier)
}
