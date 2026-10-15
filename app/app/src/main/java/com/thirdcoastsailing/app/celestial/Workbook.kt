package com.thirdcoastsailing.app.celestial

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The sight workbook: each sight worked the way a navigator works it on paper, with the printed-almanac method
 * (values at the whole hour + increments for minutes and seconds + v/d corrections), the altitude correction chain,
 * the traditional assumed position (whole-degree latitude, whole-degree LHA) and the calculated altitude by formula.
 * Every line carries its equation and the numbers that went into it, so the screen can show the working.
 *
 * Almanac values and corrections are rounded to 0.1' like the printed tables, so each line adds up exactly.
 *
 * Skill levels:
 *  BEGINNER / INTERMEDIATE  every line is filled in by the app (the screen shows where each came from)
 *  ADVANCED                 the navigator types every line; each is checked against the right value worked from
 *                           the navigator's OWN earlier lines, so the first mistake is flagged and later lines
 *                           that follow correctly from it are not. Nothing is corrected: the line of position and
 *                           fix are plotted from the navigator's own numbers.
 *
 * Pure Kotlin (no Android) so it can be tested on a PC.
 */

/**
 * One reading of the sextant: the instant "mark" was pressed, the altitude read, and where the boat was
 * (`gps` = that position came from GPS; false = a typed DR position).
 */
data class Shot(val timeMs: Long, val hsDeg: Double, val dr: LatLon, val gps: Boolean = true)

/** Lines of the workbook. Advanced users type one value per line. */
enum class Field {
    GHA_HOUR, INCREMENT, V, V_CORR, SHA, GHA,
    DEC_HOUR, D, D_CORR, DEC,
    IC, DIP, HA, REFRACTION, PARALLAX, SEMI_DIAMETER, HO,
    AP_LAT, AP_LON, LHA, HC, ZN, INTERCEPT,
}

/** How a line's value is written and typed. */
enum class FieldKind {
    ANGLE,      // 0..360 degrees, written 223°14.2'
    ALTITUDE,   // altitude above the horizon, written 38°19.6' (negative below)
    DEC,        // declination, written N 9°32.4' / S 9°32.4'
    LAT,        // latitude, N/S
    LON,        // longitude, E/W
    MINUTES,    // a correction in arc-minutes, signed: -3.1'
    BEARING,    // true bearing, written 078.4°
    NM,         // intercept, nautical miles, + toward / - away
}

fun Field.kind(): FieldKind = when (this) {
    Field.GHA_HOUR, Field.INCREMENT, Field.SHA, Field.GHA, Field.LHA -> FieldKind.ANGLE
    Field.HA, Field.HO, Field.HC -> FieldKind.ALTITUDE
    Field.DEC_HOUR, Field.DEC -> FieldKind.DEC
    Field.AP_LAT -> FieldKind.LAT
    Field.AP_LON -> FieldKind.LON
    Field.ZN -> FieldKind.BEARING
    Field.INTERCEPT -> FieldKind.NM
    else -> FieldKind.MINUTES
}

/** One sight in the workbook: a body, the shots of it (averaged) and, at ADVANCED level, what the navigator typed. */
data class WorkbookSight(
    val id: Long,
    val body: SkyBody,
    val limb: Limb = defaultLimb(body),
    val shots: List<Shot> = emptyList(),
    val entries: Map<Field, Double> = emptyMap(),
    val condition: SightCondition = SightCondition.MODERATE,
)

/**
 * How hard the sight was to take, which sets how much it can be trusted. Two parts (arc-minutes = nm):
 *  - scatter: the random error of one shot, which averaging several shots reduces (Starpath gives about 0.2′ ideal,
 *    0.4′ good, 1–2′ poor for experienced observers; these allow a little more for a small boat);
 *  - fixed: errors every shot of the sight shares, which averaging does not reduce (dip and refraction differing
 *    from the tables, the clock, index error). These are judgement values, not published figures.
 * A sight's error is √(scatter² ÷ shots kept + fixed²). BEGINNER sights are always MODERATE.
 */
enum class SightCondition(val title: String, val description: String, val scatterMin: Double, val fixedMin: Double) {
    EASY("Easy", "Calm, steady deck, sharp horizon", 0.5, 0.8),
    MODERATE("Moderate", "Some motion, fair horizon", 1.0, 1.5),
    HARD("Hard", "Rolling hard, rain or spray, hazy or dim horizon, or the body low (under 15°)", 2.5, 3.0);

    /** Error of the averaged sight (arc-minutes) from `shots` shots. */
    fun sightErrorMin(shots: Int): Double = sqrt(scatterMin * scatterMin / shots.coerceAtLeast(1) + fixedMin * fixedMin)
}

fun defaultLimb(b: SkyBody): Limb = if (b == SkyBody.Sun || b == SkyBody.Moon) Limb.LOWER else Limb.CENTER

