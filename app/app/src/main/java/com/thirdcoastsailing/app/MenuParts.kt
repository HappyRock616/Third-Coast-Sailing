package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange

/*
 * Pieces of the side menu: small line icons drawn in code (no image files), a switch row with an icon and one word,
 * a large bordered button for the screens behind the menu (Boat, Celestial, Charts, About), and the chart key
 * (buoy colors and rings, marina, track, celestial fix, light, buoy/beacon, landmark).
 */

/** The menu's icons, each drawn on a 24 x 24 grid. */
enum class MenuIcon { TRACK, WIND, GUST, BARBS, WAVES, NIGHT, GAUGE, OFFLINE, BOAT, SEXTANT, CHARTS, INFO, ANCHOR, BARO }

// Wind color stops (knots), the same scale as the map field and the buoy fill
private val KEY_WIND = listOf(0 to Color(0xFF3B6FB6), 8 to Color(0xFF3DBE6B), 14 to Color(0xFFF2D028),
    20 to Color(0xFFF28C28), 25 to Color(0xFFE5484D))
/** Chart key: buoy with no recent report. */
private val STALE_GRAY = Color(0xFF8A8F96)
/** Chart key: buoy ring for waves over 3 ft. */
private val RING_YELLOW = Color(0xFFF2C14E)
/** Chart key: buoy ring for waves over 5 ft; also the barograph trace. */
private val RING_RED = Color(0xFFE5484D)
/** Outlines and ink on light symbols. */
private val KEY_INK = Color(0xFF1B1F23)
/** Celestial fix symbol. */
private val FIX_PURPLE = Color(0xFFC9A6FF)

/** One menu icon, drawn in code (no image files), 24 dp by default. */
@Composable
fun MenuIconView(icon: MenuIcon, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) { drawMenuIcon(icon) }
}

