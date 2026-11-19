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

/** Dark blue of the current predictor line. */
private val PredictorBlue = Color(0xFF14365C)
/** Time marks on the heading and predictor lines (minutes). */
private val MARK_MIN = listOf(15, 30, 60)

/**
 * Boat overlay drawn over the chart: the sloop symbol, a thin dashed heading line projecting ahead with small dots
 * at 15 min, 30 min and 1 h at the boat's speed, and, when Currents is on, the current predictor line.
 *
 * GPS course and speed are over the ground, so they already include the current at the boat. With currents on the
 * current there is taken out to get the boat's heading and speed through the water (the orange line), and the dark
 * blue predictor line steps ahead minute by minute adding the current wherever the boat will be: the two lines start
 * together and fan apart as the current changes, and the gap between matching marks is the push from the current.
 * Without currents the orange line is the GPS course, as before.
 * @param tick changes whenever the map camera moves, so the overlay redraws in step with the chart
 */
@Composable
fun BoatOverlay(map: MapLibreMap?, fix: BoatFix?, avgSogKn: Double?, tick: Int, currents: CurrentField? = null,
                modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        tick                                          // read so a camera move triggers a redraw
        val m = map ?: return@Canvas
        val f = fix ?: return@Canvas
        val boat = m.projection.toScreenLocation(LatLng(f.lat, f.lon)).let { Offset(it.x, it.y) }
        val cog = f.cogDeg?.toDouble()
        val sog = avgSogKn ?: 0.0
        fun xy(ll: LatLng) = m.projection.toScreenLocation(ll).let { Offset(it.x, it.y) }

        // heading and speed through the water (= over the ground when there is no current data here)
        var headDeg = cog
        var waterKn = sog
        val here = if (cog != null) currents?.at(f.lat, f.lon) else null
        if (cog != null && here != null && sog >= 0.3) {
            val ge = sog * sin(Math.toRadians(cog)); val gn = sog * cos(Math.toRadians(cog))
            val we = ge - here.u * MS_TO_KN; val wn = gn - here.v * MS_TO_KN
            waterKn = hypot(we, wn)
            headDeg = (Math.toDegrees(atan2(we, wn)) + 360) % 360
        }

        var angleRad: Float? = null
        if (headDeg != null) {
            val ahead = xy(destination(f.lat, f.lon, headDeg, 1.0))
            angleRad = atan2(ahead.y - boat.y, ahead.x - boat.x)
            // the line reaches the 1 h mark (at least 120 dp, at most most of the screen)
            val oneHour = if (waterKn >= 0.3) xy(destination(f.lat, f.lon, headDeg, waterKn)) else null
            val reach = (oneHour?.let { hypot(it.x - boat.x, it.y - boat.y) } ?: 0f)
                .coerceIn(120.dp.toPx(), 0.8f * maxOf(size.width, size.height))
            val end = boat + Offset(cos(angleRad) * reach, sin(angleRad) * reach)
            val dash = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 7.dp.toPx()))
            drawLine(NavyInk.copy(alpha = 0.7f), boat, end, strokeWidth = 3.2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            drawLine(Orange, boat, end, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            if (waterKn >= 0.3) for (min in MARK_MIN) {
                val o = xy(destination(f.lat, f.lon, headDeg, waterKn * min / 60.0))
                if (hypot(o.x - boat.x, o.y - boat.y) > reach + 1f) continue
                drawCircle(NavyInk, 3.6.dp.toPx(), o)
                drawCircle(Orange, 2.4.dp.toPx(), o)
                if (currents == null) drawText(measurer, if (min == 60) "1h" else "${min}m", o + Offset(6.dp.toPx(), -14.dp.toPx()),
                    TextStyle(color = NavyInk, fontSize = 10.sp, fontWeight = FontWeight.Bold, background = White.copy(alpha = 0.7f)))
            }

            // current predictor: where the boat goes over the next hour with the current added along the way
            if (currents != null && here != null && waterKn >= 0.3) {
                val we = waterKn * sin(Math.toRadians(headDeg)); val wn = waterKn * cos(Math.toRadians(headDeg))
                var lat = f.lat; var lon = f.lon
                val path = Path().apply { moveTo(boat.x, boat.y) }
                val marks = ArrayList<Pair<Int, Offset>>()
                for (minute in 1..60) {
                    val c = currents.at(lat, lon)
                    val e = we + (c?.u ?: 0f) * MS_TO_KN; val n = wn + (c?.v ?: 0f) * MS_TO_KN
                    val nm = hypot(e, n) / 60.0
                    val brg = (Math.toDegrees(atan2(e, n)) + 360) % 360
                    val next = destination(lat, lon, brg, nm)
                    lat = next.latitude; lon = next.longitude
                    val o = xy(next)
                    path.lineTo(o.x, o.y)
                    if (minute in MARK_MIN) marks += minute to o
                }
                val dash2 = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 6.dp.toPx()))
                drawPath(path, White.copy(alpha = 0.6f), style = Stroke(3.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash2))
                drawPath(path, PredictorBlue, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash2))
                for ((min, o) in marks) {
                    drawCircle(White, 4.dp.toPx(), o)
                    drawCircle(PredictorBlue, 2.8.dp.toPx(), o)
                    drawText(measurer, if (min == 60) "1h" else "${min}m", o + Offset(6.dp.toPx(), 2.dp.toPx()),
                        TextStyle(color = PredictorBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, background = White.copy(alpha = 0.7f)))
                }
            }
        }

        // Boat symbol on top, pointing along the course over ground; north (screen up) when no course is known yet
        val cogAngle = if (cog != null) xy(destination(f.lat, f.lon, cog, 1.0)).let { atan2(it.y - boat.y, it.x - boat.x) } else null
        val symDeg = cogAngle?.let { Math.toDegrees(it.toDouble()).toFloat() + 90f } ?: 0f
        rotate(symDeg, pivot = boat) { drawSloop(boat) }
    }
}

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
