package com.thirdcoastsailing.app

import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thirdcoastsailing.app.ui.theme.Foam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.FeatureCollection
import kotlin.math.hypot

/* The chart: MapLibre's MapView inside Compose, its style, camera moves, taps, route-pin dragging and the overlays. */

/** Zoom cap so charts are not stretched past their scale. */
private const val MAX_ZOOM = 17.0
/** Hold on a route point this long (without moving) to drag it (ms). */
private const val HOLD_MS = 450L
/** The start pin let go this close to the boat (on screen, dp) snaps onto it. */
private const val SNAP_DP = 40f
/** Map source + layer for the wind / gust color field. */
private const val WX_SOURCE = "wx_field"
/** Map layer id of the wind / gust color field. */
private const val WX_LAYER = "wx_field_layer"
/** Default zoom when the chart centers on the boat. */
private const val BOAT_ZOOM = 12.0

/** What the chart shows on top of the charts themselves. */
class ChartContent(
    val track: List<GeoPt>, val trackText: String?, val trackLive: Boolean,
    val fix: BoatFix?, val avgSog: Double?,
    val frame: WxFrame?, val layers: WxLayers, val currentField: CurrentField?,
    val routePts: List<GeoPt>, val calc: RouteResult?, val routeEtas: List<PinEta>,
    val buoys: FeatureCollection, val selected: LatLng?, val celestial: CelestialPlot?, val lightsAnimate: Boolean,
    val outside: Boolean, val safetyOn: Boolean,
)

/** Requests to move the camera: each bumps a counter (center on the boat, show all charts, show one region). */
class CameraRequests(val center: Int, val overview: Int, val showRegion: Pair<Int, String>?)

/** What the chart reports back. */
class ChartCallbacks(
    val onTap: (LatLng) -> Boolean, val onInfo: (Info?) -> Unit, val onMap: (MapLibreMap) -> Unit,
    val onMovePoint: (Int, GeoPt) -> Unit, val onDragEnd: () -> Unit, val onSnapStart: () -> Unit,
)

