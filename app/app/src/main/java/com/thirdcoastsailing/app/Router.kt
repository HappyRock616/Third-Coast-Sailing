package com.thirdcoastsailing.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Grid of navigable water over a lat/lon box, built from the lakes' routing files:
 *  1. approximate depth bands (NCEI): open where the shallow end of the band is at or below the depth limit
 *  2. charted depth areas, smaller-scale charts first so harbour charts win: open / blocked by the same rule
 *  3. land, piers / breakwaters (shoreline construction areas) and hazard areas: always blocked
 *  4. small circles around the start and each route point: always open, so harbour starts and
 *     waypoints in narrow channels are reachable even when the grid is coarser than the channel
 * Anything outside every chart is blocked.
 */
class NavMask private constructor(
    private val lat0: Double, private val lon0: Double, private val dLat: Double, private val dLon: Double,
    private val nx: Int, private val ny: Int, private val cells: ByteArray,
) {
    fun free(lat: Double, lon: Double): Boolean {
        if (lat < lat0 || lon < lon0) return false
        val i = ((lon - lon0) / dLon).toInt()
        val j = ((lat - lat0) / dLat).toInt()
        if (i >= nx || j >= ny) return false
        return cells[j * nx + i] == OPEN
    }

    /** True if the straight line a→b stays on open water (checked every half cell). */
    fun clear(a: GeoPt, b: GeoPt): Boolean {
        val steps = max(1, ceil(max(abs(b.lat - a.lat) / dLat, abs(b.lon - a.lon) / dLon) * 2).toInt())
        for (k in 0..steps) {
            val f = k.toDouble() / steps
            if (!free(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)) return false
        }
        return true
    }

    companion object {
        private const val BLOCKED: Byte = 0
        private const val OPEN: Byte = 1
        private const val KEEP_OPEN_NM = 0.25

        /**
         * @param box lon_min, lat_min, lon_max, lat_max
         * @param limitM depth limit in metres (shallower = blocked)
         */
        fun build(gpkgs: List<File>, box: DoubleArray, limitM: Double, keepOpen: List<GeoPt>, cancel: () -> Boolean): NavMask {
            val midLat = (box[1] + box[3]) / 2
            val coslat = cos(Math.toRadians(midLat))
            val span = max((box[2] - box[0]) * coslat, box[3] - box[1])
            val dLat = max(span / 700.0, 0.0008)                      // ~90 m at the finest
            val dLon = dLat / coslat
            val nx = ceil((box[2] - box[0]) / dLon).toInt().coerceAtLeast(1)
            val ny = ceil((box[3] - box[1]) / dLat).toInt().coerceAtLeast(1)
            val cells = ByteArray(nx * ny)                            // all BLOCKED to start
            val g = Painter(box[1], box[0], dLat, dLon, nx, ny, cells)

            val dbs = gpkgs.mapNotNull { Gpkg.open(it) }
            try {
                fun layer(table: String, valueCol: String?, order: String?, value: (Double?) -> Byte?) {
                    for (db in dbs) for ((fid, v) in Gpkg.query(db, table, valueCol, box[0], box[1], box[2], box[3], order)) {
                        if (cancel()) throw Router.Failed("Stopped")
                        val paint = value(v) ?: continue
                        val blob = Gpkg.geom(db, table, fid) ?: continue
                        for (poly in Gpkg.polygons(blob)) g.fill(poly, paint)
                    }
                }
                layer("bathy_band", "dmin", null) { v -> if (v != null && v >= limitM) OPEN else BLOCKED }
                layer("depth_area", "drval1", "band") { v -> if (v == null) null else if (v >= limitM) OPEN else BLOCKED }
                layer("land", null, null) { BLOCKED }
                layer("shoreline_construction_area", null, null) { BLOCKED }
                layer("hazard_area", null, null) { BLOCKED }
            } finally {
                dbs.forEach(SQLiteDatabase::close)
            }
            for (p in keepOpen) g.disc(p, KEEP_OPEN_NM)
            return NavMask(box[1], box[0], dLat, dLon, nx, ny, cells)
        }
    }

    /** Paints polygons into the grid by scanlines (cell centres). */
    private class Painter(val lat0: Double, val lon0: Double, val dLat: Double, val dLon: Double,
                          val nx: Int, val ny: Int, val cells: ByteArray) {

        fun fill(rings: List<DoubleArray>, v: Byte) {
            val rows = arrayOfNulls<MutableList<Double>>(ny)
            for (r in rings) {
                val n = r.size / 2
                for (k in 0 until n) {
                    val x1 = r[2 * k]; val y1 = r[2 * k + 1]
                    val k2 = (k + 1) % n
                    val x2 = r[2 * k2]; val y2 = r[2 * k2 + 1]
                    if (y1 == y2) continue
                    val ylo = min(y1, y2); val yhi = max(y1, y2)
                    val j0 = max(0, ceil((ylo - lat0) / dLat - 0.5).toInt())
                    val j1 = min(ny - 1, floor((yhi - lat0) / dLat - 0.5).toInt())
                    for (j in j0..j1) {
                        val yc = lat0 + (j + 0.5) * dLat
                        if (yc < ylo || yc >= yhi) continue
                        val x = x1 + (yc - y1) * (x2 - x1) / (y2 - y1)
                        (rows[j] ?: mutableListOf<Double>().also { rows[j] = it }).add(x)
                    }
                }
            }
            for (j in 0 until ny) {
                val xs = rows[j] ?: continue
                xs.sort()
                var k = 0
                while (k + 1 < xs.size) {
                    val i0 = max(0, ceil((xs[k] - lon0) / dLon - 0.5).toInt())
                    val i1 = min(nx - 1, floor((xs[k + 1] - lon0) / dLon - 0.5).toInt())
                    for (i in i0..i1) cells[j * nx + i] = v
                    k += 2
                }
            }
        }

        fun disc(c: GeoPt, radiusNm: Double) {
            val rLat = radiusNm / 60.0
            val rLon = rLat / cos(Math.toRadians(c.lat))
            val j0 = max(0, ((c.lat - rLat - lat0) / dLat).toInt()); val j1 = min(ny - 1, ((c.lat + rLat - lat0) / dLat).toInt())
            val i0 = max(0, ((c.lon - rLon - lon0) / dLon).toInt()); val i1 = min(nx - 1, ((c.lon + rLon - lon0) / dLon).toInt())
            for (j in j0..j1) for (i in i0..i1) {
                val dy = (lat0 + (j + 0.5) * dLat - c.lat) / rLat
                val dx = (lon0 + (i + 0.5) * dLon - c.lon) / rLon
                if (dx * dx + dy * dy <= 1.0) cells[j * nx + i] = OPEN
            }
        }
    }
}

