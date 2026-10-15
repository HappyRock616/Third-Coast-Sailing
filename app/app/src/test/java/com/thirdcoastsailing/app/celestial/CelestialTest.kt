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

    @Test fun traditionalAssumedPositionGivesWholeLha() {
        val ap = AlmanacTables.traditionalAssumedPosition(LatLon(43.37, -86.74), 123.456)
        assertEquals(43.0, ap.lat, 1e-9)
        val lha = norm360(123.456 + ap.lon)
        assertEquals(Math.round(lha).toDouble(), lha, 1e-9)
        assertTrue(abs(ap.lon - -86.74) <= 0.5)
    }
}