/** Draws one icon to fill the canvas (designed on a 24 x 24 grid). */
private fun DrawScope.drawMenuIcon(icon: MenuIcon) {
    val k = size.minDimension / 24f
    /** Point on the 24 x 24 icon grid, in pixels. */
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    val w = 2f * k
    val line = Stroke(w, cap = StrokeCap.Round)
    when (icon) {
        MenuIcon.TRACK -> for (i in 0 until 4) drawLine(Foam, o(2f + i * 5.5f, 19f - i * 4f), o(5.5f + i * 5.5f, 16.5f - i * 4f), w, StrokeCap.Round)
        MenuIcon.WIND, MenuIcon.GUST -> {
            KEY_WIND.forEachIndexed { i, (_, c) -> drawRect(c, o(1f + i * 4.4f, 7f), Size(4.4f * k, 10f * k)) }
            if (icon == MenuIcon.GUST) drawPath(Path().apply {
                moveTo(14f * k, 1f * k); lineTo(21f * k, 11f * k); lineTo(16.5f * k, 11f * k)
                lineTo(19f * k, 23f * k); lineTo(10f * k, 9f * k); lineTo(15f * k, 9f * k); close()
            }, Foam)
        }
        MenuIcon.BARBS -> {
            drawLine(Foam, o(3f, 21f), o(19f, 4f), w, StrokeCap.Round)
            drawLine(Foam, o(19f, 4f), o(23f, 11f), w, StrokeCap.Round)
            drawLine(Foam, o(15f, 8.5f), o(19f, 15f), w, StrokeCap.Round)
        }
        MenuIcon.WAVES -> {
            drawArc(Color(0xFF7FD3F0), 200f, 140f, false, o(2f, 6f), Size(20f * k, 12f * k), style = line)
            drawArc(RING_YELLOW, 200f, 140f, false, o(2f, 12f), Size(20f * k, 12f * k), style = line)
        }
        MenuIcon.NIGHT -> {
            val p = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(o(3f, 3f), Size(18f * k, 18f * k)))
            }
            val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(o(9f, 0.5f), Size(17f * k, 17f * k))) }
            drawPath(Path().apply { op(p, cut, androidx.compose.ui.graphics.PathOperation.Difference) }, Foam)
        }
        MenuIcon.GAUGE -> {
            drawCircle(Foam, 9.5f * k, o(12f, 12f), style = line)
            drawLine(SignalOrange, o(12f, 12f), o(17.5f, 6.5f), w, StrokeCap.Round)
            drawCircle(Foam, 1.6f * k, o(12f, 12f))
        }
        MenuIcon.OFFLINE -> {
            drawArc(Foam, 225f, 90f, false, o(1f, 5f), Size(22f * k, 22f * k), style = line)
            drawArc(Foam, 225f, 90f, false, o(6f, 10f), Size(12f * k, 12f * k), style = line)
            drawCircle(Foam, 1.6f * k, o(12f, 19f))
            drawLine(SignalOrange, o(3f, 21f), o(21f, 3f), w, StrokeCap.Round)
        }
        MenuIcon.BOAT -> {
            drawLine(Foam, o(11f, 2f), o(11f, 17f), w, StrokeCap.Round)                         // mast
            drawPath(Path().apply { moveTo(12.5f * k, 3f * k); lineTo(20f * k, 15f * k); lineTo(12.5f * k, 15f * k); close() }, Foam)
            drawPath(Path().apply { moveTo(9.5f * k, 6f * k); lineTo(4f * k, 15f * k); lineTo(9.5f * k, 15f * k); close() }, Mist)
            drawPath(Path().apply { moveTo(2f * k, 18f * k); lineTo(22f * k, 18f * k); lineTo(19f * k, 22f * k); lineTo(5f * k, 22f * k); close() }, SignalOrange)
        }
        MenuIcon.SEXTANT -> {
            // the arc and frame, index arm, telescope and handle: a tiny sextant
            drawArc(Foam, 30f, 120f, false, o(2f, -6f), Size(20f * k, 26f * k), style = line)
            drawLine(Foam, o(12f, 3f), o(4.2f, 15.5f), w, StrokeCap.Round)
            drawLine(Foam, o(12f, 3f), o(19.8f, 15.5f), w, StrokeCap.Round)
            drawLine(SignalOrange, o(12f, 3f), o(15f, 19.5f), w, StrokeCap.Round)                 // index arm
            drawLine(Foam, o(6f, 8.5f), o(15f, 8.5f), 2.6f * k, StrokeCap.Round)                // telescope
            drawCircle(Foam, 1.8f * k, o(12f, 3f))
        }
        MenuIcon.CHARTS -> {
            drawPath(Path().apply {
                moveTo(2f * k, 5f * k); lineTo(8f * k, 3f * k); lineTo(16f * k, 5f * k); lineTo(22f * k, 3f * k)
                lineTo(22f * k, 19f * k); lineTo(16f * k, 21f * k); lineTo(8f * k, 19f * k); lineTo(2f * k, 21f * k); close()
            }, Foam, style = Stroke(1.8f * k))
            drawLine(Foam, o(8f, 3f), o(8f, 19f), 1.5f * k); drawLine(Foam, o(16f, 5f), o(16f, 21f), 1.5f * k)
        }
        MenuIcon.BARO -> {                                            // analog barograph: chart drum, pen arm, aneroid stack, base
            drawLine(Foam, o(1.5f, 21.5f), o(22.5f, 21.5f), w, StrokeCap.Round)                   // base
            drawRoundRect(Foam, o(3f, 5f), Size(9f * k, 14f * k), CornerRadius(2f * k), style = Stroke(1.8f * k))   // drum
            drawLine(Foam, o(3.5f, 8f), o(11.5f, 8f), 1f * k)                                     // drum top rim
            drawPath(Path().apply {                                                               // red pressure trace across the chart, ending at the pen
                moveTo(4.3f * k, 11.5f * k); lineTo(5.4f * k, 12.6f * k); lineTo(6.3f * k, 12f * k); lineTo(7.2f * k, 14.8f * k)
                lineTo(8.1f * k, 16f * k); lineTo(9f * k, 14f * k); lineTo(9.8f * k, 13.4f * k); lineTo(10.5f * k, 13f * k)
            }, RING_RED, style = Stroke(1.2f * k, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            for (i in 0 until 4) {                                                                // aneroid stack
                val y = 12.5f + i * 2.2f
                drawRoundRect(Foam, o(14.5f, y), Size(6f * k, 1.5f * k), CornerRadius(0.75f * k))
            }
            drawLine(Foam, o(20.5f, 11.5f), o(20.5f, 21f), 1.4f * k, StrokeCap.Round)             // arm post
            drawLine(SignalOrange, o(20.5f, 11.5f), o(10.5f, 13f), 1.6f * k, StrokeCap.Round)     // pen arm, tip on the drum
            drawCircle(Foam, 1.3f * k, o(20.5f, 11.5f))                                           // pivot
        }
        MenuIcon.ANCHOR -> {
            drawCircle(Foam, 2.4f * k, o(12f, 4.5f), style = Stroke(1.8f * k))
            drawLine(Foam, o(12f, 7f), o(12f, 21f), w, StrokeCap.Round)
            drawLine(Foam, o(7f, 10f), o(17f, 10f), w, StrokeCap.Round)
            drawArc(SignalOrange, 15f, 150f, false, o(3.5f, 9.5f), Size(17f * k, 12.5f * k), style = line)
        }
        MenuIcon.INFO -> {
            drawCircle(Foam, 10f * k, o(12f, 12f), style = line)
            drawCircle(Foam, 1.5f * k, o(12f, 7f))
            drawLine(Foam, o(12f, 11f), o(12f, 18f), w, StrokeCap.Round)
        }
    }
}

