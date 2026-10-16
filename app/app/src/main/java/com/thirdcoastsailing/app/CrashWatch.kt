package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.distanceNm
import com.thirdcoastsailing.app.celestial.travel
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Crash warning: a red broken-sailboat mark on the first water shallower than the boat's minimum depth (the water
 * already shaded red on the chart) that the boat would hit.
 *
 *  - Current heading: looks ahead 30 minutes at the current speed (at least 0.5 nm, at most 5 nm) along the course
 *    over ground averaged over the last 30 seconds, only when moving faster than 1 kn. It checks a narrow cone in
 *    front of the boat (the course ±5°, starting at the boat) so a few degrees of course wobble doesn't hide a
 *    shoal, but the mark is always placed straight ahead on the course line, at the distance of the closest hit.
 *  - Plotted route: follows the legs from the boat through each route point and marks the first shallow spot.
 *  - It stops at the first obstruction: what lies beyond doesn't matter, you have to turn anyway.
 *    No label and no sound. Same depth data and limit as the red shading (shallow end of the charted depth range).
 */

object CrashWatch {
    private const val LOOK_MIN = 30.0             // look-ahead, minutes at current speed
    private const val MIN_NM = 0.5
    private const val MAX_NM = 5.0
    private const val MIN_SOG_KN = 1.0
    const val AHEAD_EVERY_MS = 20_000L         // heading check interval (power: 20 s is plenty at sailing speed)
    private const val STEP_M = 50.0               // sample spacing along each line
    private const val WEDGE_DEG = 5.0             // course ± this
    private const val LIMIT_MARGIN_M = 0.1        // same margin as the chart shading (ChartStyle)

    /** Mark on the current heading, and on the plotted route (null = clear). */
    var ahead by mutableStateOf<GeoPt?>(null)
    var onRoute by mutableStateOf<GeoPt?>(null)

    /** True when the charted shallow end at the point is shallower than the limit (as shaded red on the chart). */
    private fun shallow(depth: DepthLookup, p: LatLon, limitFt: Double): Boolean {
        val d = depth.depthFt(p.lat, p.lon) ?: return false             // off the charts: not flagged
        return d * 0.3048 < limitFt * 0.3048 - LIMIT_MARGIN_M
    }

    /**
     * First shallow spot ahead on the current heading, placed on the course line; null if clear or not moving.
     * Call off the main thread (reads the depth files).
     */
    fun scanAhead(depth: DepthLookup, boat: BoatFix, cogDeg: Double?, limitFt: Double): GeoPt? {
        val sog = boat.sogKn?.toDouble() ?: return null
        val cog = cogDeg ?: return null
        if (sog < MIN_SOG_KN) return null
        val rangeNm = (sog * LOOK_MIN / 60.0).coerceIn(MIN_NM, MAX_NM)
        val start = LatLon(boat.lat, boat.lon)
        val stepNm = STEP_M / 1852.0
        // cone in front of the boat: the course, course ±2.5° and ±5°, all from the boat
        val lines = listOf(start to cog, start to cog - WEDGE_DEG / 2, start to cog + WEDGE_DEG / 2,
            start to cog - WEDGE_DEG, start to cog + WEDGE_DEG)
        // step outward on all lines together and stop at the first hit: nothing beyond it is checked
        var dist = stepNm
        while (dist <= rangeNm) {
            for ((origin, brg) in lines) {
                if (shallow(depth, travel(origin, brg, dist), limitFt)) {
                    val mark = travel(start, cog, dist)                     // straight ahead, at that distance
                    return GeoPt(mark.lat, mark.lon)
                }
            }
            dist += stepNm
        }
        return null
    }

    /** First shallow spot along the plotted route (boat, then each point), or null. Off the main thread. */
    fun scanRoute(depth: DepthLookup, start: GeoPt?, points: List<GeoPt>, limitFt: Double): GeoPt? {
        val legs = listOfNotNull(start) + points
        if (legs.size < 2) return null
        val stepNm = STEP_M / 1852.0
        for (i in 1 until legs.size) {
            val a = LatLon(legs[i - 1].lat, legs[i - 1].lon); val b = LatLon(legs[i].lat, legs[i].lon)
            val len = distanceNm(a, b)
            val brg = com.thirdcoastsailing.app.celestial.bearing(a, b)
            var dist = if (i == 1 && start != null) stepNm else 0.0           // don't flag the spot the boat is on
            while (dist <= len) {
                val p = travel(a, brg, dist)
                if (shallow(depth, p, limitFt)) return GeoPt(p.lat, p.lon)
                dist += stepNm
            }
        }
        return null
    }
}

/**
 * Course over ground averaged over the last 30 seconds (as a vector, so 350° and 010° average to 000°),
 * so the look-ahead line doesn't jump with every fix.
 */
class CourseAverager(private val windowMs: Long = 30_000L) {
    private val fixes = ArrayDeque<BoatFix>()

    fun add(f: BoatFix) {
        if (fixes.isNotEmpty() && f.time <= fixes.last().time) return
        fixes.addLast(f)
        while (fixes.isNotEmpty() && f.time - fixes.first().time > windowMs) fixes.removeFirst()
    }