/** Settings that apply to every sight. */
data class WorkbookSettings(
    val level: SkillLevel = SkillLevel.BEGINNER,
    val heightOfEyeFt: Double = DEFAULT_HEIGHT_OF_EYE_FT,
    val indexErrorMin: Double = 0.0,     // + "on the arc" (reads high)
    val temperatureC: Double = 10.0,
    val pressureMb: Double = 1010.0,
)

/** Standing on deck: the eye about 9 ft above the water. */
const val DEFAULT_HEIGHT_OF_EYE_FT = 9.0

/** Where the navigator might stand; the beginner picks one instead of typing a height. */
enum class EyePosition(val title: String, val heightFt: Double) {
    SITTING_COCKPIT("Sitting in the cockpit", 6.0),
    STANDING_DECK("Standing on deck", 9.0),
    STANDING_CABIN_TOP("Standing on the cabin top", 11.0),
}

enum class Check { AUTO, EMPTY, OK, WRONG }

/**
 * One line of the working.
 * @param value    the value used from here on (ADVANCED: what the navigator typed, if anything)
 * @param expected the right value given the earlier lines (ADVANCED: the navigator's own earlier lines)
 * @param formula  the equation, e.g. "Ho = Ha + R + PA + SD"
 * @param worked   the equation with numbers, e.g. "= 34°18.6' − 1.4' + 0.1' + 16.1'"
 */
data class WorkLine(
    val field: Field,
    val label: String,
    val value: Double?,
    val expected: Double,
    val formula: String,
    val worked: String,
    val check: Check,
    val hint: String = "",
)

/** A text line of the printed-almanac extract a value came from; highlighted lines hold the numbers used. */
data class ExtractLine(val text: String, val highlight: Boolean = false)

/** A sight worked through. lop is null until there's a shot (or, ADVANCED, until the working is complete). */
data class SightWork(
    val sight: WorkbookSight,
    val shot: Shot?,                       // the averaged shot (time, Hs, DR)
    val shotsUsed: Int,                    // after dropping any off the trend
    val lines: List<WorkLine>,
    val extract: List<ExtractLine>,
    val lop: WorkLop?,
) {
    fun line(f: Field) = lines.firstOrNull { it.field == f }
    val errors: Int get() = lines.count { it.check == Check.WRONG }
}

/** A line of position from the workbook: from the assumed position, intercept along Zn, at right angles. */
data class WorkLop(
    val bodyName: String,
    val timeMs: Long,
    val assumed: LatLon,
    val znDeg: Double,
    val interceptNm: Double,
    val dr: LatLon,                        // boat position at the sight (for advancing to the fix time)
    val drIsGps: Boolean = true,
    val sigmaNm: Double = SightCondition.MODERATE.sightErrorMin(3),   // how far this line may be off (1 standard deviation)
    val condition: SightCondition = SightCondition.MODERATE,
    val shotsUsed: Int = 1,
) {
    val footPoint: LatLon get() = travel(assumed, znDeg, interceptNm)
    fun segment(halfLengthNm: Double = 10.0): Pair<LatLon, LatLon> =
        Pair(travel(footPoint, znDeg - 90.0, halfLengthNm), travel(footPoint, znDeg + 90.0, halfLengthNm))
}

/** The fix from the workbook's lines of position, advanced to the time of the last sight. */
data class WorkFix(
    val position: LatLon,
    val timeMs: Long,
    val lops: List<WorkLop>,               // as advanced to the fix time
    val residualNm: Double,                // size of the "cocked hat"
    val drAtFix: LatLon,                   // boat position (GPS or DR) at the fix time
    val drIsGps: Boolean = true,
    val uncertainty: FixUncertainty? = null,
) {
    /** How far the fix is from where the boat really was (GPS), or from the DR. */
    val offsetNm: Double get() = distanceNm(position, drAtFix)
    /** True bearing from the GPS / DR position to the fix. */
    val offsetBearing: Double get() = bearing(drAtFix, position)
}

/**
 * How sure the fix is: a circle that should hold the true position about 95% of the time (2 drms).
 * Each line counts by how trustworthy its sight was (SightCondition and the number of shots); lines crossing at wide
 * angles pin the position better than nearly parallel ones. With 3 or more lines, if they disagree with each other
 * more than their conditions suggest, the circle is enlarged by that factor (`enlarged`).
 */
data class FixUncertainty(
    val radius95Nm: Double,
    val enlarged: Double?,                  // > 1 when the lines' own spread made the circle bigger; null if not
    val lines: List<WorkLop>,               // each with its sigmaNm, condition and shots used
)

object Workbook {

