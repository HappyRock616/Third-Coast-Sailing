package com.thirdcoastsailing.app.celestial

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.C_AUDAY
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.Vector
import io.github.cosinekitty.astronomy.baryState
import io.github.cosinekitty.astronomy.geoVector
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.moonPhase
import io.github.cosinekitty.astronomy.rotationEqjEqd
import io.github.cosinekitty.astronomy.siderealTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The built-in Nautical Almanac. For any instant it gives what the printed almanac gives: Greenwich Hour Angle
 * (GHA) and declination of the Sun, Moon, navigational planets and the 58 navigational stars, plus GHA Aries,
 * star SHA, horizontal parallax (HP), semi-diameter (SD) and magnitude.
 *
 * Positions come from Astronomy Engine (MIT license, VSOP87 + NOVAS models, about 1 arc-minute or better), as
 * apparent geocentric coordinates on the true equator and equinox of date: the same quantities the printed
 * almanac tabulates. All times are UTC in milliseconds since 1970.
 */

enum class BodyKind { SUN, MOON, PLANET, STAR }

/** Anything a sight can be taken of. */
sealed class SkyBody(val name: String, val kind: BodyKind) {
    /** The Sun. */
    object Sun : SkyBody("Sun", BodyKind.SUN)
    /** The Moon. */
    object Moon : SkyBody("Moon", BodyKind.MOON)
    /** One of the four navigational planets (Venus, Mars, Jupiter, Saturn). */
    class Planet internal constructor(name: String, internal val body: Body) : SkyBody(name, BodyKind.PLANET)
    /** One of the navigational stars. */
    class Star(val star: NavStar) : SkyBody(star.name, BodyKind.STAR)

    override fun toString() = name

