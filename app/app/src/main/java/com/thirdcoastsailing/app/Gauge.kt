package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Gauge palette (matches the app theme)
private val GNavy = Color(0xFF0F2742)
private val GLine = Color(0xFF2C5078)
private val GOrange = Color(0xFFF28C28)
private val GFoam = Color(0xFFF4F1EA)
private val GMist = Color(0xFFAFC2D6)
private val GBlue = Color(0xFF2F6FA3)
private val GPolar = Color(0xFF8FBCE0)
private val GGreen = Color(0xFF3DBE6B)
private val GRed = Color(0xFFE5484D)

/** Live values the gauge shows. Anything null is drawn as "—" or left out. */
data class GaugeData(
    val headingDeg: Double?,      // course over ground from GPS
    val sogKn: Double?,           // speed over ground from GPS
    val depthFt: Double?,         // shallow end of the charted depth range at the boat
    val twdDeg: Double?,          // forecast true wind direction (from)
    val twsKn: Double?,           // forecast true wind speed
    val polar: Polar?,            // active boat polar
)

private const val NO_GO_DEG = 30f          // red/green arcs either side of the bow

/**
 * Wind / heading gauge drawn over the chart, transparent background.
 * Heading-up compass ring; forecast wind triangle on the ring; boat-fixed polar for the current wind
 * with a dot where the wind line crosses it; boat with speed and depth boxes.
 * Pinch on the gauge resizes it, drag moves it (both reported back so they can be saved).
 * The layout is designed on a 640-unit square and scaled to the on-screen size.
 */
@Composable
fun WindGauge(
    data: GaugeData,
    offset: Offset,
    scale: Float,
    onTransform: (Offset, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val baseSize = 300.dp
    Canvas(
        modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(baseSize * scale)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ -> onTransform(pan, zoom) }
            }
    ) {
        val k = size.width / 640f
        drawGauge(data, k, measurer)
    }
}