    private const val HOUR = 3_600_000L
    private const val MOON_INCREMENT_DEG_PER_H = 14.0 + 19.0 / 60.0   // the Moon's column in the increments table
    private const val ARIES_DEG_PER_H = 15.0410686

    // Tolerances for ADVANCED checks
    private const val TOL_MIN = 0.15        // table look-ups and sums (arc-minutes): allows the last-digit rounding
    private const val TOL_CORR = 0.25       // altitude corrections read from tables
    private const val TOL_HC = 0.35         // calculated altitude
    private const val TOL_ZN = 0.35         // azimuth (degrees)
    private const val TOL_NM = 0.15         // intercept

    /** Round degrees to 0.1' (as printed). */
    fun r1(deg: Double): Double = (deg * 600.0).roundToLong() / 600.0
    /** Round arc-minutes to 0.1'. */
    fun m1(min: Double): Double = (min * 10.0).roundToLong() / 10.0

    // ---------------------------------------------------------------- working one sight

    /** Work a sight through. `average` folds several shots into one (trend fit, outliers dropped). */
    fun work(s: WorkbookSight, set: WorkbookSettings): SightWork {
        val valid = s.shots.filter { !it.hsDeg.isNaN() }                 // shots with an altitude typed in
        val shot = averageShots(valid)
        if (shot == null) return SightWork(s, null, 0, emptyList(), emptyList(), null)
        val used = usedShots(valid)
        val adv = set.level == SkillLevel.ADVANCED
        val lines = mutableListOf<WorkLine>()
        // u(f): the value later lines use. ADVANCED: what was typed (if typed), else the right value.
        val typed = if (adv) s.entries else emptyMap()
        fun add(f: Field, label: String, expected: Double, tol: Double, formula: String, worked: String, hint: String = ""): Double {
            val t = typed[f]
            val check = when {
                !adv -> Check.AUTO
                t == null -> Check.EMPTY
                close(f, t, expected, tol) -> Check.OK
                else -> Check.WRONG
            }
            val v = t ?: expected
            val clean = worked.replace("+ −", "− ").replace("+ +", "+ ")          // "+ −1.5′" reads "− 1.5′"
            lines += WorkLine(f, label, if (adv) t else expected, expected, formula, clean, check, hint)
            return v
        }

        val t = shot.timeMs
        val hourMs = floor(t.toDouble() / HOUR).toLong() * HOUR
        val dtH = (t - hourMs) / HOUR.toDouble()
        val mmss = mmss(t - hourMs)
        val body = s.body
        val now = Almanac.entry(body, t)
        val extract = mutableListOf<ExtractLine>()

        // ---- GHA and declination by the printed-almanac method
        val isStar = body is SkyBody.Star
        val ghaH: Double; val ghaH1: Double; val incr: Double
        if (isStar) {
            ghaH = r1(Almanac.ghaAries(hourMs)); ghaH1 = r1(Almanac.ghaAries(hourMs + HOUR))
            incr = r1(ARIES_DEG_PER_H * dtH)
        } else {
            ghaH = r1(Almanac.entry(body, hourMs).gha); ghaH1 = r1(Almanac.entry(body, hourMs + HOUR).gha)
            incr = r1((if (body == SkyBody.Moon) MOON_INCREMENT_DEG_PER_H else 15.0) * dtH)
        }
        val ghaName = if (isStar) "GHA ♈" else "GHA"
        val uGhaH = add(Field.GHA_HOUR, "$ghaName at ${hh(hourMs)}h", ghaH, TOL_MIN,
            "$ghaName (${hh(hourMs)}h) from the daily page", "= ${ang(ghaH)}")
        val uIncr = add(Field.INCREMENT, "Increment $mmss", incr, TOL_MIN,
            "Increment for $mmss (${if (isStar) "Aries" else if (body == SkyBody.Moon) "Moon" else "Sun/planets"} column)", "= ${ang(incr)}")

        var uVc = 0.0
        val hasV = body == SkyBody.Moon || body is SkyBody.Planet
        if (hasV) {
            val step = if (body == SkyBody.Moon) MOON_INCREMENT_DEG_PER_H else 15.0
            val v = m1((norm360(ghaH1 - ghaH) - step) * 60.0)
            val uV = add(Field.V, "v", v, TOL_MIN, "v: extra GHA per hour, from the daily page", "= ${mins(v)}")
            uVc = add(Field.V_CORR, "v correction", m1(uV * dtH), TOL_MIN, "v corr = v × minutes past the hour ÷ 60",
                "= ${mins(uV)} × ${minutesPast(dtH)} ÷ 60")
        }
        var uSha = 0.0
        if (isStar) {
            val noon = floor(t.toDouble() / (24 * HOUR)).toLong() * 24 * HOUR + 12 * HOUR
            val sha = r1(Almanac.entry(body, noon).sha ?: 0.0)
            uSha = add(Field.SHA, "SHA ${body.name}", sha, TOL_MIN, "SHA from the star list", "= ${ang(sha)}")
        }
        val ghaExp = r1(norm360(uGhaH + uIncr + uVc / 60.0 + uSha))
        val ghaWorked = buildString {
            append("= ${ang(uGhaH)} + ${ang(uIncr)}")
            if (hasV) append(" + ${mins(uVc)}")
            if (isStar) append(" + ${ang(uSha)}")
            append(" = ${ang(ghaExp)}")
        }
        val ghaFormula = when {
            isStar -> "GHA = GHA ♈ + increment + SHA"
            hasV -> "GHA = GHA (hour) + increment + v corr"
            else -> "GHA = GHA (hour) + increment"
        }
        val uGha = add(Field.GHA, "GHA ${body.name}", ghaExp, TOL_MIN, ghaFormula, ghaWorked, "Subtract 360° if over 360°")

        val uDec: Double
        if (isStar) {
            val noon = floor(t.toDouble() / (24 * HOUR)).toLong() * 24 * HOUR + 12 * HOUR
            val dec = r1(Almanac.entry(body, noon).dec)
            uDec = add(Field.DEC, "Dec ${body.name}", dec, TOL_MIN, "Dec from the star list", "= ${decText(dec)}")
        } else {
            val decH = r1(Almanac.entry(body, hourMs).dec); val decH1 = r1(Almanac.entry(body, hourMs + HOUR).dec)
            val uDecH = add(Field.DEC_HOUR, "Dec at ${hh(hourMs)}h", decH, TOL_MIN, "Dec (${hh(hourMs)}h) from the daily page", "= ${decText(decH)}")
            val d = m1((decH1 - decH) * 60.0)
            val uD = add(Field.D, "d", d, TOL_MIN, "d: change in Dec per hour (+ if moving north, − if south)", "= ${mins(d)}")
            val uDc = add(Field.D_CORR, "d correction", m1(uD * dtH), TOL_MIN, "d corr = d × minutes past the hour ÷ 60",
                "= ${mins(uD)} × ${minutesPast(dtH)} ÷ 60")
            val decExp = r1(uDecH + uDc / 60.0)
            uDec = add(Field.DEC, "Dec ${body.name}", decExp, TOL_MIN, "Dec = Dec (hour) + d corr",
                "= ${decText(uDecH)} + ${mins(uDc)} = ${decText(decExp)}")
        }

        // printed-almanac extract (the rows the numbers came from)
        extract += ExtractLine("${body.name} · ${dayText(hourMs)} UTC")
        if (isStar) {
            extract += ExtractLine("hour   GHA ♈")
            extract += ExtractLine("%2dh   %s".format(hhInt(hourMs), ang(ghaH)), true)
            extract += ExtractLine("%2dh   %s".format(hhInt(hourMs + HOUR), ang(ghaH1)))
            extract += ExtractLine("Increments $mmss, Aries column: ${ang(incr)}", true)
            extract += ExtractLine("Star list: ${body.name}  SHA ${ang(uShaOr(isStar, body, t))}  Dec ${decText(uDec)}", true)
        } else {
            val decH = r1(Almanac.entry(body, hourMs).dec); val decH1 = r1(Almanac.entry(body, hourMs + HOUR).dec)
            extract += ExtractLine("hour   GHA           Dec")
            extract += ExtractLine("%2dh   %-12s  %s".format(hhInt(hourMs), ang(ghaH), decText(decH)), true)
            extract += ExtractLine("%2dh   %-12s  %s".format(hhInt(hourMs + HOUR), ang(ghaH1), decText(decH1)))
            val col = if (body == SkyBody.Moon) "Moon" else "Sun/planets"
            extract += ExtractLine("Increments $mmss, $col column: ${ang(incr)}", true)
            if (hasV) extract += ExtractLine("v ${mins(m1((norm360(ghaH1 - ghaH) - (if (body == SkyBody.Moon) MOON_INCREMENT_DEG_PER_H else 15.0)) * 60.0))}   d ${mins(m1((decH1 - decH) * 60.0))}", true)
            else extract += ExtractLine("d ${mins(m1((decH1 - decH) * 60.0))}", true)
            if (body == SkyBody.Moon) extract += ExtractLine("HP ${"%.1f".format(now.hp)}'   SD ${"%.1f".format(now.sd)}'", true)
            if (body == SkyBody.Sun) extract += ExtractLine("SD ${"%.1f".format(now.sd)}'", true)
        }

        // ---- altitude corrections, Hs -> Ho (all signed, added)
        val hs = shot.hsDeg
        val ic = m1(-set.indexErrorMin)
        val uIc = add(Field.IC, "Index correction", ic, TOL_MIN, "IC = − index error (on the arc −, off the arc +)",
            "= ${mins(ic)}")
        val dip = m1(SightReduction.dipMin(set.heightOfEyeFt))
        val uDip = add(Field.DIP, "Dip", dip, TOL_CORR, "Dip = −0.97′ × √(height of eye in ft)",
            "= −0.97 × √${"%.0f".format(set.heightOfEyeFt)} = ${mins(dip)}")
        val haExp = r1(hs + (uIc + uDip) / 60.0)
        val uHa = add(Field.HA, "Ha (apparent altitude)", haExp, TOL_MIN, "Ha = Hs + IC + Dip",
            "= ${alt(hs)} + ${mins(uIc)} + ${mins(uDip)} = ${alt(haExp)}")
        val r = m1(-SightReduction.refractionMin(uHa, set.temperatureC, set.pressureMb))
        val uR = add(Field.REFRACTION, "Refraction", r, TOL_CORR, "R = −1′ / tan(Ha + 7.31 / (Ha + 4.4)), for temperature and pressure",
            "= ${mins(r)}")
        var uPa = 0.0
        if (body !is SkyBody.Star) {
            val pa = m1(now.hp * cos(uHa * DEG))
            uPa = add(Field.PARALLAX, "Parallax", pa, TOL_CORR, "PA = HP × cos Ha", "= ${"%.1f".format(now.hp)}′ × cos ${alt(uHa)} = ${mins(pa)}")
        }
        var uSd = 0.0
        if (body == SkyBody.Sun || body == SkyBody.Moon) {
            val base = if (body == SkyBody.Moon) now.sd * (1.0 + sin(uHa * DEG) * sin(now.hp / 60.0 * DEG)) else now.sd
            val sd = m1(when (s.limb) { Limb.LOWER -> base; Limb.UPPER -> -base; Limb.CENTER -> 0.0 })
            val which = when (s.limb) { Limb.LOWER -> "lower limb: +SD"; Limb.UPPER -> "upper limb: −SD"; Limb.CENTER -> "center: 0" }
            uSd = add(Field.SEMI_DIAMETER, "Semi-diameter", sd, TOL_CORR, "SD ($which)", "= ${mins(sd)}")
        }
        val hoExp = r1(uHa + (uR + uPa + uSd) / 60.0)
        val hoWorked = buildString {
            append("= ${alt(uHa)} + ${mins(uR)}")
            if (body !is SkyBody.Star) append(" + ${mins(uPa)}")
            if (body == SkyBody.Sun || body == SkyBody.Moon) append(" + ${mins(uSd)}")
            append(" = ${alt(hoExp)}")
        }
        val hoFormula = when {
            body is SkyBody.Star -> "Ho = Ha + R"
            body is SkyBody.Planet -> "Ho = Ha + R + PA"
            else -> "Ho = Ha + R + PA + SD"
        }
        val uHo = add(Field.HO, "Ho (observed altitude)", hoExp, TOL_MIN, hoFormula, hoWorked)

        // ---- assumed position, LHA, Hc, Zn, intercept
        val dr = shot.dr
        val apLat = Math.round(dr.lat).toDouble()
        val uApLat = add(Field.AP_LAT, "Assumed latitude", apLat, 0.001, "aLat = DR latitude to the nearest whole degree",
            "DR ${formatLat(dr.lat)} → ${latText(apLat)}")
        val f = norm360(uGha) - floor(norm360(uGha))                                    // GHA's fraction of a degree
        val apLon = r1(norm180(Math.round(dr.lon + f) - f))
        val uApLon = add(Field.AP_LON, "Assumed longitude", apLon, TOL_MIN,
            "aLon within 30′ of the DR (${formatLon(dr.lon)}) so that LHA comes out whole", "→ ${lonText(apLon)}")
        val lhaExp = r1(norm360(uGha + uApLon))
        val uLha = add(Field.LHA, "LHA", lhaExp, TOL_MIN, "LHA = GHA + aLon (east +, west −)",
            "= ${ang(uGha)} ${if (uApLon < 0) "−" else "+"} ${ang(abs(uApLon))} = ${ang(lhaExp)}")
        val (hcRaw, znRaw) = hcZn(uApLat, uDec, uLha)
        val hcExp = r1(hcRaw)
        val uHc = add(Field.HC, "Hc (calculated altitude)", hcExp, TOL_HC,
            "sin Hc = sin aLat sin Dec + cos aLat cos Dec cos LHA",
            "= sin ${latShort(uApLat)} sin ${decShort(uDec)} + cos ${latShort(uApLat)} cos ${decShort(uDec)} cos ${"%.0f".format(uLha)}° → ${alt(hcExp)}")
        val znExp = Math.round(znRaw * 10.0) / 10.0
        val uZn = add(Field.ZN, "Zn (true azimuth)", znExp, TOL_ZN,
            "tan Z = −sin LHA / (cos aLat tan Dec − sin aLat cos LHA); Zn = Z made 0–360°",
            "→ Zn = ${"%05.1f".format(znExp)}°")
        val aExp = Math.round((uHo - uHc) * 600.0) / 10.0
        val uA = add(Field.INTERCEPT, "Intercept", aExp, TOL_NM,
            "a = Ho − Hc (in minutes = nm; + toward the body, − away)",
            "= ${alt(uHo)} − ${alt(uHc)} = ${"%+.1f".format(aExp)} nm ${if (aExp >= 0) "toward" else "away"}")

        // ADVANCED: plot only once every line has been typed
        val complete = !adv || lines.all { it.value != null }
        val cond = if (set.level == SkillLevel.BEGINNER) SightCondition.MODERATE else s.condition
        val lop = if (complete) WorkLop(body.name, t, LatLon(uApLat, uApLon), uZn, uA, dr, shot.gps,
            cond.sightErrorMin(used), cond, used) else null
        return SightWork(s, shot, used, lines, extract, lop)
    }