/** The chart: MapLibre MapView inside Compose, restyled when the regions, day/night or the boat's minimum depth change. */
@Composable
fun ChartMap(lakes: List<Lake>, night: Boolean, depthLimitFt: Double, content: ChartContent,
             camera: CameraRequests, cb: ChartCallbacks) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centered by remember { mutableStateOf(false) }
    // camera counters: read only by the overlays (below), so a pan redraws them without recomposing this whole screen
    val tick = remember { mutableIntStateOf(0) }              // bumps on every camera move so the overlays follow
    val idleTick = remember { mutableIntStateOf(0) }          // bumps when the camera stops (weather marks re-space)
    val currentLakes by rememberUpdatedState(lakes)
    val currentCb by rememberUpdatedState(cb)
    val fix = content.fix

    // Forward lifecycle events; MapView needs them
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // low memory: let the map engine drop its caches
    DisposableEffect(context) {
        val cbs = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(c: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { mapView.onLowMemory() }
            override fun onTrimMemory(level: Int) {}
        }
        context.registerComponentCallbacks(cbs)
        onDispose { context.unregisterComponentCallbacks(cbs) }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.setMaxZoomPreference(MAX_ZOOM)
            m.setMinZoomPreference(GreatLakes.MIN_ZOOM)
            m.setLatLngBoundsForCameraTarget(GreatLakes.bounds)       // the chart stays on the charted waters
            m.uiSettings.isCompassEnabled = true
            // north always up: no two-finger rotate or tilt (the screen can still turn to landscape)
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = DebugStart.camera ?: GreatLakes.startView(lakes.map { it.id })
            // Land check for barbs, crests and current arrows: a snapshot of the map (LandMask), re-taken when the map
            // settles. "Did become idle" doesn't always come after a pan (the weather layers keep it drawing), so the
            // camera stopping also takes one shortly after (tiles drawn) and again a little later (slow tiles).
            var snapGen = 0
            fun snap(gen: Int) { if (gen == snapGen) m.snapshot { b -> if (gen == snapGen) { LandMask.bitmap = b; idleTick.intValue++ } } }
            m.addOnCameraMoveListener { tick.intValue++; snapGen++ }
            m.addOnCameraIdleListener {
                tick.intValue++; idleTick.intValue++
                val gen = ++snapGen
                mapView.postDelayed({ snap(gen) }, 350)
                mapView.postDelayed({ snap(gen) }, 1500)
            }
            // once per camera position: a snapshot makes the map draw a frame, which can report "idle" again
            var idleSnapped = -1
            mapView.addOnDidFinishLoadingStyleListener { snapGen++ }   // re-snapshot once the new style is drawn
            mapView.addOnDidBecomeIdleListener { if (idleSnapped != snapGen) { idleSnapped = snapGen; snap(snapGen) } }
            // Tap: look for a harbor, buoy or navigation aid within a finger's width
            m.addOnMapClickListener { ll ->
                if (currentCb.onTap(ll)) return@addOnMapClickListener true
                val p = m.projection.toScreenLocation(ll)
                val r = 28f * density
                val ids = ChartStyle.tapLayers(currentLakes)
                val hit = if (ids.isEmpty()) null
                else ids.firstNotNullOfOrNull { id ->
                    m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), id).firstOrNull()?.let { it to id }
                }
                currentCb.onInfo(hit?.let { (f, id) -> toInfo(f, id) })
                hit != null
            }
            map = m
            currentCb.onMap(m)
        }
    }

    RoutePinDrag(mapView, map, content.routePts, fix, currentCb)

    // (Re)build the style whenever the region set, day/night or the boat's minimum depth changes
    LaunchedEffect(map, lakes, night, depthLimitFt) {
        val m = map ?: return@LaunchedEffect
        val basemap = withContext(Dispatchers.IO) { Basemap.file(context) }     // copied from the assets once
        val builder = Style.Builder().fromJson(ChartStyle.build(lakes, night, depthLimitFt, basemap))
            .withImage(ChartStyle.MARINA_ICON, ChartStyle.marinaBitmap(density))
            .withImage(ChartStyle.SHALLOW_HATCH, ChartStyle.hatchBitmap(density))
        for ((name, bmp) in NavStyle.navImages(density)) builder.withImage(name, bmp)
        m.setStyle(builder) { s -> style = s; tick.intValue++ }
    }

    // Center on the first fix, and whenever the center button is pressed: boat centered, north up, default zoom.
    // (Outside the region the boat isn't passed in unless MOB or the anchor alarm is on, so the view stays put.)
    // The first time (once the style is in, so nothing that sets up the map cancels it) the view jumps there;
    // the button animates.
    LaunchedEffect(map, style != null, fix != null, camera.center) {
        val f = fix ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        if ((centered || DebugStart.camera != null) && camera.center == 0) return@LaunchedEffect   // (debug start position: stay there)
        val update = CameraUpdateFactory.newCameraPosition(boatView(f))
        if (!centered && camera.center == 0) m.moveCamera(update) else m.animateCamera(update)
        centered = true
    }

    // Hold on the center button: fit every installed chart's box, plus the boat, on screen (the zoom-out limit
    // still applies: charts far apart, e.g. Hawaii and the lakes, end up centered between them at that limit)
    LaunchedEffect(map, camera.overview) {
        val m = map ?: return@LaunchedEffect
        if (camera.overview == 0) return@LaunchedEffect
        val boxes = lakes.mapNotNull { WeatherRepo.LAKE_BOX[it.id] }
        if (boxes.isEmpty()) return@LaunchedEffect
        centered = true
        val bb = LatLngBounds.Builder()
        for (b in boxes) bb.include(LatLng(b[3], b[2])).include(LatLng(b[1], b[0]))
        fix?.let { f -> if (GreatLakes.contains(f.lat, f.lon)) bb.include(LatLng(f.lat, f.lon)) }
        // margins keep the boxes clear of the logo (top), the right-edge buttons and the weather boxes (bottom)
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bb.build(),
            (40 * density).toInt(), (100 * density).toInt(), (90 * density).toInt(), (90 * density).toInt()))
    }

    // Charts → Show: fit the region's box on screen (and keep the first GPS fix from pulling the view back to the boat)
    LaunchedEffect(map, camera.showRegion) {
        val m = map ?: return@LaunchedEffect
        val b = camera.showRegion?.let { WeatherRepo.LAKE_BOX[it.second] } ?: return@LaunchedEffect
        centered = true
        val box = LatLngBounds.Builder().include(LatLng(b[3], b[2])).include(LatLng(b[1], b[0])).build()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(box, (24 * density).toInt()))
    }

    // Pan limit: on the charted waters, except while MOB or the anchor alarm is on outside the region, when the chart
    // follows the boat over the grey "no chart" background. When that ends the view goes back.
    val outside = content.outside; val safetyOn = content.safetyOn
    val lifted = outside && safetyOn
    var wasLifted by remember { mutableStateOf(false) }
    LaunchedEffect(map, lifted) {
        val m = map ?: return@LaunchedEffect
        if (lifted) {
            m.setLatLngBoundsForCameraTarget(null)
            fix?.let { f -> m.animateCamera(CameraUpdateFactory.newCameraPosition(boatView(f))) }
        } else {
            m.setLatLngBoundsForCameraTarget(GreatLakes.bounds)
            // back from outside: on the boat if it is now inside the region, otherwise the home view
            val f = fix
            if (wasLifted) m.animateCamera(CameraUpdateFactory.newCameraPosition(
                if (!outside && f != null) boatView(f) else GreatLakes.HOME))
        }
        wasLifted = lifted
    }
    // Phone outside the region (no MOB or anchor): back to the home view
    LaunchedEffect(map, outside && !safetyOn) {
        val m = map ?: return@LaunchedEffect
        if (outside && !safetyOn) m.animateCamera(CameraUpdateFactory.newCameraPosition(GreatLakes.HOME))
    }

    WindFieldLayer(style, content.frame, content.layers, lakes)
    BuoyLayer(style, content.buoys)

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        ChartOverlays(map, tick, idleTick, lakes, night, content)
    }
}

