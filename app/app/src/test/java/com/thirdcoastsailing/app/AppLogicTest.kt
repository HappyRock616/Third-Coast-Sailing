package com.thirdcoastsailing.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the app's Android-free logic outside the celestial package: the anchor alarm rules, barograph maths,
 * light patterns, the GRIB2 reader's handling of damaged files, weather zone grouping and number entry.
 */
class AppLogicTest {

    // ------------------------------------------------------------------ anchor alarm rules

    @Test fun anchor_insideCircle_isChecked_andDoesNotAlarm() {
        assertEquals(AnchorRules.OnFix.CHECK, AnchorRules.onFix(60.0, 15.0, 100.0, rechecking = false, alarming = false, snoozed = false))
        assertFalse(AnchorRules.shouldAlarm(outside = false, rechecking = false, fixLost = false, snoozed = false))
    }

    @Test fun anchor_borderlineFix_isRecheckedOnce() {
        // 110 ft out with 20 ft of GPS error: could still be inside the 100 ft circle
        assertEquals(AnchorRules.OnFix.RECHECK, AnchorRules.onFix(110.0, 20.0, 100.0, rechecking = false, alarming = false, snoozed = false))
        // the recheck's own fix decides, even if it is borderline again
        assertEquals(AnchorRules.OnFix.CHECK, AnchorRules.onFix(110.0, 20.0, 100.0, rechecking = true, alarming = false, snoozed = false))
        // a fix with no accuracy is taken as exact
        assertEquals(AnchorRules.OnFix.CHECK, AnchorRules.onFix(110.0, 0.0, 100.0, rechecking = false, alarming = false, snoozed = false))
    }

    @Test fun anchor_clearlyOutside_alarmsAtOnce() {
        assertEquals(AnchorRules.OnFix.CHECK, AnchorRules.onFix(180.0, 20.0, 100.0, rechecking = false, alarming = false, snoozed = false))
        assertTrue(AnchorRules.shouldAlarm(outside = true, rechecking = false, fixLost = false, snoozed = false))
    }

    @Test fun anchor_noAlarmWhileRecheckingOrSilenced_butLostFixAlarms() {
        assertFalse(AnchorRules.shouldAlarm(outside = true, rechecking = true, fixLost = false, snoozed = false))
        assertFalse(AnchorRules.shouldAlarm(outside = true, rechecking = false, fixLost = false, snoozed = true))
        assertTrue(AnchorRules.shouldAlarm(outside = false, rechecking = true, fixLost = true, snoozed = false))
        assertFalse(AnchorRules.shouldAlarm(outside = false, rechecking = false, fixLost = true, snoozed = true))
    }

    @Test fun anchor_fixLostAfterTenMinutes_andNextCheckTimes() {
        val t0 = 1_000_000_000L
        assertFalse(AnchorRules.fixLost(t0 + 9 * 60_000L, t0))
        assertTrue(AnchorRules.fixLost(t0 + 10 * 60_000L, t0))
        // next check: when the fix would count as lost (plus 5 s), or earlier when a silence ends
        assertEquals(t0 + AnchorWatch.LOST_MS + 5_000L, AnchorRules.nextCheckMs(t0, t0, 0L))
        assertEquals(t0 + 60_000L, AnchorRules.nextCheckMs(t0, t0, t0 + 60_000L))
        // fix already lost and not silenced: check again in ten minutes
        assertEquals(t0 + 20 * 60_000L + AnchorWatch.LOST_MS, AnchorRules.nextCheckMs(t0 + 20 * 60_000L, t0, 0L))
    }

    @Test fun anchor_typedRadius_ignoresValuesStillBeingTyped() {
        assertNull(AnchorRules.typedRadius(""))
        assertNull(AnchorRules.typedRadius("1"))          // on the way to "100" or "150"
        assertNull(AnchorRules.typedRadius("10"))
        assertEquals(100.0, AnchorRules.typedRadius("100")!!, 0.0)
        assertEquals(AnchorWatch.MAX_RADIUS_FT, AnchorRules.typedRadius("9999")!!, 0.0)
    }

    // ------------------------------------------------------------------ barograph

    @Test fun baro_surfaceHeight_isSeaLevelOnTheCoast() {
        assertEquals(0.0, BaroMath.lakeElevationM(42.36, -71.05), 0.0)      // Boston
        assertEquals(0.0, BaroMath.lakeElevationM(40.70, -74.01), 0.0)      // New York
        assertEquals(0.0, BaroMath.lakeElevationM(25.77, -80.19), 0.0)      // Miami
        assertEquals(0.0, BaroMath.lakeElevationM(29.30, -94.79), 0.0)      // Galveston
        assertEquals(0.0, BaroMath.lakeElevationM(47.60, -122.33), 0.0)     // Seattle
        assertEquals(0.0, BaroMath.lakeElevationM(21.30, -157.86), 0.0)     // Honolulu
    }