    private fun uShaOr(isStar: Boolean, body: SkyBody, t: Long): Double {
        if (!isStar) return 0.0
        val noon = floor(t.toDouble() / (24 * HOUR)).toLong() * 24 * HOUR + 12 * HOUR
        return r1(Almanac.entry(body, noon).sha ?: 0.0)
    }

    /** Whether a typed value matches (angles compared around the circle, in arc-minutes). */
    private fun close(f: Field, typed: Double, expected: Double, tol: Double): Boolean = when (f.kind()) {
        FieldKind.ANGLE, FieldKind.LON -> abs(norm180(typed - expected)) * 60.0 <= tol + 1e-9
        FieldKind.DEC, FieldKind.LAT, FieldKind.ALTITUDE -> abs(typed - expected) * 60.0 <= tol + 1e-9
        FieldKind.BEARING -> abs(norm180(typed - expected)) <= tol + 1e-9
        FieldKind.MINUTES, FieldKind.NM -> abs(typed - expected) <= tol + 1e-9
    }

    /** Hc and Zn by the spherical formulas (degrees). */
    fun hcZn(lat: Double, dec: Double, lha: Double): Pair<Double, Double> {
        val l = lat * DEG; val d = dec * DEG; val t = lha * DEG
        val hc = asin((sin(l) * sin(d) + cos(l) * cos(d) * cos(t)).coerceIn(-1.0, 1.0)) * RAD
        val zn = norm360(atan2(-cos(d) * sin(t), sin(d) * cos(l) - cos(d) * sin(l) * cos(t)) * RAD)
        return Pair(hc, zn)
    }

