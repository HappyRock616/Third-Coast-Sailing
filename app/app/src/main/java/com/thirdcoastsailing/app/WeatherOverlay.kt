package com.thirdcoastsailing.app

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.drawText
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

/**
 * Decoded forecast fields for one forecast time; only the ones the active layers need are loaded.
 * Wave height is also loaded for the wind / gust color: the wave model covers the lakes only, so it is the water mask
 * that keeps the color off land at every zoom.
 */
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
                waveH = if (layers.waves || layers.colorKind != null) repo.field(Wx.WAVE_HEIGHT, timeMs) else null,
                waveDir = if (layers.waves) repo.field(Wx.WAVE_DIR, timeMs) else null,
                wavePer = if (layers.waves) repo.field(Wx.WAVE_PERIOD, timeMs) else null,
            )
        }
    }
}

// Wind color scale (knots): blue → green → yellow → orange → red at 25+
private val windStops = listOf(
    0.0 to Color(0xFF3B6FB6), 8.0 to Color(0xFF3DBE6B), 14.0 to Color(0xFFF2D028),
    20.0 to Color(0xFFF28C28), 25.0 to Color(0xFFE5484D),
)
const val FIELD_OPACITY = 0.45f              // color field transparency on the chart

/** Wind barb fill. */
private val BarbNavy = Color(0xFF0F2742)
/** Wind barb outline, so barbs read on any color. */
private val BarbEdge = Color(0xFFF4F1EA)

// Wave crest colors: under 2 ft, 2-4 ft, 4-6 ft, 6 ft and up
private val WaveLow = Color(0xFFE6F2FF)
/** Waves 2–4 ft. */
private val WaveMid = Color(0xFF7FD3F0)
/** Waves 4–6 ft. */
private val WaveHigh = Color(0xFFF2C14E)
/** Waves 6 ft and up. */
private val WaveBig = Color(0xFFE5484D)

private const val WAVE_FRAME_MS = 100L      // wave animation step: about 10 updates a second

/** Color for a wind speed in knots, from the scale above. */
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

/** Crest color for a wave height in feet. */
private fun waveColor(ft: Double) = when {
    ft < 2 -> WaveLow; ft < 4 -> WaveMid; ft < 6 -> WaveHigh; else -> WaveBig
}

// ---------------------------------------------------------------------------------------- color field image

/** Color field as an image with its lat/lon corners, for a MapLibre image source (so it pans and zooms with the chart). */
class FieldImage(val bitmap: Bitmap, val quad: LatLngQuad)

/** Web-Mercator y of a latitude (the map's vertical scale). */
private fun mercY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
/** Latitude of a Web-Mercator y. */
private fun mercLat(y: Double) = Math.toDegrees(2 * atan(exp(y)) - PI / 2)

/**
 * Sample a wind or gust field over a lon/lat box into an image. Rows are spaced evenly in Web-Mercator y,
 * which is how MapLibre stretches an image between its corners, so every pixel lands on the right spot.
 * About 2.5 km per pixel; the map smooths between pixels. Slow-ish: call off the main thread.
 * @param box lon_min, lat_min, lon_max, lat_max
 * @param water wave-height field used as a water mask: pixels where the wave model has no value (land) stay clear,
 *              so the color never shows on land, whichever lakes are downloaded and however far out you zoom.
 *              Null (no wave forecast yet): no mask, the chart's land layer hides the color as before.
 */
fun buildFieldImage(src: WeatherRepo.Field, box: DoubleArray, water: WeatherRepo.Field? = null): FieldImage? {
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
            if (water != null && !water.hasNearest(lat, lon)) continue          // land: leave clear
            val v = src.at(lat, lon)
            if (!v.isNaN()) { px[r * cols + c] = windColor(v * MS_TO_KN).toArgb(); any = true }
        }
    }
    if (!any) return null
    val bmp = Bitmap.createBitmap(px, cols, rows, Bitmap.Config.ARGB_8888)
    val quad = LatLngQuad(LatLng(box[3], box[0]), LatLng(box[3], box[2]), LatLng(box[1], box[2]), LatLng(box[1], box[0]))
    return FieldImage(bmp, quad)
}

