package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.Shot
import com.thirdcoastsailing.app.celestial.WorkFix
import com.thirdcoastsailing.app.celestial.WorkLop
import com.thirdcoastsailing.app.celestial.bearing
import com.thirdcoastsailing.app.celestial.distanceNm
import com.thirdcoastsailing.app.celestial.formatLat
import com.thirdcoastsailing.app.celestial.formatLon
import com.thirdcoastsailing.app.celestial.norm180
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Plotting aids for the workbook, drawn the way you would on a plotting sheet (north up, true bearings):
 *
 *  LopInsetRow    small plot at the end of each sight's reduction: assumed position (AP), the dashed bearing toward
 *                 the body (Zn) with an arrowhead and ★, the intercept in orange, the line of position at right
 *                 angles, and the boat at its GPS / DR position on its course. Text and a color key beside it.
 *                 Tap to enlarge into LopPlotDialog: the same plot labeled, with a scale and the four plotting steps.
 *  FixPlot        at the end of the workbook: every line of position (advanced to the fix time), an arrow from the
 *                 fix toward each body at its Zn, the 95% circle, the GPS (or DR) position and the boat on its course.
 *
 * The boat is only an orientation aid: its heading never changes a line, only which side of the boat you looked.
 */

private val PAPER = Color(0xFFECF1F5)
/** Plot ink (navy). */
private val INK = Color(0xFF0F2742)
/** Assumed-position marks and the GPS position. */
private val BLUE = Color(0xFF286ED2)
/** Fix and its uncertainty circle. */
private val PURPLE = Color(0xFF6A3FB5)
/** One color per body, so each line of position matches its sight. */
private val BODY_COLORS = listOf(Color(0xFF148C78), BLUE, Color(0xFFC84682), Color(0xFFB07A10), Color(0xFF5A8F29), Color(0xFF8A4FC8))

/** Course over ground for the plot: from the shots' own positions if the boat moved, else the live GPS course. */
fun plotCourse(shots: List<Shot>, gps: BoatFix?): Double? {
    val s = shots.sortedBy { it.timeMs }
    if (s.size >= 2 && distanceNm(s.first().dr, s.last().dr) > 0.05) return bearing(s.first().dr, s.last().dr)
    return gps?.takeIf { (it.sogKn ?: 0f) > 0.5f }?.cogDeg?.toDouble()
}

/** Flat local frame in nautical miles (east, north) round an origin; fine over the few miles a plot covers. */
private class Local(val o: LatLon) {
    /** A position in nm east and north of the origin. */
    fun en(p: LatLon) = Offset((norm180(p.lon - o.lon) * 60.0 * cos(Math.toRadians(o.lat))).toFloat(), ((p.lat - o.lat) * 60.0).toFloat())
}

/** Unit vector (east, north) along a true bearing. */
private fun dir(brgDeg: Double) = Offset(sin(Math.toRadians(brgDeg)).toFloat(), cos(Math.toRadians(brgDeg)).toFloat())

/** Maps the local frame to the canvas: `c` (nm) at the canvas center, `s` px per nm, north up. */
private class View(val c: Offset, val s: Float, val size: Size) {
    /** Canvas pixel of a point in the local frame. */
    fun px(p: Offset) = Offset(size.width / 2f + (p.x - c.x) * s, size.height / 2f - (p.y - c.y) * s)
    /** True when a canvas point is at least marginPx inside the canvas edges. */
    fun inside(p: Offset, marginPx: Float) = p.x in marginPx..(size.width - marginPx) && p.y in marginPx..(size.height - marginPx)
}

/** Top-view hull, bow along `hdgDeg`; `k` = px per hull unit. A plain dot when the course is unknown. */
private fun DrawScope.boat(at: Offset, hdgDeg: Double?, k: Float) {
    if (hdgDeg == null) {
        drawCircle(Color.White, 5f * k, at); drawCircle(INK, 5f * k, at, style = Stroke(1.2f * k)); return
    }
    val a = Math.toRadians(hdgDeg)
    /** Hull point (x across, y along the boat), rotated to the heading and scaled, in canvas pixels. */
    fun p(x: Float, y: Float) = Offset(at.x + ((x * cos(a) + y * sin(a)) * k).toFloat(), at.y - ((-x * sin(a) + y * cos(a)) * k).toFloat())
    val hull = listOf(p(0f, 15f), p(3.5f, 7.5f), p(4f, -2f), p(3f, -10f), p(-3f, -10f), p(-4f, -2f), p(-3.5f, 7.5f))
    val path = Path().apply { moveTo(hull[0].x, hull[0].y); hull.drop(1).forEach { lineTo(it.x, it.y) }; close() }
    drawPath(path, Color.White)
    drawPath(path, INK, style = Stroke(1.2f * k))
}

