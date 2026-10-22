package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Ink
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Barograph screens: the page (reading, trend and outlook, tendency color bar, scrolling graph, time-span slider),
 * the menu entry (Baro button with its logging switch) and the yellow box shown over the chart when the pressure
 * starts falling fast. Pressure is shown as sea-level pressure in hPa, the same as the NOAA buoys.
 */

// Tendency colors (3-hour change, Met Office bands): bar gradient stops and one color per band for text
private val BAR_STOPS = listOf(-8.0 to 0xFFD62828, -6.0 to 0xFFD62828, -3.5 to 0xFFF28C28, -1.5 to 0xFFF2C94C,
    0.0 to 0xFF5B6B7D, 1.5 to 0xFF5B6B7D, 3.5 to 0xFF5DADE2, 8.0 to 0xFF2E86C1)
private const val BAR_RANGE = 8.0                     // the bar runs -8 .. +8 hPa / 3 h
private val BUOY_DOT = Color(0xFF9BE7DD)
private val WARN_YELLOW = Color(0xFFF6D44A)
private val WARN_EDGE = Color(0xFFB8860B)

/** Text color for a 3-hour change: one color per tendency band. */
fun tendencyColor(r3: Double?, onYellow: Boolean = false): Color = when {
    r3 == null -> if (onYellow) Ink else Mist
    r3 <= -6.0 -> Color(0xFFD62828)
    r3 <= -3.5 -> if (onYellow) Color(0xFFAA4600) else Color(0xFFF28C28)
    r3 <= -1.5 -> if (onYellow) Color(0xFF8A6A00) else Color(0xFFF2C94C)
    r3 < 1.5 -> if (onYellow) Ink else Mist
    r3 < 3.5 -> if (onYellow) Color(0xFF1F6FA8) else Color(0xFF5DADE2)
    else -> Color(0xFF2E86C1)
}

/** Color under the graph line at a 3-hour change (same bands as the bar). */
private fun bandFill(r3: Double?): Color = when {
    r3 == null -> Color(0xFF5B6B7D)
    r3 <= -6.0 -> Color(0xFFD62828)
    r3 <= -3.5 -> Color(0xFFF28C28)
    r3 <= -1.5 -> Color(0xFFF2C94C)
    r3 < 1.5 -> Color(0xFF5B6B7D)
    r3 < 3.5 -> Color(0xFF5DADE2)
    else -> Color(0xFF2E86C1)
}

/** Sea-level pressure (hPa) for a logged reading. */
private fun seaLevel(p: BaroPoint, elevM: Double) = BaroMath.toSeaLevel(p.hPa, elevM)

// ---------------------------------------------------------------------------------------- shared pieces

