package com.thirdcoastsailing.app

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Alarm
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Which weather layers are switched on in the menu. colorKind is WIND_SPEED, GUST or null (only one at a time). */
data class WxLayers(val colorKind: Wx?, val barbs: Boolean, val waves: Boolean) {
    val any get() = colorKind != null || barbs || waves
    val windBox get() = colorKind != null || barbs
}

/** Decoded forecast fields for one forecast time; only the ones the active layers need are loaded. */
class WxFrame(
    val timeMs: Long,
    val speed: WeatherRepo.Field? = null,
    val dir: WeatherRepo.Field? = null,
    val gust: WeatherRepo.Field? = null,
    val waveH: WeatherRepo.Field? = null,
    val waveDir: WeatherRepo.Field? = null,
    val wavePer: WeatherRepo.Field? = null,
) {
    val empty get() = speed == null && gust == null && waveH == null

    companion object {
        /** Load what the layers need for a time. Decodes GRIB data: call off the main thread. */
        fun load(repo: WeatherRepo, layers: WxLayers, timeMs: Long): WxFrame {
            val wantWind = layers.colorKind == Wx.WIND_SPEED || layers.barbs
            return WxFrame(
                timeMs = timeMs,
                speed = if (wantWind) repo.field(Wx.WIND_SPEED, timeMs) else null,
                dir = if (layers.barbs) repo.field(Wx.WIND_DIR, timeMs) else null,
                gust = if (layers.colorKind == Wx.GUST) repo.field(Wx.GUST, timeMs) else null,
                waveH = if (layers.waves) repo.field(Wx.WAVE_HEIGHT, timeMs) else null,
                waveDir = if (layers.waves) repo.field(Wx.WAVE_DIR, timeMs) else null,
                wavePer = if (layers.waves) repo.field(Wx.WAVE_PERIOD, timeMs) else null,
            )
        }
    }
}

// Wind colour scale (knots): blue → green → yellow → orange → red at 25+
private val windStops = listOf(
    0.0 to Color(0xFF3B6FB6), 8.0 to Color(0xFF3DBE6B), 14.0 to Color(0xFFF2D028),
    20.0 to Color(0xFFF28C28), 25.0 to Color(0xFFE5484D),
)
const val FIELD_OPACITY = 0.45f              // colour field transparency on the chart

private val BarbNavy = Color(0xFF0F2742)
private val BarbEdge = Color(0xFFF4F1EA)

// Wave crest colours: under 2 ft, 2-4 ft, 4-6 ft, 6 ft and up
private val WaveLow = Color(0xFFE6F2FF)
private val WaveMid = Color(0xFF7FD3F0)
private val WaveHigh = Color(0xFFF2C14E)
private val WaveBig = Color(0xFFE5484D)

private const val WAVE_FRAME_MS = 100L      // wave animation step: about 10 updates a second
private const val WAVE_MIN_ZOOM = 7.0      // waves stop animating (and are drawn still) when zoomed out past this

/** Colour for a wind speed in knots, from the scale above. */
fun windColor(kn: Double): Color {
    if (kn <= windStops.first().first) return windStops.first().second
    for (i in 1 until windStops.size) {
        val (k1, c1) = windStops[i]
        if (kn <= k1) {
            val (k0, c0) = windStops[i - 1]
            val t = ((kn - k0) / (k1 - k0)).toFloat()
            return Color(c0.red + (c1.red - c0.red) * t, c0.green + (c1.green - c0.green) * t,
                c0.blue + (c1.blue - c0.blue) * t, 1f)
        }
    }
    return windStops.last().second
}

private fun waveColor(ft: Double) = when {
    ft < 2 -> WaveLow; ft < 4 -> WaveMid; ft < 6 -> WaveHigh; else -> WaveBig
}

// ---------------------------------------------------------------------------------------- colour field image

/** Colour field as an image with its lat/lon corners, for a MapLibre image source (so it pans and zooms with the chart). */
class FieldImage(val bitmap: Bitmap, val quad: LatLngQuad)

private fun mercY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
private fun mercLat(y: Double) = Math.toDegrees(2 * atan(exp(y)) - PI / 2)

/**
 * Sample a wind or gust field over a lon/lat box into an image. Rows are spaced evenly in Web-Mercator y,
 * which is how MapLibre stretches an image between its corners, so every pixel lands on the right spot.
 * About 2.5 km per pixel; the map smooths between pixels. Slow-ish: call off the main thread.
 * @param box lon_min, lat_min, lon_max, lat_max
 */