/** Line with an open arrowhead at `to`. */
private fun DrawScope.arrow(from: Offset, to: Offset, col: Color, w: Float, head: Float, dashed: Boolean = false) {
    drawLine(col, from, to, w, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 3f, w * 2.5f)) else null)
    val ang = kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    for (sgn in listOf(-1, 1)) {
        val b = ang + Math.PI + sgn * 0.45
        drawLine(col, to, Offset(to.x + (cos(b) * head).toFloat(), to.y + (sin(b) * head).toFloat()), w, cap = StrokeCap.Round)
    }
}

/** North arrow in the top-right corner. */
private fun DrawScope.north(m: TextMeasurer, size: TextUnit) {
    val t = m.measure("N", TextStyle(fontSize = size, color = INK, fontWeight = FontWeight.Bold))
    val x = this.size.width - t.size.width - 8.dp.toPx()
    drawText(t, topLeft = Offset(x, 4.dp.toPx()))
    val cx = x + t.size.width / 2f; val y0 = 6.dp.toPx() + t.size.height
    arrow(Offset(cx, y0 + 12.dp.toPx()), Offset(cx, y0), INK, 1.5.dp.toPx(), 4.dp.toPx())
}

/** Text with a paper-colored backing so it reads over lines; kept inside the canvas. */
private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, col: Color, size: TextUnit, bold: Boolean = false) {
    val t = m.measure(text, TextStyle(fontSize = size, color = col, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    val pad = 2.dp.toPx()
    val x = at.x.coerceIn(pad, this.size.width - t.size.width - pad)
    val y = at.y.coerceIn(pad, this.size.height - t.size.height - pad)
    drawRect(PAPER.copy(alpha = 0.85f), Offset(x - pad, y), Size(t.size.width + 2 * pad, t.size.height.toFloat()))
    drawText(t, topLeft = Offset(x, y))
}

/** Scale bar of a round number of miles, bottom left. */
private fun DrawScope.scaleBar(m: TextMeasurer, pxPerNm: Float) {
    val want = size.width * 0.28f / pxPerNm
    val mag = 10.0.pow(floor(log10(want.toDouble()))).toFloat()
    val nm = listOf(1f, 2f, 5f, 10f).map { it * mag }.last { it <= want.coerceAtLeast(mag) }
    val x0 = 10.dp.toPx(); val y = size.height - 22.dp.toPx()
    drawLine(INK, Offset(x0, y), Offset(x0 + nm * pxPerNm, y), 2.dp.toPx())
    val t = m.measure((if (nm >= 1f) "%.0f nm" else "%.1f nm").format(nm), TextStyle(fontSize = 11.sp, color = INK))
    drawText(t, topLeft = Offset(x0, y + 3.dp.toPx()))
}

/**
 * One sight's plot. `big` adds the labels and scale bar used in the enlarged view.
 * Uses the sight's own line (not advanced for a running fix): AP, Zn, intercept, and the GPS / DR at the sight.
 */
@Composable
fun LopPlot(lop: WorkLop, course: Double?, big: Boolean, modifier: Modifier = Modifier) {
    val m = rememberTextMeasurer()
    Canvas(modifier.background(PAPER, RoundedCornerShape(10.dp))) {
        val loc = Local(lop.assumed)
        val ap = Offset.Zero
        val u = dir(lop.znDeg)
        val foot = u * lop.interceptNm.toFloat()
        val boatAt = loc.en(lop.dr)
        // frame AP, the foot of the intercept and the boat, with room round them
        val xs = listOf(ap.x, foot.x, boatAt.x); val ys = listOf(ap.y, foot.y, boatAt.y)
        val c = Offset((xs.min() + xs.max()) / 2f, (ys.min() + ys.max()) / 2f)
        val span = max(max(xs.max() - xs.min(), ys.max() - ys.min()) * (if (big) 1.7f else 1.6f), 6f)
        val v = View(c, min(size.width, size.height) / span, size)
        val far = span * 1.5f
        val w = (if (big) 2.5f else 1.6f).dp.toPx()
        clipRect {
            // dashed bearing through the AP, toward the body and beyond
            drawLine(BLUE, v.px(ap - u * far), v.px(ap + u * far), w * 0.8f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 4f, w * 3f)))
            // arrowhead toward the body, as far out as stays inside the frame
            var L = span * 0.42f
            while (L > span * 0.1f && !v.inside(v.px(ap + u * L), 18.dp.toPx())) L -= span * 0.02f
            val tip = v.px(ap + u * L)
            arrow(v.px(ap + u * (L - span * 0.08f)), tip, BLUE, w, (if (big) 10f else 6f).dp.toPx())
            label(m, "★", tip + Offset(4.dp.toPx(), -6.dp.toPx()), BLUE, if (big) 16.sp else 11.sp, bold = true)
            // intercept (orange) and the line of position at right angles through its foot
            drawLine(SignalOrange, v.px(ap), v.px(foot), w * 1.6f, cap = StrokeCap.Round)
            val along = dir(lop.znDeg + 90.0)
            drawLine(BLUE, v.px(foot - along * far), v.px(foot + along * far), w * 1.6f)
            if (big) {
                // right-angle mark at the foot
                val r = 9.dp.toPx() / v.s
                val back = foot - u * r * (if (lop.interceptNm >= 0) 1f else -1f)
                drawPath(Path().apply {
                    val a = v.px(back); val b = v.px(back + along * r); val d = v.px(foot + along * r)
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y)
                }, INK, style = Stroke(1.dp.toPx()))
            }
            // AP
            drawCircle(PAPER, 5.dp.toPx(), v.px(ap)); drawCircle(INK, 5.dp.toPx(), v.px(ap), style = Stroke(1.8.dp.toPx()))
            label(m, "AP", v.px(ap) + Offset(7.dp.toPx(), 2.dp.toPx()), INK, if (big) 14.sp else 10.sp, bold = true)
            // boat at the GPS / DR position, on its course
            boat(v.px(boatAt), course, (if (big) 1.6f else 0.9f).dp.toPx())
            if (big) {
                val bz = abs(lop.interceptNm)
                label(m, "toward ${lop.bodyName}\nZn %.1f°T".format(lop.znDeg), tip + Offset(10.dp.toPx(), 10.dp.toPx()), BLUE, 13.sp, bold = true)
                val mid = v.px(foot * 0.5f)
                label(m, "%.1f nm %s".format(bz, if (lop.interceptNm >= 0) "toward" else "away"), mid + Offset(10.dp.toPx(), -22.dp.toPx()), SignalOrange, 14.sp, bold = true)
                // "line of position" near where the line leaves the top half of the frame
                var k = span * 0.45f
                while (k > 0f && !v.inside(v.px(foot + along * k), 30.dp.toPx())) k -= span * 0.02f
                val lp = if (v.px(foot + along * k).y < v.px(foot - along * k).y) foot + along * k else foot - along * k
                label(m, "line of position", v.px(lp) + Offset(8.dp.toPx(), -8.dp.toPx()), BLUE, 13.sp, bold = true)
                label(m, (if (lop.drIsGps) "boat (GPS)" else "boat (DR)") + (course?.let { ", course %03.0f°T".format(it) } ?: ""),
                    v.px(boatAt) + Offset(14.dp.toPx(), -6.dp.toPx()), INK, 12.sp)
                scaleBar(m, v.s)
            }
            north(m, if (big) 14.sp else 10.sp)
        }
    }
}

