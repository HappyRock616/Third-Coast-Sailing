package com.thirdcoastsailing.app.celestial

/*
 * Printed-almanac style tables, generated on the phone, for working sights by hand (the ADVANCED level, and for
 * anyone who wants to check the app's arithmetic):
 *
 *  - Daily pages: hourly GHA Aries, Sun GHA/Dec, Moon GHA/v/Dec/d/HP and planet GHA/Dec for one UTC day, plus
 *    star SHA/Dec, semi-diameters and Moon age (the layout of the Nautical Almanac's white pages).
 *  - Increments and corrections: GHA added for minutes and seconds past the hour (the yellow pages).
 *  (The dip and refraction tables are drawn by the Almanac tab straight from SightReduction; the assumed position
 *  is chosen by Workbook.assumedPosition.)
 */

data class HourRow(
    val hourUtc: Int,
    val ghaAries: Double,
    val sunGha: Double, val sunDec: Double,
    val moonGha: Double, val moonV: Double, val moonDec: Double, val moonD: Double, val moonHp: Double,
    val planets: Map<String, Pair<Double, Double>>,   // name -> (GHA, Dec)
)

/** One star on the daily page: number, name, SHA, declination and magnitude. */
data class StarRow(val number: Int, val name: String, val sha: Double, val dec: Double, val mag: Double)

/** One printed-style daily page of the almanac. */
data class DailyPage(
    val dayStartUtcMs: Long,
    val hours: List<HourRow>,
    val stars: List<StarRow>,                     // for 12:00 UTC of the day
    val sunSd: Double, val moonSd: Double,        // arc-minutes, at 12:00 UTC
    val sunD: Double,                             // Sun's declination change per hour (')
    val planetV: Map<String, Double>,             // planet v and d (') per hour, at 12:00 UTC
    val planetD: Map<String, Double>,
    val moonAgeDays: Double, val moonIllumPct: Double,
)

/** One row of the increments table: seconds, and the GHA increment for the Sun and planets, Aries and the Moon. */
data class IncrementRow(val second: Int, val sunPlanets: Double, val aries: Double, val moon: Double)

/** Builds the printed-style almanac pages from the same computations the workbook uses. */
object AlmanacTables {

    private const val HOUR = 3_600_000L

    /** One UTC day (pass 00:00 UTC of the day). */
    fun dailyPage(dayStartUtcMs: Long): DailyPage {
        val hours = (0..23).map { h ->
            val ms = dayStartUtcMs + h * HOUR
            val sun = Almanac.entry(SkyBody.Sun, ms, withMagnitude = false)
            val moon = Almanac.entry(SkyBody.Moon, ms, withMagnitude = false)
            val moon1 = Almanac.entry(SkyBody.Moon, ms + HOUR, withMagnitude = false)
            val v = (norm360(moon1.gha - moon.gha) - MOON_GHA_DEG_PER_H) * 60.0   // excess over 14°19.0′ per hour
            HourRow(
                h, Almanac.ghaAries(ms), sun.gha, sun.dec,
                moon.gha, v, moon.dec, (moon1.dec - moon.dec) * 60.0, moon.hp,
                SkyBody.planets.associate { p -> Almanac.entry(p, ms, withMagnitude = false).let { p.name to Pair(it.gha, it.dec) } },
            )
        }
        val noon = dayStartUtcMs + 12 * HOUR
        val stars = SkyBody.stars.map { b ->
            val e = Almanac.entry(b, noon, withMagnitude = false)
            StarRow(b.star.number, b.name, e.sha ?: 0.0, e.dec, e.mag)
        }.sortedBy { if (it.number == 0) 99 else it.number }
        val sun = Almanac.entry(SkyBody.Sun, noon, withMagnitude = false); val sun1 = Almanac.entry(SkyBody.Sun, noon + HOUR, withMagnitude = false)
        val pv = mutableMapOf<String, Double>(); val pd = mutableMapOf<String, Double>()
        for (p in SkyBody.planets) {
            val a = Almanac.entry(p, noon, withMagnitude = false); val b = Almanac.entry(p, noon + HOUR, withMagnitude = false)
            pv[p.name] = (norm360(b.gha - a.gha) - SUN_GHA_DEG_PER_H) * 60.0       // excess over the 15°/h in the increments table
            pd[p.name] = (b.dec - a.dec) * 60.0
        }
        return DailyPage(
            dayStartUtcMs, hours, stars, sun.sd, Almanac.entry(SkyBody.Moon, noon, withMagnitude = false).sd, (sun1.dec - sun.dec) * 60.0,
            pv, pd, Almanac.moonAgeDays(noon), Almanac.moonIllumination(noon) * 100.0,
        )
    }

    /** Increments for minute `m` past the hour, every second (degrees). Sun/planets 15°/h, Aries 15°02.46'/h, Moon 14°19.0'/h. */
    fun increments(minute: Int): List<IncrementRow> = (0..59).map { s ->
        val h = (minute * 60 + s) / 3600.0
        IncrementRow(s, SUN_GHA_DEG_PER_H * h, ARIES_GHA_DEG_PER_H * h, MOON_GHA_DEG_PER_H * h)
    }
}
