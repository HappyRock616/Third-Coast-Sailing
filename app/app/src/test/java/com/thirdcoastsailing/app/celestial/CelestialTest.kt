package com.thirdcoastsailing.app.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * Checks the celestial back end against JPL's DE421 ephemeris (reference values computed with Skyfield) and
 * with a round trip: sights made up for a known position must reduce to a fix at that position.
 */
class CelestialTest {

    private val ms = 1792280760000L        // 2026-10-17 23:46 UTC (evening twilight on Lake Michigan)

    /** Difference in arc-minutes, with GHA differences scaled by cos(dec) (distance on the sky). */
    private fun arcmin(a: Double, b: Double, dec: Double = 0.0) = abs(norm180(a - b)) * 60.0 * cos(dec * DEG)

    @Test fun almanacMatchesDE421() {
        assertTrue(arcmin(Almanac.ghaAries(ms), 22.991469521094956) < 0.1)
        val refs = listOf(
            Triple(SkyBody.Sun, 180.18488257457133, -9.539178475136415),
            Triple(SkyBody.Moon, 93.90835636517136, -25.654784809286667),
            Triple(SkyBody.Jupiter, 238.01103502685444, 14.652739154951892),
            Triple(SkyBody.byName("Vega")!!, 103.53101378121369, 38.81271525793432),
        )
        for ((b, gha, dec) in refs) {
            val e = Almanac.entry(b, ms)
            assertTrue("${b.name} GHA", arcmin(e.gha, gha, dec) < 0.3)
            assertTrue("${b.name} dec", abs(e.dec - dec) * 60.0 < 0.3)
        }
    }

    @Test fun hcZnRoundTrip() {
        // A body on the observer's meridian, south of them: Zn 180, Hc = 90 - lat + dec
        val h = SightReduction.hcZn(LatLon(43.0, -86.9), 86.9, -9.5)
        assertEquals(180.0, h.znDeg, 1e-6)
        assertEquals(90.0 - 43.0 - 9.5, h.hcDeg, 1e-6)
    }

    @Test fun threeStarFixFindsTheTruePosition() {
        val truth = LatLon(43.0, -86.9)
        // make perfect sights for the true position: Hs that corrects back to exactly Hc there
        fun sight(name: String, t: Long): SightInput {
            val body = SkyBody.byName(name)!!
            val hc = SightReduction.altAz(body, truth, t).hcDeg
            val probe = SightInput(body, t, hc, indexErrorMin = 2.0, heightOfEyeFt = 9.0)
            var hs = hc
            repeat(10) { hs += hc - SightReduction.correct(probe.copy(hsDeg = hs), Almanac.entry(body, t)).hoDeg }
            return probe.copy(hsDeg = hs)
        }
        val sights = listOf(sight("Markab", ms - 240_000), sight("Polaris", ms - 120_000), sight("Rasalhague", ms))
        val fix = SightReduction.fix(sights, travel(truth, 300.0, 8.0), ms, null, 0.0)
        assertNotNull(fix)
        assertTrue("fix within 0.1 nm", distanceNm(fix!!.position, truth) < 0.1)
    }

    @Test fun plannerPicksSpreadBodiesInTwilight() {
        val plan = SightPlanner.plan(LatLon(43.0, -86.9), 1792209600000L)   // 17 Oct 2026, 00:00 EDT
        val evening = plan.eveningStars
        assertNotNull(evening)
        assertEquals(3, evening!!.picks.size)
        assertTrue(evening.spreadDeg > 90.0)
        evening.picks.forEach { assertTrue(it.altitudeDeg in SightPlanner.MIN_ALT..SightPlanner.MAX_ALT) }
        assertNotNull(plan.sun.noon)
    }