/** Forecast wind at a time and place, cached per forecast hour: (speed kn, direction from deg) or null. */
class WindLookup(private val repo: WeatherRepo) {
    private val cache = HashMap<Long, Pair<WeatherRepo.Field?, WeatherRepo.Field?>>()

    fun at(tMs: Long, lat: Double, lon: Double): Pair<Double, Double>? {
        val hour = Math.round(tMs / 3_600_000.0)
        val (sf, df) = cache.getOrPut(hour) {
            repo.field(Wx.WIND_SPEED, hour * 3_600_000L) to repo.field(Wx.WIND_DIR, hour * 3_600_000L)
        }
        val s = sf?.at(lat, lon) ?: return null
        val d = df?.at(lat, lon) ?: return null
        if (s.isNaN() || d.isNaN()) return null
        return s * WeatherRepo.MS_TO_KN to d.toDouble()
    }
}

/**
 * Weather routing by isochrones. From the start, every 5° heading is sailed for one time step at the polar speed
 * (cruise factor applied) for the forecast wind at that place and hour; the furthest point in each 2° sector
 * (seen from the leg start) is kept and the process repeats until a point can reach the next route point
 * within one step on clear water. Tacks and gybes (wind changes sides) cost the tack penalty in lost time.
 * Each leg must pass through the user's route points in order.
 */
object Router {

    class Failed(msg: String) : Exception(msg)

    private class Node(val lat: Double, val lon: Double, val t: Long, val parent: Node?, val side: Int, val debtH: Double)

    fun route(start: GeoPt, waypoints: List<GeoPt>, departMs: Long, polar: Polar, wind: WindLookup, mask: NavMask,
              cruise: Double, tackMin: Double, cancel: () -> Boolean): RouteResult {
        val path = mutableListOf(RoutePoint(start.lat, start.lon, departMs))
        var from = start
        var t = departMs
        waypoints.forEachIndexed { i, wp ->
            val leg = leg(from, wp, i + 1, t, polar, wind, mask, cruise, tackMin / 60.0, cancel)
            path += leg.drop(1)
            from = wp
            t = leg.last().timeMs
        }
        val dist = path.zipWithNext { a, b -> distNm(GeoPt(a.lat, a.lon), GeoPt(b.lat, b.lon)) }.sum()
        return RouteResult(path, dist, departMs, t)
    }

    /** Polar speed with the cruise factor; below the polar's lightest wind, scaled down toward zero. */
    private fun speed(polar: Polar, cruise: Double, tws: Double, twaAbs: Double): Double {
        val lightest = polar.tws.first()
        val s = polar.boatSpeed(max(tws, lightest), twaAbs) * cruise
        return if (tws < lightest) s * tws / lightest else s
    }