// ---------------------------------------------------------------------------------------- barbs and wave crests

/**
 * Land check for barbs and crests: true when the map shows land at that spot (a chart's land, or the basemap's land
 * outside every chart area). Off-screen spots are never "land" here; marks are re-placed when the camera stops.
 * Keeps wind barbs off shore beyond a coastal chart's 5-mile strip, and wave crests off Cape Cod and the islands
 * (the wave model's 0.16 deg grid counts them as water).
 */
internal class LandCheck(private val m: MapLibreMap, private val wPx: Float, private val hPx: Float) {
    // Read from a snapshot of the chart taken when the map last went idle (LandMask), not queryRenderedFeatures:
    // that hands back each land polygon whole for every point asked, and with the current arrows asking thousands
    // of times per redraw it froze the app (ANR). Land is the one flat land colour (the weather colour sits under it).
    private val bmp = LandMask.bitmap?.takeIf { it.width > 0 && it.width.toFloat() == wPx }

    fun onLand(lat: Double, lon: Double): Boolean {
        val b = bmp ?: return false
        val p = m.projection.toScreenLocation(LatLng(lat, lon))
        // well off the screen: not drawn anyway. Near or over the edge: checked at the nearest pixels on screen
        // (a barb half off the edge over land used to slip through)
        if (p.x < -60 || p.y < -60 || p.x > b.width + 60 || p.y > b.height + 60) return false
        val cx = p.x.toInt().coerceIn(R, b.width - 1 - R)
        val cy = p.y.toInt().coerceIn(R, b.height - 1 - R)
        // nine pixels (centre and a ring 7 px out): a label, symbol or line over the land doesn't make it water
        var n = 0
        for ((dx, dy) in SAMPLES) if (LandMask.isLand(b.getPixel(cx + dx, cy + dy))) n++
        return n >= 3
    }

    private companion object {
        const val R = 8
        val SAMPLES = listOf(0 to 0, 7 to 0, -7 to 0, 0 to 7, 0 to -7, 5 to 5, -5 to 5, 5 to -5, -5 to -5)
    }
}

/** The last idle snapshot of the map, for LandCheck; set from the map's idle listener (see ChartMap). */
internal object LandMask {
    @Volatile var bitmap: Bitmap? = null
    /** Day and night land fill (ChartStyle) and the faded land of a basemap outside every chart. */
    private val LAND = listOf(0xE9DDBF, 0x2A2D31)

    fun isLand(argb: Int): Boolean = LAND.any { c ->
        kotlin.math.abs(((argb shr 16) and 0xFF) - ((c shr 16) and 0xFF)) <= 8 &&
            kotlin.math.abs(((argb shr 8) and 0xFF) - ((c shr 8) and 0xFF)) <= 8 &&
            kotlin.math.abs((argb and 0xFF) - (c and 0xFF)) <= 8
    }
}

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
 * The wind color field is not drawn here: it is a map layer (see buildFieldImage), so it moves with the chart natively.
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
        else LandCheck(m, wPx, hPx).let { land -> geoGrid(m, wPx, hPx, BARB_SPACING_DP * densityOf(m), half = false).mapNotNull { (lat, lon) ->
            if (land.onLand(lat, lon)) return@mapNotNull null
            val s = sp.at(lat, lon); val d = dr.at(lat, lon)
            if (s.isNaN() || d.isNaN()) null else GeoSite(lat, lon, s * MS_TO_KN, d.toDouble())
        } }
    }
    val waves = remember(idleTick, f, layers.waves, wPx, hPx) {
        val h = f.waveH
        if (!layers.waves || h == null || wPx == 0f) emptyList()
        else LandCheck(m, wPx, hPx).let { land -> geoGrid(m, wPx, hPx, CREST_SPACING_DP * densityOf(m), half = true).mapNotNull { (lat, lon) ->
            if (land.onLand(lat, lon)) return@mapNotNull null
            val ht = h.at(lat, lon)
            val d = f.waveDir?.at(lat, lon) ?: Float.NaN
            if (ht.isNaN() || d.isNaN()) null
            else GeoSite(lat, lon, ht * FT_PER_M, d.toDouble(), f.wavePer?.at(lat, lon)?.toDouble() ?: Double.NaN)
        } }
    }

    // wave animation clock: runs whenever crests are shown, at any zoom, about 10 updates a second (the crests drift
    // slowly, so this looks the same as every frame for a fraction of the battery; the number of crests is set by
    // screen spacing, so zooming out costs no more)
    var nowMs by remember { mutableLongStateOf(0L) }
    val animate = waves.isNotEmpty()
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

