package com.thirdcoastsailing.app.celestial

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Sight reduction: from what the sextant read to a line of position (LOP) on the chart.
 *
 *  1. Altitude corrections, Hs -> Ho (the sextant reading corrected to a true altitude):
 *       index correction, dip of the horizon, refraction (with temperature and pressure), parallax,
 *       semi-diameter (Sun and Moon limbs) - the same corrections as the Nautical Almanac's tables.
 *  2. Calculated altitude Hc and azimuth Zn of the body from an assumed position (normally the DR position),
 *     using the almanac GHA and declination at the time of the sight.
 *  3. Intercept a = Ho - Hc (Marcq St Hilaire method): positive = "toward" the body, negative = "away".
 *     The LOP runs at right angles to Zn, a nautical miles from the assumed position.
 *  4. Fix: where two or more LOPs cross (least squares), earlier sights advanced for the boat's run.
 *
 * Angles in degrees unless named ...Min (arc-minutes); heights in feet; distances in nautical miles.
 */

/** Which part of the Sun or Moon was put on the horizon. Stars and planets are points: CENTER. */
enum class Limb { LOWER, UPPER, CENTER }

/** Everything the navigator writes down for one sight. */
data class SightInput(
    val body: SkyBody,
    val timeMs: Long,                   // UTC instant of "mark"
    val hsDeg: Double,                  // sextant altitude as read (degrees, decimal)
    val limb: Limb = Limb.CENTER,
    val indexErrorMin: Double = 0.0,    // index error: + when the sextant reads "on the arc" (too high)
    val heightOfEyeFt: Double = 8.0,
    val temperatureC: Double = 10.0,
    val pressureMb: Double = 1010.0,
    val dipShortDistanceNm: Double? = null,   // horizon is a shoreline this far away (dip short), else null
)

/** The correction chain from Hs to Ho, every step shown (minutes of arc). */
data class AltitudeCorrections(
    val hsDeg: Double,
    val indexCorrMin: Double,
    val dipMin: Double,
    val haDeg: Double,                  // apparent altitude: Hs + IC + dip
    val refractionMin: Double,          // subtracted
    val parallaxMin: Double,            // added
    val semiDiameterMin: Double,        // + lower limb, - upper limb
    val hoDeg: Double,
)

/** Calculated altitude and azimuth of a body from a position. */
data class HcZn(val hcDeg: Double, val znDeg: Double, val lhaDeg: Double)

/** A reduced sight: its line of position. */
data class LineOfPosition(
    val sight: SightInput,
    val almanac: AlmanacEntry,
    val corrections: AltitudeCorrections,
    val assumed: LatLon,                // where the sight was worked from
    val hcDeg: Double,
    val znDeg: Double,
    val interceptNm: Double,            // + toward the body, - away
) {
    val toward: Boolean get() = interceptNm >= 0
    /** The point on the LOP closest to the assumed position. */
    val footPoint: LatLon get() = travel(assumed, znDeg, interceptNm)
    /** Two ends of the LOP for drawing: halfLengthNm either side of the foot point, at right angles to Zn. */
    fun segment(halfLengthNm: Double = 10.0): Pair<LatLon, LatLon> =
        Pair(travel(footPoint, znDeg - 90.0, halfLengthNm), travel(footPoint, znDeg + 90.0, halfLengthNm))
}

/** Result of crossing two or more LOPs. */
data class Fix(
    val position: LatLon,
    val timeMs: Long,
    val lops: List<LineOfPosition>,     // as advanced to the fix time
    val residualNm: Double,             // RMS distance of the fix from its LOPs ("size of the cocked hat")
    val iterations: Int,
)

/** Sight reduction by formula: altitude corrections, computed altitude and azimuth, intercepts and fixes. */
object SightReduction {

    // ---------------------------------------------------------------- altitude corrections

    /** Dip of the sea horizon (arc-minutes, negative): 0.97' x sqrt(height of eye in feet). */
    fun dipMin(heightOfEyeFt: Double): Double = -0.97 * sqrt(heightOfEyeFt.coerceAtLeast(0.0))

    /** Distance to the sea horizon (nm) for a height of eye (ft). */
    fun horizonDistanceNm(heightOfEyeFt: Double): Double = 1.17 * sqrt(heightOfEyeFt.coerceAtLeast(0.0))

    /**
     * Dip short of the horizon (arc-minutes, negative), when the horizon used is a shoreline closer than the sea
     * horizon (common on the Great Lakes near shore). Bowditch: D = 0.416 d + 0.566 h / d (d nm, h ft).
     */
    fun dipShortMin(heightOfEyeFt: Double, distanceNm: Double): Double =
        -(0.416 * distanceNm + 0.566 * heightOfEyeFt / distanceNm.coerceAtLeast(0.05))

