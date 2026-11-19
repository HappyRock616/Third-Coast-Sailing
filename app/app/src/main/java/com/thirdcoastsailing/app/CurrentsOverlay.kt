package com.thirdcoastsailing.app

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Alarm
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Top of the current colour scale (knots): the Great Lakes rarely run faster outside the rivers; 1+ is red. */
const val CURRENT_MAX_KT = 1.0f
/** Spacing between current arrows on screen (dp): denser than the wind barbs. */
private const val CURRENT_SPACING_DP = 26f
/** Length of one arrow (dp), drawn along its streamline. */
private const val ARROW_DP = 13f
/** Streamline length behind each arrow (dp): the arrow slides along it, so this sets how far it travels per cycle. */
private const val TRACK_DP = 22f
/** Points per streamline. */
private const val TRACK_POINTS = 9
/** Animation updates per second (about 20 fps). */
private const val CURRENT_FRAME_MS = 50L
private val ArrowEdge = Color(0xFF0F2742)

/** Colour stops: navy, blue, teal, yellow, orange, red at the top of the scale. */
private val CURRENT_STOPS = listOf(
    0.0f to Color(0xFF1B3A6B), 0.2f to Color(0xFF2E8FC9), 0.4f to Color(0xFF3FC1A5),
    0.6f to Color(0xFFF2D13A), 0.8f to Color(0xFFF28C28), 1.0f to Color(0xFFD62828),
)

/** The scale's colour stops, for the Current box. */
fun currentStops(): List<Pair<Float, Color>> = CURRENT_STOPS

/** Arrow colour for a current speed in knots (0..CURRENT_MAX_KT, red above). */
fun currentColor(kt: Float): Color {
    val f = (kt / CURRENT_MAX_KT).coerceIn(0f, 1f)
    for (i in 1 until CURRENT_STOPS.size) {
        val (a, ca) = CURRENT_STOPS[i - 1]
        val (b, cb) = CURRENT_STOPS[i]
        if (f <= b) {
            val t = (f - a) / (b - a)
            return Color(ca.red + (cb.red - ca.red) * t, ca.green + (cb.green - ca.green) * t, ca.blue + (cb.blue - ca.blue) * t)
        }
    }
    return CURRENT_STOPS.last().second
}

/**
 * One arrow: its anchor on the chart and its streamline as screen offsets from the anchor (worked out when the
 * camera stops, at that zoom), with its speed and a phase so neighbouring arrows don't pulse together.
 */
private class CurrentArrow(val lat: Double, val lon: Double, val dx: FloatArray, val dy: FloatArray,
                           val kt: Float, val phase: Float) {
    // worked out once (they don't change while the arrow lives): distance along the streamline, colour, cycle time
    /** Distance along the streamline to each of its points (px). */
    val cum = FloatArray(dx.size).also { c -> for (i in 1 until dx.size) c[i] = c[i - 1] + hypot(dx[i] - dx[i - 1], dy[i] - dy[i - 1]) }
    val color = currentColor(kt)
    /** Seconds per slide: faster water slides faster (1.6 s per cycle at 1 kt, 4 s when slack). */
    val period = 4.0 - 2.4 * (kt / CURRENT_MAX_KT).coerceIn(0f, 1f)
}

/**
 * Animated current arrows over the lakes: small arrows on a dense screen grid, each sliding along the local flow
 * (a short streamline, so it bends where the current turns) and fading in and out, coloured by speed.
 * Re-placed when the camera stops (idleTick); moved with the chart on every camera change (tick).
 */
