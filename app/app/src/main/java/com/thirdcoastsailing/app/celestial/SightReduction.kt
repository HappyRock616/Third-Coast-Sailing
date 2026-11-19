package com.thirdcoastsailing.app.celestial

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Sight reduction: the formulas, shared by the workbook (which works them line by line, rounded like the printed
 * tables), the planner and the star finder.
 *
 *  1. Altitude corrections, Hs -> Ho (the sextant reading corrected to a true altitude):
 *       index correction, dip of the horizon, refraction (with temperature and pressure), parallax,
 *       semi-diameter (Sun and Moon limbs) - the same corrections as the Nautical Almanac's tables.
 *  2. Calculated altitude Hc and azimuth Zn of the body from an assumed position, using the almanac GHA and
 *     declination at the time of the sight (one formula, used everywhere).
 *  3. Intercept a = Ho - Hc (Marcq St Hilaire method): positive = "toward" the body, negative = "away".
 *     The LOP runs at right angles to Zn, a nautical miles from the assumed position.
 *  4. Fix: Workbook.fix crosses the lines (least squares), earlier sights advanced for the boat's run.
 *
 * Angles in degrees unless named ...Min (arc-minutes); heights in feet; distances in nautical miles.
 */

/** Which part of the Sun or Moon was put on the horizon. Stars and planets are points: CENTER. */
enum class Limb { LOWER, UPPER, CENTER }

/** One sextant reading with the settings that correct it (the unrounded correction chain, used by the tests). */
data class SightInput(
    val body: SkyBody,
    val timeMs: Long,                   // UTC instant of "mark"
    val hsDeg: Double,                  // sextant altitude as read (degrees, decimal)
    val limb: Limb = Limb.CENTER,
    val indexErrorMin: Double = 0.0,    // index error: + when the sextant reads "on the arc" (too high)
    val heightOfEyeFt: Double = 8.0,
    val temperatureC: Double = 10.0,
    val pressureMb: Double = 1010.0,
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

/** Sight reduction by formula: altitude corrections and the computed altitude and azimuth. */
object SightReduction {

    // ---------------------------------------------------------------- altitude corrections

    /** Dip of the sea horizon (arc-minutes, negative): 0.97' x sqrt(height of eye in feet). */
    fun dipMin(heightOfEyeFt: Double): Double = -0.97 * sqrt(heightOfEyeFt.coerceAtLeast(0.0))

    /** Distance to the sea horizon (nm) for a height of eye (ft). */
    fun horizonDistanceNm(heightOfEyeFt: Double): Double = 1.17 * sqrt(heightOfEyeFt.coerceAtLeast(0.0))

    /**
     * Mean refraction (arc-minutes, subtracted from Ha), Bennett's formula as used for the Nautical Almanac,
     * scaled for air temperature (°C) and pressure (mb).
     */
    fun refractionMin(haDeg: Double, temperatureC: Double = 10.0, pressureMb: Double = 1010.0): Double {
        val h = haDeg.coerceAtLeast(-0.9)
        val r = 1.0 / tan((h + 7.31 / (h + 4.4)) * DEG)
        return r * (pressureMb / 1010.0) * (283.0 / (273.0 + temperatureC))
    }

    /** Parallax in altitude (arc-minutes, added): HP x cos Ha. */
    fun parallaxMin(hpMin: Double, haDeg: Double): Double = hpMin * cos(haDeg * DEG)

    /**
     * Semi-diameter correction (arc-minutes): + for the lower limb, - for the upper, 0 for the center. The Moon's
     * disc looks a little larger the higher it is (it's nearer the observer): augmentation.
     */
    fun semiDiameterMin(body: SkyBody, limb: Limb, sdMin: Double, hpMin: Double, haDeg: Double): Double {
        val base = if (body == SkyBody.Moon) sdMin * (1.0 + sin(haDeg * DEG) * sin(hpMin / 60.0 * DEG)) else sdMin
        return when (limb) { Limb.LOWER -> base; Limb.UPPER -> -base; Limb.CENTER -> 0.0 }
    }

    /** Hs -> Ho for one sight, with every step (unrounded). */
    fun correct(s: SightInput, a: AlmanacEntry): AltitudeCorrections {
        val ic = -s.indexErrorMin
        val dip = dipMin(s.heightOfEyeFt)
        val ha = s.hsDeg + (ic + dip) / 60.0
        val r = refractionMin(ha, s.temperatureC, s.pressureMb)
        val pa = parallaxMin(a.hp, ha)
        val sd = semiDiameterMin(s.body, s.limb, a.sd, a.hp, ha)
        val ho = ha + (-r + pa + sd) / 60.0
        return AltitudeCorrections(s.hsDeg, ic, dip, ha, r, pa, sd, ho)
    }

    // ---------------------------------------------------------------- Hc and Zn

    /**
     * Calculated altitude and true azimuth from latitude, declination and local hour angle (degrees): the one
     * Hc / Zn formula in the app.
     *   sin Hc = sin lat sin Dec + cos lat cos Dec cos LHA
     *   Zn = atan2(−cos Dec sin LHA, sin Dec cos lat − cos Dec sin lat cos LHA), made 0–360°
     */
    fun hcZnFromLha(latDeg: Double, decDeg: Double, lhaDeg: Double): HcZn {
        val l = latDeg * DEG; val d = decDeg * DEG; val t = lhaDeg * DEG
        val sinHc = sin(l) * sin(d) + cos(l) * cos(d) * cos(t)
        val hc = asin(sinHc.coerceIn(-1.0, 1.0)) * RAD
        val zn = norm360(atan2(-cos(d) * sin(t), sin(d) * cos(l) - cos(d) * sin(l) * cos(t)) * RAD)
        return HcZn(hc, zn, norm360(lhaDeg))
    }

    /** Calculated altitude and true azimuth of a body with the given GHA and declination, seen from `pos` (LHA = GHA + lon east). */
    fun hcZn(pos: LatLon, ghaDeg: Double, decDeg: Double): HcZn = hcZnFromLha(pos.lat, decDeg, norm360(ghaDeg + pos.lon))

    /** Where a body is in the sky (altitude, azimuth) from a position at an instant, no refraction. */
    fun altAz(body: SkyBody, pos: LatLon, ms: Long): HcZn {
        val e = Almanac.entry(body, ms, withMagnitude = false)
        return hcZn(pos, e.gha, e.dec)
    }
}