fun buildFieldImage(src: WeatherRepo.Field, box: DoubleArray): FieldImage? {
    val step = 0.025
    val cols = ((box[2] - box[0]) / step).roundToInt().coerceIn(2, 900)
    val yTop = mercY(box[3])
    val yBot = mercY(box[1])
    val rows = ((yTop - yBot) / Math.toRadians(step)).roundToInt().coerceIn(2, 900)
    val px = IntArray(cols * rows)
    var any = false
    for (r in 0 until rows) {
        val lat = mercLat(yTop + (yBot - yTop) * (r + 0.5) / rows)
        for (c in 0 until cols) {
            val lon = box[0] + (box[2] - box[0]) * (c + 0.5) / cols
            val v = src.at(lat, lon)
            if (!v.isNaN()) { px[r * cols + c] = windColor(v * WeatherRepo.MS_TO_KN).toArgb(); any = true }
        }
    }
    if (!any) return null
    val bmp = Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
    val quad = LatLngQuad(LatLng(box[3], box[0]), LatLng(box[3], box[2]), LatLng(box[1], box[2]), LatLng(box[1], box[0]))
    return FieldImage(bmp, quad)
}

// ---------------------------------------------------------------------------------------- barbs and wave crests

/** A barb or wave crest anchored to a lat/lon, with the forecast values there. */
private class GeoSite(val lat: Double, val lon: Double, val a: Double, val b: Double, val c: Double = Double.NaN)

/**
 * Lat/lon grid covering the view plus a margin, spaced so points land roughly `spacingPx` apart on screen.
 * The spacing snaps to powers of two so small pans keep the same points. half = shift by half a step (for waves).
 */
private fun geoGrid(m: MapLibreMap, wPx: Float, hPx: Float, spacingPx: Float, half: Boolean): List<Pair<Double, Double>> {
    val proj = m.projection
    val c = proj.fromScreenLocation(PointF(wPx / 2, hPx / 2))
    val e = proj.fromScreenLocation(PointF(wPx / 2 + spacingPx, hPx / 2))
    val meters = c.distanceTo(e)
    if (meters <= 0 || meters.isNaN()) return emptyList()
    val rawLat = meters / 111_320.0
    val stepLat = 0.001 * 2.0.pow(log2(rawLat / 0.001).roundToInt())
    val coslat = cos(Math.toRadians(c.latitude)).coerceAtLeast(0.2)
    val stepLon = 0.001 * 2.0.pow(log2(rawLat / coslat / 0.001).roundToInt())
    val b = proj.visibleRegion.latLngBounds
    val padLat = (b.latitudeNorth - b.latitudeSouth) * 0.4
    val padLon = (b.longitudeEast - b.longitudeWest) * 0.4
    val off = if (half) 0.5 else 0.0
    val out = mutableListOf<Pair<Double, Double>>()
    var lat = (floor((b.latitudeSouth - padLat) / stepLat) + off) * stepLat
    while (lat <= b.latitudeNorth + padLat) {
        var lon = (floor((b.longitudeWest - padLon) / stepLon) + off) * stepLon
        while (lon <= b.longitudeEast + padLon) {
            out += lat to lon
            if (out.size > 2500) return out                      // guard against a runaway grid
            lon += stepLon
        }
        lat += stepLat
    }
    return out
}

/**
 * Wind barbs and animated wave crests over the chart. Each mark is pinned to a lat/lon, so it moves with every pan,
 * zoom and rotation; the set of marks is re-spaced when the camera comes to rest (idleTick).
 * The wind colour field is not drawn here: it is a map layer (see buildFieldImage), so it moves with the chart natively.
 * @param tick changes on every camera move (redraw); idleTick changes when the camera stops (re-space the marks)
 */