    // ---------------------------------------------------------------- shots

    /** One averaged shot: mean time, mean Hs (shots off the trend dropped), DR of the shot nearest the mean time. */
    fun averageShots(shots: List<Shot>): Shot? {
        if (shots.isEmpty()) return null
        val kept = keptShots(shots)
        val t = kept.map { it.timeMs }.average().toLong()
        val hs = kept.map { it.hsDeg }.average()
        val near = kept.minBy { abs(it.timeMs - t) }
        return Shot(t, hs, near.dr, near.gps)
    }

    private fun usedShots(shots: List<Shot>) = keptShots(shots).size

    /** Shots kept after a straight-line fit of Hs against time drops any more than 3' off the line (3 or more shots). */
    fun keptShots(shots: List<Shot>, rejectMin: Double = 3.0): List<Shot> {
        if (shots.size < 3) return shots
        val t0 = shots.first().timeMs
        val tx = shots.map { (it.timeMs - t0) / 60_000.0 }; val hy = shots.map { it.hsDeg }
        val mt = tx.average(); val mh = hy.average()
        val sxx = tx.sumOf { (it - mt) * (it - mt) }
        val slope = if (sxx > 1e-9) tx.indices.sumOf { (tx[it] - mt) * (hy[it] - mh) } / sxx else 0.0
        val kept = shots.filterIndexed { i, s -> abs((s.hsDeg - (mh + slope * (tx[i] - mt))) * 60.0) <= rejectMin }
        return kept.ifEmpty { shots }
    }