/** Menu switch: icon, one word, switch. */
@Composable
fun IconSwitchRow(icon: MenuIcon, label: String, value: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true,
                  extra: @Composable () -> Unit = {}) {
    // greyed out (enabled = false): the row fades and the switch can't be changed
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp).alpha(if (enabled) 1f else 0.38f)) {
        MenuIconView(icon)
        Text(label, color = Foam, fontSize = 17.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
        extra()
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = SignalOrange, checkedThumbColor = Foam,
                uncheckedTrackColor = NavyLight, uncheckedThumbColor = Mist, uncheckedBorderColor = NavyLight))
    }
}

/** Large bordered button to a screen behind the menu: icon, word, optional short value (e.g. the boat), ›. */
@Composable
fun MenuButton(icon: MenuIcon, label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, SignalOrange, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        MenuIconView(icon, Modifier.size(28.dp))
        // the label takes the free width, so the value and › always stay visible (Anchor and Barograph share the row
        // with a reset arrow and a switch)
        Text(label, color = Foam, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (value != null) Text(value, color = Mist, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        Text("›", color = SignalOrange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** Chart key: buoy fill (wind, kt), buoy ring (waves, ft), marina, track, celestial fix, light, aid, landmark. */
@Composable
fun ChartKey() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // buoy fill = wind speed
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buoy", color = Mist, fontSize = 12.sp, modifier = Modifier.width(46.dp))
            for ((kt, c) in KEY_WIND) KeySwatch(if (kt == 25) "25+" else "$kt") { drawBuoy(c, null) }
            KeySwatch("old") { drawBuoy(STALE_GRAY, null) }
            Text("kt", color = Mist, fontSize = 11.sp)
        }
        // buoy ring = waves
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Waves", color = Mist, fontSize = 12.sp, modifier = Modifier.width(46.dp))
            KeySwatch("<3") { drawBuoy(KEY_WIND[1].second, null) }
            KeySwatch("3–5") { drawBuoy(KEY_WIND[1].second, RING_YELLOW) }
            KeySwatch("5+") { drawBuoy(KEY_WIND[1].second, RING_RED) }
            Text("ft", color = Mist, fontSize = 11.sp)
        }
        // other marks on the chart: marina, trip track, celestial fix, light, buoy/beacon, landmark
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            KeySwatch("Marina", fill = true) { drawMarina() }
            KeySwatch("Track", fill = true) {
                for (i in 0 until 3) drawLine(Foam, Offset(size.width * (0.12f + i * 0.28f), size.height / 2),
                    Offset(size.width * (0.28f + i * 0.28f), size.height / 2), 2.dp.toPx(), StrokeCap.Round)
            }
            KeySwatch("Fix", fill = true) {
                drawCircle(FIX_PURPLE, size.minDimension * 0.34f, style = Stroke(2.dp.toPx())); drawCircle(FIX_PURPLE, size.minDimension * 0.08f)
            }
            KeySwatch("Light", fill = true) { drawKeyFlare() }
            KeySwatch("Aid", fill = true) { drawKeyAid() }
            KeySwatch("Landmark", fill = true) {
                drawCircle(Color.White, size.minDimension * 0.22f)
                drawCircle(KEY_INK, size.minDimension * 0.22f, style = Stroke(1.dp.toPx()))
                drawCircle(KEY_INK, size.minDimension * 0.07f)
            }
        }
    }
}

