package com.thirdcoastsailing.app.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dropping a bad shot (whichever shot it is) and reading typed values. */
class ShotsAndParseTest {

    private val t0 = 1792280760000L
    private val p = LatLon(43.0, -86.9)

    /** Shots one minute apart; each Hs given in arc-minutes above 40°. */
    private fun shots(vararg minutesAbove40: Double) =
        minutesAbove40.mapIndexed { i, m -> Shot(t0 + i * 60_000L, 40.0 + m / 60.0, p) }

    /** Which shots (by index) were kept. */
    private fun keptIdx(s: List<Shot>, rate: Double? = null) = Workbook.keptShots(s, rateMinPerMin = rate).map { s.indexOf(it) }

    @Test fun workedExampleDropsTheThirdShot() {
        // 40°00.0', 40°12.0', 40°34.0' at 0 / 1 / 2 min: the last one is 10' off the trend of the first two. A fit
        // through all three would have pushed out the good middle shot instead.
        val s = shots(0.0, 12.0, 34.0)
        assertEquals(listOf(0, 1), keptIdx(s))
        assertEquals(listOf(0, 1), keptIdx(s, rate = 12.0))
        assertEquals(10.0, Workbook.residuals(s)[2]!!, 0.01)
    }

    @Test fun outlierFirstMiddleOrLastWithThreeShots() {
        // body rising 2' a minute (rate from the almanac); a 5' blunder in each place in turn
        assertEquals(listOf(1, 2), keptIdx(shots(5.0, 2.0, 4.0), rate = 2.0))
        assertEquals(listOf(0, 2), keptIdx(shots(0.0, 7.0, 4.0), rate = 2.0))
        assertEquals(listOf(0, 1), keptIdx(shots(0.0, 2.0, 9.0), rate = 2.0))
        // faster body, no rate given: the trend of the other two still finds it
        assertEquals(listOf(1, 2), keptIdx(shots(-6.0, 12.0, 24.0)))
        assertEquals(listOf(0, 1), keptIdx(shots(0.0, 12.0, 30.0)))
    }

    @Test fun outlierFirstMiddleOrLastWithFourAndFiveShots() {
        // 12' a minute, a 6' blunder; no rate given
        assertEquals(listOf(1, 2, 3), keptIdx(shots(6.0, 12.0, 24.0, 36.0)))
        assertEquals(listOf(0, 1, 3), keptIdx(shots(0.0, 12.0, 30.0, 36.0)))
        assertEquals(listOf(0, 1, 2), keptIdx(shots(0.0, 12.0, 24.0, 30.0)))
        assertEquals(listOf(1, 2, 3, 4), keptIdx(shots(-5.0, 12.0, 24.0, 36.0, 48.0)))
        assertEquals(listOf(0, 1, 3, 4), keptIdx(shots(0.0, 12.0, 29.0, 36.0, 48.0)))
        assertEquals(listOf(0, 1, 2, 3), keptIdx(shots(0.0, 12.0, 24.0, 36.0, 53.0)))
        // two blunders in five (with the rate known, as the workbook has it): both go, worst first
        assertEquals(listOf(1, 2, 3), keptIdx(shots(8.0, 12.0, 24.0, 36.0, 42.0), rate = 12.0))
    }

    @Test fun goodShotsAreAllKept() {
        // small scatter (under 1') is never dropped
        assertEquals(listOf(0, 1, 2, 3, 4), keptIdx(shots(0.0, 12.6, 23.5, 36.4, 47.8)))
        assertEquals(listOf(0, 1, 2), keptIdx(shots(0.0, 0.4, -0.3), rate = 0.0))
        // fewer than 3 shots: nothing to compare, nothing dropped
        assertEquals(listOf(0, 1), keptIdx(shots(0.0, 20.0)))
        assertEquals(listOf(null, null), Workbook.residuals(shots(0.0, 20.0)))
    }

