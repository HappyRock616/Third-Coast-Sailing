package com.thirdcoastsailing.app.celestial

import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Printed-almanac style tables, generated on the phone, for working sights by hand (the ADVANCED level, and for
 * anyone who wants to check the app's arithmetic):
 *
 *  - Daily pages: hourly GHA Aries, Sun GHA/Dec, Moon GHA/v/Dec/d/HP and planet GHA/Dec for one UTC day, plus
 *    star SHA/Dec, semi-diameters and Moon age (the layout of the Nautical Almanac's white pages).
 *  - Increments and corrections: GHA added for minutes and seconds past the hour (the yellow pages).
 *  - Altitude corrections: dip by height of eye, and refraction by apparent altitude.
 *  - Traditional assumed position: whole-degree latitude and whole-degree LHA, as used with sight reduction
 *    tables such as Pub. 229 / Pub. 249.
 */

data class HourRow(
    val hourUtc: Int,
    val ghaAries: Double,
    val sunGha: Double, val sunDec: Double,
    val moonGha: Double, val moonV: Double, val moonDec: Double, val moonD: Double, val moonHp: Double,
    val planets: Map<String, Pair<Double, Double>>,   // name -> (GHA, Dec)
)

data class StarRow(val number: Int, val name: String, val sha: Double, val dec: Double, val mag: Double)

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

data class IncrementRow(val second: Int, val sunPlanets: Double, val aries: Double, val moon: Double)

object AlmanacTables {

    private const val HOUR = 3_600_000L

    /** One UTC day (pass 00:00 UTC of the day). */
    fun dailyPage(dayStartUtcMs: Long): DailyPage {
        val hours = (0..23).map { h ->
            val ms = dayStartUtcMs + h * HOUR
            val sun = Almanac.entry(SkyBody.Sun, ms)
            val moon = Almanac.entry(SkyBody.Moon, ms)
            val moon1 = Almanac.entry(SkyBody.Moon, ms + HOUR)
            val v = (norm360(moon1.gha - moon.gha) - (14.0 + 19.0 / 60.0)) * 60.0   // excess over 14°19.0' per hour
            HourRow(
                h, Almanac.ghaAries(ms), sun.gha, sun.dec,
                moon.gha, v, moon.dec, (moon1.dec - moon.dec) * 60.0, moon.hp,
                SkyBody.planets.associate { p -> Almanac.entry(p, ms).let { p.name to Pair(it.gha, it.dec) } },
            )
        }
        val noon = dayStartUtcMs + 12 * HOUR
        val stars = SkyBody.stars.map { b ->
            val e = Almanac.entry(b, noon)
            StarRow(b.star.number, b.name, e.sha ?: 0.0, e.dec, e.mag)
        }.sortedBy { if (it.number == 0) 99 else it.number }
        val sun = Almanac.entry(SkyBody.Sun, noon); val sun1 = Almanac.entry(SkyBody.Sun, noon + HOUR)
        val pv = mutableMapOf<String, Double>(); val pd = mutableMapOf<String, Double>()
        for (p in SkyBody.planets) {
            val a = Almanac.entry(p, noon); val b = Almanac.entry(p, noon + HOUR)
            pv[p.name] = (norm360(b.gha - a.gha) - 15.0) * 60.0       // excess over the 15°/h in the increments table
            pd[p.name] = (b.dec - a.dec) * 60.0
        }
        return DailyPage(
            dayStartUtcMs, hours, stars, sun.sd, Almanac.entry(SkyBody.Moon, noon).sd, (sun1.dec - sun.dec) * 60.0,
            pv, pd, Almanac.moonAgeDays(noon), Almanac.moonIllumination(noon) * 100.0,
        )
    }

    /** Increments for minute `m` past the hour, every second (degrees). Sun/planets 15°/h, Aries 15°02.46'/h, Moon 14°19.0'/h. */
    fun increments(minute: Int): List<IncrementRow> = (0..59).map { s ->
        val h = (minute * 60 + s) / 3600.0
        IncrementRow(s, 15.0 * h, 15.0410686 * h, (14.0 + 19.0 / 60.0) * h)
    }

    /** Dip of the horizon (minutes, negative) for heights of eye in feet. */
    fun dipTable(heightsFt: List<Double> = listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0)): List<Pair<Double, Double>> =
        heightsFt.map { it to SightReduction.dipMin(it) }

    /** Mean refraction (minutes, subtracted) for apparent altitudes (degrees), 10 °C and 1010 mb. */
    fun refractionTable(altitudes: List<Double> = listOf(5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0)): List<Pair<Double, Double>> =
        altitudes.map { it to SightReduction.refractionMin(it) }

    /**
     * Traditional assumed position for a sight: latitude rounded to the nearest whole degree, longitude chosen
     * within 30' of the DR so the local hour angle (GHA + longitude east) comes out a whole degree.
     */
    fun traditionalAssumedPosition(dr: LatLon, ghaDeg: Double): LatLon {
        val f = ghaDeg - floor(ghaDeg)                                 // fraction of a degree in the GHA
        val lon = (dr.lon + f).roundToInt() - f                        // GHA + lon is then a whole number of degrees
        return LatLon(dr.lat.roundToInt().toDouble(), norm180(lon))
    }
}
