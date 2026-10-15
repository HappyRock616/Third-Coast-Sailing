package com.thirdcoastsailing.app.celestial

import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.searchAltitude
import io.github.cosinekitty.astronomy.searchHourAngle
import io.github.cosinekitty.astronomy.searchRiseSet
import kotlin.math.abs
import kotlin.math.min

/*
 * Sight planning: what to shoot and when, for a position and day. The rules follow Bowditch, The American
 * Practical Navigator (2017), chapter 18 "Sight Planning":
 *
 *  - Stars and planets: only in twilight, when both the stars and a sharp horizon are visible. The brightest
 *    bodies can be taken in civil twilight (Sun 0-6° below the horizon), the rest in nautical twilight (6-12°).
 *    Plan for the middle of the window and pre-compute 8-10 candidates in case of cloud.
 *  - The horizon stays sharp longest toward where the Sun set (or rose): in the evening take bodies far from the
 *    sunset bearing first and those near it last; in the morning the reverse.
 *  - Altitudes between about 15° (refraction gets large and uncertain below) and 75° (hard to swing the arc,
 *    and the straight-line LOP assumption fails near the zenith); similar altitudes for all bodies of one fix.
 *  - Three bodies about 120° apart in azimuth (errors in Ho then cancel); two bodies at least 30° apart.
 *  - Sun: a morning sight, the noon sight at local apparent noon (latitude), and an afternoon sight;
 *    sights at least 2 hours apart give a good running fix when the azimuth has changed enough.
 *  - Moon: good for a daytime fix with the Sun around first and last quarter (age 6-8 and 21-23 days), when it's
 *    roughly 90° in azimuth from the Sun; avoid relying on it near full Moon.
 *
 * Times are UTC ms; the caller formats them in local time.
 */

/** Sunrise, sunset and twilight times for one day at a position (null when the event doesn't happen). */
data class Twilight(
    val nauticalDawn: Long?, val civilDawn: Long?, val sunrise: Long?,
    val localApparentNoon: Long?, val sunset: Long?, val civilDusk: Long?, val nauticalDusk: Long?,
)

/** A body worth shooting at a given time, with where to point the sextant. */
data class Target(
    val body: SkyBody,
    val timeMs: Long,
    val altitudeDeg: Double,        // preset the sextant to about this (Hc)
    val azimuthDeg: Double,         // face this true bearing (Zn)
    val mag: Double,
)

/** Twilight star session: the window, the best three, backups and a suggested order. */
data class StarSession(
    val evening: Boolean,
    val windowStart: Long,          // evening: civil dusk (Sun 6° down, brightest stars out); morning: nautical dawn (12° down)
    val windowEnd: Long,            // evening: nautical dusk (horizon fading); morning: civil dawn (stars fading)
    val planTime: Long,             // altitudes and azimuths below are for this moment
    val picks: List<Target>,        // best three (or two), in suggested shooting order
    val backups: List<Target>,      // other good candidates, brightest first
    val spreadDeg: Double,          // smallest azimuth gap between the picks (120 = ideal for three)
)

/** Daytime Sun sights for a running fix plus the noon latitude. */
data class SunPlan(
    val morning: Target?,
    val noon: Target?,              // local apparent noon (Sun on the meridian): altitude gives latitude
    val afternoon: Target?,
    val azimuthChangeDeg: Double?,  // morning to afternoon; under ~30° the running fix is weak
)

/** A daytime Sun + Moon fix opportunity. */
data class MoonPlan(
    val moon: Target,
    val sun: Target,
    val azimuthDifferenceDeg: Double,
    val ageDays: Double,
    val illuminationPct: Double,
)

/** Everything planned for a day. */
data class SightPlan(
    val position: LatLon,
    val twilight: Twilight,
    val morningStars: StarSession?,
    val eveningStars: StarSession?,
    val sun: SunPlan,
    val moon: MoonPlan?,
    val notes: List<String>,
)

object SightPlanner {

    const val MIN_ALT = 15.0
    const val MAX_ALT = 75.0
    private const val HOUR = 3_600_000L

