package com.thirdcoastsailing.app.celestial

import io.github.cosinekitty.astronomy.rotationEqjEqd
import io.github.cosinekitty.astronomy.siderealTime
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Star finder: the sky as seen from the boat at a given time, ready to draw.
 *
 * Data (assets/celestial/, see LICENSES.txt):
 *  - stars.csv: every star down to magnitude 4.5 (about 900), Yale Bright Star Catalogue 5 (public domain)
 *  - constellation_lines.csv, constellations.csv: stick figures and names, d3-celestial (BSD 3-Clause)
 *
 * Everything comes out as altitude / true azimuth; the 3D renderer (StarFinderRenderer) turns it with the phone.
 * Navigational stars, planets, Sun and Moon come from the almanac (full accuracy); the background stars and lines
 * use a quick precession-only conversion (good to about 1 arc-minute, plenty for a picture).
 */

data class CatalogStar(val raDeg: Double, val decDeg: Double, val mag: Double, val hr: Int)
data class ConstellationLine(val abbr: String, val rank: Int, val points: List<Pair<Double, Double>>)  // (ra, dec) degrees
/** A constellation's name label: abbreviation, full name, size rank (bigger first) and where to draw the name. */
data class ConstellationName(val abbr: String, val name: String, val rank: Int, val raDeg: Double, val decDeg: Double)

/** The star finder's catalog, parsed once from the three CSV assets. */
class StarCatalog(
    val stars: List<CatalogStar>,
    val lines: List<ConstellationLine>,
    val names: List<ConstellationName>,
) {
    /** HR numbers of the navigational stars, so the drawing can skip them (they're drawn from the almanac). */
    private val navHr: Set<Int> = NAV_STARS.map { it.hr }.toSet()
    /** True for a catalog star that is one of the 57 navigational stars (or Polaris). */
    fun isNavStar(s: CatalogStar) = s.hr in navHr

    /** Constellations that contain a navigational star (these get their names drawn). */
    val navConstellations: Set<String> = NAV_STARS.map { it.constellation }.toSet()

    companion object {
        fun parse(starsCsv: String, linesCsv: String, namesCsv: String): StarCatalog {
            fun rows(s: String) = s.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            val stars = rows(starsCsv).map { r ->
                val f = r.split(","); CatalogStar(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3].toInt())
            }.toList()
            val lines = rows(linesCsv).map { r ->
                val f = r.split(",", limit = 3)
                ConstellationLine(f[0], f[1].toIntOrNull() ?: 3, f[2].split(";").map { p ->
                    val (ra, dec) = p.trim().split(" "); Pair(ra.toDouble(), dec.toDouble())
                })
            }.toList()
            val names = rows(namesCsv).map { r ->
                val f = r.split(","); ConstellationName(f[0], f[1], f[2].toIntOrNull() ?: 3, f[3].toDouble(), f[4].toDouble())
            }.toList()
            return StarCatalog(stars, lines, names)
        }
    }
}

/** What a dot in the star finder is, which sets its look and label. */
enum class SkyObjectKind { STAR, NAV_STAR, PLANET, SUN, MOON }

/** An object in the 3D star finder (altitude / true azimuth in degrees). */
data class Sky3DObject(
    val kind: SkyObjectKind, val name: String?, val navNumber: Int?,
    val altDeg: Double, val azDeg: Double, val mag: Double, val body: SkyBody?,
)

/** A constellation stick-figure segment, ends as altitude / azimuth. */
data class Sky3DSegment(val abbr: String, val alt1: Double, val az1: Double, val alt2: Double, val az2: Double)

/** A constellation name; `nav` = contains a navigational star (drawn brighter). */
data class Sky3DName(val name: String, val nav: Boolean, val altDeg: Double, val azDeg: Double)

/** Everything the 3D star finder draws for one time and place, as altitude / azimuth (the renderer turns it with the phone). */
data class Sky3D(
    val timeMs: Long, val position: LatLon,
    val objects: List<Sky3DObject>, val segments: List<Sky3DSegment>, val names: List<Sky3DName>,
    val sunAltDeg: Double, val sunAzDeg: Double,
)

/** Works out the sky for the star finder: where every star, planet, the Sun and Moon, and the constellation figures are. */
object StarFinder {

    /**
     * The sky for the 3D star finder: every object and constellation figure as altitude / azimuth (true), at one
     * place and time. Objects are faint first so bright ones are drawn on top. Things a little below the horizon
     * are kept (they come up as time passes; the renderer hides what's below).
     */
    fun sky3d(catalog: StarCatalog, pos: LatLon, ms: Long, maxMag: Double = 4.5): Sky3D {
        val conv = Converter(pos, ms)
        val objects = mutableListOf<Sky3DObject>()
        for (s in catalog.stars) {
            if (s.mag > maxMag || catalog.isNavStar(s)) continue
            val (alt, az) = conv.altAz(s.raDeg, s.decDeg)
            if (alt < -5.0) continue
            objects += Sky3DObject(SkyObjectKind.STAR, null, null, alt, az, s.mag, null)
        }
        var sunAlt = -90.0; var sunAz = 0.0
        for (b in SkyBody.all) {
            val e = Almanac.entry(b, ms)
            val h = SightReduction.hcZn(pos, e.gha, e.dec)
            if (b == SkyBody.Sun) { sunAlt = h.hcDeg; sunAz = h.znDeg }
            val kind = when (b) {
                SkyBody.Sun -> SkyObjectKind.SUN
                SkyBody.Moon -> SkyObjectKind.MOON
                is SkyBody.Planet -> SkyObjectKind.PLANET
                is SkyBody.Star -> SkyObjectKind.NAV_STAR
            }
            objects += Sky3DObject(kind, b.name, (b as? SkyBody.Star)?.star?.number, h.hcDeg, h.znDeg, e.mag, b)
        }
        val segments = mutableListOf<Sky3DSegment>()
        for (line in catalog.lines) {
            var prev: Pair<Double, Double>? = null
            for ((ra, dec) in line.points) {
                val p = conv.altAz(ra, dec)
                if (prev != null && (p.first > -2.0 || prev.first > -2.0)) segments += Sky3DSegment(line.abbr, prev.first, prev.second, p.first, p.second)
                prev = p
            }
        }
        val names = catalog.names.map { n ->
            val (alt, az) = conv.altAz(n.raDeg, n.decDeg)
            Sky3DName(n.name, n.abbr in catalog.navConstellations, alt, az)
        }
        return Sky3D(ms, pos, objects.sortedByDescending { it.mag }, segments, names, sunAlt, sunAz)
    }

    /** J2000 RA/Dec -> altitude/azimuth for one place and time (precession + nutation, no aberration). */
    private class Converter(val pos: LatLon, ms: Long) {
        private val t = Almanac.time(ms)
        private val m = rotationEqjEqd(t).rot
        private val gast = siderealTime(t) * 15.0
        fun altAz(raDeg: Double, decDeg: Double): Pair<Double, Double> {
            val ra = raDeg * DEG; val d = decDeg * DEG
            val x = cos(d) * cos(ra); val y = cos(d) * sin(ra); val z = sin(d)
            val xd = m[0][0] * x + m[1][0] * y + m[2][0] * z
            val yd = m[0][1] * x + m[1][1] * y + m[2][1] * z
            val zd = m[0][2] * x + m[1][2] * y + m[2][2] * z
            val raD = atan2(yd, xd) * RAD
            val decD = asin(zd.coerceIn(-1.0, 1.0)) * RAD
            val h = SightReduction.hcZn(pos, norm360(gast - raD), decD)
            return Pair(h.hcDeg, h.znDeg)
        }
    }
}
