package com.thirdcoastsailing.app.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Altitude corrections and Hc / Zn checked against numbers worked out independently: printed Nautical Almanac
 * table values (dip, refraction, the Sun's semi-diameter) and sights built here with the corrections written out
 * by hand, so a sign error in the app's chain can't cancel itself out the way a round trip through the same code would.
 */
class CorrectionsTest {

    private val evening = 1792280760000L      // 2026-10-17 23:46 UTC: the Moon is near the meridian at 43N 86.9W, about 21° up
    private val afternoon = 1792256400000L    // 2026-10-17 17:00 UTC: the Sun is about 33° up there

    // ---------------------------------------------------------------- printed tables

    @Test fun dipMatchesTheAlmanacTable() {
        // Nautical Almanac dip table (height of eye in feet -> dip): 9 ft -2.9', 16 ft -3.9', 36 ft -5.8', 100 ft -9.7'
        for ((ft, dip) in listOf(9.0 to -2.9, 16.0 to -3.9, 36.0 to -5.8, 100.0 to -9.7))
            assertEquals("dip at $ft ft", dip, Workbook.m1(SightReduction.dipMin(ft)), 0.051)
        assertEquals(0.0, SightReduction.dipMin(0.0), 1e-12)
    }

    @Test fun refractionMatchesTheAlmanacTable() {
        // Nautical Almanac star / planet table (10 °C, 1010 mb): 5° 9.9', 10° 5.3', 20° 2.6', 30° 1.7', 45° 1.0', 60° 0.6', 90° 0.0'
        for ((alt, r) in listOf(5.0 to 9.9, 10.0 to 5.3, 20.0 to 2.6, 30.0 to 1.7, 45.0 to 1.0, 60.0 to 0.6, 90.0 to 0.0))
            assertEquals("refraction at $alt°", r, SightReduction.refractionMin(alt), 0.15)
        // colder, denser air bends more: scales with pressure / absolute temperature
        val std = SightReduction.refractionMin(10.0)
        assertEquals(std * 283.0 / 273.0, SightReduction.refractionMin(10.0, 0.0, 1010.0), 1e-9)
        assertEquals(std * 1030.0 / 1010.0, SightReduction.refractionMin(10.0, 10.0, 1030.0), 1e-9)
    }

    @Test fun sunSemiDiameterMatchesTheAlmanac() {
        // the daily pages give the Sun's SD as 16.3' in early January (nearest the Sun) and 15.8' in early July
        val jan = Almanac.entry(SkyBody.Sun, 1767528000000L).sd       // 2026-01-04 12:00 UTC
        val jul = Almanac.entry(SkyBody.Sun, 1783166400000L).sd       // 2026-07-04 12:00 UTC
        assertEquals(16.3, jan, 0.1)
        assertEquals(15.8, jul, 0.1)
        // the Sun's horizontal parallax is about 0.15', so its parallax in altitude is never more than 0.2'
        assertTrue(Almanac.entry(SkyBody.Sun, afternoon).hp in 0.14..0.16)
    }

    @Test fun moonSemiDiameterFollowsHorizontalParallax() {
        // the almanac's Moon SD and HP go together: SD = 0.2724 HP (HP 54.0' <-> SD 14.7', HP 61.0' <-> SD 16.6')
        val e = Almanac.entry(SkyBody.Moon, evening)
        assertTrue("HP ${e.hp}", e.hp in 53.5..61.6)
        assertEquals(0.2724 * e.hp, e.sd, 0.05)
    }

    @Test fun limbAndParallaxSigns() {
        // lower limb adds SD, upper limb takes it away, a star gets none; parallax adds HP cos Ha
        assertEquals(16.0, SightReduction.semiDiameterMin(SkyBody.Sun, Limb.LOWER, 16.0, 0.15, 30.0), 1e-12)
        assertEquals(-16.0, SightReduction.semiDiameterMin(SkyBody.Sun, Limb.UPPER, 16.0, 0.15, 30.0), 1e-12)
        assertEquals(0.0, SightReduction.semiDiameterMin(SkyBody.Sun, Limb.CENTER, 16.0, 0.15, 30.0), 1e-12)
        // the Moon's disc grows with altitude (augmentation): at 30° with HP 57' about 0.13' larger
        val aug = SightReduction.semiDiameterMin(SkyBody.Moon, Limb.LOWER, 15.53, 57.0, 30.0)
        assertEquals(15.53 * (1 + 0.5 * sin(57.0 / 60.0 * DEG)), aug, 1e-9)
        // Moon at 30°, HP 57.0': parallax 49.4'
        assertEquals(49.36, SightReduction.parallaxMin(57.0, 30.0), 0.01)
    }

    // ---------------------------------------------------------------- worked sights

    /** Bennett's refraction written out here (10 °C, 1010 mb), independent of the app's copy. */
    private fun bennett(ha: Double) = 1.0 / tan((ha + 7.31 / (ha + 4.4)) * DEG)

    /**
     * The sextant altitude that corrects to `ho` for a lower-limb sight, with every correction written out by hand:
     * Ho = Ha − R + HP cos Ha + SD (Moon: SD grown by augmentation), Ha = Hs + IC + dip.
     */
    private fun hsForLowerLimb(ho: Double, moon: Boolean, sd: Double, hp: Double, ieMin: Double, eyeFt: Double): Double {
        var ha = ho
        repeat(30) {
            val sdNow = if (moon) sd * (1 + sin(ha * DEG) * sin(hp / 60.0 * DEG)) else sd
            ha = ho - (-bennett(ha) + hp * cos(ha * DEG) + sdNow) / 60.0
        }
        return ha - (-ieMin - 0.97 * sqrt(eyeFt)) / 60.0
    }

    /** Signed distance (nm) of `p` from the line: + when p is on the body's side of it. */
    private fun side(l: WorkLop, p: LatLon): Double {
        val d = distanceNm(l.assumed, p)
        return d * cos((bearing(l.assumed, p) - l.znDeg) * DEG) - l.interceptNm
    }

    private fun limbSight(body: SkyBody, t: Long, truth: LatLon, limb: Limb): WorkLop {
        val e = Almanac.entry(body, t)
        val hc = SightReduction.altAz(body, truth, t).hcDeg
        val hs = hsForLowerLimb(hc, body == SkyBody.Moon, e.sd, e.hp, ieMin = 1.5, eyeFt = 9.0)
        val set = WorkbookSettings(SkillLevel.BEGINNER, 9.0, 1.5)
        return Workbook.work(WorkbookSight(1, body, limb, listOf(Shot(t, hs, truth))), set).lop!!
    }

    @Test fun sunLowerAndUpperLimb() {
        val truth = LatLon(43.0, -86.9)
        val sd = Almanac.entry(SkyBody.Sun, afternoon).sd
        val ll = limbSight(SkyBody.Sun, afternoon, truth, Limb.LOWER)
        assertEquals("lower limb line runs through the true position", 0.0, side(ll, truth), 0.4)
        assertEquals(SightReduction.altAz(SkyBody.Sun, truth, afternoon).znDeg, ll.znDeg, 0.5)
        // the same reading called an upper-limb sight: Ho is 2 SD lower, so the line moves 2 SD away from the Sun,
        // which leaves the true position 2 SD on the Sun's side of it
        val ul = limbSight(SkyBody.Sun, afternoon, truth, Limb.UPPER)
        assertEquals(2 * sd, side(ul, truth), 0.4)
    }

    @Test fun moonLowerLimb() {
        val truth = LatLon(43.0, -86.9)
        val e = Almanac.entry(SkyBody.Moon, evening)
        val ll = limbSight(SkyBody.Moon, evening, truth, Limb.LOWER)
        assertEquals("Moon line runs through the true position", 0.0, side(ll, truth), 0.4)
        val ul = limbSight(SkyBody.Moon, evening, truth, Limb.UPPER)
        val ha = SightReduction.altAz(SkyBody.Moon, truth, evening).hcDeg
        val aug = e.sd * (1 + sin(ha * DEG) * sin(e.hp / 60.0 * DEG))
        assertEquals(2 * aug, side(ul, truth), 0.5)     // as for the Sun: the true position is on the Moon's side
    }

    @Test fun movingBoatRunningFix() {
        // boat sailing 090° at 6 kn, GPS position with each shot: Sun at 17:00, Sun again at 19:30 and Moon at 23:46
        // all advanced to the last sight; the fix should be where the boat is then
        val start = LatLon(43.0, -86.9)
        fun at(t: Long) = travel(start, 90.0, 6.0 * (t - afternoon) / 3_600_000.0)
        val set = WorkbookSettings(SkillLevel.BEGINNER, 9.0, 0.0)
        fun sight(id: Long, body: SkyBody, t: Long): WorkbookSight {
            val e = Almanac.entry(body, t)
            val hc = SightReduction.altAz(body, at(t), t).hcDeg
            val hs = hsForLowerLimb(hc, body == SkyBody.Moon, e.sd, e.hp, 0.0, 9.0)
            return WorkbookSight(id, body, Limb.LOWER, listOf(Shot(t, hs, at(t))))
        }
        val t2 = afternoon + 150 * 60_000L
        val (_, fix) = Workbook.solve(listOf(sight(1, SkyBody.Sun, afternoon), sight(2, SkyBody.Sun, t2), sight(3, SkyBody.Moon, evening)), set)
        assertTrue("fix within 1 nm of the boat (it ran 40 nm between sights), was ${distanceNm(fix!!.position, at(evening))}", distanceNm(fix.position, at(evening)) < 1.0)
    }

    // ---------------------------------------------------------------- Hc, Zn and LHA

    @Test fun znInEveryQuadrant() {
        // on the equator, a body on the celestial equator 90° west of the meridian is setting due west
        SightReduction.hcZnFromLha(0.0, 0.0, 90.0).let { assertEquals(0.0, it.hcDeg, 1e-9); assertEquals(270.0, it.znDeg, 1e-9) }
        SightReduction.hcZnFromLha(0.0, 0.0, 270.0).let { assertEquals(0.0, it.hcDeg, 1e-9); assertEquals(90.0, it.znDeg, 1e-9) }
        // on the meridian: south of a northern observer, north when the declination is further north
        SightReduction.hcZnFromLha(40.0, 0.0, 0.0).let { assertEquals(50.0, it.hcDeg, 1e-9); assertEquals(180.0, it.znDeg, 1e-9) }
        SightReduction.hcZnFromLha(40.0, 60.0, 0.0).let { assertEquals(70.0, it.hcDeg, 1e-9); assertEquals(0.0, norm180(it.znDeg), 1e-9) }
        // southern hemisphere, body further south: due south
        SightReduction.hcZnFromLha(-30.0, -50.0, 0.0).let { assertEquals(70.0, it.hcDeg, 1e-9); assertEquals(180.0, it.znDeg, 1e-9) }
        // east (LHA 300) and west (LHA 60) mirror each other about the meridian
        val east = SightReduction.hcZnFromLha(40.0, 10.0, 300.0)
        val west = SightReduction.hcZnFromLha(40.0, 10.0, 60.0)
        assertTrue("east: ${east.znDeg}", east.znDeg in 0.0..180.0)
        assertTrue("west: ${west.znDeg}", west.znDeg in 180.0..360.0)
        assertEquals(360.0 - east.znDeg, west.znDeg, 1e-9)
        assertEquals(east.hcDeg, west.hcDeg, 1e-9)
        // a worked value: lat 40N, Dec 10N, LHA 60: sin Hc = .6428×.1736 + .7660×.9848×.5 = .4888, Hc 29°15.8';
        // tan Z = −.9848×.8660 / (.1736×.7660 − .9848×.6428×.5) = −.8529 / −.1835, Zn 257.9°
        assertEquals(29.0 + 15.8 / 60.0, west.hcDeg, 0.1 / 60.0)
        assertEquals(257.86, west.znDeg, 0.05)
    }

    @Test fun lhaWrapsForEastAndWestLongitudes() {
        // LHA = GHA + longitude east, made 0–360
        assertEquals(10.0, SightReduction.hcZn(LatLon(10.0, 170.0), 200.0, 0.0).lhaDeg, 1e-9)
        assertEquals(290.0, SightReduction.hcZn(LatLon(10.0, -170.0), 100.0, 0.0).lhaDeg, 1e-9)
        assertEquals(0.0, SightReduction.hcZn(LatLon(10.0, -86.9), 86.9, 0.0).lhaDeg, 1e-9)
        // assumed positions near the antimeridian, east and west of Greenwich: LHA whole, within 30′ of the DR
        for ((near, gha) in listOf(LatLon(35.6, 139.7) to 250.3, LatLon(10.0, 179.9) to 10.4, LatLon(10.0, -179.8) to 10.4,
            LatLon(-33.9, 151.2) to 123.95, LatLon(10.0, 179.9) to 20.9)) {
            val ap = Workbook.assumedPosition(near, gha)
            val lha = norm360(gha + ap.lon)
            assertEquals("LHA whole for $near", Math.round(lha).toDouble() % 360.0, lha % 360.0, 1e-3)
            assertTrue("within 30′ of $near: ${ap.lon}", abs(norm180(ap.lon - near.lon)) <= 0.5 + 1e-9)
            assertTrue(abs(ap.lon) <= 180.0)
        }
    }
}