/** Camera centered on the boat: north up, default zoom. */
private fun boatView(f: BoatFix): CameraPosition =
    CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom(BOAT_ZOOM).bearing(0.0).tilt(0.0).build()

/**
 * Everything drawn over the chart. The camera counters are read here, so a pan or zoom recomposes only the overlays,
 * not the screen around them.
 */
@Composable
private fun ChartOverlays(map: MapLibreMap?, tickState: IntState, idleState: IntState, lakes: List<Lake>, night: Boolean,
                          c: ChartContent) {
    val tick = tickState.intValue
    val idleTick = idleState.intValue
    val lightLayers = remember(lakes) { lakes.flatMap { NavStyle.lightLayers("lake_${it.id}") } }
    CurrentsOverlay(map, tick, idleTick, c.currentField)
    WeatherOverlay(map, tick, idleTick, c.frame, c.layers)
    NavLightGlow(map, tick, idleTick, c.lightsAnimate, lightLayers)
    RouteOverlay(map, tick, c.routePts, c.calc, night, c.routeEtas)
    TrackOverlay(map, tick, c.track, if (c.trackLive) c.fix else null, c.trackText, night)
    CelestialOverlay(map, tick, c.celestial, night)
    AnchorOverlay(map, tick, night)
    MobOverlay(map, tick, c.fix)
    CrashOverlay(map, tick)
    SelectionRing(map, tick, c.selected)
    BoatOverlay(map, c.fix, c.avgSog, tick, c.currentField)
    if (c.outside && !c.safetyOn) RegionLogo(map, tick)              // outside the charted waters: the logo instead
}

/**
 * Route point drag: a short hold on a numbered pin without moving (HOLD_MS) picks it up with a buzz, then it follows
 * the finger; letting go saves the route. A tap, swipe, pan or pinch never moves a point; those go to the map as usual.
 */