    @Test fun baro_surfaceHeight_onEachLake() {
        assertEquals(74.7, BaroMath.lakeElevationM(43.6, -77.5), 0.0)       // Lake Ontario
        assertEquals(174.2, BaroMath.lakeElevationM(42.2, -81.2), 0.0)      // Lake Erie
        assertEquals(174.2, BaroMath.lakeElevationM(42.49, -79.34), 0.0)    // Lake Erie's east end: Dunkirk
        assertEquals(174.2, BaroMath.lakeElevationM(42.88, -78.89), 0.0)    // Buffalo
        assertEquals(74.7, BaroMath.lakeElevationM(43.26, -79.07), 0.0)     // Lake Ontario at the Niagara River mouth
        assertEquals(176.0, BaroMath.lakeElevationM(43.0, -82.42), 0.0)     // Lake Huron's south end: Sarnia
        assertEquals(183.4, BaroMath.lakeElevationM(47.5, -88.0), 0.0)      // Lake Superior
        assertEquals(176.0, BaroMath.lakeElevationM(44.0, -87.0), 0.0)      // Lake Michigan
        assertEquals(176.0, BaroMath.lakeElevationM(45.0, -82.5), 0.0)      // Lake Huron
        assertEquals(176.0, BaroMath.lakeElevationM(null, null), 0.0)       // no position yet
    }

    /** A log with one reading every 5 minutes; `hPaPerHour` is the steady rate of change. */
    private fun steadyLog(hours: Int, hPaPerHour: Double): List<BaroPoint> {
        val t0 = 1_700_000_000_000L
        return (0..hours * 12).map { i -> BaroPoint(t0 + i * 5 * 60_000L, 1015.0 + hPaPerHour * i / 12.0) }
    }

    @Test fun baro_countUpTo_findsThePlaceInTheLog() {
        val log = steadyLog(2, 0.0)
        assertEquals(0, BaroMath.countUpTo(log, log.first().t - 1))
        assertEquals(1, BaroMath.countUpTo(log, log.first().t))
        assertEquals(log.size, BaroMath.countUpTo(log, log.last().t))
        assertEquals(log.size, BaroMath.countUpTo(log, log.last().t + 1))
        assertEquals(0, BaroMath.countUpTo(emptyList(), 5L))
    }

    @Test fun baro_rates_matchASteadyFall_anywhereInTheLog() {
        val log = steadyLog(8, -2.0)
        assertEquals(-2.0, BaroMath.rate1h(log, log.last().t)!!, 1e-6)
        assertEquals(-6.0, BaroMath.rate3h(log, log.last().t)!!, 1e-6)
        // in the middle of the log too (the window search must not depend on being at the end)
        val mid = log[log.size / 2].t
        assertEquals(-2.0, BaroMath.rate1h(log, mid)!!, 1e-6)
        assertEquals(-6.0, BaroMath.rate3h(log, mid)!!, 1e-6)
        // not enough readings before the start of the log
        assertNull(BaroMath.rate3h(log, log[6].t))
    }

    // ------------------------------------------------------------------ light patterns

    @Test fun light_onlyFixedIsSteady() {
        val fixed = LightPattern.of("F", "1", null, null)
        assertNotNull(fixed.colourAt(0.0)); assertNotNull(fixed.colourAt(0.7))
        assertTrue(LightPattern.plainWords("F", "3", null, null).startsWith("Steady red"))
    }

    @Test fun light_quickWithoutPeriod_flashes_andIsNotCalledSteady() {
        val q = LightPattern.of("Q", "4", null, null)
        assertEquals(1.0, q.periodS, 0.0)
        assertNotNull(q.colourAt(0.1)); assertNull(q.colourAt(0.6))
        val vq = LightPattern.of("VQ", "1", null, null)
        assertEquals(0.5, vq.periodS, 0.0)
        assertFalse(LightPattern.plainWords("Q", "4", null, null).contains("Steady"))
        assertFalse(LightPattern.plainWords("Fl", "4", null, null).contains("Steady"))
        assertTrue(LightPattern.plainWords("Fl", "4", null, null).contains("period not charted"))
    }

    @Test fun light_groupWithoutPeriod_keepsItsShape() {
        // "Q(3)" with no charted period: three separate flashes and a dark rest, not one near-steady light
        val p = LightPattern.of("Q(3)", "1", null, null)
        assertEquals(3, p.on.size)
        assertTrue(p.on[1].from >= p.on[0].to)                  // flashes don't run into each other
        assertTrue("dark rest", p.periodS - p.on.last().to >= 2.0)
        // a NOAA sequence without a period gives the period
        assertEquals(6.0, LightPattern.of("Fl", "4", null, "00.5+(05.5)").periodS, 1e-9)
    }

    @Test fun light_southCardinal_keepsItsLongFlash() {
        // Q(6)+LFl 15 s: six quick flashes, then a long one
        val p = LightPattern.of("Q(6)+LFl", "1", 15.0, null)
        assertEquals(7, p.on.size)
        assertTrue(p.on.last().to - p.on.last().from >= 1.9)
        assertTrue(p.on.last().to <= 15.0)
        assertTrue(LightPattern.plainWords("Q(6)+LFl", "1", 15.0, null).contains("then a long flash"))
    }