    /**
     * Mean refraction (arc-minutes, subtracted from Ha), Bennett's formula as used for the Nautical Almanac,
     * scaled for air temperature (°C) and pressure (mb).
     */
    fun refractionMin(haDeg: Double, temperatureC: Double = 10.0, pressureMb: Double = 1010.0): Double {
        val h = haDeg.coerceAtLeast(-0.9)
        val r = 1.0 / tan((h + 7.31 / (h + 4.4)) * DEG)
        return r * (pressureMb / 1010.0) * (283.0 / (273.0 + temperatureC))
    }

    /** Hs -> Ho for one sight, with every step. */
    fun correct(s: SightInput, a: AlmanacEntry): AltitudeCorrections {
        val ic = -s.indexErrorMin
        val dip = s.dipShortDistanceNm?.takeIf { it < horizonDistanceNm(s.heightOfEyeFt) }
            ?.let { dipShortMin(s.heightOfEyeFt, it) } ?: dipMin(s.heightOfEyeFt)
        val ha = s.hsDeg + (ic + dip) / 60.0
        val r = refractionMin(ha, s.temperatureC, s.pressureMb)
        val pa = a.hp * cos(ha * DEG)
        // the Moon's disc looks a little larger the higher it is (it's nearer the observer): augmentation
        val sdBase = if (s.body == SkyBody.Moon) a.sd * (1.0 + sin(ha * DEG) * sin(a.hp / 60.0 * DEG)) else a.sd
        val sd = when (s.limb) { Limb.LOWER -> sdBase; Limb.UPPER -> -sdBase; Limb.CENTER -> 0.0 }
        val ho = ha + (-r + pa + sd) / 60.0
        return AltitudeCorrections(s.hsDeg, ic, dip, ha, r, pa, sd, ho)
    }

    // ---------------------------------------------------------------- Hc and Zn

    /** Calculated altitude and true azimuth of a body with the given GHA and declination, seen from `pos`. */
    fun hcZn(pos: LatLon, ghaDeg: Double, decDeg: Double): HcZn {
        val lha = norm360(ghaDeg + pos.lon)
        val l = pos.lat * DEG; val d = decDeg * DEG; val t = lha * DEG
        val sinHc = sin(l) * sin(d) + cos(l) * cos(d) * cos(t)
        val hc = asin(sinHc.coerceIn(-1.0, 1.0)) * RAD
        val zn = norm360(atan2(-cos(d) * sin(t), sin(d) * cos(l) - cos(d) * sin(l) * cos(t)) * RAD)
        return HcZn(hc, zn, lha)
    }

    /** Where a body is in the sky (altitude, azimuth) from a position at an instant, no refraction. */
    fun altAz(body: SkyBody, pos: LatLon, ms: Long): HcZn {
        val e = Almanac.entry(body, ms)
        return hcZn(pos, e.gha, e.dec)
    }

    // ---------------------------------------------------------------- one sight -> LOP

    /** Reduce one sight from an assumed position (use the DR position at the time of the sight). */
    fun reduce(s: SightInput, assumed: LatLon): LineOfPosition {
        val a = Almanac.entry(s.body, s.timeMs)
        val c = correct(s, a)
        val h = hcZn(assumed, a.gha, a.dec)
        return LineOfPosition(s, a, c, assumed, h.hcDeg, h.znDeg, (c.hoDeg - h.hcDeg) * 60.0)
    }

    /**
     * Average several sights of the same body (taken a minute or two apart) into one: mean time and mean Hs.
     * Sights more than `rejectMin` arc-minutes off the trend of the others are dropped. Returns null if none remain.
     */
    fun average(sights: List<SightInput>, rejectMin: Double = 3.0): SightInput? {
        if (sights.isEmpty()) return null
        if (sights.size < 3) return mean(sights)
        // straight-line fit of Hs against time; drop outliers, refit once
        fun fit(xs: List<SightInput>): Pair<Double, Double> {
            val t0 = xs.first().timeMs
            val tx = xs.map { (it.timeMs - t0) / 60_000.0 }; val hy = xs.map { it.hsDeg }
            val mt = tx.average(); val mh = hy.average()
            val sxx = tx.sumOf { (it - mt) * (it - mt) }
            val slope = if (sxx > 1e-9) tx.indices.sumOf { (tx[it] - mt) * (hy[it] - mh) } / sxx else 0.0
            return Pair(mh - slope * mt, slope)
        }
        val t0 = sights.first().timeMs
        val (b, m) = fit(sights)
        val kept = sights.filter { abs((it.hsDeg - (b + m * (it.timeMs - t0) / 60_000.0)) * 60.0) <= rejectMin }
        return mean(kept.ifEmpty { return null })
    }

    /** One sight from several shots: their average time and average sextant altitude. */
    private fun mean(xs: List<SightInput>): SightInput =
        xs.first().copy(timeMs = xs.map { it.timeMs }.average().toLong(), hsDeg = xs.map { it.hsDeg }.average())