@Composable
fun WeatherOverlay(map: MapLibreMap?, tick: Int, idleTick: Int, frame: WxFrame?, layers: WxLayers, modifier: Modifier = Modifier) {
    val m = map ?: return
    val f = frame ?: return
    if (!layers.barbs && !layers.waves) return
    val view = m.projection                                    // always reflects the live camera
    var wPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var hPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    val barbs = remember(idleTick, f, layers.barbs, wPx, hPx) {
        val sp = f.speed; val dr = f.dir
        if (!layers.barbs || sp == null || dr == null || wPx == 0f) emptyList()
        else geoGrid(m, wPx, hPx, 64f * densityOf(m), half = false).mapNotNull { (lat, lon) ->
            val s = sp.at(lat, lon); val d = dr.at(lat, lon)
            if (s.isNaN() || d.isNaN()) null else GeoSite(lat, lon, s * WeatherRepo.MS_TO_KN, d.toDouble())
        }
    }
    val waves = remember(idleTick, f, layers.waves, wPx, hPx) {
        val h = f.waveH
        if (!layers.waves || h == null || wPx == 0f) emptyList()
        else geoGrid(m, wPx, hPx, 72f * densityOf(m), half = true).mapNotNull { (lat, lon) ->
            val ht = h.at(lat, lon)
            val d = f.waveDir?.at(lat, lon) ?: Float.NaN
            if (ht.isNaN() || d.isNaN()) null
            else GeoSite(lat, lon, ht * WeatherRepo.M_TO_FT, d.toDouble(), f.wavePer?.at(lat, lon)?.toDouble() ?: Double.NaN)
        }
    }

    // wave animation clock: runs only while crests are shown and zoomed in enough, at about 10 updates a second
    // (the crests drift slowly, so this looks the same as every frame for a fraction of the battery)
    var nowMs by remember { mutableLongStateOf(0L) }
    val animate = waves.isNotEmpty() && m.cameraPosition.zoom >= WAVE_MIN_ZOOM
    LaunchedEffect(animate) { if (animate) while (true) { withFrameMillis { nowMs = it }; delay(WAVE_FRAME_MS) } }

    Canvas(modifier.fillMaxSize().onSizeChanged { wPx = it.width.toFloat(); hPx = it.height.toFloat() }) {
        tick                                                     // read so camera moves redraw
        val bearing = m.cameraPosition.bearing
        val margin = 80.dp.toPx()
        fun screen(lat: Double, lon: Double): Offset? {
            val p = view.toScreenLocation(LatLng(lat, lon))
            return if (p.x < -margin || p.y < -margin || p.x > size.width + margin || p.y > size.height + margin) null
            else Offset(p.x, p.y)
        }
        val t = if (animate) nowMs / 1000.0 else 0.0
        for (w in waves) screen(w.lat, w.lon)?.let { drawCrests(it, w, bearing, t) }
        for (b in barbs) screen(b.lat, b.lon)?.let { drawBarb(it, (b.b - bearing).toFloat(), b.a) }
    }
}

/** Screen density (px per dp) from the map's view context. */
private fun densityOf(m: MapLibreMap): Float = android.content.res.Resources.getSystem().displayMetrics.density

/**
 * Standard wind barb: the staff points toward where the wind comes FROM; feathers on the clockwise side of the staff
 * (northern hemisphere). Half feather 5 kt, full feather 10 kt, pennant 50 kt; a circle means calm (under 3 kt).
 * Drawn navy with a light edge so it reads on both day and night charts.
 * @param fromScreenDeg wind-from direction on screen, degrees clockwise from screen up
 */