/** TREND and OUTLOOK lines: small label, colored tendency words; outlook symbol and words. */
@Composable
fun BaroTrendLines(t: BaroTrend, onYellow: Boolean = false) {
    val sub = if (onYellow) Color(0xFF5A4614) else Mist
    val ink = if (onYellow) Ink else Foam
    val (sym, words) = BaroMath.outlook(t)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TREND", color = sub, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
            Text(BaroMath.tendencyWords(t.shownRate), color = tendencyColor(t.shownRate, onYellow), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OUTLOOK", color = sub, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
            Text("$sym ", fontSize = 13.sp)
            Text(words, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Tendency color bar, -8 .. +8 hPa per 3 h, with a pointer and the value at the current rate. */
@Composable
fun BaroTendencyBar(rate3: Double?, onYellow: Boolean = false, labels: Boolean = true, modifier: Modifier = Modifier) {
    val m = rememberTextMeasurer()
    val ink = if (onYellow) Ink else Foam
    Canvas(modifier.fillMaxWidth().height(if (labels) 44.dp else 30.dp)) {
        val barTop = 18.dp.toPx(); val barH = 7.dp.toPx()
        val stops = BAR_STOPS.map { (v, c) -> ((v + BAR_RANGE) / (2 * BAR_RANGE)).toFloat() to Color(c) }.toTypedArray()
        drawRect(Brush.horizontalGradient(*stops, startX = 0f, endX = size.width), Offset(0f, barTop), Size(size.width, barH))
        if (rate3 != null) {
            val x = (((rate3.coerceIn(-BAR_RANGE, BAR_RANGE) + BAR_RANGE) / (2 * BAR_RANGE)) * size.width).toFloat()
            val tri = Path().apply { moveTo(x, barTop - 1.dp.toPx()); lineTo(x - 6.dp.toPx(), barTop - 10.dp.toPx()); lineTo(x + 6.dp.toPx(), barTop - 10.dp.toPx()); close() }
            drawPath(tri, ink)
            val lab = m.measure("%+.1f /3 h".format(rate3), TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            val lx = if (x + 10.dp.toPx() + lab.size.width < size.width) x + 10.dp.toPx() else x - 10.dp.toPx() - lab.size.width
            drawText(lab, topLeft = Offset(lx, barTop - 10.dp.toPx() - lab.size.height / 2f + 2.dp.toPx()))
        }
        if (labels) for ((v, s) in listOf(-6.0 to "−6", -3.5 to "−3.5", 0.0 to "0", 3.5 to "+3.5", 6.0 to "+6")) {
            val lab = m.measure(s, TextStyle(color = Mist, fontSize = 9.5.sp))
            val x = ((v + BAR_RANGE) / (2 * BAR_RANGE) * size.width).toFloat()
            drawText(lab, topLeft = Offset(x - lab.size.width / 2f, barTop + barH + 3.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------------------- menu entry

/** Baro: button to the page (with the reading and 3 h change in the tendency color) and the logging switch. Gray without a barometer. */
@Composable
fun BaroMenuRow(onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    Baro.version                                                          // redraw when the log changes
    val has = remember { Baro.hasSensor(context) }
    val on = Baro.isOn(context)
    val pts = remember(Baro.version) { BaroMath.clean(Baro.log(context)) }
    // same button as Anchor: icon, "Barograph", ›; grayed out on phones without a barometer
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().alpha(if (has) 1f else 0.4f)) {
        Box(Modifier.weight(1f)) { MenuButton(MenuIcon.BARO, "Barograph", onClick = onOpen) }
        if (has && pts.isNotEmpty()) HoldToResetArrow(onReset = { Baro.reset(context) })
        Switch(checked = on && has, onCheckedChange = onToggle, enabled = has, modifier = Modifier.padding(start = 4.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = SignalOrange, checkedThumbColor = Foam,
                uncheckedTrackColor = NavyLight, uncheckedThumbColor = Mist, uncheckedBorderColor = NavyLight))
    }
}

// ---------------------------------------------------------------------------------------- warning box

/** Yellow box over the chart: ⚠️, trend and outlook, color bar. Tap it for the barograph. */
@Composable
fun BaroWarningBox(t: BaroTrend, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.widthIn(max = 420.dp).clip(shape).background(WARN_YELLOW, shape).border(2.dp, WARN_EDGE, shape)
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("⚠️", fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            BaroTrendLines(t, onYellow = true)
            BaroTendencyBar(t.shownRate, onYellow = true, labels = false)
        }
    }
}

// ---------------------------------------------------------------------------------------- the page

private val SPANS = listOf(3 * BaroMath.HOUR to "3 h", 6 * BaroMath.HOUR to "6 h", 24 * BaroMath.HOUR to "24 h", 72 * BaroMath.HOUR to "3 d")

/**
 * Barograph page. Opening it takes one fresh reading; the sampling interval is otherwise unchanged.
 * Drag the graph sideways to scroll back; the slider picks how much time is shown (3 h, 6 h, 24 h, 3 d).
 * @param buoy nearest NOAA station reporting pressure (id, name) and its last-24-h readings loader
 */
@Composable
fun BarographScreen(buoy: Pair<String, String>?, loadBuoy: suspend () -> List<BuoyObs>?, onClose: () -> Unit) {
    val context = LocalContext.current
    val has = remember { Baro.hasSensor(context) }
    LaunchedEffect(Unit) { Baro.sampleNow(context) }                      // one fresh reading when the page opens
    var buoyHist by remember { mutableStateOf<List<BuoyObs>?>(null) }
    LaunchedEffect(buoy?.first) { buoyHist = withContext(Dispatchers.IO) { runCatching { loadBuoy() }.getOrNull() } }
    var spanIx by remember { mutableIntStateOf(1) }                       // default 6 h
    var endMs by remember { mutableLongStateOf(0L) }                      // 0 = now (follows new readings)
    val version = Baro.version
    val pts = remember(version) { BaroMath.clean(Baro.log(context)) }
    val now = System.currentTimeMillis()
    val t = remember(version) { BaroMath.trend(pts, now) }
    val elev = Baro.elevationM(context)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(NavyDeep)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // ---- title bar, like the Celestial page
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Barograph", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Foam, modifier = Modifier.weight(1f))
                    if (has && pts.isNotEmpty()) HoldToResetArrow(onReset = { Baro.reset(context) })
                    TextButton(onClick = onClose) { Text("Done", color = SignalOrange, fontSize = 16.sp) }
                }
                Column(Modifier.fillMaxSize().background(Navy).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (!has) Text("This phone has no barometer.", color = Foam, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    else {
                    // ---- reading, trend and outlook, color bar
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(130.dp)) {
                            Text(t.now?.let { "%.1f".format(seaLevel(it, elev)) } ?: "—", color = Foam, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("hPa", color = Mist, fontSize = 12.sp)
                        }
                        BaroTrendLines(t)
                    }
                    BaroTendencyBar(t.shownRate, modifier = Modifier.padding(top = 6.dp))
                    if (!Baro.isOn(context)) Text("Logging is off: turn Baro on in the menu.", color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

                    // ---- graph
                    val span = SPANS[spanIx].first
                    val end = if (endMs == 0L) now else endMs
                    BaroGraph(pts, buoyHist, elev, end, span, live = endMs == 0L,
                        onScroll = { dMs ->
                            val first = pts.firstOrNull()?.t ?: now
                            val e = ((if (endMs == 0L) now else endMs) - dMs).coerceIn(minOf(now, first + span), now)
                            endMs = if (e >= now - 60_000L) 0L else e
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp))

                    // ---- time span slider
                    SpanSlider(spanIx, { spanIx = it; endMs = 0L }, Modifier.fillMaxWidth().padding(top = 12.dp))
                    // ---- one-line key
                    val bLast = buoyHist?.lastOrNull { it.presHpa != null }
                    Text(
                        listOfNotNull(buoy?.let { "●  buoy ${it.first}" + (bLast?.presHpa?.let { p -> " %.1f".format(p) } ?: "") }, "▼ alert", "- - trend").joinToString("     "),
                        color = Mist, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** The graph: tendency band under the line, the line, buoy dots, alert marks, trend continued, outlook symbols on top. */
@Composable
private fun BaroGraph(pts: List<BaroPoint>, buoy: List<BuoyObs>?, elev: Double, end: Long, span: Long, live: Boolean,
                      onScroll: (Long) -> Unit, modifier: Modifier) {
    val m = rememberTextMeasurer()
    val start = end - span
    // readings in view (plus one either side so the line reaches the edges), as sea level
    val view = remember(pts, end, span) {
        val i0 = pts.indexOfFirst { it.t >= start }.let { if (it <= 0) 0 else it - 1 }
        val i1 = pts.indexOfLast { it.t <= end }.let { if (it < 0) -1 else minOf(pts.size - 1, it + 1) }
        if (i1 < i0) emptyList() else pts.subList(i0, i1 + 1)
    }
    // 3 h change at each reading in view (for the band), and the warning level (for the alert marks)
    val rates = remember(view) { view.map { BaroMath.rate3h(pts, it.t) ?: BaroMath.rate1h(pts, it.t)?.times(3) } }
    val levels = remember(view) { view.map { BaroMath.level(BaroMath.rate1h(pts, it.t), BaroMath.rate3h(pts, it.t), BaroMath.squall(pts, it.t) != null) } }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().pointerInput(span) {
            detectHorizontalDragGestures { _, dx -> onScroll((dx / size.width * span).toLong()) }
        }) {
            val left = 44.dp.toPx(); val top = 30.dp.toPx(); val bottom = size.height - 22.dp.toPx()
            val right = size.width - 6.dp.toPx()
            val proj = if (live) span / 4 else 0L                                // room for the trend continued
            val t1 = end + proj
            fun x(t: Long) = left + (right - left) * ((t - start).toFloat() / (t1 - start))
            val ys = view.map { BaroMath.toSeaLevel(it.hPa, elev) }
            val now = view.lastOrNull()
            val r3 = now?.let { BaroMath.rate3h(pts, it.t) ?: BaroMath.rate1h(pts, it.t)?.times(3) }
            val projEnd = if (live && now != null && r3 != null) BaroMath.toSeaLevel(now.hPa, elev) + r3 / 3 * proj / BaroMath.HOUR else null
            val bys = buoy.orEmpty().filter { it.timeMs in start..end }.mapNotNull { b -> b.presHpa?.let { b.timeMs to it } }
            val all = ys + listOfNotNull(projEnd) + bys.map { it.second }
            if (all.isEmpty()) {
                val lab = m.measure("No readings yet", TextStyle(color = Mist, fontSize = 13.sp))
                drawText(lab, topLeft = Offset(size.width / 2 - lab.size.width / 2, size.height / 2))
                return@Canvas
            }
            // y range: at least 4 hPa, rounded to whole hPa
            var lo = floor(all.min() - 0.5); var hi = ceil(all.max() + 0.5)
            if (hi - lo < 4) { val c = (hi + lo) / 2; lo = floor(c - 2); hi = ceil(c + 2) }
            fun y(v: Double) = bottom - (bottom - top) * ((v - lo) / (hi - lo)).toFloat()

            // grid: hPa lines and time lines (hours or days by span)
            val stepH = when { hi - lo <= 6 -> 1.0; hi - lo <= 15 -> 2.0; else -> 5.0 }
            var v = ceil(lo / stepH) * stepH
            while (v <= hi) {
                drawLine(NavyLight, Offset(left, y(v)), Offset(right, y(v)), 1f)
                val lab = m.measure("%.0f".format(v), TextStyle(color = Mist, fontSize = 9.5.sp))
                drawText(lab, topLeft = Offset(left - lab.size.width - 4.dp.toPx(), y(v) - lab.size.height / 2))
                v += stepH
            }
            val tick = when { span <= 3 * BaroMath.HOUR -> BaroMath.HOUR / 2; span <= 6 * BaroMath.HOUR -> BaroMath.HOUR; span <= 24 * BaroMath.HOUR -> 4 * BaroMath.HOUR; else -> 12 * BaroMath.HOUR }
            val cal = java.util.Calendar.getInstance()
            var tt = (start / tick + 1) * tick
            while (tt <= end) {
                cal.timeInMillis = tt
                val hh = cal.get(java.util.Calendar.HOUR_OF_DAY); val mm = cal.get(java.util.Calendar.MINUTE)
                val midnight = hh == 0 && mm == 0
                drawLine(if (midnight) Mist else NavyLight, Offset(x(tt), top), Offset(x(tt), bottom), if (midnight) 1.5f else 1f,
                    pathEffect = if (midnight) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null)
                val s = if (midnight) java.text.SimpleDateFormat("EEE", java.util.Locale.US).format(java.util.Date(tt)) else "%02d:%02d".format(hh, mm)
                val lab = m.measure(s, TextStyle(color = Mist, fontSize = 9.5.sp))
                drawText(lab, topLeft = Offset(x(tt) - lab.size.width / 2, bottom + 4.dp.toPx()))
                tt += tick
            }
            if (live) drawLine(Mist, Offset(x(end), top), Offset(x(end), bottom), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))

            // band under the line, colored by the 3 h change at each reading
            for (i in 0 until view.size - 1) {
                val a = view[i]; val b = view[i + 1]
                if (b.t - a.t > 30 * BaroMath.MINUTE) continue                 // gap in the log: leave it empty
                val p = Path().apply {
                    moveTo(x(a.t), bottom); lineTo(x(a.t), y(ys[i])); lineTo(x(b.t), y(ys[i + 1])); lineTo(x(b.t), bottom); close()
                }
                drawPath(p, bandFill(rates[i]).copy(alpha = 0.38f))
            }
            // the line
            val line = Path()
            for (i in view.indices) {
                val px = x(view[i].t); val py = y(ys[i])
                if (i == 0 || view[i].t - view[i - 1].t > 30 * BaroMath.MINUTE) line.moveTo(px, py) else line.lineTo(px, py)
            }
            drawPath(line, Foam, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
            // trend continued
            if (projEnd != null && now != null)
                drawLine(SignalOrange, Offset(x(now.t), y(ys.last())), Offset(x(t1), y(projEnd)), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
            // buoy dots
            for ((bt, bp) in bys) drawCircle(BUOY_DOT, 3.dp.toPx(), Offset(x(bt), y(bp)))
            // alert marks where the level first went up
            for (i in 1 until view.size) if (levels[i] > levels[i - 1] && levels[i] > 0) {
                val c = if (levels[i] >= 2) Color(0xFFD62828) else SignalOrange
                val px = x(view[i].t); val py = y(ys[i]) - 10.dp.toPx()
                drawPath(Path().apply { moveTo(px, py + 8.dp.toPx()); lineTo(px - 6.dp.toPx(), py - 2.dp.toPx()); lineTo(px + 6.dp.toPx(), py - 2.dp.toPx()); close() }, c)
            }
            // outlook symbols along the top: one per third of the view, from the 3 h change in its middle
            for (k in 0 until 3) {
                val tm = start + span * (2 * k + 1) / 6
                if (pts.none { abs(it.t - tm) < 30 * BaroMath.MINUTE }) continue
                val tr = BaroTrend(null, BaroMath.rate1h(pts, tm), BaroMath.rate3h(pts, tm), BaroMath.squall(pts, tm), 0, 0)
                val sym = BaroMath.outlook(tr).first
                val lab = m.measure(sym, TextStyle(fontSize = 16.sp))
                drawText(lab, topLeft = Offset(x(tm) - lab.size.width / 2, top - lab.size.height - 2.dp.toPx()))
            }
        }
        // scroll tabs at the edges: more log that way
        val canBack = (pts.firstOrNull()?.t ?: Long.MAX_VALUE) < start
        if (canBack) ScrollTab("‹", Modifier.align(Alignment.CenterStart)) { onScroll(span / 2) }       // back in time
        if (!live) ScrollTab("›", Modifier.align(Alignment.CenterEnd)) { onScroll(-span / 2) }          // toward now
    }
}

@Composable
private fun ScrollTab(s: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(width = 26.dp, height = 52.dp).clip(RoundedCornerShape(13.dp)).background(NavyLight).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(s, color = SignalOrange, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}

/** Slider with four stops (3 h, 6 h, 24 h, 3 d): drag or tap the orange knob onto one. */
@Composable
private fun SpanSlider(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val m = rememberTextMeasurer()
    Canvas(modifier.height(58.dp).pointerInput(Unit) {
        fun pick(x: Float) {
            val l = 28.dp.toPx(); val r = size.width - 28.dp.toPx()
            onSelect(((x - l) / (r - l) * (SPANS.size - 1)).roundToInt().coerceIn(0, SPANS.size - 1))
        }
        detectTapGestures { pick(it.x) }
    }.pointerInput(Unit) {
        detectHorizontalDragGestures { change, _ ->
            val l = 28.dp.toPx(); val r = size.width - 28.dp.toPx()
            onSelect(((change.position.x - l) / (r - l) * (SPANS.size - 1)).roundToInt().coerceIn(0, SPANS.size - 1))
        }
    }) {
        val l = 28.dp.toPx(); val r = size.width - 28.dp.toPx(); val cy = 16.dp.toPx()
        fun sx(i: Int) = l + (r - l) * i / (SPANS.size - 1)
        drawLine(NavyLight, Offset(l, cy), Offset(r, cy), 4.dp.toPx(), StrokeCap.Round)
        drawLine(SignalOrange, Offset(l, cy), Offset(sx(selected), cy), 4.dp.toPx(), StrokeCap.Round)
        for (i in SPANS.indices) {
            drawCircle(Mist, 3.5.dp.toPx(), Offset(sx(i), cy))
            val lab = m.measure(SPANS[i].second, TextStyle(color = if (i == selected) Foam else Mist, fontSize = 13.sp,
                fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal))
            drawText(lab, topLeft = Offset(sx(i) - lab.size.width / 2, cy + 14.dp.toPx()))
        }
        drawCircle(SignalOrange, 11.dp.toPx(), Offset(sx(selected), cy))
        drawCircle(Foam, 11.dp.toPx(), Offset(sx(selected), cy), style = Stroke(1.5.dp.toPx()))
    }
}