/** The small plot with its text and color key, at the end of a sight's reduction. Tapping opens the large plot. */
@Composable
fun LopInsetRow(lop: WorkLop, course: Double?, onEnlarge: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onEnlarge), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LopPlot(lop, course, big = false, modifier = Modifier.width(120.dp).aspectRatio(1f).border(1.dp, NavyLight, RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Text("Line of position", color = Foam, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Zn %.1f°T".format(lop.znDeg), color = Foam, fontSize = 14.sp)
            Text("%.1f nm %s %s".format(abs(lop.interceptNm), if (lop.interceptNm >= 0) "toward" else "away from", lop.bodyName),
                color = Foam, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            KeyRow(BLUE, dashed = true, "toward the body")
            KeyRow(SignalOrange, dashed = false, "the intercept")
            KeyRow(BLUE, dashed = false, "line of position")
            Text("Tap to enlarge", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** One row of the color key: a short line sample and its meaning. */
@Composable
private fun KeyRow(col: Color, dashed: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
            drawLine(col, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
        }
        Text(text, color = Mist, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** The enlarged sight plot, labeled, with the four plotting steps worked for this sight. */
@Composable
fun LopPlotDialog(lop: WorkLop, course: Double?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(NavyDeep).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(lop.bodyName, color = Foam, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Plot it", color = SignalOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onClose) { Text("Done ✓", color = SignalOrange, fontWeight = FontWeight.Bold) }
                }
                LopPlot(lop, course, big = true, modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(1f).padding(vertical = 8.dp))
                val way = if (lop.interceptNm >= 0) "toward" else "away from"
                val steps = listOf(
                    "Mark the assumed position (AP): ${formatLat(lop.assumed.lat)} ${formatLon(lop.assumed.lon)}.",
                    "Draw a line through it toward ${lop.bodyName}, at Zn %.1f°T: the direction you looked.".format(lop.znDeg),
                    "Measure the intercept along it: %.1f nm %s ${lop.bodyName} (toward if Ho is bigger than Hc).".format(abs(lop.interceptNm), way),
                    "Draw the line of position at right angles there. You are somewhere on it.",
                )
                for ((i, s) in steps.withIndex()) Text("${i + 1}. $s", color = Foam, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text("The boat's heading doesn't change the line, only which side you looked.", color = Mist, fontSize = 15.sp)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The fix at the end of the workbook: lines, where you looked for each body, the 95% circle, GPS / DR and the boat. */
@Composable
fun FixPlot(f: WorkFix, course: Double?, modifier: Modifier = Modifier) {
    val m = rememberTextMeasurer()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).background(PAPER, RoundedCornerShape(10.dp)).border(1.dp, NavyLight, RoundedCornerShape(10.dp))) {
            val loc = Local(f.position)
            val gpsAt = loc.en(f.drAtFix)
            val r95 = f.uncertainty?.radius95Nm?.toFloat() ?: 0f
            val half = max(max(r95 * 1.6f, hypot(gpsAt.x, gpsAt.y) * 1.3f), 2f)
            val v = View(Offset.Zero, min(size.width, size.height) / 2f / half, size)
            val far = half * 3f
            clipRect {
                // 95% circle
                if (r95 > 0f) {
                    drawCircle(PURPLE.copy(alpha = 0.10f), r95 * v.s, v.px(Offset.Zero))
                    drawCircle(PURPLE, r95 * v.s, v.px(Offset.Zero), style = Stroke(2.dp.toPx()))
                }
                // each line of position (advanced to the fix time), then the arrows toward each body
                for ((i, l) in f.lops.withIndex()) {
                    val col = BODY_COLORS[i % BODY_COLORS.size]
                    val p = loc.en(l.footPoint); val along = dir(l.znDeg + 90.0)
                    drawLine(col, v.px(p - along * far), v.px(p + along * far), 2.5.dp.toPx())
                }
                for ((i, l) in f.lops.withIndex()) {
                    val col = BODY_COLORS[i % BODY_COLORS.size]
                    val u = dir(l.znDeg)
                    val tip = v.px(u * (half * 0.62f))
                    arrow(v.px(Offset.Zero), tip, col, 1.8.dp.toPx(), 7.dp.toPx())
                    val t = "${l.bodyName} %03.0f°".format(l.znDeg)
                    val tw = m.measure(t, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)).size
                    val at = v.px(u * (half * 0.78f)) - Offset(tw.width / 2f, tw.height / 2f)
                    label(m, t, at, col, 12.sp, bold = true)
                }
                // GPS (or DR) position: a square, with the boat on its course
                val g = v.px(gpsAt); val sq = 6.dp.toPx()
                boat(g, course, 1.1.dp.toPx())
                drawRect(INK, Offset(g.x - sq, g.y - sq), Size(2 * sq, 2 * sq), style = Stroke(1.5.dp.toPx()))
                label(m, if (f.drIsGps) "GPS" else "DR", g + Offset(sq + 4.dp.toPx(), sq), INK, 11.sp, bold = true)
                // the fix
                drawCircle(PAPER, 6.dp.toPx(), v.px(Offset.Zero))
                drawCircle(PURPLE, 6.dp.toPx(), v.px(Offset.Zero), style = Stroke(2.dp.toPx()))
                drawCircle(PURPLE, 2.dp.toPx(), v.px(Offset.Zero))
                scaleBar(m, v.s)
                north(m, 12.sp)
            }
        }
        Text("Arrows: where you looked (true bearing). Lines: square to them; the fix is where they cross. " +
            "Purple circle: about 95%. Square: " + (if (f.drIsGps) "GPS" else "your DR") + ". The boat is drawn on its course for orientation only.",
            color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