    /**
     * Plan for the day containing `dayStartMs` (start of the local day, UTC ms) at `pos`.
     * @param heightOfEyeFt used for the horizon distance note
     */
    fun plan(pos: LatLon, dayStartMs: Long, heightOfEyeFt: Double = 8.0): SightPlan {
        val tw = twilight(pos, dayStartMs)
        val notes = mutableListOf<String>()
        val morning = tw.nauticalDawn?.let { nd -> tw.civilDawn?.let { cd -> starSession(pos, nd, cd, false, tw) } }
        val evening = tw.civilDusk?.let { cd -> tw.nauticalDusk?.let { nd -> starSession(pos, cd, nd, true, tw) } }
        val sun = sunPlan(pos, tw)
        val moon = moonPlan(pos, tw)
        val horizon = SightReduction.horizonDistanceNm(heightOfEyeFt)
        notes += "Your sea horizon is about %.1f nm away. If the far shore is closer than that in the direction you're shooting, use the dip-short correction or choose another body."
            .format(horizon)
        if (sun.azimuthChangeDeg != null && sun.azimuthChangeDeg < 30.0)
            notes += "The Sun's bearing changes less than 30° between the morning and afternoon sights today, so a Sun-only running fix will be weak. Add a Moon or Venus sight if you can."
        val age = Almanac.moonAgeDays(dayStartMs + 12 * HOUR)
        if (age > 12.5 && age < 17.0) notes += "Near full Moon: Moon and Sun are almost opposite, so their lines cross poorly. Use the Moon only together with other bodies."
        return SightPlan(pos, tw, morning, evening, sun, moon, notes)
    }

    // ---------------------------------------------------------------- twilight

    fun twilight(pos: LatLon, dayStartMs: Long): Twilight {
        val obs = Observer(pos.lat, pos.lon, 0.0)
        val t0 = Almanac.time(dayStartMs)
        fun alt(dir: Direction, deg: Double) = runCatching { searchAltitude(Body.Sun, obs, dir, t0, 1.0, deg)?.toMillisecondsSince1970() }.getOrNull()
        fun rs(dir: Direction) = runCatching { searchRiseSet(Body.Sun, obs, dir, t0, 1.0)?.toMillisecondsSince1970() }.getOrNull()
        val lan = runCatching { searchHourAngle(Body.Sun, obs, 0.0, t0, +1).time.toMillisecondsSince1970() }.getOrNull()
            ?.takeIf { it < dayStartMs + 24 * HOUR }
        return Twilight(
            nauticalDawn = alt(Direction.Rise, -12.0), civilDawn = alt(Direction.Rise, -6.0), sunrise = rs(Direction.Rise),
            localApparentNoon = lan,
            sunset = rs(Direction.Set), civilDusk = alt(Direction.Set, -6.0), nauticalDusk = alt(Direction.Set, -12.0),
        )
    }

    // ---------------------------------------------------------------- twilight stars

    /** Bodies visible for star sights at `ms`: navigational stars plus bright planets, within the altitude limits. */
    fun candidates(pos: LatLon, ms: Long, maxMag: Double = 2.6): List<Target> {
        val out = mutableListOf<Target>()
        for (b in SkyBody.stars + SkyBody.planets) {
            val e = Almanac.entry(b, ms)
            if (e.mag > maxMag) continue
            val h = SightReduction.hcZn(pos, e.gha, e.dec)
            if (h.hcDeg < MIN_ALT || h.hcDeg > MAX_ALT) continue
            out += Target(b, ms, h.hcDeg, h.znDeg, e.mag)
        }
        return out
    }

    private fun starSession(pos: LatLon, start: Long, end: Long, evening: Boolean, tw: Twilight): StarSession? {
        val a = min(start, end); val b = maxOf(start, end)
        val plan = (a + b) / 2                                   // Sun about 9° below the horizon
        val cands = candidates(pos, plan)
        if (cands.size < 2) return null
        val best = bestSet(cands) ?: return null
        // horizon stays sharp longest toward the Sun: evening -> far from the sunset bearing first; morning -> near first
        val sunAz = SightReduction.altAz(SkyBody.Sun, pos, plan).znDeg
        val ordered = best.first.sortedBy { t ->
            val sep = abs(norm180(t.azimuthDeg - sunAz))
            if (evening) -sep else sep
        }
        val backups = cands.filter { c -> best.first.none { it.body == c.body } }
            .sortedWith(compareBy({ it.mag }, { abs(it.altitudeDeg - 45.0) })).take(6)
        return StarSession(evening, a, b, plan, ordered, backups, best.second)
    }

