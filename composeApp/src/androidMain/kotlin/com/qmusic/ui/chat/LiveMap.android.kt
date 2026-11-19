package com.qmusic.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.Gravity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** The map's look: a free vector map (no key), dark in the dark theme. */
private fun styleUrl(dark: Boolean) = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/liberty"

/** If that map cannot be reached: OpenStreetMap's pictures through Nuvelo's own server. */
private const val FALLBACK_STYLE = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://qeyf.159.195.147.140.sslip.io/tiles/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap"}},"layers":[{"id":"osm","type":"raster","source":"osm"}]}"""

@Composable
private fun darkMap(): Boolean = com.qmusic.ui.theme.BgRoot.luminance() < 0.5f

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
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = darkMap()
    val latestGesture by rememberUpdatedState(onGesture)
    val latestCenter by rememberUpdatedState(onCenter)
    val mapView = remember {
        MapLibre.getInstance(context)
        // A TextureView, so the map takes part in the screen's animations (a SurfaceView would not fade or scale).
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped as the camera moves: the pins, halos and trails over the map follow it.
    var cameraTick by remember { mutableIntStateOf(0) }

    // The map lives as long as this screen, and sleeps with it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            control.camera = null
            runCatching {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(mapView) {
        var fellBack = false
        mapView.addOnDidFailLoadingMapListener {
            if (!fellBack) {
                fellBack = true
                map?.setStyle(Style.Builder().fromJson(FALLBACK_STYLE))
            }
        }
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isCompassEnabled = true
                compassGravity = Gravity.TOP or Gravity.END
                val top = with(density) { 130.dp.roundToPx() }
                val side = with(density) { 14.dp.roundToPx() }
                setCompassMargins(0, top, side, 0)
                attributionGravity = Gravity.TOP or Gravity.START
                setAttributionMargins(side, top, 0, 0)
            }
            m.setStyle(Style.Builder().fromUri(styleUrl(dark)))
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.first, start.second)).zoom(startZoom).build()
            m.addOnCameraMoveListener { cameraTick++ }
            m.addOnCameraIdleListener {
                cameraTick++
                val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                latestCenter(t.latitude, t.longitude)
            }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) = latestGesture()
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })
            map = m
            control.camera = object : LiveMapControl.Camera {
                override fun move(lat: Double, lng: Double, zoom: Double?, animate: Boolean) {
                    val update = if (zoom != null) CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom) else CameraUpdateFactory.newLatLng(LatLng(lat, lng))
                    if (animate) m.animateCamera(update, 750) else m.moveCamera(update)
                }

                override fun fit(points: List<Pair<Double, Double>>) {
                    if (points.isEmpty()) return
                    if (points.size == 1 || points.all { distanceMeters(it.first, it.second, points[0].first, points[0].second) < 50 }) {
                        move(points[0].first, points[0].second, 15.5, true)
                        return
                    }
                    val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
                    val side = with(density) { 70.dp.roundToPx() }
                    val top = with(density) { 140.dp.roundToPx() }
                    val bottom = with(density) { (fitBottom + 60.dp).roundToPx() }
                    m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, side, top, side, bottom), 800)
                }
            }
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val m = map ?: return@Box
        // The ways they came, and how sure each place is: drawn over the map, under the pictures.
        Canvas(Modifier.fillMaxSize()) {
            cameraTick.let { }
            val projection = m.projection
            pins.forEach { pin ->
                val color = if (pin.me) MeBlue else LiveGreen
                if (pin.trail.size >= 2) {
                    val path = androidx.compose.ui.graphics.Path()
                    (pin.trail + (pin.lat to pin.lng)).forEachIndexed { i, (a, b) ->
                        val p = projection.toScreenLocation(LatLng(a, b))
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val acc = pin.accuracy
                if (acc != null && acc > 8 && pin.live) {
                    val p = projection.toScreenLocation(LatLng(pin.lat, pin.lng))
                    val radius = (acc / projection.getMetersPerPixelAtLatitude(pin.lat)).toFloat()
                    if (radius > 30.dp.toPx()) {
                        drawCircle(color.copy(alpha = 0.12f), radius, Offset(p.x, p.y))
                        drawCircle(color.copy(alpha = 0.35f), radius, Offset(p.x, p.y), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        val half = with(density) { (PIN_SIZE / 2).roundToPx() }
        val mapBearing = run { cameraTick; m.cameraPosition.bearing.toFloat() }
        pins.forEach { pin ->
            key(pin.key) {
                // Glides to a new place instead of jumping to it.
                val glide = remember { Animatable(1f) }
                var from by remember { mutableStateOf(pin.lat to pin.lng) }
                var to by remember { mutableStateOf(pin.lat to pin.lng) }
                LaunchedEffect(pin.lat, pin.lng) {
                    if (to.first != pin.lat || to.second != pin.lng) {
                        val t = glide.value.toDouble()
                        from = (from.first + (to.first - from.first) * t) to (from.second + (to.second - from.second) * t)
                        to = pin.lat to pin.lng
                        glide.snapTo(0f)
                        glide.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing))
                    }
                }
                LivePinView(
                    pin,
                    mapBearing,
                    Modifier
                        .offset {
                            cameraTick.let { }
                            val t = glide.value.toDouble()
                            val lat = from.first + (to.first - from.first) * t
                            val lng = from.second + (to.second - from.second) * t
                            val p = m.projection.toScreenLocation(LatLng(lat, lng))
                            IntOffset(p.x.roundToInt() - half, p.y.roundToInt() - half)
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPinTap(pin.key) },
                )
            }
        }
    }
}

// ==================================================================== a still picture of a place (the chat's card)

private object Snapshots {
    val cache = LruCache<String, Bitmap>(24)
}

/** A sharp picture of the map around a place (the vector map, drawn once and kept), for cards in the chat. */
@Composable
internal actual fun MapPreview(lat: Double, lng: Double, zoom: Double, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = darkMap()
    BoxWithConstraints(modifier.background(if (dark) Color(0xFF1E1E1E) else Color(0xFFE5E3DF))) {
        val widthDp = maxWidth.value.roundToInt().coerceAtLeast(1)
        val heightDp = maxHeight.value.roundToInt().coerceAtLeast(1)
        // About 20 m apart counts as the same place: the picture is not drawn again for every small move.
        val key = "${(lat * 5000).roundToInt()},${(lng * 5000).roundToInt()},$zoom,$widthDp,$heightDp,$dark"
        var bitmap by remember(key) { mutableStateOf(Snapshots.cache.get(key)) }
        LaunchedEffect(key) {
            if (bitmap != null) return@LaunchedEffect
            bitmap = snapshot(context, lat, lng, zoom, widthDp, heightDp, density.density, dark)?.also { Snapshots.cache.put(key, it) }
        }
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private suspend fun snapshot(context: Context, lat: Double, lng: Double, zoom: Double, widthDp: Int, heightDp: Int, ratio: Float, dark: Boolean): Bitmap? =
    suspendCancellableCoroutine { cont ->
        runCatching {
            MapLibre.getInstance(context)
            val options = MapSnapshotter.Options(widthDp, heightDp)
                .withStyleBuilder(Style.Builder().fromUri(styleUrl(dark)))
                .withCameraPosition(CameraPosition.Builder().target(LatLng(lat, lng)).zoom(zoom).build())
                .withPixelRatio(ratio)
                .withLogo(false)
            val snapshotter = MapSnapshotter(context, options)
            snapshotter.start(
                { snap -> if (cont.isActive) cont.resume(snap.bitmap) },
                { _ -> if (cont.isActive) cont.resume(null) },
            )
            cont.invokeOnCancellation { runCatching { snapshotter.cancel() } }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }
