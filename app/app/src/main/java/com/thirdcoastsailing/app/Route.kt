package com.thirdcoastsailing.app

import android.content.SharedPreferences
import android.hardware.GeomagneticField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the water. */
/** Wind barbs at the route pins (arrival wind): orange, unlike the forecast barbs. */
private val RouteBarb = Color(0xFFE8730C)
/** Course of the next leg beside a pin. */
private val RouteCourse = Color(0xFFC0392B)

data class GeoPt(val lat: Double, val lon: Double)

/** One point of a calculated route, with the time the boat gets there. */
data class RoutePoint(val lat: Double, val lon: Double, val timeMs: Long)

/** A calculated (weather-routed) route. distanceNm = distance actually sailed. */
class RouteResult(val path: List<RoutePoint>, val distanceNm: Double, val startMs: Long, val endMs: Long)

val RouteTeal = Color(0xFF19C3B3)                      // calculated route line

// ---------------------------------------------------------------------------------------- geometry

private const val EARTH_NM = 3440.065

/** Great-circle distance in nautical miles. */
/** Point reached from `from` after `nm` nautical miles on a true bearing (great circle). */
fun destination(from: GeoPt, bearingDeg: Double, nm: Double): GeoPt {
    val d = nm / EARTH_NM; val th = Math.toRadians(bearingDeg)
    val p1 = Math.toRadians(from.lat); val l1 = Math.toRadians(from.lon)
    val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(th))
    val l2 = l1 + atan2(sin(th) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    return GeoPt(Math.toDegrees(p2), ((Math.toDegrees(l2) + 540.0) % 360.0) - 180.0)
}

/** Great-circle distance between two points in nautical miles (haversine). */
fun distNm(a: GeoPt, b: GeoPt): Double {
    val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
    val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_NM * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Initial true bearing from a to b, 0..360. */
fun bearingDeg(a: GeoPt, b: GeoPt): Double {
    val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** A course as three digits, 000-359 (359.6 shows as 000, never 360). */
fun deg3(d: Double): String = "%03d".format(((d.roundToInt() % 360) + 360) % 360)

/** True bearing to magnetic, using the phone's built-in world magnetic model (no network needed). */
fun toMagnetic(trueDeg: Double, at: GeoPt): Double {
    val decl = Declination.at(at.lat, at.lon)
    return ((trueDeg - decl) % 360 + 360) % 360
}

/** Magnetic variation by 0.1 deg cell and day, kept so labels redrawn on every camera move don't rebuild the model. */
private object Declination {
    private val cache = object : LinkedHashMap<Long, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Float>?) = size > 256
    }

    @Synchronized
    fun at(lat: Double, lon: Double): Float {
        val now = System.currentTimeMillis()
        val key = ((Math.round(lat * 10) + 900) * 4000L + (Math.round(lon * 10) + 1800)) * 100_000L + now / 86_400_000L
        return cache.getOrPut(key) { GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, now).declination }
    }
}

/** "3 h 28" / "45 min". */
fun hoursText(h: Double): String {
    val mins = (h * 60).roundToInt()
    return if (mins < 60) "$mins min" else "${mins / 60} h %02d".format(mins % 60)
}

/** "1h 17m" / "45m": time to a route pin, short enough to sit beside it. */
fun etaText(ms: Long): String {
    val mins = (ms / 60_000.0).roundToInt()
    return if (mins < 60) "${mins}m" else "${mins / 60}h %02dm".format(mins % 60)
}

/** Arrival at one route pin: time after departure (ms) and the forecast wind there and then (kn, from deg true). */
class PinEta(val afterMs: Long?, val windKn: Double?, val windFromDeg: Double?)

/**
 * Times along a planned route for manual weather routing: each leg is sailed straight at the polar speed (cruise
 * factor applied) for the forecast wind met along the way, in 0.5 nm steps. Upwind or dead downwind the best
 * speed made good along the leg is used (tacking or gybing, no extra penalty). Wind is blended between forecast
 * hours (direction the short way round). Without a polar or forecast, the fallback speed (the boat's average
 * speed) is used; without either, no times.
 */
object RouteTiming {
    fun compute(points: List<GeoPt>, departMs: Long, polar: Polar?, wind: WindLookup, cruise: Double,
                fallbackKn: Double?): List<PinEta> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<PinEta>()
        var t = departMs
        var ok = true
        fun windAt(tMs: Long, p: GeoPt): Pair<Double, Double>? {
            val h0 = Math.floorDiv(tMs, 3_600_000L) * 3_600_000L
            val f = (tMs - h0) / 3_600_000.0
            val a = wind.at(h0, p.lat, p.lon); val b = wind.at(h0 + 3_600_000L, p.lat, p.lon)
            if (a == null || b == null) return a ?: b
            fun vec(w: Pair<Double, Double>) = Pair(w.first * sin(Math.toRadians(w.second)), w.first * cos(Math.toRadians(w.second)))
            val (ax, ay) = vec(a); val (bx, by) = vec(b)
            val x = ax + (bx - ax) * f; val y = ay + (by - ay) * f
            val kn = a.first + (b.first - a.first) * f                    // speed blended directly (vectors shrink when turning)
            return kn to (Math.toDegrees(atan2(x, y)) + 360) % 360
        }
        out += windAt(t, points[0]).let { PinEta(0L, it?.first, it?.second) }
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val d = distNm(a, b); val brg = bearingDeg(a, b)
            val n = ceil(d / 0.5).toInt().coerceIn(1, 400)
            val step = d / n
            if (ok) for (k in 0 until n) {
                val mid = destination(a, brg, step * (k + 0.5))
                val w = windAt(t, mid)
                val kn = if (polar != null && w != null) madeGood(polar, w.first, abs(signedDeg(w.second - brg))) * cruise
                         else fallbackKn
                if (kn == null || kn < 0.3) { ok = false; break }
                t += (step / kn * 3_600_000).toLong()
            }
            val w = if (ok) windAt(t, b) else null
            out += PinEta(if (ok) t - departMs else null, w?.first, w?.second)
        }
        return out
    }

    /** Best speed made good along a course `twa` degrees off the wind (0 = dead upwind): sail any angle, take the best. */
    private fun madeGood(polar: Polar, tws: Double, twa: Double): Double {
        val lightest = polar.tws.first()
        var best = 0.0
        var alpha = 0.0
        while (alpha <= 180.0) {
            val s = polar.boatSpeed(maxOf(tws, lightest), alpha) * (if (tws < lightest) tws / lightest else 1.0)
            val off = abs(alpha - twa)
            if (off < 90) best = maxOf(best, s * cos(Math.toRadians(off)))
            alpha += 2.0
        }
        return best
    }

    private fun signedDeg(a: Double): Double { var x = (a % 360 + 360) % 360; if (x > 180) x -= 360; return x }
}