    companion object {
        val Venus = Planet("Venus", Body.Venus)
        val Mars = Planet("Mars", Body.Mars)
        val Jupiter = Planet("Jupiter", Body.Jupiter)
        val Saturn = Planet("Saturn", Body.Saturn)

        /** The four navigational planets, as in the Nautical Almanac. */
        val planets: List<Planet> = listOf(Venus, Mars, Jupiter, Saturn)
        val stars: List<Star> = NAV_STARS.map { Star(it) }

        /** Every body the almanac covers. */
        val all: List<SkyBody> = listOf(Sun, Moon) + planets + stars

        fun byName(name: String): SkyBody? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** One almanac line for one body at one instant. Angles in degrees; hp and sd in arc-minutes. */
data class AlmanacEntry(
    val body: SkyBody,
    val timeMs: Long,
    val gha: Double,
    val dec: Double,
    val sha: Double?,           // stars only (sidereal hour angle)
    val distanceAu: Double,
    val hp: Double,             // horizontal parallax ('), Sun / Moon / planets
    val sd: Double,             // semi-diameter ('), Sun and Moon; 0 for points of light
    val mag: Double,
)

/** Almanac values (GHA, declination, SHA, semi-diameter, HP, ...) worked out for any instant from Astronomy Engine. */
object Almanac {

    private const val EARTH_RADIUS_KM = 6378.14
    private const val KM_PER_AU = 149_597_870.7
    private const val MOON_RADIUS_KM = 1737.4
    private const val SUN_SD_1AU_ARCSEC = 959.63
    private const val SOLAR_PARALLAX_1AU_ARCSEC = 8.794

    /** Astronomy Engine's time for a Unix time in milliseconds. */
    fun time(ms: Long): Time = Time.fromMillisecondsSince1970(ms)

    /** Greenwich Hour Angle of Aries (the first point of Aries): apparent sidereal time in degrees. */
    fun ghaAries(ms: Long): Double = norm360(siderealTime(time(ms)) * 15.0)

    /** Almanac values for a body at an instant (UTC ms). */
    fun entry(body: SkyBody, ms: Long): AlmanacEntry {
        val t = time(ms)
        val gast = siderealTime(t) * 15.0
        val (raDeg, dec, distAu) = apparentRaDec(body, t)
        val gha = norm360(gast - raDeg)
        val hp: Double
        val sd: Double
        when (body) {
            SkyBody.Moon -> {
                val km = distAu * KM_PER_AU
                hp = asin(EARTH_RADIUS_KM / km) * RAD * 60.0
                sd = asin(MOON_RADIUS_KM / km) * RAD * 60.0
            }
            SkyBody.Sun -> {
                hp = SOLAR_PARALLAX_1AU_ARCSEC / distAu / 60.0
                sd = SUN_SD_1AU_ARCSEC / distAu / 60.0
            }
            is SkyBody.Planet -> { hp = SOLAR_PARALLAX_1AU_ARCSEC / distAu / 60.0; sd = 0.0 }
            is SkyBody.Star -> { hp = 0.0; sd = 0.0 }
        }
        val mag = when (body) {
            is SkyBody.Star -> body.star.mag
            SkyBody.Sun -> illumination(Body.Sun, t).mag
            SkyBody.Moon -> illumination(Body.Moon, t).mag
            is SkyBody.Planet -> illumination(body.body, t).mag
        }
        val sha = if (body is SkyBody.Star) norm360(360.0 - raDeg) else null
        return AlmanacEntry(body, ms, gha, dec, sha, distAu, hp, sd, mag)
    }

    /** Moon phase angle (0 new, 90 first quarter, 180 full, 270 last quarter) and age in days. */
    fun moonPhaseDeg(ms: Long): Double = moonPhase(time(ms))
    /** Age of the Moon in days since new moon. */
    fun moonAgeDays(ms: Long): Double = moonPhaseDeg(ms) / 360.0 * 29.530589

    /** Fraction of the Moon's disc that is lit (0..1). */
    fun moonIllumination(ms: Long): Double = illumination(Body.Moon, time(ms)).phaseFraction

    /** Apparent geocentric right ascension (degrees), declination (degrees) and distance (AU), true equator of date. */
    internal fun apparentRaDec(body: SkyBody, t: Time): Triple<Double, Double, Double> {
        val vec = when (body) {
            SkyBody.Sun -> geoVector(Body.Sun, t, Aberration.Corrected)
            SkyBody.Moon -> geoVector(Body.Moon, t, Aberration.Corrected)
            is SkyBody.Planet -> geoVector(body.body, t, Aberration.Corrected)
            is SkyBody.Star -> starApparentJ2000(body.star, t)
        }
        val eq = rotationEqjEqd(t).rotate(vec).toEquatorial()
        return Triple(eq.ra * 15.0, eq.dec, eq.dist)
    }

    /**
     * A star's direction (unit vector, J2000 axes) as seen from the Earth on the date: catalog position moved by
     * proper motion, then annual aberration from the Earth's orbital velocity (up to 20 arc-seconds). Stellar
     * parallax is ignored (under 1 arc-second for every navigational star).
     */
    private fun starApparentJ2000(s: NavStar, t: Time): Vector {
        val (raH, dec) = starPositionAtEpoch(s, t)
        val ra = raH * 15.0 * DEG; val d = dec * DEG
        val ux = cos(d) * cos(ra); val uy = cos(d) * sin(ra); val uz = sin(d)
        val v = baryState(Body.Earth, t)
        val bx = v.vx / C_AUDAY; val by = v.vy / C_AUDAY; val bz = v.vz / C_AUDAY
        val ub = ux * bx + uy * by + uz * bz
        // first-order (classical) aberration: u' = u + beta - u (u . beta), renormalized
        var x = ux + bx - ux * ub; var y = uy + by - uy * ub; var z = uz + bz - uz * ub
        val n = sqrt(x * x + y * y + z * z); x /= n; y /= n; z /= n
        val far = 63_241.077 * 1000.0                        // 1000 light-years in AU (only the direction matters)
        return Vector(x * far, y * far, z * far, t)
    }

    /** J2000 catalog position moved by proper motion to the date (still J2000 orientation): ra hours, dec degrees. */
    internal fun starPositionAtEpoch(s: NavStar, t: Time): Pair<Double, Double> {
        val years = t.tt / 365.25
        val dec = s.decJ2000 + s.pmDec * years / 3600.0
        val raDeg = s.raJ2000 * 15.0 + s.pmRa * years / 3600.0 / cos(s.decJ2000 * DEG)
        return Pair(norm360(raDeg) / 15.0 % 24.0, dec.coerceIn(-90.0, 90.0))
    }
}