    @Test fun workbookFixFindsTheTruePosition() {
        val truth = LatLon(43.0, -86.9)
        val dr = travel(truth, 300.0, 8.0)
        val set = WorkbookSettings(SkillLevel.BEGINNER, 9.0, 2.0)
        fun sight(id: Long, name: String, t: Long): WorkbookSight {
            val b = SkyBody.byName(name)!!
            val hc = SightReduction.altAz(b, truth, t).hcDeg
            val probe = SightInput(b, t, hc, defaultLimb(b), 2.0, 9.0)
            var hs = hc
            repeat(10) { hs += hc - SightReduction.correct(probe.copy(hsDeg = hs), Almanac.entry(b, t)).hoDeg }
            return WorkbookSight(id, b, defaultLimb(b), listOf(Shot(t, hs, dr)))
        }
        val works = listOf(sight(1, "Markab", ms - 240_000), sight(2, "Polaris", ms - 120_000), sight(3, "Rasalhague", ms))
            .map { Workbook.work(it, set) }
        val fix = Workbook.fix(works.mapNotNull { it.lop })
        assertNotNull(fix)
        assertTrue("workbook fix within 0.3 nm (table rounding)", distanceNm(fix!!.position, truth) < 0.3)
        // uncertainty: Moderate sights of 1 shot each (σ = √(1 + 1.5²) = 1.80′), 3 lines spread round the sky
        val u = fix.uncertainty!!
        assertEquals(null, u.enlarged)                                        // perfect sights: no extra spread
        assertTrue("radius ${u.radius95Nm}", u.radius95Nm in 3.5..6.0)
        // the same sights rated Hard give a bigger circle
        val hard = Workbook.fix(works.map { Workbook.work(it.sight.copy(condition = SightCondition.HARD), set.copy(level = SkillLevel.INTERMEDIATE)) }.mapNotNull { it.lop })!!
        assertTrue(hard.uncertainty!!.radius95Nm > 1.8 * u.radius95Nm)
        assertTrue(fix.offsetNm > 7.0 && fix.drIsGps)                       // DR was set 8 nm off
    }

    @Test fun advancedFlagsOnlyTheWrongLine() {
        val b = SkyBody.byName("Vega")!!
        val s = WorkbookSight(1, b, Limb.CENTER, listOf(Shot(ms, 50.0, LatLon(43.0, -86.9))))
        val right = Workbook.work(s, WorkbookSettings(SkillLevel.BEGINNER))
        val typed = right.lines.associate { it.field to it.expected }.toMutableMap()
        typed[Field.SHA] = typed[Field.SHA]!! + 1.0 / 60.0                  // 1' wrong
        val adv = Workbook.work(s.copy(entries = typed), WorkbookSettings(SkillLevel.ADVANCED))
        assertEquals(Check.WRONG, adv.line(Field.SHA)!!.check)
        assertEquals(Check.OK, adv.line(Field.DIP)!!.check)
    }

    @Test fun droppedShotIsExplainedAndTypedTimesRead() {
        val p = LatLon(43.0, -86.9)
        val shots = listOf(Shot(ms, 40.0, p), Shot(ms + 60_000, 40.2, p), Shot(ms + 120_000, 40.4 + 5.0 / 60.0, p), Shot(ms + 180_000, 40.6, p))
        val kept = Workbook.keptShots(shots)
        assertEquals(3, kept.size)
        val r = Workbook.residuals(shots)[2]!!
        assertEquals(5.0, r, 0.3)
        assertTrue(Workbook.dropNote(r).startsWith("5.0′ higher"))
        val edt = -4 * 3_600_000L                                           // 19:46:12 EDT on 17 Oct = 23:46:12 UT
        assertEquals(ms + 12_000, Workbook.parseTimeOfDay("19:46:12", ms, edt))
        assertEquals(ms + 12_000, Workbook.parseTimeOfDay("194612", ms, edt))
        assertEquals(null, Workbook.parseTimeOfDay("25:00", ms, edt))
    }

    @Test fun starFinderPointsWhereThePhonePoints() {
        val cam = FinderCameras(PhonePose.look(255.0, 18.0), 720, 1440, 70.0, 255.0)
        val c = cam.projectSky(V3.altAz(18.0, 255.0))
        assertEquals(360.0, c.x.toDouble(), 0.5); assertEquals(720.0, c.y.toDouble(), 0.5)
        val s = Math.sqrt(0.5)                                               // phone upright, facing magnetic north
        assertEquals(10.0, PhonePose.fromQuaternion(s, 0.0, 0.0, s, 0, 10.0).azDeg, 1e-6)
    }

    @Test fun traditionalAssumedPositionGivesWholeLha() {
        val ap = AlmanacTables.traditionalAssumedPosition(LatLon(43.37, -86.74), 123.456)
        assertEquals(43.0, ap.lat, 1e-9)
        val lha = norm360(123.456 + ap.lon)
        assertEquals(Math.round(lha).toDouble(), lha, 1e-9)
        assertTrue(abs(ap.lon - -86.74) <= 0.5)
    }
}
