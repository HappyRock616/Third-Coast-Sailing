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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Celestial lines of position and the fix from the workbook, drawn over the chart (purple, so they're not mistaken
 * for the route or the trip track). Each line is labelled with the body and time; the fix is a circle with a dot.
 * Tapping the fix opens the workbook; the workbook's reset arrow (↺) removes them.
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
        if (p.lops.isEmpty()) return@Canvas
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
    val f = plot?.fix ?: return false
    val a = m.projection.toScreenLocation(tap)
    val b = m.projection.toScreenLocation(LatLng(f.position.lat, f.position.lon))
    return kotlin.math.hypot(a.x - b.x, a.y - b.y) <= radiusPx
}