    // ---------------------------------------------------------------- fixes

    /**
     * Fix from two or more sights. Each sight is worked from the DR position at its own time, then moved
     * ("advanced") to the fix time along the boat's course and speed, so sights taken hours apart (a running fix,
     * e.g. morning Sun + noon Sun) can be crossed. The fix is the least-squares crossing point; the sights are then
     * re-worked from the fix and solved again (iterates until it moves less than 0.05 nm), so a poor DR doesn't
     * spoil the answer.
     *
     * @param dr        dead-reckoning position at time drTimeMs
     * @param courseDeg course over ground (true) for advancing sights; null = boat stationary
     * @param speedKn   speed over ground
     * @param fixTimeMs time the fix is for (normally the last sight)
     */
    fun fix(
        sights: List<SightInput>,
        dr: LatLon,
        drTimeMs: Long,
        courseDeg: Double?,
        speedKn: Double,
        fixTimeMs: Long = sights.maxOf { it.timeMs },
    ): Fix? {
        if (sights.size < 2) return null
        fun posAt(base: LatLon, baseMs: Long, ms: Long): LatLon {
            if (courseDeg == null || speedKn <= 0.0) return base
            return travel(base, courseDeg, speedKn * (ms - baseMs) / 3_600_000.0)
        }
        var ref = posAt(dr, drTimeMs, fixTimeMs)
        var lops = emptyList<LineOfPosition>()
        var iterations = 0
        var residual = 0.0
        repeat(6) {
            iterations++
            // work each sight from the reference position carried back to the sight's own time
            lops = sights.map { s -> reduce(s, posAt(ref, fixTimeMs, s.timeMs)) }
            // each LOP advanced to the fix time: same Zn and intercept, assumed position moved with the boat
            val advanced = lops.map { it.copy(assumed = posAt(it.assumed, it.sight.timeMs, fixTimeMs)) }
            val solved = leastSquares(advanced, ref) ?: return null
            val moved = distanceNm(ref, solved.first)
            ref = solved.first; residual = solved.second
            lops = advanced
            if (moved < 0.05) return Fix(ref, fixTimeMs, lops, residual, iterations)
        }
        return Fix(ref, fixTimeMs, lops, residual, iterations)
    }

    /**
     * Least-squares crossing of LOPs in a flat local frame (nm east / north of `ref`). Each LOP is the line
     * n . p = c with n the unit vector toward Zn. Returns the position and the RMS miss distance, or null when the
     * lines are too close to parallel to cross (azimuths within about 20° of each other or of the reciprocal).
     */
    internal fun leastSquares(lops: List<LineOfPosition>, ref: LatLon): Pair<LatLon, Double>? {
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        val rows = lops.map { l ->
            val nx = sin(l.znDeg * DEG); val ny = cos(l.znDeg * DEG)
            val ap = local(ref, l.assumed)
            val c = l.interceptNm + nx * ap.first + ny * ap.second
            Triple(nx, ny, c)
        }
        for ((nx, ny, c) in rows) { a11 += nx * nx; a12 += nx * ny; a22 += ny * ny; b1 += nx * c; b2 += ny * c }
        val det = a11 * a22 - a12 * a12
        if (det < 0.03 * lops.size * lops.size) return null          // nearly parallel lines: no reliable fix
        val x = (b1 * a22 - b2 * a12) / det
        val y = (a11 * b2 - a12 * b1) / det
        val rms = sqrt(rows.sumOf { (nx, ny, c) -> val r = nx * x + ny * y - c; r * r } / rows.size)
        return Pair(fromLocal(ref, x, y), rms)
    }

    /** nm east and north of ref (flat approximation, fine within a few tens of miles). */
    private fun local(ref: LatLon, p: LatLon): Pair<Double, Double> =
        Pair(norm180(p.lon - ref.lon) * 60.0 * cos(ref.lat * DEG), (p.lat - ref.lat) * 60.0)

    /** Position eastNm east and northNm north of ref (flat-earth step, fine over a few miles). */
    private fun fromLocal(ref: LatLon, eastNm: Double, northNm: Double): LatLon =
        LatLon(ref.lat + northNm / 60.0, norm180(ref.lon + eastNm / 60.0 / cos(ref.lat * DEG)))

    // ---------------------------------------------------------------- special cases

    /**
     * Latitude from a meridian (noon) sight of any body: the observed altitude when the body crosses the meridian.
     * Zn near 180 (body to the south): lat = 90 - Ho + dec. Body to the north: lat = dec - (90 - Ho).
     */
    fun latitudeFromMeridian(hoDeg: Double, decDeg: Double, bodyToSouth: Boolean): Double =
        if (bodyToSouth) 90.0 - hoDeg + decDeg else decDeg - (90.0 - hoDeg)
}
