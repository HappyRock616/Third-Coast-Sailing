package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.bearing
import com.thirdcoastsailing.app.celestial.distanceNm
import com.thirdcoastsailing.app.celestial.travel
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Celestial lines of position and the fix from the workbook, drawn over the chart (purple, so they're not mistaken
 * for the route or the trip track). Each line is labelled with the body and time; the fix is a circle with a dot.
 * Saved fixes (the trip's fix log) are joined by a dashed line, each leg labelled with its true bearing and distance.
 * Tapping any fix opens the workbook; the workbook's reset arrow (↺) removes them all.
 */

private val CEL_DAY = Color(0xFF6A3FB5)
private val CEL_NIGHT = Color(0xFFC9A6FF)

/** Half length of each drawn line of position (nm). */
private const val LOP_HALF_NM = 8.0

@Composable
fun CelestialOverlay(map: MapLibreMap?, tick: Int, plot: CelestialPlot?, night: Boolean) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        tick                                                         // read so camera moves redraw
        val m = map ?: return@Canvas
        val p = plot ?: return@Canvas
        if (p.lops.isEmpty() && p.log.isEmpty()) return@Canvas
        fun xy(l: LatLon) = m.projection.toScreenLocation(LatLng(l.lat, l.lon)).let { Offset(it.x, it.y) }
        val ink = if (night) CEL_NIGHT else CEL_DAY
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        // with a fix, draw the lines as advanced to the fix time (so they cross at the fix)
        val lines = p.fix?.lops ?: p.lops
        for (l in lines) {
            val (a, b) = l.segment(LOP_HALF_NM)
            val pa = xy(a); val pb = xy(b)
            drawLine(ink, pa, pb, 2.dp.toPx(), cap = StrokeCap.Round)
            val t = measurer.measure("${l.bodyName} ${time.format(Date(l.timeMs))}", TextStyle(fontSize = 11.sp, color = ink, fontWeight = FontWeight.Medium))
            drawText(t, topLeft = pb + Offset(4.dp.toPx(), -t.size.height.toFloat()))
        }
        // ---- fix log: saved fixes (and the current one) joined by a dashed line, each leg labelled bearing · distance
        val pts = p.log.map { xy(it.position) }
        for (i in 1 until p.log.size) {
            val a0 = p.log[i - 1]; val a1 = p.log[i]
            drawLine(ink, pts[i - 1], pts[i], 1.8.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())))
            val brg = bearing(a0.position, a1.position); val nm = distanceNm(a0.position, a1.position)
            val t = measurer.measure("%03.0f°T · %.1f nm".format(brg, nm), TextStyle(fontSize = 11.sp, color = ink, fontWeight = FontWeight.Medium))
            val mid = (pts[i - 1] + pts[i]) / 2f
            drawText(t, topLeft = mid + Offset(6.dp.toPx(), -t.size.height - 4.dp.toPx()))
        }
        for ((i, lf) in p.log.withIndex()) if (lf.saved) {
            val c = pts[i]
            drawCircle(if (night) Color.Black else Color.White, 6.dp.toPx(), c)
            drawCircle(ink, 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            drawCircle(ink, 1.8.dp.toPx(), c)
            val t = measurer.measure(time.format(Date(lf.timeMs)), TextStyle(fontSize = 11.sp, color = ink, fontWeight = FontWeight.Bold))
            drawText(t, topLeft = c + Offset(9.dp.toPx(), 2.dp.toPx()))
        }

        // ---- uncertainty circles (about 95%) round each fix
        for ((i, lf) in p.log.withIndex()) {
            val r = lf.radius95Nm ?: continue
            val edge = xy(travel(lf.position, 0.0, r))
            val rpx = kotlin.math.hypot(edge.x - pts[i].x, edge.y - pts[i].y)
            drawCircle(ink.copy(alpha = if (lf.saved) 0.08f else 0.12f), rpx, pts[i])
            drawCircle(ink.copy(alpha = if (lf.saved) 0.5f else 0.8f), rpx, pts[i], style = Stroke(1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        }

        p.fix?.let { f ->
            val c = xy(f.position)
            drawCircle(if (night) Color.Black else Color.White, 9.dp.toPx(), c)
            drawCircle(ink, 9.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
            drawCircle(ink, 2.5.dp.toPx(), c)
            val t = measurer.measure("Celestial fix ${time.format(Date(f.timeMs))}", TextStyle(fontSize = 12.sp, color = ink, fontWeight = FontWeight.Bold))
            drawText(t, topLeft = c + Offset(12.dp.toPx(), 4.dp.toPx()))
        }
    }
}

/** True when a screen tap (pixels) lands on the fix. */
fun tapsCelestialFix(map: MapLibreMap?, plot: CelestialPlot?, tap: LatLng, radiusPx: Float): Boolean {
    val m = map ?: return false
    val pts = plot?.log ?: return false
    val a = m.projection.toScreenLocation(tap)
    return pts.any { lf ->
        val b = m.projection.toScreenLocation(LatLng(lf.position.lat, lf.position.lon))
        kotlin.math.hypot(a.x - b.x, a.y - b.y) <= radiusPx
    }
}