    fun cog(): Double? {
        var x = 0.0; var y = 0.0
        for (f in fixes) { val c = f.cogDeg ?: continue; x += sin(Math.toRadians(c.toDouble())); y += cos(Math.toRadians(c.toDouble())) }
        if (x == 0.0 && y == 0.0) return null
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }
}

/** Draws the crash marks (heading and route) over the chart. */
@Composable
fun CrashOverlay(map: MapLibreMap?, tick: Int) {
    val ahead = CrashWatch.ahead
    val route = CrashWatch.onRoute
    Canvas(Modifier.fillMaxSize()) {
        tick                                                             // read so camera moves redraw
        val m = map ?: return@Canvas
        for (p in listOfNotNull(ahead, route)) {
            val s = m.projection.toScreenLocation(LatLng(p.lat, p.lon))
            drawBrokenBoat(Offset(s.x, s.y), 1.dp.toPx())
        }
    }
}

private val CRASH_RED = Color(0xFFD62828)
private val CRASH_FILL = Color.White.copy(alpha = 0.92f)

/**
 * Crash mark: a cartoon line drawing of a little sailboat snapped in two, sinking into two wavy water lines.
 * Red rounded strokes with white fill inside the hull pieces and sail, so it reads on red shading and blue water.
 * Drawn on a 40 x 40 grid centered at (20, 22); `k` = px per grid unit (1.2 dp gives about a 48 dp mark).
 */
fun DrawScope.drawBrokenBoat(c: Offset, dp: Float) {
    val k = 1.2f * dp
    fun o(x: Float, y: Float) = Offset(c.x + (x - 20f) * k, c.y + (y - 22f) * k)
    val st = Stroke(1.5f * k, join = StrokeJoin.Round, cap = StrokeCap.Round)
    fun shape(fill: Boolean, build: Path.() -> Unit) {
        val p = Path().apply(build)
        if (fill) drawPath(p, CRASH_FILL)
        drawPath(p, CRASH_RED, style = st)
    }
    // bow half (with mast and sail), tipped nose-up to the left about its break
    rotate(-24f, pivot = o(17f, 24f)) {
        shape(true) {
            moveTo(o(6f, 20f).x, o(6f, 20f).y)
            quadraticTo(o(8f, 27f).x, o(8f, 27f).y, o(17f, 27f).x, o(17f, 27f).y)               // curved bottom
            lineTo(o(18.5f, 24f).x, o(18.5f, 24f).y); lineTo(o(16.5f, 22.5f).x, o(16.5f, 22.5f).y)  // jagged break
            lineTo(o(18.5f, 20.5f).x, o(18.5f, 20.5f).y); lineTo(o(17f, 19f).x, o(17f, 19f).y)
            close()                                                                           // deck line
        }
        drawLine(CRASH_RED, o(12f, 19f), o(12f, 5f), 1.5f * k, cap = StrokeCap.Round)          // mast
        shape(true) {
            moveTo(o(12.6f, 6f).x, o(12.6f, 6f).y)
            quadraticTo(o(17.5f, 11f).x, o(17.5f, 11f).y, o(16.5f, 17.5f).x, o(16.5f, 17.5f).y)   // sail
            lineTo(o(12.6f, 17.5f).x, o(12.6f, 17.5f).y); close()
        }
        shape(false) { moveTo(o(12f, 5f).x, o(12f, 5f).y); lineTo(o(9f, 6.2f).x, o(9f, 6.2f).y); lineTo(o(12f, 7.2f).x, o(12f, 7.2f).y) }  // flag
    }
    // stern half, tipped stern-up to the right
    rotate(22f, pivot = o(23f, 24f)) {
        shape(true) {
            moveTo(o(22f, 19f).x, o(22f, 19f).y)
            lineTo(o(20.5f, 20.5f).x, o(20.5f, 20.5f).y); lineTo(o(22.5f, 22.5f).x, o(22.5f, 22.5f).y)  // jagged break
            lineTo(o(20.5f, 24f).x, o(20.5f, 24f).y); lineTo(o(21f, 27f).x, o(21f, 27f).y)
            quadraticTo(o(30f, 27f).x, o(30f, 27f).y, o(33f, 19f).x, o(33f, 19f).y)               // curved bottom to transom
            close()
        }
    }
    // splash marks over the break
    for ((a, b) in listOf(o(18.5f, 15f) to o(17.5f, 12.5f), o(20f, 14.5f) to o(20f, 11.5f), o(21.5f, 15f) to o(22.5f, 12.5f)))
        drawLine(CRASH_RED, a, b, 1.5f * k, cap = StrokeCap.Round)
    // two wavy water lines
    fun wave(y: Float, x0: Float, x1: Float) {
        val p = Path(); var x = x0; var first = true
        while (x <= x1) {
            val pt = o(x, y + 0.9f * sin((x - x0) / 7f * 2f * Math.PI.toFloat()))
            if (first) { p.moveTo(pt.x, pt.y); first = false } else p.lineTo(pt.x, pt.y)
            x += 0.5f
        }
        drawPath(p, CRASH_RED, style = st)
    }
    wave(29.5f, 3f, 37f); wave(34f, 8f, 32f)
}