    /**
     * Pick the best three bodies (two if only two are up): azimuths as evenly spread as possible (120° ideal),
     * similar altitudes, bright. Returns the set and its smallest azimuth gap.
     */
    internal fun bestSet(c: List<Target>): Pair<List<Target>, Double>? {
        if (c.size < 2) return null
        var best: List<Target>? = null; var bestScore = -1.0; var bestGap = 0.0
        val n = c.size
        if (n == 2) {
            val gap = abs(norm180(c[0].azimuthDeg - c[1].azimuthDeg))
            return if (gap >= 30.0) Pair(c, gap) else null
        }
        for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) {
            val set = listOf(c[i], c[j], c[k])
            val az = set.map { it.azimuthDeg }.sorted()
            val gaps = listOf(az[1] - az[0], az[2] - az[1], 360.0 - az[2] + az[0])
            val minGap = gaps.min()
            val spread = minGap / 120.0
            val altRange = set.maxOf { it.altitudeDeg } - set.minOf { it.altitudeDeg }
            val altScore = (1.0 - altRange / 50.0).coerceIn(0.0, 1.0)
            val altMid = set.sumOf { 1.0 - (abs(it.altitudeDeg - 40.0) / 35.0).coerceIn(0.0, 1.0) } / 3.0
            val bright = set.sumOf { ((2.6 - it.mag) / 4.0).coerceIn(0.0, 1.0) } / 3.0
            val score = 0.55 * spread + 0.15 * altScore + 0.1 * altMid + 0.2 * bright
            if (score > bestScore) { bestScore = score; best = set; bestGap = minGap }
        }
        return best?.let { Pair(it, bestGap) }
    }

    // ---------------------------------------------------------------- Sun

    private fun sunPlan(pos: LatLon, tw: Twilight): SunPlan {
        val lan = tw.localApparentNoon ?: return SunPlan(null, null, null, null)
        fun at(ms: Long): Target? {
            val e = Almanac.entry(SkyBody.Sun, ms)
            val h = SightReduction.hcZn(pos, e.gha, e.dec)
            return if (h.hcDeg >= MIN_ALT) Target(SkyBody.Sun, ms, h.hcDeg, h.znDeg, e.mag) else null
        }
        // Morning / afternoon: about 3 h from noon; if the Sun is still too low then, the first time it's high enough
        fun side(sign: Int): Target? {
            at(lan + sign * 3 * HOUR)?.let { return it }
            var ms = lan + sign * 3 * HOUR
            while (abs(ms - lan) > 2 * HOUR) { ms -= sign * 10 * 60_000L; at(ms)?.let { return it } }
            return null
        }
        val noon = at(lan)
        val am = side(-1); val pm = side(+1)
        val change = if (am != null && pm != null) abs(norm180(pm.azimuthDeg - am.azimuthDeg)) else null
        return SunPlan(am, noon, pm, change)
    }

    // ---------------------------------------------------------------- Moon

    private fun moonPlan(pos: LatLon, tw: Twilight): MoonPlan? {
        val rise = tw.sunrise ?: return null; val set = tw.sunset ?: return null
        var best: MoonPlan? = null; var bestScore = Double.MAX_VALUE
        var ms = rise
        while (ms <= set) {
            val s = SightReduction.altAz(SkyBody.Sun, pos, ms)
            val m = SightReduction.altAz(SkyBody.Moon, pos, ms)
            if (s.hcDeg in MIN_ALT..MAX_ALT && m.hcDeg in MIN_ALT..MAX_ALT) {
                val diff = abs(norm180(m.znDeg - s.znDeg))
                if (diff >= 45.0) {
                    val score = abs(diff - 90.0)
                    if (score < bestScore) {
                        bestScore = score
                        best = MoonPlan(
                            Target(SkyBody.Moon, ms, m.hcDeg, m.znDeg, Almanac.entry(SkyBody.Moon, ms).mag),
                            Target(SkyBody.Sun, ms, s.hcDeg, s.znDeg, -26.7),
                            diff, Almanac.moonAgeDays(ms), Almanac.moonIllumination(ms) * 100.0,
                        )
                    }
                }
            }
            ms += 15 * 60_000L
        }
        return best
    }
}