/**
 * Size of the weather marks: 1 = the original (28 dp barbs every 64 dp, crests every 72 dp). 0.6 with tighter spacing
 * gives smaller, denser marks, like Darren's reference image (2026-11-18); current arrows use the same family of sizes.
 */
private const val MARK_SCALE = 0.6f
/** Spacing on screen between wind barbs and between wave-crest groups, dp. */
private const val BARB_SPACING_DP = 40f
private const val CREST_SPACING_DP = 44f

/** Screen density (px per dp) from the map's view context. */
private fun densityOf(m: MapLibreMap): Float = android.content.res.Resources.getSystem().displayMetrics.density

/**
 * Standard wind barb: the staff points toward where the wind comes FROM; feathers on the clockwise side of the staff
 * (northern hemisphere). Half feather 5 kt, full feather 10 kt, pennant 50 kt; a circle means calm (under 3 kt).
 * Drawn navy with a light edge so it reads on both day and night charts.
 * @param fromScreenDeg wind-from direction on screen, degrees clockwise from screen up
 */
internal fun DrawScope.drawBarb(p: Offset, fromScreenDeg: Float, kn: Double, ink: Color = BarbNavy, edgeColor: Color = BarbEdge,
                                k: Float = MARK_SCALE) {
    val edge = (3.6f * k + 0.4f).dp.toPx()
    val inkW = (1.8f * k + 0.25f).dp.toPx()
    val rounded = (kn / 5).roundToInt() * 5
    if (rounded == 0) {
        drawCircle(edgeColor, (5f * k).dp.toPx(), p, style = Stroke(edge))
        drawCircle(ink, (5f * k).dp.toPx(), p, style = Stroke(inkW))
        return
    }
    val a = Math.toRadians(fromScreenDeg.toDouble())
    val u = Offset(sin(a).toFloat(), -cos(a).toFloat())          // along the staff, toward the wind source
    val r = Offset(-u.y, u.x)                                   // clockwise side for the feathers
    val staff = (28f * k).dp.toPx()
    val full = (12f * k).dp.toPx()
    val gap = (4.5f * k).dp.toPx()
    val pennantBase = (6f * k).dp.toPx()
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
    drawPath(lines, edgeColor, style = Stroke(edge, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(pennants, edgeColor, style = Stroke(edge * 0.8f, join = StrokeJoin.Round))
    drawPath(lines, ink, style = Stroke(inkW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(pennants, ink)
    drawCircle(ink, (2f * k + 0.3f).dp.toPx(), p)
}

/**
 * Two short crest lines across the direction of travel, drifting the way the waves move and fading in and out
 * as they cycle. Length, thickness and color scale with height. t = seconds (0 = still).
 */
private fun DrawScope.drawCrests(at: Offset, s: GeoSite, mapBearing: Double, t: Double) {
    val heightFt = s.a
    val travel = Math.toRadians(s.b + 180 - mapBearing)        // waves travel opposite to the direction they come from
    val u = Offset(sin(travel).toFloat(), -cos(travel).toFloat())
    val across = Offset(-u.y, u.x)
    val k = MARK_SCALE
    val half = ((9 + 4.5 * heightFt.coerceAtMost(10.0)) * k).dp.toPx() / 2
    val width = ((1.4 + 0.45 * heightFt.coerceAtMost(10.0)) * (k + 0.15f)).dp.toPx()
    val spacing = (18f * k).dp.toPx()
    val period = if (s.c.isNaN() || s.c <= 0) 5.0 else s.c
    val speed = (3.5 * period * k).dp.toPx()                        // longer-period swell drifts faster, like the real thing
    val phase = ((t * speed) % spacing).toFloat()
    val color = waveColor(heightFt)
    for (slot in -1..1) {                                        // (not `k`: that is the mark scale above)
        val d = slot * spacing + phase                              // position along the travel direction
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
        drawPath(path, BarbNavy.copy(alpha = alpha * 0.55f), style = Stroke(width + (2f * k + 0.4f).dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------------------- bottom weather panel

/** What the Currents box needs (null = Currents switched off). */
class CurrentsBox(val validMs: Long?, val lastUpdateMs: Long, val updating: Boolean, val onUpdate: () -> Unit)

/**
 * Weather boxes along the bottom of the screen, all the same size side by side (Darren, 2026-11-18): Wind (or Gusts
 * or Barbs), Waves and Current, each just its name and colour key. Under them one thin strip: the forecast time,
 * the forecast slider and when the weather was updated (tap it to update now).
 * The Waves box shows the wave model run; the Current box shows the model hour (tap it to download currents now).
 * Offline mode = orange borders. Update times turn orange after 6 h and red after 12 h.
 */
@Composable
fun WeatherPanel(
    layers: WxLayers, aheadH: Int, maxH: Int, onAhead: (Int) -> Unit, forecastLabel: String, noData: Boolean, nowH: Int = 0,
    lastUpdateMs: Long, nowMs: Long, updating: Boolean, offline: Boolean, waveRun: String?, onUpdate: () -> Unit,
    failed: Boolean = false,
    currents: CurrentsBox? = null, modifier: Modifier = Modifier,
) {
    if (!layers.any) return
    val shape = RoundedCornerShape(10.dp)
    /** The rounded navy box shared by the weather boxes (orange border when offline). */
    fun Modifier.box() = this.clip(shape).background(Navy.copy(alpha = 0.93f), shape)
        .then(if (offline) Modifier.border(2.dp, SignalOrange, shape) else Modifier)
        .padding(horizontal = 8.dp, vertical = 5.dp)
    fun ageColor(updatingNow: Boolean, ms: Long): Color {
        val age = nowMs - ms
        return when {
            updatingNow || ms == 0L -> Foam
            age > 12 * 3_600_000L -> Alarm
            age > 6 * 3_600_000L -> SignalOrange
            else -> Foam
        }
    }

    /** Top line of a box: name, unit, and a short note on the right. */
    @Composable
    fun Header(title: String, unit: String, note: String = "", noteColor: Color = Mist, onNote: (() -> Unit)? = null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SignalOrange, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(" $unit", color = Mist, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (note.isNotEmpty()) Text(note, color = noteColor, fontSize = 10.sp, maxLines = 1,
                modifier = if (onNote != null) Modifier.clickable(onClick = onNote) else Modifier)
        }
    }

    /** A thin colour bar with its labels. */
    @Composable
    fun Scale(stops: List<Pair<Float, Color>>, labels: List<String>) {
        Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp)) {
            drawRoundRect(Brush.horizontalGradient(*stops.toTypedArray()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(l, color = Mist, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1,
                    textAlign = if (i == 0) TextAlign.Start else if (i == labels.lastIndex) TextAlign.End else TextAlign.Center)
            }
        }
    }

    Column(modifier) {
        // the legends live in the menu, under each weather switch (WxMenuGroup); here only the forecast strip
        // the strip: play/pause, the last update (the slider's start; tap to update), and the slider whose open box
        // shows the time on screen (orange) - its distance from the start is how far past the update you're looking
        if (layers.any) Row(Modifier.fillMaxWidth().box(), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(aheadH, maxH, nowH, onAhead)
            Row(Modifier.clickable(onClick = onUpdate), verticalAlignment = Alignment.CenterVertically) {
                Text("↻ ", color = SignalOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (!updating && !failed && lastUpdateMs != 0L) Text("Updated ", color = Mist, fontSize = 9.5.sp, maxLines = 1)
                Text(when { updating -> "Updating…"; failed -> "Update failed"; lastUpdateMs == 0L -> "Never updated"; else -> WeatherRepo.clock(lastUpdateMs) },
                    color = if (failed && !updating) SignalOrange else ageColor(updating, lastUpdateMs), fontSize = 10.5.sp, maxLines = 1)
            }
            HourSlider(aheadH, maxH, onAhead, if (noData) "no data" else forecastLabel, Modifier.weight(1f).padding(start = 6.dp))
        }
    }
}

/** Seconds each forecast hour shows while playing. */
private const val PLAY_STEP_MS = 1_000L

/**
 * Play / pause for the forecast: steps the slider one hour a second to the end, holds there a moment, and starts
 * again from now. Dragging the slider while it plays carries on from there.
 */
@Composable
private fun PlayButton(aheadH: Int, maxH: Int, loopTo: Int, onAhead: (Int) -> Unit) {
    var playing by remember { mutableStateOf(false) }
    val hour by rememberUpdatedState(aheadH)
    val set by rememberUpdatedState(onAhead)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(if (hour >= maxH) 2 * PLAY_STEP_MS else PLAY_STEP_MS)
            set(if (hour >= maxH) loopTo else hour + 1)
        }
    }
    Canvas(Modifier.size(26.dp).clip(CircleShape).background(if (playing) SignalOrange else NavyLight)
        .clickable { playing = !playing }.padding(8.dp)) {
        val ink = if (playing) Navy else SignalOrange
        if (playing) {
            val w = size.width * 0.32f
            drawRect(ink, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
            drawRect(ink, Offset(size.width - w, 0f), androidx.compose.ui.geometry.Size(w, size.height))
        } else drawPath(Path().apply { moveTo(size.width * 0.1f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.1f, size.height); close() }, ink)
    }
    Spacer(Modifier.width(6.dp))
}

/**
 * Forecast slider: thin track from the last update, with an open box for a thumb showing the time on screen.
 * Tap or drag anywhere on it.
 */
@Composable
fun HourSlider(value: Int, max: Int, onChange: (Int) -> Unit, label: String, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val maxV by rememberUpdatedState(max)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = androidx.compose.ui.text.TextStyle(color = SignalOrange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    val lay = measurer.measure(label, style)
    val boxWpx by rememberUpdatedState(lay.size.width.toFloat())
    Canvas(
        modifier.fillMaxWidth().height(18.dp)
            .pointerInput(Unit) {
                fun pick(x: Float) {
                    val half = boxWpx / 2 + 6.dp.toPx()
                    val f = ((x - half) / (size.width - 2 * half)).coerceIn(0f, 1f)
                    change((f * maxV).roundToInt())
                }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { ch, _ ->
                    val half = boxWpx / 2 + 6.dp.toPx()
                    val f = ((ch.position.x - half) / (size.width - 2 * half)).coerceIn(0f, 1f)
                    change((f * maxV).roundToInt())
                }
            }
    ) {
        val padX = 5.dp.toPx()
        val boxW = lay.size.width + 2 * padX
        val boxH = size.height
        val y = size.height / 2
        val x0 = boxW / 2
        val x1 = size.width - boxW / 2
        val xc = x0 + (x1 - x0) * (if (max > 0) value.toFloat() / max else 0f)
        drawLine(NavyLight, Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(SignalOrange.copy(alpha = 0.6f), Offset(0f, y), Offset(xc, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        val tl = Offset(xc - boxW / 2, 0f)
        val corner = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
        drawRoundRect(Navy, tl, androidx.compose.ui.geometry.Size(boxW, boxH), corner)
        drawRoundRect(SignalOrange, tl, androidx.compose.ui.geometry.Size(boxW, boxH), corner, style = Stroke(1.5.dp.toPx()))
        drawText(lay, topLeft = Offset(xc - lay.size.width / 2f, (boxH - lay.size.height) / 2f))
    }
}


// ------------------------------------------------------------------------------------------- menu legends

/** What the weather legends in the menu show besides the keys: the wave model run and the currents' times. */
class WxMenuInfo(val waveRun: String?, val currentsValidMs: Long?, val currentsLastMs: Long, val currentsUpdating: Boolean,
                 val nowMs: Long, val onCurrentsUpdate: () -> Unit)

/**
 * A weather switch in the menu. When on, an orange box groups the switch with a one-line legend; when off the box
 * and legend fold away (the row keeps its place, so nothing shifts sideways).
 */
@Composable
fun WxMenuGroup(on: Boolean, row: @Composable () -> Unit, legend: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)
        .border(1.5.dp, if (on) SignalOrange else Color.Transparent, shape)
        .padding(start = 6.dp, end = 4.dp, bottom = if (on) 6.dp else 0.dp)) {
        row()
        if (on) Row(Modifier.fillMaxWidth().height(14.dp).padding(start = 2.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) { legend() }
    }
}

private val LegendText = 9.5.sp

@Composable
private fun LegendLabel(t: String, color: Color = Mist, modifier: Modifier = Modifier) =
    Text(t, color = color, fontSize = LegendText, maxLines = 1, modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(lineHeight = LegendText))

/** One line: low label, a thin colour bar, high label. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.LegendBar(stops: List<Pair<Float, Color>>, low: String, high: String) {
    LegendLabel(low)
    Canvas(Modifier.weight(1f).padding(horizontal = 5.dp).height(5.dp)) {
        drawRoundRect(Brush.horizontalGradient(*stops.toTypedArray()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()))
    }
    LegendLabel(high)
}

/** Wind or gust colour key, 0 to 25+ kt. */
@Composable
fun androidx.compose.foundation.layout.RowScope.WindLegend() =
    LegendBar(windStops.map { (k, c) -> (k / 25.0).toFloat() to c }, "0", "25+ kt")

/** Wind barb key on one line: calm circle, half feather 5, feather 10, 25, pennant 50 kt. */
@Composable
fun androidx.compose.foundation.layout.RowScope.BarbLegend() {
    listOf(0.0 to "calm", 5.0 to "5", 10.0 to "10", 25.0 to "25", 50.0 to "50 kt").forEach { (kn, l) ->
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(16.dp, 12.dp)) {
                // lying flat (wind from the left), station at the right, feathers up
                if (kn == 0.0) drawBarb(Offset(size.width / 2, size.height / 2), 270f, kn, k = 0.45f)
                else drawBarb(Offset(size.width - 1.dp.toPx(), size.height - 2.dp.toPx()), 270f, kn, k = 0.5f)
            }
            LegendLabel(l, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

/** Wave crest key by height on one line, then the model run hour. */
@Composable
fun androidx.compose.foundation.layout.RowScope.WaveLegend(run: String?) {
    listOf(WaveLow to "<2", WaveMid to "2–4", WaveHigh to "4–6", WaveBig to "6+ ft").forEachIndexed { i, (c, l) ->
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(14.dp).height(7.dp)) {
                val half = (3.5f + i * 0.8f).dp.toPx()
                val sw = (1.2f + i * 0.35f).dp.toPx()
                val cx = size.width / 2
                val path = Path().apply {
                    moveTo(cx - half, size.height * 0.9f)
                    quadraticTo(cx, -size.height * 0.1f, cx + half, size.height * 0.9f)
                }
                drawPath(path, BarbEdge, style = Stroke(sw + 1.2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round))
            }
            LegendLabel(l, modifier = Modifier.padding(start = 2.dp))
        }
    }
    if (run != null) LegendLabel(run.substringAfterLast(' '))
}

/** Current speed bar, 0 to 1+ kt, then the model hour (tap to update; orange/red when old). */
@Composable
fun androidx.compose.foundation.layout.RowScope.CurrentLegend(info: WxMenuInfo) {
    LegendBar(currentStops(), "0", "1+ kt")
    val age = info.nowMs - info.currentsLastMs
    val color = when {
        info.currentsUpdating || info.currentsLastMs == 0L -> Mist
        age > 12 * 3_600_000L -> Alarm
        age > 6 * 3_600_000L -> SignalOrange
        else -> Mist
    }
    Row(Modifier.padding(start = 8.dp).clickable(onClick = info.onCurrentsUpdate), verticalAlignment = Alignment.CenterVertically) {
        LegendLabel(when {
            info.currentsUpdating -> "…"
            info.currentsValidMs == null -> "none"
            else -> WeatherRepo.clock(info.currentsValidMs)
        }, color)
        Text(" ↻", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(lineHeight = 12.sp))
    }
}