private fun DrawScope.drawBarb(p: Offset, fromScreenDeg: Float, kn: Double) {
    val edge = 3.6.dp.toPx()
    val ink = 1.8.dp.toPx()
    val rounded = (kn / 5).roundToInt() * 5
    if (rounded == 0) {
        drawCircle(BarbEdge, 5.dp.toPx(), p, style = Stroke(edge))
        drawCircle(BarbNavy, 5.dp.toPx(), p, style = Stroke(ink))
        return
    }
    val a = Math.toRadians(fromScreenDeg.toDouble())
    val u = Offset(sin(a).toFloat(), -cos(a).toFloat())          // along the staff, toward the wind source
    val r = Offset(-u.y, u.x)                                   // clockwise side for the feathers
    val staff = 28.dp.toPx()
    val full = 12.dp.toPx()
    val gap = 4.5.dp.toPx()
    val pennantBase = 6.dp.toPx()
    val tip = p + u * staff

    val lines = Path().apply { moveTo(p.x, p.y); lineTo(tip.x, tip.y) }
    val pennants = Path()
    var left = rounded
    var q = tip
    while (left >= 50) {                                         // pennants first, from the tip inward
        val base2 = q - u * pennantBase
        val apex = q + r * full
        pennants.moveTo(q.x, q.y); pennants.lineTo(apex.x, apex.y); pennants.lineTo(base2.x, base2.y); pennants.close()
        q = base2 - u * (gap * 0.5f); left -= 50
    }
    while (left >= 10) {                                         // full feathers
        val end = q + r * full + u * (full * 0.35f)
        lines.moveTo(q.x, q.y); lines.lineTo(end.x, end.y)
        q -= u * gap; left -= 10
    }
    if (left >= 5) {                                             // half feather; set in from the tip when alone
        if (q == tip) q -= u * gap
        val end = q + r * (full * 0.5f) + u * (full * 0.18f)
        lines.moveTo(q.x, q.y); lines.lineTo(end.x, end.y)
    }
    drawPath(lines, BarbEdge, style = Stroke(edge, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(pennants, BarbEdge, style = Stroke(edge * 0.8f, join = StrokeJoin.Round))
    drawPath(lines, BarbNavy, style = Stroke(ink, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(pennants, BarbNavy)
    drawCircle(BarbNavy, 2.dp.toPx(), p)
}

/**
 * Two short crest lines across the direction of travel, drifting the way the waves move and fading in and out
 * as they cycle. Length, thickness and colour scale with height. t = seconds (0 = still).
 */
private fun DrawScope.drawCrests(at: Offset, s: GeoSite, mapBearing: Double, t: Double) {
    val heightFt = s.a
    val travel = Math.toRadians(s.b + 180 - mapBearing)        // waves travel opposite to the direction they come from
    val u = Offset(sin(travel).toFloat(), -cos(travel).toFloat())
    val across = Offset(-u.y, u.x)
    val half = (9 + 4.5 * heightFt.coerceAtMost(10.0)).dp.toPx() / 2
    val width = (1.4 + 0.45 * heightFt.coerceAtMost(10.0)).dp.toPx()
    val spacing = 18.dp.toPx()
    val period = if (s.c.isNaN() || s.c <= 0) 5.0 else s.c
    val speed = (3.5 * period).dp.toPx()                        // longer-period swell drifts faster, like the real thing
    val phase = ((t * speed) % spacing).toFloat()
    val color = waveColor(heightFt)
    for (k in -1..1) {
        val d = k * spacing + phase                              // position along the travel direction
        val frac = (d + spacing) / (3 * spacing)                 // 0..1 across the three slots
        val alpha = (sin(Math.PI * frac).toFloat()).coerceIn(0f, 1f) * 0.95f
        if (alpha < 0.05f) continue
        val c = at + u * d
        // slight bow toward the direction of travel, like a real crest
        val path = Path().apply {
            val a0 = c - across * half
            val a1 = c + across * half
            val ctrl = c + u * (half * 0.35f)
            moveTo(a0.x, a0.y); quadraticTo(ctrl.x, ctrl.y, a1.x, a1.y)
        }
        drawPath(path, BarbNavy.copy(alpha = alpha * 0.55f), style = Stroke(width + 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------------------- bottom weather panel

/**
 * Weather boxes along the bottom of the screen: a Wind box (colour scale, forecast time slider, last update)
 * and a Waves box (crest colour key, wave model run). Only the boxes for switched-on layers show.
 * The forecast slider lives in the first box; the "Updated … ↻" line in the first box forces one update when tapped.
 * Offline mode = orange border. The update time turns orange after 6 h and red after 12 h.
 */
@Composable
fun WeatherPanel(
    layers: WxLayers, aheadH: Int, maxH: Int, onAhead: (Int) -> Unit, forecastLabel: String, noData: Boolean,
    lastUpdateMs: Long, nowMs: Long, updating: Boolean, offline: Boolean, waveRun: String?, onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!layers.any) return
    val shape = RoundedCornerShape(12.dp)
    fun Modifier.box() = this.widthIn(max = 200.dp).clip(shape).background(Navy.copy(alpha = 0.93f), shape)
        .then(if (offline) Modifier.border(2.5.dp, SignalOrange, shape) else Modifier)
        .padding(horizontal = 10.dp, vertical = 7.dp)

    val age = nowMs - lastUpdateMs
    val stampColor = when {
        updating || lastUpdateMs == 0L -> Foam
        age > 12 * 3_600_000L -> Alarm
        age > 6 * 3_600_000L -> SignalOrange
        else -> Foam
    }
    val stamp = when {
        updating -> "updating…"
        lastUpdateMs == 0L -> "never"
        else -> WeatherRepo.stamp(lastUpdateMs)
    }

    @Composable
    fun UpdateLine() {
        HorizontalDivider(color = NavyLight, modifier = Modifier.padding(top = 4.dp, bottom = 3.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onUpdate), verticalAlignment = Alignment.CenterVertically) {
            Text("Updated ", color = Mist, fontSize = 10.5.sp)
            Text(stamp, color = stampColor, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
            Text("↻", color = SignalOrange, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun Header(title: String, unit: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SignalOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", color = Mist, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(if (noData) "no forecast" else forecastLabel, color = if (noData) SignalOrange else Foam, fontSize = 11.sp)
        }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        if (layers.windBox) Column(Modifier.weight(1f, fill = false).box()) {
            when (layers.colorKind) {
                Wx.GUST -> Header("GUSTS", "kt")
                Wx.WIND_SPEED -> Header("WIND", "kt")
                else -> Header("BARBS", "kt")
            }
            if (layers.colorKind != null) {
                Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp)) {
                    drawRoundRect(Brush.horizontalGradient(*windStops.map { (k, c) -> (k / 25.0).toFloat() to c }.toTypedArray()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                }
                Row(Modifier.fillMaxWidth().padding(top = 1.dp)) {
                    listOf("0", "5", "10", "15", "20", "25+").forEachIndexed { i, l ->
                        Text(l, color = Mist, fontSize = 9.5.sp, modifier = Modifier.weight(1f),
                            textAlign = if (i == 0) TextAlign.Start else if (i == 5) TextAlign.End else TextAlign.Center)
                    }
                }
            }
            HourSlider(aheadH, maxH, onAhead, Modifier.padding(top = 2.dp))
            UpdateLine()
        }
        if (layers.waves) Column(Modifier.weight(1f, fill = false).box()) {
            Header("WAVES", "ft")
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                listOf(WaveLow to "<2", WaveMid to "2–4", WaveHigh to "4–6", WaveBig to "6+").forEachIndexed { i, (c, l) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.width(30.dp).height(12.dp)) {
                            val half = (8 + i * 2.2f).dp.toPx()
                            val sw = (1.8f + i * 0.7f).dp.toPx()
                            val cx = size.width / 2
                            val path = Path().apply {
                                moveTo(cx - half, size.height * 0.85f)
                                quadraticTo(cx, -size.height * 0.15f, cx + half, size.height * 0.85f)
                            }
                            drawPath(path, BarbNavy, style = Stroke(sw + 2.dp.toPx(), cap = StrokeCap.Round))
                            drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        Text(l, color = Mist, fontSize = 10.sp)
                    }
                }
            }
            if (layers.windBox) {
                HorizontalDivider(color = NavyLight, modifier = Modifier.padding(top = 6.dp, bottom = 3.dp))
                Text("Model run ${waveRun ?: "—"}", color = Mist, fontSize = 10.5.sp)
            } else {
                HourSlider(aheadH, maxH, onAhead, Modifier.padding(top = 2.dp))
                UpdateLine()
            }
        }
    }
}

/** Compact forecast-hour slider: thin track, round orange thumb. Tap or drag anywhere on it. */
@Composable
fun HourSlider(value: Int, max: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val maxV by rememberUpdatedState(max)
    Canvas(
        modifier.fillMaxWidth().height(24.dp)
            .pointerInput(Unit) {
                fun pick(x: Float) {
                    val r = 9.dp.toPx()
                    val f = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                    change((f * maxV).roundToInt())
                }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { ch, _ ->
                    val r = 9.dp.toPx()
                    val f = ((ch.position.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                    change((f * maxV).roundToInt())
                }
            }
    ) {
        val r = 9.dp.toPx()
        val y = size.height / 2
        val x0 = r
        val x1 = size.width - r
        val xt = x0 + (x1 - x0) * (if (max > 0) value.toFloat() / max else 0f)
        drawLine(NavyLight, Offset(x0, y), Offset(x1, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(SignalOrange, Offset(x0, y), Offset(xt, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(SignalOrange, r, Offset(xt, y))
        drawCircle(Navy, r, Offset(xt, y), style = Stroke(2.dp.toPx()))
    }
}
