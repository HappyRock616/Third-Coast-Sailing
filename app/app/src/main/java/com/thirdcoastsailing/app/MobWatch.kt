package com.thirdcoastsailing.app

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/*
 * Man overboard (MOB).
 *
 *  - The MOB button is at the top of the menu (soft red, thick red border). One tap marks the boat's GPS position
 *    with a red push pin and clears the chart: wind / gust color, barbs, waves, the route and any weather routing
 *    are switched off (the wind gauge stays as it was).
 *  - A red line runs from the boat to the pin, labeled with true bearing and distance, and the same MOB box sits
 *    across the top of the chart: bearing, distance and time since the mark.
 *  - Drift area: an orange triangle from the pin pointing downwind, growing with time since the mark. Its length is
 *    the downwind leeway of a person in the water; its spread is the crosswind leeway either side.
 *    Coefficients: "Person-in-water (PIW), unknown state (mean values)" from the US Coast Guard leeway studies
 *    (Allen & Plourde 1999, CG-D-08-99; Allen 2005), as tabulated in OpenDrift's OBJECTPROP.DAT: downwind 0.96 %
 *    of the 10 m wind, crosswind ±0.54 % of the wind (offsets 0). Wind: the forecast at the pin, refreshed hourly.
 *    Surface current is not included (no current data in the app).
 *  - Reset: hold the MOB button or the MOB box for 5 seconds (a darker shade fills across it as you hold).
 *    The mark is saved on the phone until then.
 */

object MobWatch {
    const val RESET_HOLD_MS = 5000
    /** PIW, unknown state: downwind leeway as a fraction of the 10 m wind speed. */
    const val LEEWAY_DOWNWIND = 0.0096
    /** PIW, unknown state: crosswind leeway (each side) as a fraction of the 10 m wind speed. */
    const val LEEWAY_CROSSWIND = 0.0054
    private const val PREFS = "tcs_mob"

    /** The MOB mark: where and when, plus the forecast wind at the pin for the drift triangle. No pin = MOB not active. */
    data class State(
        val pin: GeoPt? = null,                  // where the person went in
        val timeMs: Long = 0L,                   // when MOB was pressed
        val windKn: Double? = null,              // forecast wind at the pin (10 m), knots
        val windFromDeg: Double? = null,         // direction the wind blows from, true
    ) {
        val active get() = pin != null

        /** Drift triangle: (downwind length, crosswind half-width) in nm after `nowMs`, or null without wind. */
        fun drift(nowMs: Long): Pair<Double, Double>? {
            val w = windKn ?: return null
            val h = ((nowMs - timeMs) / 3_600_000.0).coerceAtLeast(0.0)
            return w * LEEWAY_DOWNWIND * h to w * LEEWAY_CROSSWIND * h
        }
    }

    var state by mutableStateOf(State())
        private set
    private var loaded = false

    /** Restore a MOB mark saved before the app was closed (only once per app start). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull(); val lon = p.getString("lon", null)?.toDoubleOrNull()
        if (lat != null && lon != null) state = State(GeoPt(lat, lon), p.getLong("t", 0L),
            p.getString("wkn", null)?.toDoubleOrNull(), p.getString("wdir", null)?.toDoubleOrNull())
    }

    /** Write the current mark to the phone, or clear it when MOB is reset. */
    private fun save(context: Context) {
        val s = state
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            clear()
            if (s.pin != null) {
                putString("lat", s.pin.lat.toString()); putString("lon", s.pin.lon.toString()); putLong("t", s.timeMs)
                s.windKn?.let { putString("wkn", it.toString()) }; s.windFromDeg?.let { putString("wdir", it.toString()) }
            }
        }.apply()
    }

    /** Mark the man overboard at the boat's position now. */
    fun activate(context: Context, at: BoatFix) {
        state = State(GeoPt(at.lat, at.lon), System.currentTimeMillis())
        save(context)
    }

    /** Forecast wind at the pin: (knots, from degrees true). */
    fun setWind(context: Context, wind: Pair<Double, Double>?) {
        if (!state.active || wind == null) return
        state = state.copy(windKn = wind.first, windFromDeg = wind.second)
        save(context)
    }

    /** End MOB: clear the mark and the saved copy. */
    fun reset(context: Context) { state = State(); save(context) }
}

