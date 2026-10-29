package com.thirdcoastsailing.app

import kotlin.math.abs
import kotlin.math.pow

/*
 * Barograph math (no Android parts, so it can be tested on its own):
 *  - blip rejection: a reading that jumps away and snaps straight back (a slammed hatch, a gust on the phone) is
 *    dropped; a jump that holds is kept (it may be a squall)
 *  - trends: straight-line fits over the last 1 hour and 3 hours (hPa per hour, hPa per 3 hours)
 *  - tendency words (Met Office terms), the outlook symbol, and the warning level
 *  - station pressure to sea-level pressure for the lake surface, so readings compare with the NOAA buoys
 */

/** One logged reading: time, pressure at the phone (hPa, station pressure). */
data class BaroPoint(val t: Long, val hPa: Double)

/** Warning levels: 0 = none, 1 = falling quickly, 2 = falling very rapidly or a squall jump. */
data class BaroTrend(
    val now: BaroPoint?,
    val rate1h: Double?,          // hPa per hour over the last hour (null: under 50 minutes of data)
    val rate3h: Double?,          // hPa per 3 hours over the last 3 hours (null: under 2.5 hours of data)
    val squallAt: Long?,          // time a squall jump was confirmed in the last hour, if any
    val level: Int,               // warning level right now
    val heldLevel: Int,           // warning level that has held long enough to warn about
) {
    /** The rate the tendency words and the color bar use: the 3 h trend, or 3 x the 1 h rate until there is 3 h of data. */
    val shownRate: Double? get() = rate3h ?: rate1h?.let { it * 3 }
}

/** Pressure trend arithmetic for the barograph: cleaning the log, rates of change, squalls and the Met Office tendency words. */
object BaroMath {
    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L

    // Blip rejection
    private const val BLIP_HPA = 0.5           // away from the reading before by more than this ...
    private const val SNAP_BACK_HPA = 0.3      // ... and the next one back within this of it: a blip

    // Trend fits: enough data spread over the window, no long gaps
    private const val MAX_GAP_MS = 20 * MINUTE

    // Warning thresholds (falling): the 1-hour rate catches a speeding-up fall sooner than the 3-hour tendency
    const val L1_RATE_1H = -1.2                // hPa/h, the pace of -3.6 in 3 h ("falling quickly")
    const val L1_RATE_3H = -3.6
    const val L2_RATE_1H = -2.0                // hPa/h ("falling very rapidly" pace)
    const val L2_RATE_3H = -6.0
    const val L1_HOLD_MS = 10 * MINUTE
    const val L2_HOLD_MS = 5 * MINUTE
    // Squall jump: a rise of at least SQUALL_HPA between readings no more than SQUALL_GAP apart, still up SQUALL_HOLD later
    private const val SQUALL_HPA = 1.5
    private const val SQUALL_GAP_MS = 6 * MINUTE
    private const val SQUALL_HOLD_MS = 2 * MINUTE

    // Sampling: faster while the weather is turning
    const val NORMAL_EVERY_MS = 5 * MINUTE
    const val FAST_EVERY_MS = MINUTE
    const val TURNING_RATE_1H = -0.5           // hPa/h falling faster than this, or ...
    const val TURNING_JUMP_HPA = 1.0           // ... a jump this big between readings: sample every minute
    const val TURNING_KEEP_MS = HOUR           // until it has been calm this long

    /** Readings with blips removed (a jump away that snaps straight back on the next reading). */
    fun clean(pts: List<BaroPoint>): List<BaroPoint> {
        if (pts.size < 3) return pts
        val out = ArrayList<BaroPoint>(pts.size)
        out += pts[0]
        for (i in 1 until pts.size - 1) {
            val prev = out.last(); val p = pts[i]; val next = pts[i + 1]
            val blip = abs(p.hPa - prev.hPa) > BLIP_HPA && abs(next.hPa - prev.hPa) < SNAP_BACK_HPA
            if (!blip) out += p
        }
        out += pts.last()
        return out
    }

    /**
     * Least-squares slope (hPa per hour) of the readings in (end - window, end], or null when the data don't cover
     * at least `minCover` of the window or have a gap over MAX_GAP_MS.
     */
    fun slope(pts: List<BaroPoint>, end: Long, window: Long, minCover: Long): Double? {
        val from = end - window
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        var first = Long.MAX_VALUE; var last = Long.MIN_VALUE; var prevT = Long.MIN_VALUE
        for (p in pts) {
            if (p.t <= from || p.t > end) continue
            if (prevT != Long.MIN_VALUE && p.t - prevT > MAX_GAP_MS) return null
            prevT = p.t
            val x = (p.t - end) / HOUR.toDouble()
            n++; sx += x; sy += p.hPa; sxx += x * x; sxy += x * p.hPa
            if (p.t < first) first = p.t
            if (p.t > last) last = p.t
        }
        if (n < 3 || last - first < minCover || end - last > MAX_GAP_MS) return null
        val d = n * sxx - sx * sx
        if (d <= 0.0) return null
        return (n * sxy - sx * sy) / d
    }

