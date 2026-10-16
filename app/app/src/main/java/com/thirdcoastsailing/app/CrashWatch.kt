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
private val CRASH_DARK = Color(0xFF6E0E0E)

/** Red cartoon sailboat broken in half, centered at `c`; `k` = px per dp (about 34 dp wide). */
fun DrawScope.drawBrokenBoat(c: Offset, k: Float) {
    val st = Stroke(1.6f * k, join = StrokeJoin.Round, cap = StrokeCap.Round)
    // bow half, tipped up to the left, with a jagged break edge
    rotate(-18f, pivot = c + Offset(-3f * k, 6f * k)) {
        val bow = Path().apply {
            moveTo(c.x - 17f * k, c.y + 1f * k)                     // bow tip
            lineTo(c.x - 2f * k, c.y + 1f * k)
            lineTo(c.x + 0f * k, c.y + 4f * k); lineTo(c.x - 3f * k, c.y + 6f * k); lineTo(c.x - 1f * k, c.y + 9f * k)  // break
            lineTo(c.x - 11f * k, c.y + 9f * k)
            close()
        }
        drawPath(bow, CRASH_RED); drawPath(bow, CRASH_DARK, style = st)
        // broken mast stub and sail scrap
        drawLine(CRASH_DARK, c + Offset(-7f * k, 1f * k), c + Offset(-7f * k, -9f * k), 1.8f * k, cap = StrokeCap.Round)
        val sail = Path().apply { moveTo(c.x - 6f * k, c.y - 9f * k); lineTo(c.x - 1f * k, c.y - 1f * k); lineTo(c.x - 6f * k, c.y - 1f * k); close() }
        drawPath(sail, Color.White); drawPath(sail, CRASH_DARK, style = st)
    }
    // stern half, tipped up to the right
    rotate(20f, pivot = c + Offset(5f * k, 6f * k)) {
        val stern = Path().apply {
            moveTo(c.x + 3f * k, c.y + 1f * k)
            lineTo(c.x + 16f * k, c.y + 1f * k)                    // transom top
            lineTo(c.x + 14f * k, c.y + 9f * k)
            lineTo(c.x + 4f * k, c.y + 9f * k); lineTo(c.x + 6f * k, c.y + 6f * k); lineTo(c.x + 3f * k, c.y + 4f * k)   // break
            close()
        }
        drawPath(stern, CRASH_RED); drawPath(stern, CRASH_DARK, style = st)
    }
    // splash / rock under the break
    drawLine(CRASH_DARK, c + Offset(-14f * k, 13f * k), c + Offset(14f * k, 13f * k), 1.6f * k, cap = StrokeCap.Round)
    drawLine(CRASH_DARK, c + Offset(-3f * k, 12f * k), c + Offset(1f * k, 9f * k), 1.4f * k, cap = StrokeCap.Round)
    drawLine(CRASH_DARK, c + Offset(4f * k, 12f * k), c + Offset(2f * k, 9f * k), 1.4f * k, cap = StrokeCap.Round)
}