/** One key entry: a 24 dp picture over a short label; `fill` shares the row's width equally (the bottom row of six). */
@Composable
private fun RowScope.KeySwatch(label: String, width: androidx.compose.ui.unit.Dp = 30.dp, fill: Boolean = false,
                               draw: DrawScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (fill) Modifier.weight(1f) else Modifier.width(width)) {
        Canvas(Modifier.size(22.dp)) { draw() }
        Text(label, color = Mist, fontSize = if (fill) 10.sp else 10.5.sp, maxLines = 1)
    }
}

/** A buoy as drawn on the chart: wind-colored dot with a dark edge, and a wave ring when the waves are up. */
private fun DrawScope.drawBuoy(fill: Color, ring: Color?) {
    val r = size.minDimension * 0.3f
    if (ring != null) drawCircle(ring, r + 3.dp.toPx())
    drawCircle(fill, r); drawCircle(KEY_INK, r, style = Stroke(1.dp.toPx()))
}

/** The marina tile, the same design as the chart symbol (ChartStyle.marinaBitmap). */
fun DrawScope.drawMarina() {
    val k = size.minDimension / 30f
    val tl = Offset((size.width - 30f * k) / 2 + 1.5f * k, (size.height - 30f * k) / 2 + 1.5f * k)
    val sz = Size(27f * k, 27f * k)
    drawRoundRect(SignalOrange, tl, sz, CornerRadius(6f * k))
    drawRoundRect(KEY_INK, tl, sz, CornerRadius(6f * k), style = Stroke(1.6f * k))
    val ox = tl.x - 1.5f * k; val oy = tl.y - 1.5f * k
    /** Point on the marina tile's 30 x 30 grid, in pixels. */
    fun o(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)
    val st = Stroke(2.6f * k, cap = StrokeCap.Round)
    drawCircle(Navy, 2.2f * k, o(15f, 7.5f), style = st)
    drawLine(Navy, o(15f, 9.7f), o(15f, 24f), 2.6f * k, StrokeCap.Round)
    drawLine(Navy, o(10.5f, 12.5f), o(19.5f, 12.5f), 2.6f * k, StrokeCap.Round)
    drawArc(Navy, 20f, 140f, false, o(7.5f, 12f), Size(15f * k, 12.5f * k), style = st)
}

/** The red light flare as drawn on the chart (NavStyle): a teardrop from the light toward the lower right. */
private fun DrawScope.drawKeyFlare() {
    val k = size.minDimension / 24f
    val start = Offset(5f * k, 5f * k)
    val tx = 0.7071f; val ty = 0.7071f; val nx = -ty; val ny = tx
    val len = 14f * k; val w = 3.2f * k
    val ex = start.x + tx * len; val ey = start.y + ty * len
    val path = Path().apply {
        moveTo(start.x, start.y)
        for (i in 0..12) {
            val t = -Math.PI / 2 + Math.PI * i / 12
            lineTo(ex + w * (kotlin.math.cos(t).toFloat() * tx + kotlin.math.sin(t).toFloat() * nx),
                ey + w * (kotlin.math.cos(t).toFloat() * ty + kotlin.math.sin(t).toFloat() * ny))
        }
        close()
    }
    drawPath(path, Color(0xFFD62828)); drawPath(path, KEY_INK, style = Stroke(0.7f * k))
}

/** Red triangle and green square, the buoy and beacon shapes. */
private fun DrawScope.drawKeyAid() {
    val k = size.minDimension / 24f
    val tri = Path().apply { moveTo(7f * k, 6f * k); lineTo(12f * k, 17f * k); lineTo(2f * k, 17f * k); close() }
    drawPath(tri, Color(0xFFD62828)); drawPath(tri, KEY_INK, style = Stroke(0.8f * k))
    drawRect(Color(0xFF1F9D3A), Offset(14f * k, 8f * k), Size(9f * k, 9f * k))
    drawRect(KEY_INK, Offset(14f * k, 8f * k), Size(9f * k, 9f * k), style = Stroke(0.8f * k))
}
