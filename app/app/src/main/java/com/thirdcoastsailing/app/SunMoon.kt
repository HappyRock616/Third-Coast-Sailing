package com.thirdcoastsailing.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.celestial.Almanac
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.searchRiseSet
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Sun and moon for the menu's GPS box: today's sunrise and sunset at a position (local day, phone time zone) and the
 * moon drawn in its current phase. Times come from Astronomy Engine, the same almanac as the celestial trainer.
 */

/** Today's sun times (ms, null when the sun doesn't rise or set that day) and the moon's phase. */
data class SunMoonDay(val sunriseMs: Long?, val sunsetMs: Long?, val moonLitFraction: Double, val moonWaxing: Boolean)

/** Sunrise, sunset and moon phase for the boat's position, cached per local day. */
object SunMoon {
    private var cacheDay = Long.MIN_VALUE
    private var cache: SunMoonDay? = null

    /** Today's values, worked out once per local calendar day (the first time they're asked for that day). */
    @Synchronized
    fun today(lat: Double, lon: Double, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SunMoonDay {
        val epochDay = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay()
        cache?.takeIf { epochDay == cacheDay }?.let { return it }
        return day(lat, lon, nowMs, zone).also { cache = it; cacheDay = epochDay }
    }

    /** Sun times for the local calendar day containing `nowMs`, and the moon's phase at `nowMs`. */
    fun day(lat: Double, lon: Double, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SunMoonDay {
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val obs = Observer(lat, lon, 0.0)
        fun rs(dir: Direction) = runCatching {
            searchRiseSet(Body.Sun, obs, dir, Almanac.time(dayStart), 1.0)?.toMillisecondsSince1970()
        }.getOrNull()?.takeIf { it < dayStart + 24 * 3_600_000L }
        val phase = Almanac.moonPhaseDeg(nowMs)                     // 0 new, 90 first quarter, 180 full, 270 last quarter
        return SunMoonDay(rs(Direction.Rise), rs(Direction.Set), Almanac.moonIllumination(nowMs), phase < 180.0)
    }
}

/** Sun glyph color. */
private val SUN_YELLOW = Color(0xFFF2C14E)
/** Lit part of the moon glyph. */
private val MOON_LIT = Color(0xFFE9E4D4)
/** Dark part of the moon glyph. */
private val MOON_DARK = Color(0xFF33475F)

/** "▲ 8:03a ▼ 6:55p ◐" in one short row: sun glyphs with the times, then the moon in its phase (worked out once a day). */
@Composable
fun SunMoonRow(lat: Double, lon: Double, nowMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val d = SunMoon.today(lat, lon, nowMs)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SunGlyph(rise = true)
        Text(clockText(d.sunriseMs, h24), color = Mist, fontSize = 12.5.sp, modifier = Modifier.padding(start = 2.dp, end = 6.dp))
        SunGlyph(rise = false)
        Text(clockText(d.sunsetMs, h24), color = Mist, fontSize = 12.5.sp, modifier = Modifier.padding(start = 2.dp, end = 6.dp))
        Canvas(Modifier.size(14.dp)) { drawMoon(d.moonLitFraction, d.moonWaxing) }
    }
}

/** 8:03a / 6:55p, or 08:03 / 18:55 on a phone set to 24-hour time; "—" when there is no rise or set. */
private fun clockText(ms: Long?, h24: Boolean): String {
    if (ms == null) return "—"
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return if (h24) "%02d:%02d".format(t.hour, t.minute)
    else "%d:%02d%s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "a" else "p")
}

/** Half sun on a horizon line, with a small solid triangle under it: up = sunrise, down = sunset. */
@Composable
private fun SunGlyph(rise: Boolean) {
    Canvas(Modifier.size(16.dp)) {
        val k = size.minDimension / 16f
        val cx = 8f * k; val hy = 8.5f * k                          // horizon height
        drawArc(SUN_YELLOW, 180f, 180f, true, Offset(cx - 4.2f * k, hy - 4.2f * k), Size(8.4f * k, 8.4f * k))
        for (deg in listOf(205.0, 240.0, 270.0, 300.0, 335.0)) {   // rays
            val a = deg * PI / 180
            drawLine(SUN_YELLOW, Offset(cx + 5.8f * k * cos(a).toFloat(), hy + 5.8f * k * sin(a).toFloat()),
                Offset(cx + 7.6f * k * cos(a).toFloat(), hy + 7.6f * k * sin(a).toFloat()), 1f * k, StrokeCap.Round)
        }
        drawLine(Mist, Offset(cx - 7.5f * k, hy + 0.4f * k), Offset(cx + 7.5f * k, hy + 0.4f * k), 1f * k)
        val ty = 13f * k
        drawPath(Path().apply {
            if (rise) { moveTo(cx - 2.8f * k, ty + 2.2f * k); lineTo(cx + 2.8f * k, ty + 2.2f * k); lineTo(cx, ty - 2.2f * k) }
            else { moveTo(cx - 2.8f * k, ty - 2.2f * k); lineTo(cx + 2.8f * k, ty - 2.2f * k); lineTo(cx, ty + 2.2f * k) }
            close()
        }, Foam)
    }
}

/**
 * The moon as seen from the northern hemisphere: a dark disc with the lit part on the right while waxing and on the
 * left while waning. The lit part is the limb half-circle closed by the terminator, a half-ellipse whose width
 * follows the lit fraction (0 new, 0.5 quarter, 1 full).
 */
private fun DrawScope.drawMoon(lit: Double, waxing: Boolean) {
    val r = size.minDimension / 2f - 0.5.dp.toPx()
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(MOON_DARK, r, c)
    val side = if (waxing) 1f else -1f
    val k = (2 * lit - 1).toFloat()                                 // terminator x-scale: -1 new .. +1 full
    val n = 24
    val p = Path()
    for (i in 0..n) {                                               // limb, top to bottom on the lit side
        val a = -PI / 2 + PI * i / n
        val pt = Offset(c.x + side * r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    for (i in n downTo 0) {                                         // terminator, bottom to top
        val a = -PI / 2 + PI * i / n
        p.lineTo(c.x - side * k * r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
    }
    p.close()
    drawPath(p, MOON_LIT)
    drawCircle(Mist, r, c, style = Stroke(0.6.dp.toPx()))
}
