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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Navy
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/*
 * Trip track: a thin dashed line of where the boat has been since the menu toggle was switched on,
 * labelled with distance and elapsed time. Kept in memory only; switching it off clears it.
 * Pause stops recording and the clock but keeps the line; Resume starts a new segment (no line across the gap).
 * Points are recorded while the app is open (GPS stops when the app is in the background).
 */

/** Minimum spacing between recorded points (m), so GPS jitter doesn't add distance. */
private const val TRACK_STEP_M = 15.0

/** Below this speed (kn) a point is only recorded after a larger move, so drifting at the dock adds nothing. */
private const val TRACK_SLOW_KN = 0.3f
private const val TRACK_SLOW_STEP_M = 50.0

/**
 * Distance (nm) to add if this fix should become the next track point, or null to skip it.
 * The first fix of a trip always becomes a point (returns 0.0).
 */
fun trackStep(last: GeoPt?, fix: BoatFix): Double? {
    if (last == null) return 0.0
    val d = distNm(last, GeoPt(fix.lat, fix.lon))
    val m = d * 1852.0
    val slow = fix.sogKn != null && fix.sogKn < TRACK_SLOW_KN
    return if (m >= (if (slow) TRACK_SLOW_STEP_M else TRACK_STEP_M)) d else null
}

/** Label next to the track, e.g. "12.4 nm · 1:23". */
fun trackLabel(distNm: Double, elapsedMs: Long): String {
    val min = (elapsedMs / 60_000).coerceAtLeast(0)
    return "%.1f nm · %d:%02d".format(distNm, min / 60, min % 60)
}

/**
 * Draws the trip track over the chart: thin dashed line through the recorded points to the boat's
 * current position (null boat = paused: no live segment), with the label beside the newest end.
 */
@Composable
fun TrackOverlay(map: MapLibreMap?, tick: Int, points: List<GeoPt>, breaks: List<Int>, boat: BoatFix?, label: String?,
                 night: Boolean) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        tick                                                         // read so camera moves redraw
        val m = map ?: return@Canvas
        if (points.isEmpty() || label == null) return@Canvas
        fun xy(lat: Double, lon: Double) = m.projection.toScreenLocation(LatLng(lat, lon)).let { Offset(it.x, it.y) }

        val ink = if (night) Foam else Navy                          // dark on the day chart, light at night
        val path = Path()
        // a resume after a pause starts a new segment: no line across the gap
        points.forEachIndexed { i, p ->
            val o = xy(p.lat, p.lon)
            if (i == 0 || i in breaks) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        val end = boat?.let { xy(it.lat, it.lon) } ?: xy(points.last().lat, points.last().lon)
        if (boat != null) path.lineTo(end.x, end.y)                  // live segment to the boat
        drawPath(path, ink, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))

        // label just above and right of the newest end, plain text like the route leg labels
        val layout = measurer.measure(label, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
        drawText(layout, color = if (night) Foam else Color.Black,
            topLeft = end + Offset(14.dp.toPx(), -layout.size.height - 10.dp.toPx()))
    }
}