    /**
     * For each shot, how far (arc-minutes, + above) it is from the straight line through the shots that are kept;
     * null with fewer than 3 shots. Used to explain a dropped shot.
     */
    fun residuals(shots: List<Shot>): List<Double?> {
        val kept = keptShots(shots)
        if (shots.size < 3 || kept.size < 2) return shots.map { null }
        val t0 = kept.first().timeMs
        val tx = kept.map { (it.timeMs - t0) / 60_000.0 }; val hy = kept.map { it.hsDeg }
        val mt = tx.average(); val mh = hy.average()
        val sxx = tx.sumOf { (it - mt) * (it - mt) }
        val slope = if (sxx > 1e-9) tx.indices.sumOf { (tx[it] - mt) * (hy[it] - mh) } / sxx else 0.0
        return shots.map { s -> (s.hsDeg - (mh + slope * ((s.timeMs - t0) / 60_000.0 - mt))) * 60.0 }
    }

    /** One sentence saying why a shot was dropped, from its distance off the trend (arc-minutes, + above). */
    fun dropNote(residualMin: Double): String =
        if (residualMin > 0) "%.1f′ higher than the line through your other shots, likely a misread drum or the body held above the horizon, so it isn't used.".format(residualMin)
        else "%.1f′ lower than the line through your other shots, likely a misread drum or the body dipped below the horizon, so it isn't used.".format(-residualMin)