    /** Pressure change over the last hour (hPa per hour, least-squares slope), or null without enough readings. */
    fun rate1h(pts: List<BaroPoint>, end: Long): Double? = slope(pts, end, HOUR, 50 * MINUTE)
    /** Pressure change over the last 3 hours (hPa per 3 h), or null without enough readings. */
    fun rate3h(pts: List<BaroPoint>, end: Long): Double? = slope(pts, end, 3 * HOUR, (2.5 * HOUR).toLong())?.let { it * 3 }

    /** Time a squall jump was confirmed at or before `end` (looking back an hour), or null. */
    fun squall(pts: List<BaroPoint>, end: Long): Long? {
        for (j in pts.indices.reversed()) {
            val p = pts[j]
            if (p.t > end) continue
            if (end - p.t > HOUR) break
            if (j == 0) break
            val before = pts[j - 1]
            if (p.t - before.t > SQUALL_GAP_MS || p.hPa - before.hPa < SQUALL_HPA) continue
            // still up a little later?
            val held = pts.firstOrNull { it.t >= p.t + SQUALL_HOLD_MS && it.t <= end }
            if (held != null && held.hPa - before.hPa >= SQUALL_HPA) return held.t
        }
        return null
    }

    /** Warning level from the rates at one moment. */
    fun level(r1: Double?, r3: Double?, squall: Boolean): Int = when {
        squall || (r1 != null && r1 <= L2_RATE_1H) || (r3 != null && r3 <= L2_RATE_3H) -> 2
        (r1 != null && r1 <= L1_RATE_1H) || (r3 != null && r3 <= L1_RATE_3H) -> 1
        else -> 0
    }

    /** Trend and warning level at `end` from cleaned readings. */
    fun trend(clean: List<BaroPoint>, end: Long): BaroTrend {
        val now = clean.lastOrNull { it.t <= end }
        val r1 = rate1h(clean, end); val r3 = rate3h(clean, end); val sq = squall(clean, end)
        val lvl = level(r1, r3, sq != null)
        // held: every reading over the hold time (and at least that long ago) gives at least this level
        fun heldFor(l: Int, hold: Long): Boolean {
            val recent = clean.filter { it.t in (end - hold)..end }
            if (recent.size < 2 || end - recent.first().t < hold - 2 * MINUTE) return false
            return recent.all { p -> level(rate1h(clean, p.t), rate3h(clean, p.t), squall(clean, p.t) != null) >= l }
        }
        val held = when {
            sq != null -> 2                                                   // a confirmed squall jump counts at once
            lvl >= 2 && heldFor(2, L2_HOLD_MS) -> 2
            lvl >= 1 && heldFor(1, L1_HOLD_MS) -> 1
            else -> 0
        }
        return BaroTrend(now, r1, r3, sq, lvl, held)
    }

    /** True while the weather is turning: falling faster than TURNING_RATE_1H, or a big jump between the last two readings. */
    fun turning(clean: List<BaroPoint>, end: Long): Boolean {
        val r1 = rate1h(clean, end)
        if (r1 != null && r1 < TURNING_RATE_1H) return true
        val last = clean.lastOrNull { it.t <= end } ?: return false
        val prev = clean.lastOrNull { it.t < last.t } ?: return false
        return abs(last.hPa - prev.hPa) > TURNING_JUMP_HPA
    }

    // ---------------------------------------------------------------- words and symbols

    /** Tendency in Met Office terms from a 3-hour change (hPa). */
    fun tendencyWords(r3: Double?): String {
        if (r3 == null) return "Measuring…"
        val dir = if (r3 < 0) "Falling" else "Rising"
        val a = abs(r3)
        return when {
            a < 0.1 -> "Steady"
            a <= 1.5 -> "$dir slowly"
            a <= 3.5 -> dir
            a <= 6.0 -> "$dir quickly"
            else -> "$dir very rapidly"
        }
    }

    /** Outlook from pressure alone: (symbol, short words). Chances, not a forecast. */
    fun outlook(t: BaroTrend): Pair<String, String> {
        val r = t.shownRate
        return when {
            t.squallAt != null -> "⛈️" to "Squall likely"
            r == null -> "⏳" to "Needs an hour of readings"
            t.level >= 2 || r <= -6.0 -> "⛈️" to "Storm likely"
            t.level >= 1 || r <= -3.6 -> "🌧️" to "Wind & rain likely"
            r <= -1.6 -> "🌥️" to "Rain possible"
            r < 1.6 -> "⛅" to "Little change"
            r < 3.6 -> "🌤️" to "Improving"
            else -> "💨" to "Clearing, windy"
        }
    }

    // ---------------------------------------------------------------- sea level

    /** Lake surface height (m) for a position: Ontario 75, Erie 174, Superior 183, Michigan / Huron 176 (default). */
    fun lakeElevationM(lat: Double?, lon: Double?): Double {
        if (lat == null || lon == null) return 176.0
        return when {
            lon > -79.9 -> 74.7                                   // Lake Ontario (east of the Niagara River)
            lat < 42.95 && lon > -83.6 -> 174.2                   // Lake Erie
            lat > 46.4 && lon < -84.3 -> 183.4                    // Lake Superior
            else -> 176.0                                         // Lakes Michigan and Huron
        }
    }

    /** Station pressure at height h (m) to sea-level pressure (standard atmosphere, 15 °C). */
    fun toSeaLevel(stationHpa: Double, h: Double): Double = stationHpa * (1 - 0.0065 * h / (288.15 + 0.0065 * h)).pow(-5.257)
}