    @Test fun workbookChecksShotsAgainstTheBodysOwnRate() {
        // real Vega shots at its real rate, the first one 5' high: the workbook drops it and says why
        val vega = SkyBody.byName("Vega")!!
        val good = (0..3).map { i -> val t = t0 + i * 60_000L; Shot(t, SightReduction.altAz(vega, p, t).hcDeg + 0.5, p) }
        val bad = listOf(good[0].copy(hsDeg = good[0].hsDeg + 5.0 / 60.0)) + good.drop(1)
        val w = Workbook.work(WorkbookSight(1, vega, Limb.CENTER, bad), WorkbookSettings())
        assertEquals(3, w.shotsUsed)
        assertTrue(bad[0] !in w.kept)
        assertEquals(5.0, w.residuals[0]!!, 0.2)
        val rate = Workbook.hsRate(vega, bad)!!
        val actual = (good[3].hsDeg - good[0].hsDeg) * 60.0 / 3.0
        assertEquals(actual, rate, 0.05)
    }

    // ---------------------------------------------------------------- typed values

    @Test fun parseReadsTheUsualForms() {
        assertEquals(223.0 + 14.2 / 60.0, Workbook.parse("223 14.2", FieldKind.ANGLE)!!, 1e-9)
        assertEquals(223.0 + 14.2 / 60.0, Workbook.parse("223°14.2'", FieldKind.ANGLE)!!, 1e-9)
        assertEquals(223.237, Workbook.parse("223.237", FieldKind.ANGLE)!!, 1e-9)
        assertEquals(-(9.0 + 32.4 / 60.0), Workbook.parse("S 9 32.4", FieldKind.DEC)!!, 1e-9)
        assertEquals(-(9.0 + 32.4 / 60.0), Workbook.parse("-9 32.4", FieldKind.DEC)!!, 1e-9)
        assertEquals(43.0 + 4.0 / 60.0, Workbook.parse("43 04.0N", FieldKind.LAT)!!, 1e-9)
        assertEquals(-(86.0 + 12.0 / 60.0), Workbook.parse("86 12.0W", FieldKind.LON)!!, 1e-9)
        assertEquals(151.2, Workbook.parse("151 12.0 E", FieldKind.LON)!!, 1e-9)
        assertEquals(-3.1, Workbook.parse("-3.1", FieldKind.MINUTES)!!, 1e-9)
        assertEquals(-3.1, Workbook.parse("−3.1", FieldKind.MINUTES)!!, 1e-9)       // typographic minus
        assertEquals(12.5, Workbook.parse("12,5", FieldKind.MINUTES)!!, 1e-9)       // decimal comma
        assertEquals(78.4, Workbook.parse("78.4", FieldKind.BEARING)!!, 1e-9)
        assertEquals(4.2, Workbook.parse("+4.2", FieldKind.NM)!!, 1e-9)
        assertEquals(-4.2, Workbook.parse("4.2A", FieldKind.NM)!!, 1e-9)
        assertEquals(4.2, Workbook.parse("4.2T", FieldKind.NM)!!, 1e-9)
    }

    @Test fun parseRejectsJunkNonFiniteAndOutOfRange() {
        assertNull(Workbook.parse("", FieldKind.ANGLE))
        assertNull(Workbook.parse("abc", FieldKind.MINUTES))
        assertNull(Workbook.parse("NaN", FieldKind.MINUTES))
        assertNull(Workbook.parse("1e999", FieldKind.MINUTES))
        assertNull(Workbook.parse("1e999", FieldKind.ALTITUDE))
        assertNull(Workbook.parse("Infinity", FieldKind.NM))
        assertNull(Workbook.parse("91 00", FieldKind.LAT))
        assertNull(Workbook.parse("N 90 30", FieldKind.DEC))
        assertNull(Workbook.parse("181", FieldKind.LON))
        assertNull(Workbook.parse("361", FieldKind.ANGLE))
        assertNull(Workbook.parse("95 00", FieldKind.ALTITUDE))
        assertNull(Workbook.parse("12 61", FieldKind.ANGLE))                         // minutes must be under 60
        assertNull(Workbook.parse("12 30 15", FieldKind.ANGLE))
        assertNull(Workbook.parse("1 2", FieldKind.MINUTES))
        assertEquals(90.0, Workbook.parse("90", FieldKind.LAT)!!, 1e-9)              // the limits themselves are fine
        assertEquals(-180.0, Workbook.parse("180W", FieldKind.LON)!!, 1e-9)
        assertNull(Workbook.parseTimeOfDay("19:46:NaN", t0, 0))
    }
}