    /**
     * A typed time of day (local), "19:46:12", "19:46" or "194612", as UTC ms on the day of `nearMs`, taking the
     * day before if that puts it more than 12 hours in the future. Null if it can't be read.
     */
    fun parseTimeOfDay(text: String, nearMs: Long, utcOffsetMs: Long): Long? {
        val t = text.trim()
        val parts = if (t.contains(':')) t.split(':') else when (t.length) {
            6 -> listOf(t.substring(0, 2), t.substring(2, 4), t.substring(4, 6))
            4 -> listOf(t.substring(0, 2), t.substring(2, 4))
            else -> return null
        }
        if (parts.size !in 2..3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val sec = if (parts.size == 3) parts[2].toDoubleOrNull() ?: return null else 0.0
        if (h !in 0..23 || m !in 0..59 || sec < 0 || sec >= 60) return null
        val day = 86_400_000L
        val localNear = nearMs + utcOffsetMs
        val midnight = localNear - Math.floorMod(localNear, day)
        var local = midnight + h * 3_600_000L + m * 60_000L + (sec * 1000).toLong()
        if (local - localNear > day / 2) local -= day
        return local - utcOffsetMs
    }

    // ---------------------------------------------------------------- fix

    /**
     * Fix from two or more lines of position. Earlier lines are advanced to the time of the last sight by the boat's
     * run between them (the change in its GPS / DR position), then crossed by least squares.
     * Returns null with fewer than two lines or when they're too close to parallel.
     */
    fun fix(lops: List<WorkLop>): WorkFix? {
        if (lops.size < 2) return null
        val last = lops.maxBy { it.timeMs }
        val adv = lops.map { l ->
            val run = distanceNm(l.dr, last.dr)
            if (run < 0.01) l else l.copy(assumed = travel(l.assumed, bearing(l.dr, last.dr), run))
        }
        val ref = last.dr
        // nearly parallel lines can't make a fix (checked on the bare geometry)
        var g11 = 0.0; var g12 = 0.0; var g22 = 0.0
        for (l in adv) { val nx = sin(l.znDeg * DEG); val ny = cos(l.znDeg * DEG); g11 += nx * nx; g12 += nx * ny; g22 += ny * ny }
        if (g11 * g22 - g12 * g12 < 0.03 * adv.size * adv.size) return null
        // weighted least squares: each line counts by 1 / σ² (its sight's condition and shots)
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        val rows = adv.map { l ->
            val nx = sin(l.znDeg * DEG); val ny = cos(l.znDeg * DEG)
            val ex = norm180(l.assumed.lon - ref.lon) * 60.0 * cos(ref.lat * DEG); val ny0 = (l.assumed.lat - ref.lat) * 60.0
            doubleArrayOf(nx, ny, l.interceptNm + nx * ex + ny * ny0, 1.0 / (l.sigmaNm * l.sigmaNm))
        }
        for (r in rows) { val (nx, ny, c, w) = r.toList(); a11 += w * nx * nx; a12 += w * nx * ny; a22 += w * ny * ny; b1 += w * nx * c; b2 += w * ny * c }
        val det = a11 * a22 - a12 * a12
        val x = (b1 * a22 - b2 * a12) / det
        val y = (a11 * b2 - a12 * b1) / det
        val miss = rows.map { r -> r[0] * x + r[1] * y - r[2] }
        val rms = sqrt(miss.sumOf { it * it } / rows.size)
        // uncertainty: covariance (AᵀWA)⁻¹; enlarged if the lines disagree more than their conditions suggest
        val n = rows.size
        val chi = if (n >= 3) sqrt(rows.indices.sumOf { rows[it][3] * miss[it] * miss[it] } / (n - 2)) else null
        val scale = chi?.takeIf { it > 1.0 }
        val drms = sqrt((a11 + a22) / det) * (scale ?: 1.0)
        val unc = FixUncertainty(2.0 * drms, scale, adv)
        val pos = LatLon(ref.lat + y / 60.0, norm180(ref.lon + x / 60.0 / cos(ref.lat * DEG)))
        return WorkFix(pos, last.timeMs, adv, rms, last.dr, last.drIsGps, unc)
    }

    // ---------------------------------------------------------------- typing values (ADVANCED)

    /**
     * Read a typed value for a line. Accepts degrees and minutes ("223 14.2", "223°14.2'"), decimal degrees
     * ("223.237"), hemisphere letters for Dec / latitude / longitude ("S 9 32.4", "86 12.0W", "-9 32.4"),
     * and plain signed numbers for corrections ("-3.1"), bearings ("78.4") and intercepts ("+4.2", "4.2A").
     * Returns degrees for angles, arc-minutes for corrections, nm for the intercept; null if it can't be read.
     */
    fun parse(text: String, kind: FieldKind): Double? {
        var s = text.trim().uppercase().replace("°", " ").replace("'", " ").replace("′", " ").replace(",", ".")
            .replace("−", "-")
        if (s.isEmpty()) return null
        var sign = 1.0
        when (kind) {
            FieldKind.DEC, FieldKind.LAT -> {
                if (s.contains('S')) sign = -1.0
                s = s.replace("N", " ").replace("S", " ")
            }
            FieldKind.LON -> {
                if (s.contains('W')) sign = -1.0
                s = s.replace("E", " ").replace("W", " ")
            }
            FieldKind.NM -> {
                if (s.endsWith("A")) sign = -1.0
                s = s.removeSuffix("A").removeSuffix("T")
            }
            else -> {}
        }
        val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return when (kind) {
            FieldKind.MINUTES, FieldKind.NM, FieldKind.BEARING -> parts.singleOrNull()?.toDoubleOrNull()?.let { sign * it }
            else -> {
                val d = parts[0].toDoubleOrNull() ?: return null
                val m = if (parts.size > 1) parts[1].toDoubleOrNull() ?: return null else 0.0
                if (parts.size > 2 || m < 0 || m >= 60) return null
                val neg = d < 0 || parts[0].startsWith("-")
                val v = abs(d) + m / 60.0
                sign * (if (neg) -v else v)
            }
        }
    }

    /** A value written the way the workbook shows it. */
    fun format(v: Double, kind: FieldKind): String = when (kind) {
        FieldKind.ANGLE -> ang(v)
        FieldKind.ALTITUDE -> alt(v)
        FieldKind.DEC -> decText(v)
        FieldKind.LAT -> latText(v)
        FieldKind.LON -> lonText(v)
        FieldKind.MINUTES -> mins(v)
        FieldKind.BEARING -> "%05.1f°".format(norm360(v))
        FieldKind.NM -> "%+.1f nm".format(v)
    }

    // ---------------------------------------------------------------- text helpers

    fun ang(deg: Double): String {
        val a = norm360(r1(deg)); var d = floor(a).toInt(); var m = (a - d) * 60.0
        if (m >= 59.95) { d = (d + 1) % 360; m = 0.0 }
        return "%d°%04.1f′".format(d, m)
    }
    fun alt(deg: Double): String = (if (r1(deg) < 0) "−" else "") + dmAbs(deg)
    fun decText(deg: Double): String = (if (deg < 0) "S " else "N ") + dmAbs(deg)
    fun latText(deg: Double): String = (if (deg < 0) "S " else "N ") + dmAbs(deg)
    fun lonText(deg: Double): String = (if (deg < 0) "W " else "E ") + dmAbs(deg)
    fun mins(m: Double): String = (if (m < 0) "−" else "+") + "%.1f′".format(abs(m))
    private fun dmAbs(deg: Double): String {
        val a = abs(r1(deg)); var d = floor(a).toInt(); var m = (a - d) * 60.0
        if (m >= 59.95) { d += 1; m = 0.0 }
        return "%d°%04.1f′".format(d, m)
    }
    private fun latShort(deg: Double) = "%.0f°".format(deg)
    private fun decShort(deg: Double) = "%.1f°".format(deg)
    private fun hhInt(ms: Long) = ((ms / HOUR) % 24).toInt()
    private fun hh(ms: Long) = "%02d".format(hhInt(ms))
    private fun mmss(ms: Long): String { val s = ms / 1000; return "%dm %02ds".format(s / 60, s % 60) }
    private fun minutesPast(dtH: Double) = "%.1f".format(dtH * 60.0)
    private fun dayText(ms: Long): String {
        val days = ms / (24 * HOUR)
        // civil date from days since 1970-01-01 (Howard Hinnant's algorithm), no java.time needed
        val z = days + 719468; val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097; val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400; val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1; val m = if (mp < 10) mp + 3 else mp - 9
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "%d %s %d · %02dh".format(d, months[(m - 1).toInt()], if (m <= 2) y + 1 else y, hhInt(ms))
    }
}