    @Test fun light_compositeGroup_hasALongerGapBetweenGroups() {
        // Fl(2+1): two flashes, a longer gap, one flash
        val p = LightPattern.of("Fl(2+1)", "3", 12.0, null)
        assertEquals(3, p.on.size)
        val gap1 = p.on[1].from - p.on[0].from
        val gap2 = p.on[2].from - p.on[1].from
        assertTrue("second gap $gap2 should be longer than the first $gap1", gap2 > gap1 * 1.5)
    }

    @Test fun light_ruleNeverRunsPastThePeriod() {
        for (chr in listOf("Fl", "Fl(3)", "Q(9)", "VQ(6)+LFl", "LFl", "Iso", "Oc", "Mo(A)", "Fl(4+3)")) for (per in listOf(2.0, 5.0, 10.0, 15.0)) {
            val total = LightPattern.rule(chr, per).sumOf { it.second }
            assertEquals("$chr $per s", per, total, 0.05)
            assertTrue("$chr $per s", LightPattern.rule(chr, per).all { it.second >= 0.0 })
        }
    }

    @Test fun light_noaaSequenceIsUsedWhenItMatchesThePeriod() {
        val p = LightPattern.of("Fl", "4", 4.0, "00.4+(03.6)")
        assertEquals(1, p.on.size)
        assertEquals(0.0, p.on[0].from, 0.0)
        assertNull(p.colourAt(2.0))
    }

    // ------------------------------------------------------------------ GRIB2: damaged files must not hang or crash

    /** A "GRIB" header with the given edition and total length, padded with zeros. */
    private fun gribHeader(edition: Int, total: Long, size: Int = 64): ByteArray {
        val b = ByteArray(size)
        "GRIB".toByteArray().copyInto(b)
        b[7] = edition.toByte()
        for (i in 0 until 8) b[8 + i] = (total ushr (8 * (7 - i))).toByte()
        return b
    }

    @Test(timeout = 5_000) fun grib_zeroLengthMessage_doesNotLoopForever() {
        assertTrue(Grib2.index(gribHeader(2, 0)).isEmpty())
    }

    @Test(timeout = 5_000) fun grib_otherEditionsAndGarbage_areSkipped() {
        assertTrue(Grib2.index(gribHeader(1, 0)).isEmpty())
        assertTrue(Grib2.index(gribHeader(2, 1_000_000)).isEmpty())          // length beyond the file, sections all zero
        assertTrue(Grib2.index(ByteArray(0)).isEmpty())
        assertTrue(Grib2.index("GRIBGRIBGRIBGRIB".toByteArray()).isEmpty())
        // two headers in a row: the search must move past the first
        assertTrue(Grib2.index(gribHeader(2, 0) + gribHeader(2, 0)).isEmpty())
    }

    // ------------------------------------------------------------------ weather zones and number entry

    @Test fun weather_zonesGroupByCoast() {
        assertEquals("atlantic", WeatherRepo.coastOf("maine"))
        assertEquals("atlantic", WeatherRepo.coastOf("chesapeake"))
        assertEquals("atlantic", WeatherRepo.coastOf("florida_east"))
        assertEquals("gulf", WeatherRepo.coastOf("florida_west"))
        assertEquals("gulf", WeatherRepo.coastOf("texas"))
        assertEquals("pacific", WeatherRepo.coastOf("washington"))
        assertEquals("pacific", WeatherRepo.coastOf("southern_california"))
        assertEquals("hawaii", WeatherRepo.coastOf("hawaii"))
        // every coastal zone lands on one of the four coasts
        for (z in WeatherRepo.LAKE_BOX.keys - WeatherRepo.GREAT_LAKES)
            assertTrue(z, WeatherRepo.coastOf(z) in setOf("atlantic", "gulf", "pacific", "hawaii"))
    }

    @Test fun numbers_acceptADecimalComma_andRefuseNonNumbers() {
        assertEquals(4.9, "4,9".toDecimalOrNull()!!, 0.0)
        assertEquals(4.9, " 4.9 ".toDecimalOrNull()!!, 0.0)
        assertNull("".toDecimalOrNull())
        assertNull("abc".toDecimalOrNull())
        assertNull("NaN".toDecimalOrNull())
        assertNull("1e999".toDecimalOrNull())
    }

    @Test fun polar_exportUsesADecimalPoint_whateverThePhoneLanguage() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val polar = Polar(listOf(6.0, 10.0), listOf(0.0, 90.0), listOf(listOf(0.0, 0.0), listOf(4.25, 6.5)))
            val text = polar.toPol()
            assertTrue(text, text.contains("4.25") && text.contains("6.50") && !text.contains("4,25"))
            // and it reads back as the same table
            assertEquals(6.5, Polar.parse(text).speed[1][1], 1e-9)
        } finally { java.util.Locale.setDefault(saved) }
    }
}