@Composable
private fun RoutePinDrag(mapView: MapView, map: MapLibreMap?, routePts: List<GeoPt>, fix: BoatFix?, cb: ChartCallbacks) {
    val context = LocalContext.current
    val currentRoute by rememberUpdatedState(routePts)
    val currentCb by rememberUpdatedState(cb)
    val currentFix by rememberUpdatedState(fix)
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose { }
        val density = context.resources.displayMetrics.density
        val hitPx = 28f * density
        val pinHeadPx = ROUTE_PIN_HEAD_DP * density
        val snapPx = SNAP_DP * density
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f; var downY = 0f
        var candidate = -1                                   // pin under the finger, waiting for the hold
        var dragging = -1                                    // pin being moved
        val pickUp = Runnable {
            if (candidate < 0) return@Runnable
            dragging = candidate
            mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // end the map's own gesture so it doesn't pan along with the point
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            mapView.onTouchEvent(cancel)
            cancel.recycle()
        }
        mapView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; dragging = -1
                    // a pin is hit at its tip (the point) or its head, drawn above the tip
                    fun hit(i: Int): Float {
                        val s = m.projection.toScreenLocation(LatLng(currentRoute[i].lat, currentRoute[i].lon))
                        return minOf(hypot(s.x - e.x, s.y - e.y), hypot(s.x - e.x, s.y - pinHeadPx - e.y))
                    }
                    candidate = currentRoute.indices.minByOrNull { hit(it) }?.takeIf { hit(it) <= hitPx } ?: -1
                    if (candidate >= 0) handler.postDelayed(pickUp, HOLD_MS)
                    false                                    // the map still sees the touch (it may become a pan)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging >= 0) {
                        val ll = m.projection.fromScreenLocation(PointF(e.x, e.y))
                        currentCb.onMovePoint(dragging, GeoPt(ll.latitude, ll.longitude))
                        true
                    } else {
                        if (candidate >= 0 && hypot(e.x - downX, e.y - downY) > slop) {   // moved: it's a pan, not a hold
                            handler.removeCallbacks(pickUp); candidate = -1
                        }
                        false
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {         // second finger: pinch/zoom, never a drag
                    handler.removeCallbacks(pickUp); candidate = -1
                    dragging >= 0
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(pickUp); candidate = -1
                    val wasDragging = dragging >= 0
                    // start pin let go next to the boat: snap it on; otherwise the drag ends where it was let go
                    val f = currentFix
                    val snap = dragging == 0 && e.actionMasked == MotionEvent.ACTION_UP && f != null &&
                        m.projection.toScreenLocation(LatLng(f.lat, f.lon)).let { b -> hypot(b.x - e.x, b.y - e.y) <= snapPx }
                    if (snap) currentCb.onSnapStart() else if (wasDragging) currentCb.onDragEnd()
                    dragging = -1
                    wasDragging
                }
                else -> dragging >= 0
            }
        }
        onDispose {
            mapView.setOnTouchListener(null); handler.removeCallbacks(pickUp)
            if (dragging >= 0) currentCb.onDragEnd()         // the screen went away mid-drag: save where the pin is
        }
    }
}

/**
 * Wind / gust color field as map image layers under the land, so they move with the chart and tint water only.
 * One image for the Great Lakes together (masked to the GLWU water) and one per coastal zone (no mask: the coarse
 * GFS-Wave grid has no values in bays and sounds, and the chart's land layer hides the color anyway), so a phone
 * with, say, Michigan and Hawaii doesn't stretch one blurry image across the continent.
 */