@Composable
fun CurrentsOverlay(map: MapLibreMap?, tick: Int, idleTick: Int, field: CurrentField?, modifier: Modifier = Modifier) {
    val m = map ?: return
    val f = field ?: return
    var wPx by remember { mutableFloatStateOf(0f) }
    var hPx by remember { mutableFloatStateOf(0f) }
    val density = android.content.res.Resources.getSystem().displayMetrics.density

    val arrows = remember(idleTick, f, wPx, hPx) {
        if (wPx == 0f) return@remember emptyList<CurrentArrow>()
        val proj = m.projection
        val spacing = CURRENT_SPACING_DP * density
        val trackPx = TRACK_DP * density
        val out = ArrayList<CurrentArrow>()
        val land = LandCheck(m, wPx, hPx)                  // the model's nearest point can be a shore triangle: skip land
        var row = 0
        var y = spacing / 2
        while (y < hPx) {
            var x = spacing / 2 + if (row % 2 == 1) spacing / 2 else 0f      // staggered rows read better
            while (x < wPx) {
                val ll = proj.fromScreenLocation(PointF(x, y))
                val c = if (land.onLand(ll.latitude, ll.longitude)) null else f.at(ll.latitude, ll.longitude)
                if (c != null && c.knots >= 0.01f) {
                    // streamline: step along the local direction in screen space, re-sampling the field each step
                    val dx = FloatArray(TRACK_POINTS); val dy = FloatArray(TRACK_POINTS)
                    var px = x; var py = y
                    val step = trackPx / (TRACK_POINTS - 1)
                    var steps = 0
                    for (i in 1 until TRACK_POINTS) {
                        val here = proj.fromScreenLocation(PointF(px, py))
                        if (i > 1 && land.onLand(here.latitude, here.longitude)) break      // streamline reached the shore
                        val v = f.at(here.latitude, here.longitude) ?: break        // off the model
                        val sp = hypot(v.u, v.v)
                        if (sp <= 0f) break
                        px += v.u / sp * step; py -= v.v / sp * step                 // screen y points down
                        dx[i] = px - x; dy[i] = py - y
                        steps = i
                    }
                    for (i in steps + 1 until TRACK_POINTS) { dx[i] = dx[steps]; dy[i] = dy[steps] }
                    val ok = steps >= 3
                    if (ok && (dx.last() != 0f || dy.last() != 0f)) {
                        val phase = (((ll.latitude * 7919 + ll.longitude * 104729) % 1.0 + 1.0) % 1.0).toFloat()
                        out += CurrentArrow(ll.latitude, ll.longitude, dx, dy, c.knots, phase)
                    }
                }
                x += spacing
            }
            y += spacing; row++
        }
        out
    }

    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(arrows.isNotEmpty()) {
        if (arrows.isNotEmpty()) while (true) { withFrameMillis { nowMs = it }; delay(CURRENT_FRAME_MS) }
    }

    // where each arrow's anchor is on screen: worked out when the camera moves (tick), not on every animation frame
    val screen = remember(arrows, tick) {
        FloatArray(arrows.size * 2).also { xy ->
            arrows.forEachIndexed { i, a ->
                val p = m.projection.toScreenLocation(LatLng(a.lat, a.lon))
                xy[2 * i] = p.x; xy[2 * i + 1] = p.y
            }
        }
    }
    val path = remember { Path() }                                  // one path reused for every arrow and frame

    Canvas(modifier.fillMaxSize().onSizeChanged { wPx = it.width.toFloat(); hPx = it.height.toFloat() }) {
        val t = nowMs / 1000.0
        val arrowPx = ARROW_DP.dp.toPx()
        val edge = 3.2.dp.toPx()
        val ink = 1.7.dp.toPx()
        val head = 4.dp.toPx()
        val margin = 40.dp.toPx()
        for ((ix, a) in arrows.withIndex()) {
            val px = screen[2 * ix]; val py = screen[2 * ix + 1]
            if (px < -margin || py < -margin || px > size.width + margin || py > size.height + margin) continue
            val cyc = ((t / a.period + a.phase) % 1.0).toFloat()
            val alpha = sin(PI * cyc).toFloat().coerceIn(0f, 1f)
            if (alpha < 0.06f) continue
            // the arrow is the part of the streamline from `start` to `start + arrowPx`
            val cum = a.cum
            val total = cum.last()
            val len = arrowPx.coerceAtMost(total)
            val start = (total - len) * cyc
            fun pointAt(s: Float): Offset {
                var i = 1
                while (i < TRACK_POINTS - 1 && cum[i] < s) i++
                val seg = (cum[i] - cum[i - 1]).takeIf { it > 0f } ?: 1f
                val k = ((s - cum[i - 1]) / seg).coerceIn(0f, 1f)
                return Offset(px + a.dx[i - 1] + (a.dx[i] - a.dx[i - 1]) * k, py + a.dy[i - 1] + (a.dy[i] - a.dy[i - 1]) * k)
            }
            path.rewind()
            val n = 6
            for (j in 0..n) {
                val q = pointAt(start + len * j / n)
                if (j == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            // open arrow head along the last bit of the curve
            val tip = pointAt(start + len)
            val back = pointAt(start + len - head)
            val ux = (tip.x - back.x); val uy = (tip.y - back.y)
            val ul = hypot(ux, uy).takeIf { it > 0f } ?: 1f
            val ex = ux / ul; val ey = uy / ul
            val h1 = Offset(tip.x - ex * head + -ey * head * 0.7f, tip.y - ey * head + ex * head * 0.7f)
            val h2 = Offset(tip.x - ex * head - -ey * head * 0.7f, tip.y - ey * head - ex * head * 0.7f)
            path.moveTo(h1.x, h1.y); path.lineTo(tip.x, tip.y); path.lineTo(h2.x, h2.y)
            val color = a.color
            drawPath(path, ArrowEdge.copy(alpha = alpha * 0.6f), style = Stroke(edge, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color.copy(alpha = alpha), style = Stroke(ink, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