private val MOB_SOFT = Color(0xFFF6C9C9)        // soft red fill
private val MOB_RED = Color(0xFFD62828)         // border, pin, line
private val MOB_DARK = Color(0xFF7A1010)        // text, hold-progress shade
/** Drift triangle fill. */
private val DRIFT_ORANGE = Color(0xFFF28C28)

/** "214°T · 0.42 nm" from the boat to the pin, or "No GPS". */
private fun mobLine(s: MobWatch.State, boat: BoatFix?): String {
    val pin = s.pin ?: return ""
    if (boat == null) return "No GPS"
    val from = LatLon(boat.lat, boat.lon); val to = LatLon(pin.lat, pin.lon)
    val nm = distanceNm(from, to)
    val dist = if (nm < 0.1) "%d ft".format((nm * 6076.12).roundToInt()) else "%.2f nm".format(nm)
    return "%03.0f°T · %s".format(bearing(from, to), dist)
}

/** Time in the water, counting up: "12:34", or "1:02:34" after an hour. */
private fun mobTimer(s: MobWatch.State, nowMs: Long): String {
    val sec = ((nowMs - s.timeMs) / 1000).coerceAtLeast(0)
    val h = sec / 3600; val m = (sec / 60) % 60; val ss = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/**
 * The MOB box: in the menu (compact, the same height as the Boat button, just above the GPS box) and across the top
 * of the chart (large). Not active: "MOB" — one tap marks it. Active: a timer counting up the time in the water,
 * bearing and distance to the pin, and "hold 5 s to reset" (a darker shade fills across while held).
 */
@Composable
fun MobBox(boat: BoatFix?, onActivate: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val s = MobWatch.state
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val activate by rememberUpdatedState(onActivate)
    val reset by rememberUpdatedState(onReset)
    val active by rememberUpdatedState(s.active)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.active) { while (s.active) { now = System.currentTimeMillis(); delay(1_000) } }   // timer ticks each second
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(MOB_SOFT, shape).border(4.dp, MOB_RED, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!active) activate() },
                    onPress = {
                        if (!active) return@detectTapGestures
                        val job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(MobWatch.RESET_HOLD_MS, easing = LinearEasing))
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            reset()
                            progress.snapTo(0f)
                        }
                        tryAwaitRelease()
                        if (job.isActive) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                    },
                )
            },
    ) {
        // hold-to-reset progress: a darker shade filling from left to right
        Canvas(Modifier.matchParentSize()) {
            val p = progress.value
            if (p > 0f) drawRect(MOB_DARK.copy(alpha = 0.35f), size = Size(size.width * p, size.height))
        }
        if (large) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // chart banner: MOB and the timer large, then bearing and distance
            Canvas(Modifier.size(30.dp)) { drawLifebuoy() }
            Column(Modifier.padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("MOB", color = MOB_DARK, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    if (s.active) Text("  " + mobTimer(s, now), color = MOB_DARK, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                if (s.active) {
                    Text(mobLine(s, boat), color = MOB_DARK, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("hold 5 s to reset", color = MOB_DARK.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(52.dp).padding(horizontal = if (s.active) 10.dp else 14.dp)) {
            // menu: one compact row, the same height as the Boat button; active: "MOB 12:34" then bearing / distance
            Canvas(Modifier.size(if (s.active) 22.dp else 28.dp)) { drawLifebuoy() }
            Text(if (s.active) "MOB " + mobTimer(s, now) else "MOB", color = MOB_DARK, fontSize = if (s.active) 17.sp else 19.sp, fontWeight = FontWeight.Black,
                maxLines = 1, modifier = Modifier.padding(start = if (s.active) 8.dp else 12.dp))
            if (s.active) Column(Modifier.padding(start = 8.dp)) {
                Text(mobLine(s, boat), color = MOB_DARK, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("hold 5 s to reset", color = MOB_DARK.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** Small red-and-white lifebuoy for the MOB box. */
private fun DrawScope.drawLifebuoy() {
    val r = size.minDimension / 2f; val c = center
    drawCircle(Color.White, r * 0.92f, c)
    for (k in 0 until 4) drawArc(MOB_RED, k * 90f - 22f, 44f, true, c - Offset(r * 0.92f, r * 0.92f), Size(r * 1.84f, r * 1.84f))
    drawCircle(MOB_SOFT, r * 0.45f, c)
    drawCircle(MOB_DARK, r * 0.92f, c, style = Stroke(1.5.dp.toPx()))
    drawCircle(MOB_DARK, r * 0.45f, c, style = Stroke(1.5.dp.toPx()))
}

/** Over the chart: the drift triangle, the line from the boat to the pin with bearing and distance, and the pin. */
@Composable
fun MobOverlay(map: MapLibreMap?, tick: Int, boat: BoatFix?) {
    val s = MobWatch.state
    val measurer = rememberTextMeasurer()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.active) { while (s.active) { now = System.currentTimeMillis(); delay(15_000) } }
    Canvas(Modifier.fillMaxSize()) {
        tick                                                             // read so camera moves redraw
        val m = map ?: return@Canvas
        val pin = s.pin ?: return@Canvas
        fun xy(p: LatLon) = m.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { Offset(it.x, it.y) }
        val pinLL = LatLon(pin.lat, pin.lon)
        val c = xy(pinLL)

        // drift area: tip at the pin, opening downwind; length = downwind leeway x time, each side = crosswind leeway x time
        val d = s.drift(now); val from = s.windFromDeg
        if (d != null && from != null && d.first > 0.0) {
            val down = (from + 180.0) % 360.0
            val end = travel(pinLL, down, d.first)
            val left = travel(end, down - 90.0, d.second); val right = travel(end, down + 90.0, d.second)
            val pl = xy(left); val pr = xy(right)
            val tri = Path().apply { moveTo(c.x, c.y); lineTo(pl.x, pl.y); lineTo(pr.x, pr.y); close() }
            drawPath(tri, DRIFT_ORANGE.copy(alpha = 0.35f))
            drawPath(tri, DRIFT_ORANGE, style = Stroke(2.dp.toPx()))
        }

        // line from the boat to the pin, labeled true bearing and distance
        if (boat != null) {
            val b = xy(LatLon(boat.lat, boat.lon))
            drawLine(MOB_RED, b, c, 3.dp.toPx(), cap = StrokeCap.Round)
            val from2 = LatLon(boat.lat, boat.lon)
            val nm = distanceNm(from2, pinLL)
            val label = "%03.0f°T · %s".format(bearing(from2, pinLL),
                if (nm < 0.1) "%d ft".format((nm * 6076.12).roundToInt()) else "%.2f nm".format(nm))
            val t = measurer.measure(label, TextStyle(fontSize = 13.sp, color = MOB_RED, fontWeight = FontWeight.Bold))
            val mid = (b + c) / 2f
            drawRect(Color.White.copy(alpha = 0.85f), mid + Offset(6.dp.toPx(), -t.size.height - 2.dp.toPx()),
                Size(t.size.width + 6.dp.toPx(), t.size.height.toFloat()))
            drawText(t, topLeft = mid + Offset(9.dp.toPx(), -t.size.height - 2.dp.toPx()))
        }

        // red push pin, its point at the mark
        drawPushPin(c, 1.dp.toPx())
    }
}

/** Red push pin with its needle point at `tip`; `k` = px per dp. */
private fun DrawScope.drawPushPin(tip: Offset, k: Float) {
    val head = tip + Offset(0f, -22f * k)
    drawLine(Color(0xFF555555), tip, head + Offset(0f, 6f * k), 2.5f * k, cap = StrokeCap.Round)          // needle
    drawCircle(MOB_RED, 9f * k, head)
    drawCircle(MOB_DARK, 9f * k, head, style = Stroke(1.5f * k))
    drawCircle(Color.White.copy(alpha = 0.7f), 2.8f * k, head + Offset(-3f * k, -3f * k))                // shine
}
