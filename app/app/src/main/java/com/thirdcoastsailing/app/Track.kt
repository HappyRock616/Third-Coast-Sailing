package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/*
 * Trip track: a thin dashed line of where the boat has been since the menu toggle was switched on,
 * labeled with distance and elapsed recording time. Kept in memory only. Switching it off stops recording and
 * the clock but keeps the line; the reset arrow in the menu clears it. The line is continuous: a gap (switched off,
 * phone asleep) is spanned by a straight line and its distance counts.
 * Points come from the 1-second GPS while the app is on screen, and from a fix every 5 minutes (TrackService) while
 * it is in the background or the screen is off.
 */

/** Minimum spacing between recorded points (m), so GPS jitter doesn't add distance. */
private const val TRACK_STEP_M = 15.0

/** Below this speed (kn) a point is only recorded after a larger move, so drifting at the dock adds nothing. */
private const val TRACK_SLOW_KN = 0.3f
private const val TRACK_SLOW_STEP_M = 50.0

/** A jump is impossible when it implies more than twice the reported speed plus this margin (kn)... */
private const val JUMP_MARGIN_KN = 5.0
/** ...with this floor (kn), and this speed assumed when the GPS reports none. */
private const val JUMP_FLOOR_KN = 15.0
private const val JUMP_NO_SOG_KN = 30.0
/** Track points are the moving average of the last 3 fixes; a gap longer than this (ms) starts the average fresh. */
const val TRACK_AVG_GAP_MS = 30_000L
/** After this many impossible jumps in a row the new position is believed (the GPS has really moved on, e.g. after a gap). */
const val TRACK_JUMP_ACCEPT_AFTER = 3

/** What to do with a fix for the trip track. */
sealed interface TrackDecision {
    /** Add it as the next point; `nm` is the distance from the last point. */
    data class Add(val nm: Double) : TrackDecision
    /** Not far enough from the last point yet. */
    data object TooClose : TrackDecision
    /** Impossible jump from the last point (GPS glitch): leave it off the track. */
    data object Jump : TrackDecision
}

/**
 * Whether this fix becomes the next track point. The first fix of a trip always does. A fix is a jump (rejected) when
 * getting there from the last point would need far more speed than the GPS reports; the boat marker and navigation
 * still use every fix, only the track skips it.
 * @param lastMs time of the last track point; `jumpsInRow` impossible jumps just before this one
 */
fun trackStep(last: GeoPt?, lastMs: Long, fix: BoatFix, jumpsInRow: Int = 0): TrackDecision {
    if (last == null) return TrackDecision.Add(0.0)
    val d = distNm(last, GeoPt(fix.lat, fix.lon))
    val m = d * 1852.0
    val slow = fix.sogKn != null && fix.sogKn < TRACK_SLOW_KN
    if (m < (if (slow) TRACK_SLOW_STEP_M else TRACK_STEP_M)) return TrackDecision.TooClose
    val hours = (fix.time - lastMs) / 3_600_000.0
    if (hours > 0 && jumpsInRow < TRACK_JUMP_ACCEPT_AFTER) {
        val sog = fix.sogKn?.toDouble() ?: JUMP_NO_SOG_KN
        val allowed = maxOf(2 * sog + JUMP_MARGIN_KN, JUMP_FLOOR_KN)
        if (d / hours > allowed) return TrackDecision.Jump
    }
    return TrackDecision.Add(d)
}

/** Label next to the track, e.g. "12.4 nm · 1:23". */
fun trackLabel(distNm: Double, elapsedMs: Long): String {
    val min = (elapsedMs / 60_000).coerceAtLeast(0)
    return "%.1f nm · %d:%02d".format(distNm, min / 60, min % 60)
}

/**
 * Draws the trip track over the chart: thin dashed line through the recorded points to the boat's
 * current position (null boat = not recording: no live segment), with the label beside the newest end.
 */
@Composable
fun TrackOverlay(map: MapLibreMap?, tick: Int, points: List<GeoPt>, boat: BoatFix?, label: String?, night: Boolean) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        tick                                                         // read so camera moves redraw
        val m = map ?: return@Canvas
        if (points.isEmpty() || label == null) return@Canvas
        fun xy(lat: Double, lon: Double) = m.projection.toScreenLocation(LatLng(lat, lon)).let { Offset(it.x, it.y) }

        val ink = if (night) Foam else Navy                          // dark on the day chart, light at night
        val path = Path()
        points.forEachIndexed { i, p -> val o = xy(p.lat, p.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
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

/** How long the trip track's reset arrow must be held to clear the track (ms). */
const val TRACK_RESET_HOLD_MS = 1200

/**
 * The trip track's reset arrow (↺): hold it for TRACK_RESET_HOLD_MS to clear the track, so a stray tap can't wipe it.
 * An orange ring fills around the arrow while held; letting go early cancels. The phone buzzes when it clears.
 */
@Composable
fun HoldToResetArrow(onReset: () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val reset by androidx.compose.runtime.rememberUpdatedState(onReset)
    androidx.compose.foundation.layout.Box(
        Modifier.size(48.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, androidx.compose.animation.core.tween(TRACK_RESET_HOLD_MS, easing = androidx.compose.animation.core.LinearEasing))
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    reset()
                    progress.snapTo(0f)
                }
                tryAwaitRelease()
                if (job.isActive) { job.cancel(); scope.launch { progress.snapTo(0f) } }
            })
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Canvas(Modifier.size(36.dp)) {
            val p = progress.value
            if (p > 0f) drawArc(SignalOrange, -90f, 360f * p, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        androidx.compose.material3.Text("↺", color = SignalOrange, fontSize = 24.sp)
    }
}