@Composable
private fun WindFieldLayer(style: Style?, frame: WxFrame?, layers: WxLayers, lakes: List<Lake>) {
    LaunchedEffect(style, frame, layers.colorKind, lakes) {
        val s = style ?: return@LaunchedEffect
        val src = when (layers.colorKind) { Wx.WIND_SPEED -> frame?.speed; Wx.GUST -> frame?.gust; else -> null }
        val ids = lakes.map { it.id }
        val groups = buildList {
            val gl = ids.filter { it in WeatherRepo.GREAT_LAKES }
            if (gl.isNotEmpty()) add(Triple("", WeatherRepo.boxFor(gl), true))
            ids.filter { it !in WeatherRepo.GREAT_LAKES }.forEach { add(Triple("_$it", WeatherRepo.boxFor(listOf(it)), false)) }
        }
        val imgs = if (src == null) emptyMap() else withContext(Dispatchers.Default) {
            groups.mapNotNull { (suffix, box, masked) ->
                box ?: return@mapNotNull null
                runCatching { buildFieldImage(src, box, if (masked) frame?.waveH else null) }.getOrNull()?.let { suffix to it }
            }.toMap()
        }
        if (!s.isFullyLoaded) return@LaunchedEffect
        // drop images of regions no longer shown
        s.layers.map { it.id }.filter { it.startsWith(WX_LAYER) && it.removePrefix(WX_LAYER) !in imgs }.forEach { s.removeLayer(it) }
        s.sources.map { it.id }.filter { it.startsWith(WX_SOURCE) && it.removePrefix(WX_SOURCE) !in imgs }.forEach { s.removeSource(it) }
        for ((suffix, img) in imgs) {
            val existing = s.getSourceAs<ImageSource>(WX_SOURCE + suffix)
            if (existing != null) {
                existing.setCoordinates(img.quad)
                existing.setImage(img.bitmap)
            } else {
                s.addSource(ImageSource(WX_SOURCE + suffix, img.quad, img.bitmap))
                val layer = RasterLayer(WX_LAYER + suffix, WX_SOURCE + suffix).withProperties(PropertyFactory.rasterOpacity(FIELD_OPACITY))
                // under the basemap's inland layer (land outside the charts), else under the first chart land
                val anchor = listOf(ChartStyle.INLAND_LAYER, ChartStyle.landAnchor(lakes)).firstOrNull { it != null && s.getLayer(it) != null }
                if (anchor != null) s.addLayerBelow(layer, anchor) else s.addLayer(layer)
            }
        }
    }
}

/** Live buoy markers on top of the chart: fill = wind speed, ring = yellow over 3 ft / red over 5 ft waves. */
@Composable
private fun BuoyLayer(style: Style?, buoys: FeatureCollection) {
    LaunchedEffect(style, buoys) {
        val s = style ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        val src = s.getSourceAs<GeoJsonSource>(BUOY_SOURCE)
        if (src != null) src.setGeoJson(buoys)
        else {
            s.addSource(GeoJsonSource(BUOY_SOURCE, buoys))
            s.addLayer(CircleLayer(BUOY_LAYER, BUOY_SOURCE).withProperties(
                PropertyFactory.circleRadius(6.5f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("ring"))),
                PropertyFactory.circleStrokeWidth(Expression.get("ringW")),
            ).apply { minZoom = 6f })
        }
    }
}

/** Blue ring around the marina or buoy whose info window is open, so it's clear which one was tapped. */
@Composable
private fun SelectionRing(map: MapLibreMap?, tick: Int, at: LatLng?) {
    Canvas(Modifier.fillMaxSize()) {
        tick                                                     // read so camera moves redraw
        val m = map ?: return@Canvas
        val p = at ?: return@Canvas
        val s = m.projection.toScreenLocation(p)
        val c = Offset(s.x, s.y)
        val r = 15.dp.toPx()
        drawCircle(Foam, r, c, style = Stroke(6.dp.toPx()))
        drawCircle(Color(0xFF1E88FF), r, c, style = Stroke(3.5.dp.toPx()))
    }
}

/** True from sunset to sunrise (today's times, worked out once a day) at `pos`; false when unknown. */
fun sunIsDown(pos: Pair<Double, Double>?, nowMs: Long): Boolean {
    val p = pos ?: return false
    val d = SunMoon.today(p.first, p.second, nowMs)
    val rise = d.sunriseMs; val set = d.sunsetMs
    return when {
        rise != null && nowMs < rise -> true
        set != null && nowMs >= set -> true
        else -> false
    }
}