private fun DrawScope.drawGauge(d: GaugeData, k: Float, tm: TextMeasurer) {
    val c = Offset(320f * k, 330f * k)
    val rOut = 215f * k
    val rIn = 172f * k
    val mid = (rOut + rIn) / 2
    val heading = d.headingDeg ?: 0.0

    // screen angle (degrees clockwise from up) of a true bearing, heading-up
    fun screen(trueDeg: Double) = ((trueDeg - heading) % 360 + 360) % 360
    fun at(r: Float, screenDeg: Double): Offset {
        val a = Math.toRadians(screenDeg - 90)
        return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
    }
    fun text(s: String, center: Offset, size: Float, color: Color, bold: Boolean = true, rotateDeg: Float = 0f) {
        val layout = tm.measure(s, TextStyle(color = color, fontSize = (size * k).toSp(),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        val tl = center - Offset(layout.size.width / 2f, layout.size.height / 2f)
        rotate(rotateDeg, pivot = center) { drawText(layout, topLeft = tl) }
    }

    // ---- compass ring (rotates with heading)
    drawCircle(GNavy.copy(alpha = 0.82f), mid, c, style = Stroke(rOut - rIn))
    drawCircle(GLine, rOut, c, style = Stroke(2f * k))
    drawCircle(GLine, rIn, c, style = Stroke(2f * k))
    for (deg in 0 until 360 step 5) {
        val s = screen(deg.toDouble())
        val big = deg % 30 == 0
        val len = (if (big) 14f else if (deg % 10 == 0) 9f else 5f) * k
        drawLine(if (big) GFoam else GMist, at(rOut - 1f * k, s), at(rOut - len, s),
            strokeWidth = (if (big) 2.4f else 1.4f) * k)
        if (big) text(if (deg == 0) "N" else "%03d".format(deg), at(mid - 6f * k, s),
            if (deg == 0) 19f else 15f, if (deg == 0) GOrange else GFoam, rotateDeg = s.toFloat())
    }

    // ---- no-go arcs just inside the ring, fixed to the bow
    val arcR = rIn - 8f * k
    val arcBox = Size(2 * arcR, 2 * arcR)
    val arcTL = Offset(c.x - arcR, c.y - arcR)
    drawArc(GRed, -90f - NO_GO_DEG, NO_GO_DEG - 2f, false, arcTL, arcBox, style = Stroke(10f * k))
    drawArc(GGreen, -88f, NO_GO_DEG - 2f, false, arcTL, arcBox, style = Stroke(10f * k))

    // ---- polar for the current forecast wind, fixed to the boat (0 deg true wind angle at the bow)
    val tws = d.twsKn
    val polar = d.polar
    val rPolar = rIn - 26f * k
    var windScreen: Double? = null
    if (d.twdDeg != null) windScreen = screen(d.twdDeg)
    if (polar != null) {
        val w = tws ?: 10.0                                     // shape preview until forecast wind exists
        val vmax = (0..180 step 2).maxOf { polar.boatSpeed(w, it.toDouble()) }.coerceAtLeast(0.5)
        val path = Path()
        for (a in -180..180 step 2) {
            val p = at(rPolar * (polar.boatSpeed(w, kotlin.math.abs(a).toDouble()) / vmax).toFloat(), a.toDouble())
            if (a == -180) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        val faded = tws == null
        drawPath(path, GPolar.copy(alpha = if (faded) 0.08f else 0.18f))
        drawPath(path, GNavy, style = Stroke(6f * k, join = StrokeJoin.Round))
        drawPath(path, GPolar.copy(alpha = if (faded) 0.45f else 1f), style = Stroke(3f * k, join = StrokeJoin.Round))

        // where the wind line meets the polar = where you are on it
        if (windScreen != null && tws != null) {
            val twa = kotlin.math.abs(((windScreen + 180) % 360) - 180)
            val target = polar.boatSpeed(tws, twa)
            drawLine(GBlue, c, at(rIn - 4f * k, windScreen), strokeWidth = 3f * k,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * k, 6f * k)))
            val dot = at(rPolar * (target / vmax).toFloat(), windScreen)
            drawCircle(GNavy, 9.5f * k, dot)
            drawCircle(GOrange, 7f * k, dot)
            text("target %.1f".format(target), dot + Offset(62f * k, -14f * k), 15f, GFoam)
        }
    }

    // ---- forecast wind triangle outside the ring, pointing at the center, speed inside
    if (windScreen != null) {
        val tc = at(rOut + 34f * k, windScreen)
        rotate(windScreen.toFloat(), pivot = tc) {
            val tri = Path().apply {
                moveTo(tc.x, tc.y + 28f * k); lineTo(tc.x - 27f * k, tc.y - 20f * k); lineTo(tc.x + 27f * k, tc.y - 20f * k); close()
            }
            drawPath(tri, GBlue)
            drawPath(tri, GFoam, style = Stroke(2.5f * k, join = StrokeJoin.Round))
        }
        text(tws?.let { "%.0f".format(it) } ?: "—", tc - Offset(0f, 3f * k), 17f, GFoam)
    }

    // ---- heading "magnifier" on the ring at the top
    val bw = 104f * k
    val bh = 56f * k
    val bt = Offset(c.x - bw / 2, c.y - mid - bh / 2)
    drawRoundRect(GNavy, bt, Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(12f * k))
    drawRoundRect(GOrange, bt, Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(12f * k), style = Stroke(3f * k))
    text(d.headingDeg?.let { "%03d°".format(it.roundToInt() % 360) } ?: "---°", Offset(c.x, c.y - mid + 1f * k), 32f, GFoam)
    val ptr = Path().apply {
        moveTo(c.x - 9f * k, c.y - mid + bh / 2); lineTo(c.x + 9f * k, c.y - mid + bh / 2); lineTo(c.x, c.y - mid + bh / 2 + 10f * k); close()
    }
    drawPath(ptr, GOrange)

    // ---- boat (bow up), speed box on deck, depth box at the stern
    val len = 150f * k
    val beam = 56f * k
    val top = c.y - len * 0.55f
    val bottom = c.y + len * 0.45f
    val hull = Path().apply {
        moveTo(c.x, top)
        cubicTo(c.x + beam * 0.55f, top + len * 0.25f, c.x + beam * 0.5f, c.y + len * 0.2f, c.x + beam * 0.38f, bottom)
        lineTo(c.x - beam * 0.38f, bottom)
        cubicTo(c.x - beam * 0.5f, c.y + len * 0.2f, c.x - beam * 0.55f, top + len * 0.25f, c.x, top)
        close()
    }
    drawPath(hull, GOrange.copy(alpha = 0.92f))
    drawPath(hull, GNavy, style = Stroke(3f * k))
    val mast = Offset(c.x, c.y - len * 0.12f)
    drawLine(GFoam, mast, Offset(c.x, top + 12f * k), strokeWidth = 3f * k, cap = StrokeCap.Round)
    drawCircle(GNavy, 5f * k, mast)

    val sTL = Offset(c.x - 92f * k, c.y - 22f * k)
    drawRoundRect(GNavy, sTL, Size(184f * k, 76f * k), androidx.compose.ui.geometry.CornerRadius(10f * k))
    drawRoundRect(GFoam, sTL, Size(184f * k, 76f * k), androidx.compose.ui.geometry.CornerRadius(10f * k), style = Stroke(2f * k))
    text(d.sogKn?.let { "%.1f".format(it) } ?: "—", Offset(c.x - 16f * k, c.y + 16f * k), 57f, GFoam)
    text("kt", Offset(c.x + 56f * k, c.y + 26f * k), 24f, GMist)

    val dTL = Offset(c.x - 40f * k, bottom + 8f * k)
    drawRoundRect(GBlue, dTL, Size(80f * k, 32f * k), androidx.compose.ui.geometry.CornerRadius(6f * k))
    drawRoundRect(GFoam, dTL, Size(80f * k, 32f * k), androidx.compose.ui.geometry.CornerRadius(6f * k), style = Stroke(1.5f * k))
    text(d.depthFt?.let { if (it < 6) "<6 ft" else "%.0f ft".format(it) } ?: "— ft", Offset(c.x, bottom + 24f * k), 18f, GFoam)

    if (d.twdDeg == null) text("no wind forecast yet", Offset(c.x, c.y + rIn - 40f * k), 14f, GMist, bold = false)
}