    /** Signed angle -180..180 (positive = wind on starboard side). */
    private fun signed(a: Double): Double { var x = (a % 360 + 360) % 360; if (x > 180) x -= 360; return x }

    private fun leg(a: GeoPt, b: GeoPt, number: Int, t0: Long, polar: Polar, wind: WindLookup, mask: NavMask,
                    cruise: Double, tackH: Double, cancel: () -> Boolean): List<RoutePoint> {
        val legNm = distNm(a, b)
        if (legNm < 0.05) return listOf(RoutePoint(a.lat, a.lon, t0), RoutePoint(b.lat, b.lon, t0))
        val dtH = (legNm / 5.0 / 15).coerceIn(5 / 60.0, 0.5)           // ~15 steps for the leg at 5 kn
        val dtMs = (dtH * 3_600_000).toLong()
        val maxSteps = (max(6.0, legNm).coerceAtMost(72.0) / dtH).toInt()   // gives up below ~1 kn made good
        var frontier = listOf(Node(a.lat, a.lon, t0, null, 0, 0.0))

        repeat(maxSteps) {
            if (cancel()) throw Failed("Stopped")
            // 1. can any point finish the leg within this step?
            var best: Node? = null
            var bestArr = Long.MAX_VALUE
            var anyWind = false
            for (n in frontier) {
                val w = wind.at(n.t, n.lat, n.lon) ?: continue
                anyWind = true
                val p = GeoPt(n.lat, n.lon)
                val twa = signed(bearingDeg(p, b) - w.second)
                val v = speed(polar, cruise, w.first, abs(twa))
                if (v < 0.2) continue
                val side = if (twa >= 0) 1 else -1
                val hrs = n.debtH + (if (n.side != 0 && side != n.side) tackH else 0.0) + distNm(p, b) / v
                if (hrs > dtH || !mask.clear(p, b)) continue
                val arr = n.t + (hrs * 3_600_000).toLong()
                if (arr < bestArr) { bestArr = arr; best = n }
            }
            best?.let { return trail(it) + RoutePoint(b.lat, b.lon, bestArr) }
            if (!anyWind) throw Failed("No wind forecast for ${WeatherRepo.dayHour(frontier.first().t)} on leg $number. Update weather and try again.")

            // 2. sail every heading for one step; keep the furthest point per 2° sector
            val keep = arrayOfNulls<Node>(180)
            val far = DoubleArray(180) { -1.0 }
            for (n in frontier) {
                val w = wind.at(n.t, n.lat, n.lon) ?: continue
                val p = GeoPt(n.lat, n.lon)
                for (h in 0 until 360 step 5) {
                    val twa = signed(h - w.second)
                    val v = speed(polar, cruise, w.first, abs(twa))
                    if (v < 0.2) continue
                    val side = if (twa >= 0) 1 else -1
                    val debt = n.debtH + (if (n.side != 0 && side != n.side) tackH else 0.0)
                    val avail = dtH - debt
                    val q: GeoPt
                    val newDebt: Double
                    if (avail <= 0) { q = p; newDebt = -avail }               // still finishing the tack
                    else {
                        val ll = destination(n.lat, n.lon, h.toDouble(), v * avail)
                        q = GeoPt(ll.latitude, ll.longitude); newDebt = 0.0
                        if (!mask.clear(p, q)) continue
                    }
                    val d = distNm(a, q)
                    val sec = (bearingDeg(a, q) / 2).toInt().coerceIn(0, 179)
                    if (d > far[sec]) { far[sec] = d; keep[sec] = Node(q.lat, q.lon, n.t + dtMs, n, side, newDebt) }
                }
            }
            frontier = keep.filterNotNull()
            if (frontier.isEmpty()) throw Failed("No clear water toward point $number (land or water shallower than the depth limit).")
        }
        throw Failed("Leg to point $number is too slow to finish with this forecast.")
    }

    /** Points from the leg start to this node. */
    private fun trail(n: Node): List<RoutePoint> {
        val out = ArrayList<RoutePoint>()
        var c: Node? = n
        while (c != null) { out += RoutePoint(c.lat, c.lon, c.t); c = c.parent }
        out.reverse()
        return out
    }

    /** Lon/lat box around the route, padded so the router has room to tack around it. */
    fun boxFor(points: List<GeoPt>): DoubleArray {
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val padLat = max(0.12, (maxLat - minLat) * 0.3)
        val padLon = max(0.16, (maxLon - minLon) * 0.3)
        return doubleArrayOf(minLon - padLon, minLat - padLat, maxLon + padLon, maxLat + padLat)
    }
}