/** Local clock time as "HH:mm". */
fun clockText(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

// ---------------------------------------------------------------------------------------- storage

/** The laid-in route points, kept in app preferences so they survive a restart. */
class RouteStore(private val prefs: SharedPreferences) {
    /** The saved route points ("lat,lon;lat,lon;..."), empty when none; unreadable entries are skipped. */
    fun load(): List<GeoPt> = (prefs.getString("route", "") ?: "").split(';').mapNotNull {
        val p = it.split(',')
        if (p.size == 2) runCatching { GeoPt(p[0].toDouble(), p[1].toDouble()) }.getOrNull() else null
    }

    /** Save the route points (6 decimals, about 0.1 m). */
    fun save(points: List<GeoPt>) {
        prefs.edit().putString("route", points.joinToString(";") { "%.6f,%.6f".format(Locale.US, it.lat, it.lon) }).apply()
    }
}

// ---------------------------------------------------------------------------------------- chart overlay

/**
 * Route over the chart: orange dashed line from the start pin (green) through the waypoint pins (black, numbered)
 * to the finish (checkered flag), each leg labeled along the line with its distance and magnetic bearing.
 * A calculated route is drawn as a solid teal line and the planned line fades behind it.
 */
@Composable
fun RouteOverlay(map: MapLibreMap?, tick: Int, points: List<GeoPt>, calc: RouteResult?, night: Boolean,
                 etas: List<PinEta> = emptyList(), modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        tick                                                         // read so camera moves redraw
        val m = map ?: return@Canvas
        if (points.isEmpty()) return@Canvas
        fun xy(p: GeoPt) = m.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { Offset(it.x, it.y) }
        val legs = points
        val faded = if (calc != null) 0.45f else 1f

        // planned line
        if (legs.size > 1) {
            val path = Path()
            legs.forEachIndexed { i, p -> val o = xy(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            val dash = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 8.dp.toPx()))
            drawPath(path, Navy.copy(alpha = faded * 0.75f), style = Stroke(3.4.dp.toPx(), join = StrokeJoin.Round, pathEffect = dash))
            drawPath(path, SignalOrange.copy(alpha = faded), style = Stroke(1.7.dp.toPx(), join = StrokeJoin.Round, pathEffect = dash))
        }

        // calculated line
        if (calc != null && calc.path.size > 1) {
            val path = Path()
            calc.path.forEachIndexed { i, p -> val o = xy(GeoPt(p.lat, p.lon)); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, Navy, style = Stroke(6.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
            drawPath(path, RouteTeal, style = Stroke(3.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        }

        // leg labels along the line: "4.2 nm 212°M" (distance and magnetic course to steer), kept upright, just
        // above the line. With a calculated route, its own legs (tack to tack) are labeled instead of the plan's.
        val turns = calc?.let { routeTurns(it.path, points) }
        val labelLegs = turns ?: legs
        for (i in 0 until labelLegs.size - 1) {
            val a = labelLegs[i]; val b = labelLegs[i + 1]
            val pa = xy(a); val pb = xy(b)
            val len = hypot(pb.x - pa.x, pb.y - pa.y)
            // the plan's legs: distance only (course and time sit at the pins); a calculated route keeps its courses
            val label = if (turns == null) "%.1f nm".format(distNm(a, b))
                        else "%.1f nm  %s°M".format(distNm(a, b), deg3(toMagnetic(bearingDeg(a, b), a)))
            val layout = measurer.measure(label, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium))
            if (len < layout.size.width + 24.dp.toPx()) continue    // leg too short on screen for its label
            var ang = Math.toDegrees(atan2((pb.y - pa.y).toDouble(), (pb.x - pa.x).toDouble())).toFloat()
            if (ang > 90f) ang -= 180f
            if (ang < -90f) ang += 180f
            val mid = Offset((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
            val tl = mid - Offset(layout.size.width / 2f, layout.size.height + 3.dp.toPx())
            rotate(ang, pivot = mid) {
                // plain solid text, no outline: black on the day chart, light on the night chart
                drawText(layout, color = if (night) Foam else Color.Black, topLeft = tl)
            }
        }

        // turn markers on the calculated route (tacks and gybes; the pins mark their own points)
        turns?.drop(1)?.dropLast(1)?.forEach { t ->
            if (points.any { distNm(it, t) < 0.01 }) return@forEach
            val o = xy(t)
            drawCircle(Navy, 6.dp.toPx(), o)
            drawCircle(Color.White, 4.5.dp.toPx(), o)
            drawCircle(RouteTeal, 2.5.dp.toPx(), o)
        }

        // at each pin (planned route): the forecast wind on arrival as an orange barb, the time to get there and the
        // course of the next leg; for manual weather routing
        if (calc == null) points.forEachIndexed { i, p ->
            val e = etas.getOrNull(i) ?: return@forEachIndexed
            val o = xy(p)
            if (e.windKn != null && e.windFromDeg != null) {
                val north = m.projection.toScreenLocation(LatLng(p.lat + 0.01, p.lon))
                val screenNorth = Math.toDegrees(atan2((north.x - o.x).toDouble(), -(north.y - o.y).toDouble()))
                // full size with a white edge so it stands out from the forecast barbs; the pin is drawn over its foot
                drawBarb(o, (e.windFromDeg + screenNorth).toFloat(), e.windKn, ink = RouteBarb, edgeColor = Color.White, k = 1.0f)
            }
            val lines = buildList {
                if (i > 0) add((e.afterMs?.let { etaText(it) } ?: "—") to (if (night) Foam else Navy))
                if (i < points.lastIndex) add("${deg3(toMagnetic(bearingDeg(p, points[i + 1]), p))}°M" to RouteCourse)
            }
            var y = o.y + 6.dp.toPx()
            for ((text, color) in lines) {
                val lay = measurer.measure(text, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color,
                    background = (if (night) Navy else Color.White).copy(alpha = 0.7f)))
                drawText(lay, topLeft = Offset(o.x + 10.dp.toPx(), y))
                y += lay.size.height
            }
        }

        // pins: start green, waypoints black and numbered, the last one a checkered finish flag
        val k = 1.dp.toPx()
        points.forEachIndexed { i, p ->
            val o = xy(p)
            when {
                i == 0 -> drawRoutePin(o, k, StartGreen, null)
                i == points.lastIndex -> drawFinishFlag(o, k)
                else -> drawRoutePin(o, k, PinBlack, measurer.measure("$i", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)))
            }
        }
    }
}

/**
 * Corners of a calculated route: its start, every place the course changes by more than TURN_DEG (a tack or
 * gybe), every route pin it passes, and its end. The legs between them are what you steer.
 */
fun routeTurns(path: List<RoutePoint>, pins: List<GeoPt>): List<GeoPt> {
    val pts = path.map { GeoPt(it.lat, it.lon) }
    if (pts.size < 3) return pts
    val out = mutableListOf(pts.first())
    var legBrg = bearingDeg(pts[0], pts[1])
    for (i in 1 until pts.size - 1) {
        val b = bearingDeg(pts[i], pts[i + 1])
        val d = abs(((b - legBrg) % 360 + 540) % 360 - 180)
        val onPin = pins.any { distNm(it, pts[i]) < 0.01 }
        if (onPin || d > TURN_DEG) { out += pts[i]; legBrg = b }
    }
    out += pts.last()
    return out
}

/** Course change that makes a new leg of a calculated route. */
const val TURN_DEG = 10.0
/** A route point counts as reached once the boat is this close to it. */
const val POINT_REACHED_NM = 0.1

/** How far above the point (its tip) a route pin's head is drawn, in dp; also used to pick pins up by the head. */
const val ROUTE_PIN_HEAD_DP = 24f
/** Start pin color. */
private val StartGreen = Color(0xFF2E9E4F)
/** Finish flag and pin outline color. */
private val PinBlack = Color(0xFF1B1F24)

/** Push pin standing on the point: gray needle, colored round head with a white rim and shine, optional number. */
private fun DrawScope.drawRoutePin(tip: Offset, k: Float, color: Color, label: androidx.compose.ui.text.TextLayoutResult?) {
    val head = tip + Offset(0f, -ROUTE_PIN_HEAD_DP * k)
    drawLine(Color(0xFF6B6B6B), tip, head + Offset(0f, 7f * k), 2.5f * k, cap = StrokeCap.Round)          // needle
    drawCircle(Color.White, 10.5f * k, head)                                                              // rim
    drawCircle(color, 9f * k, head)
    if (label != null) drawText(label, topLeft = head - Offset(label.size.width / 2f, label.size.height / 2f))
    else drawCircle(Color.White.copy(alpha = 0.7f), 2.8f * k, head + Offset(-3f * k, -3f * k))           // shine
}

/** Checkered finish flag: pole standing on the point, a 4 x 3 black-and-white flag at the top. */
private fun DrawScope.drawFinishFlag(foot: Offset, k: Float) {
    val top = foot + Offset(0f, -32f * k)
    drawLine(Color.White, foot, top, 4.5f * k, cap = StrokeCap.Round)                                     // pole halo
    drawLine(PinBlack, foot, top, 2.5f * k, cap = StrokeCap.Round)
    val cell = 4.5f * k; val cols = 4; val rows = 3
    val tl = top + Offset(1.5f * k, 0f)
    drawRect(Color.White, tl - Offset(1f * k, 1f * k), androidx.compose.ui.geometry.Size(cols * cell + 2f * k, rows * cell + 2f * k))
    for (r in 0 until rows) for (c in 0 until cols) {
        drawRect(if ((r + c) % 2 == 0) PinBlack else Color.White, tl + Offset(c * cell, r * cell), androidx.compose.ui.geometry.Size(cell, cell))
    }
    drawRect(PinBlack, tl, androidx.compose.ui.geometry.Size(cols * cell, rows * cell), style = Stroke(1f * k))
}

// ---------------------------------------------------------------------------------------- top route box

/** Numbers for the route box. Null fields show as "—". */
data class RouteStats(val distNm: Double?, val vmgKn: Double?, val hours: Double?, val etaMs: Long?)

/** Planned-route numbers: distance along the legs (start = first point), VMG toward the final point, time at the 15-minute average speed. */
fun plannedStats(start: GeoPt?, points: List<GeoPt>, fix: BoatFix?, avgSogKn: Double?, nowMs: Long): RouteStats {
    val legs = listOfNotNull(start) + points
    val dist = legs.zipWithNext { a, b -> distNm(a, b) }.sum()
    val dest = points.lastOrNull()
    val vmg = if (fix?.cogDeg != null && fix.sogKn != null && dest != null) {
        val brg = bearingDeg(GeoPt(fix.lat, fix.lon), dest)
        fix.sogKn * cos(Math.toRadians(fix.cogDeg - brg))
    } else null
    val hours = if (avgSogKn != null && avgSogKn >= 0.3) dist / avgSogKn else null
    return RouteStats(dist, vmg, hours, hours?.let { nowMs + (it * 3_600_000).toLong() })
}

/** Calculated-route numbers: distance sailed, average VMG to the destination, time left to arrival. */
fun calculatedStats(r: RouteResult, nowMs: Long): RouteStats {
    val a = r.path.first(); val b = r.path.last()
    val totalH = (r.endMs - r.startMs) / 3_600_000.0
    val vmg = if (totalH > 0) distNm(GeoPt(a.lat, a.lon), GeoPt(b.lat, b.lon)) / totalH else null
    val left = ((r.endMs - nowMs) / 3_600_000.0).coerceAtLeast(0.0)
    return RouteStats(r.distanceNm, vmg, left, r.endMs)
}

/** State of the route calculation button: nothing calculated, calculating, or a calculated route shown. */
enum class CalcState { IDLE, WORKING, DONE }

/**
 * Route box at the top: DIST, VMG, TIME (+ ETA) and the route-calculation button. A route not snapped to the boat
 * (a what-if plan) shows only DIST and, once calculated, TIME as the time the route takes.
 * Calculated route active = bold orange border and orange labels; tapping the button again clears it.
 */
@Composable
fun RouteBox(stats: RouteStats, calc: CalcState, boatLinked: Boolean, nextNm: Double?, steerDegM: Double?,
             onCalc: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val active = calc == CalcState.DONE
    val tight = { size: androidx.compose.ui.unit.TextUnit -> TextStyle(lineHeight = size * 1.05f) }
    Column(
        modifier.widthIn(max = 420.dp).clip(shape).background(Navy.copy(alpha = 0.94f), shape)
            .border(if (active) 3.dp else 1.dp, if (active) SignalOrange else NavyLight, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            @Composable
            fun Stat(label: String, value: String, unit: String) {
                Column(Modifier.widthIn(min = 44.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(label, color = if (active) SignalOrange else Mist, fontSize = 9.sp, fontWeight = FontWeight.Bold, style = tight(9.sp))
                        if (unit.isNotEmpty()) Text(" $unit", color = Mist, fontSize = 9.sp, maxLines = 1, style = tight(9.sp))
                    }
                    Text(value, color = Foam, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, style = tight(17.sp))
                }
            }
            Stat("DIST", stats.distNm?.let { "%.1f".format(it) } ?: "—", if (active) "nm sailed" else "nm")
            if (boatLinked) {
                Stat("VMG", stats.vmgKn?.let { "%.1f".format(if (kotlin.math.abs(it) < 0.05) 0.0 else it) } ?: "—", "kn")
                Stat("TIME", stats.hours?.let { hoursText(it) } ?: "—", stats.etaMs?.let { "ETA ${clockText(it)}" } ?: "")
            } else if (stats.hours != null) Stat("TIME", hoursText(stats.hours), if (active) "elapsed" else "planned")
            Spacer(Modifier.weight(1f))
            val btnShape = RoundedCornerShape(8.dp)
            Column(
                Modifier.clip(btnShape)
                    .then(if (active) Modifier.background(SignalOrange, btnShape) else Modifier.border(1.5.dp, SignalOrange, btnShape))
                    .clickable(onClick = onCalc).padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val (top, sub) = when (calc) {
                    CalcState.IDLE -> "CALC" to "best route"
                    CalcState.WORKING -> "CALC…" to "tap to stop"
                    CalcState.DONE -> "ROUTED" to "tap to clear"
                }
                Text(top, color = if (active) Navy else SignalOrange, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, style = tight(10.5.sp))
                Text(sub, color = if (active) Navy else Mist, fontSize = 8.5.sp, style = tight(8.5.sp))
            }
        }
        // sailing the route: the leg being sailed, distance to its end and the magnetic course to steer
        if (boatLinked) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (nextNm == null) Text("ARRIVED", color = SignalOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = tight(17.sp))
            else {
                Text("STEER", color = Mist, fontSize = 9.sp, fontWeight = FontWeight.Bold, style = tight(9.sp), modifier = Modifier.padding(bottom = 3.dp))
                Text(steerDegM?.let { "${deg3(it)}°M" } ?: "—", color = Foam, fontSize = 20.sp, fontWeight = FontWeight.Black, style = tight(20.sp))
                Text("NEXT", color = Mist, fontSize = 9.sp, fontWeight = FontWeight.Bold, style = tight(9.sp), modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
                Text("%.1f nm".format(nextNm), color = Foam, fontSize = 20.sp, fontWeight = FontWeight.Black, style = tight(20.sp))
            }
        }
    }
}
