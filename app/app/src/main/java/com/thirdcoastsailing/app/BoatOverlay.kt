package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The boat on the chart: an orange top-down sloop at the GPS position, turned to the course, with the course line
 * and 1, 2 and 3-hour marks spaced by the 15-minute average speed.
 */

/** Boat marker fill (brand orange). */
private val Orange = Color(0xFFF28C28)
/** Boat marker outline (navy). */
private val NavyInk = Color(0xFF0F2742)
/** Speed label text (off-white). */
private val White = Color(0xFFF4F1EA)

/** Speed over ground averaged over the last 15 minutes (time-weighted). */
class SpeedAverage(private val windowMs: Long = 15 * 60_000L) {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    /** Add one GPS speed sample and drop samples older than the averaging window. Null speeds are ignored. */
    fun add(timeMs: Long, sogKn: Float?) {
        if (sogKn == null) return
        samples.addLast(timeMs to sogKn)
        while (samples.isNotEmpty() && timeMs - samples.first().first > windowMs) samples.removeFirst()
    }

    /** Average in knots, or null with no data. */
    fun average(): Double? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) return samples.first().second.toDouble()
        var area = 0.0
        var span = 0.0
        for (i in 1 until samples.size) {
            val dt = (samples[i].first - samples[i - 1].first).toDouble()
            area += dt * (samples[i].second + samples[i - 1].second) / 2
            span += dt
        }
        return if (span > 0) area / span else samples.last().second.toDouble()
    }
}

/** Point reached from (lat, lon) after traveling distNm nautical miles on a true bearing (great circle). */
fun destination(lat: Double, lon: Double, bearingDeg: Double, distNm: Double): LatLng {
    val d = distNm / 3440.065                         // earth radius in nautical miles
    val b = Math.toRadians(bearingDeg)
    val p1 = Math.toRadians(lat)
    val l1 = Math.toRadians(lon)
    val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
    val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    return LatLng(Math.toDegrees(p2), Math.toDegrees(l2))
}

/**
 * Boat overlay drawn over the chart: sloop symbol pointing along course over ground, a fixed-length
 * course line, and dots where the boat will be in 1, 2 and 3 hours at its 15-minute average speed.
 * The line is always the same length on screen; only the dots move. A dot beyond the end of the line is not drawn.
 * @param tick changes whenever the map camera moves, so the overlay redraws in step with the chart
 */
@Composable
fun BoatOverlay(map: MapLibreMap?, fix: BoatFix?, avgSogKn: Double?, tick: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        tick                                          // read so a camera move triggers a redraw
        val m = map ?: return@Canvas
        val f = fix ?: return@Canvas
        val boat = m.projection.toScreenLocation(LatLng(f.lat, f.lon)).let { Offset(it.x, it.y) }
        val lineLen = 150.dp.toPx()
        val cog = f.cogDeg?.toDouble()

        // Screen direction of the course: project a point 1 nm ahead (handles map rotation and latitude)
        var angleRad: Float? = null
        if (cog != null) {
            val ahead = m.projection.toScreenLocation(destination(f.lat, f.lon, cog, 1.0))
            angleRad = atan2(ahead.y - boat.y, ahead.x - boat.x)
        }

        if (angleRad != null) {
            val end = boat + Offset(cos(angleRad) * lineLen, sin(angleRad) * lineLen)
            drawLine(NavyInk, boat, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Orange, boat, end, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f)))

            // 1 h, 2 h, 3 h at the average speed
            val sog = avgSogKn ?: 0.0
            if (sog >= 0.3) for (h in 1..3) {
                val p = m.projection.toScreenLocation(destination(f.lat, f.lon, cog!!, sog * h))
                val off = Offset(p.x, p.y)
                if (hypot(off.x - boat.x, off.y - boat.y) <= lineLen) {
                    drawCircle(NavyInk, 6.dp.toPx(), off)
                    drawCircle(Orange, 4.5.dp.toPx(), off)
                    drawText(measurer, "${h}h", off + Offset(7.dp.toPx(), -16.dp.toPx()),
                        TextStyle(color = NavyInk, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            background = White.copy(alpha = 0.75f)))
                }
            }
        }

        // Boat symbol on top; points north (screen up) when no course is known yet
        val headingDeg = angleRad?.let { Math.toDegrees(it.toDouble()).toFloat() + 90f } ?: 0f
        rotate(headingDeg, pivot = boat) { drawSloop(boat) }
    }
}

/** Top-down sloop: pointed bow, transom stern, mast, boom and jib. Drawn bow-up, centered on c. */
private fun DrawScope.drawSloop(c: Offset) {
    val len = 34.dp.toPx()
    val beam = 13.dp.toPx()
    val top = c.y - len * 0.55f
    val bottom = c.y + len * 0.45f
    val hull = Path().apply {
        moveTo(c.x, top)                                                        // bow
        cubicTo(c.x + beam * 0.55f, top + len * 0.25f, c.x + beam * 0.5f, c.y + len * 0.2f, c.x + beam * 0.38f, bottom)
        lineTo(c.x - beam * 0.38f, bottom)                                      // transom
        cubicTo(c.x - beam * 0.5f, c.y + len * 0.2f, c.x - beam * 0.55f, top + len * 0.25f, c.x, top)
        close()
    }
    drawPath(hull, Orange)
    drawPath(hull, NavyInk, style = Stroke(1.8.dp.toPx()))
    val mast = Offset(c.x, c.y - len * 0.12f)
    // jib from mast forward to the bow, mainsail boom aft from the mast
    drawLine(White, mast, Offset(c.x, top + len * 0.08f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(NavyInk, mast, Offset(c.x, bottom - len * 0.08f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(NavyInk, 2.6.dp.toPx(), mast)
}
