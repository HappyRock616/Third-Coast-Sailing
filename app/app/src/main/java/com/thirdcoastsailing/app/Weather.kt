package com.thirdcoastsailing.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which forecast quantity. */
enum class Wx { WIND_SPEED, WIND_DIR, GUST, WAVE_HEIGHT, WAVE_DIR, WAVE_PERIOD }

/**
 * Forecast weather downloaded straight from NOAA by the phone (no relay server):
 *  - wind speed, direction and gusts: NWS NDFD regional sector files (GRIB2), days 1-3
 *  - waves: NOAA GLWU Great Lakes wave model (lakes) and GFS-Wave global 0.16° (coastal zones) via the NOMADS
 *    filter, trimmed to each imported region
 * Files are kept in files/weather/ so the last forecast works offline.
 */
/** Largest forecast file accepted (NDFD sectors and the per-lake wave cut-outs are a few MB). */
private const val MAX_FORECAST_BYTES = 100_000_000L
/** Largest NOMADS directory listing accepted. */
private const val MAX_LISTING_BYTES = 2_000_000

/** Downloads NOAA wind (NDFD) and wave (GLWU / GFS-Wave) forecasts for the installed regions into files/weather/, and reads them back as fields that can be sampled at any point and time. */
class WeatherRepo(private val context: Context) {

    private val dir = File(context.filesDir, "weather").apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")
    private val ua = "ThirdCoastSailing/${BuildConfig.VERSION_NAME} (Android)"

    // NDFD sectors needed to cover each lake / coastal zone (from the coverage trials, Docs\weather_coast_trial_report.md)
    private val sectors = mapOf(
        "superior" to listOf("umissvly", "nplains", "crgrlake"),
        "michigan" to listOf("crgrlake", "umissvly"),
        "huron" to listOf("crgrlake"),
        "erie" to listOf("crgrlake"),
        "ontario" to listOf("ergrlake"),
        "maine" to listOf("neast"), "mass_ri" to listOf("neast"), "long_island" to listOf("neast"),
        "new_jersey" to listOf("neast"), "chesapeake" to listOf("midatlan"), "north_carolina" to listOf("midatlan"),
        "carolina_georgia" to listOf("midatlan", "seast"), "florida_east" to listOf("seast"),
        "florida_keys" to listOf("seast"), "florida_west" to listOf("seast"), "northern_gulf" to listOf("seast"),
        "louisiana" to listOf("smissvly", "splains", "seast"), "texas" to listOf("splains"),
        "southern_california" to listOf("pacswest"), "central_california" to listOf("pacswest"),
        "norcal_oregon" to listOf("pacnwest", "pacswest"), "washington" to listOf("pacnwest"),
        "hawaii" to listOf("hawaii"),
    )
    private val ndfdBase = "https://tgftp.nws.noaa.gov/SL.us008001/ST.opnl/DF.gr2/DC.ndfd"
    private val glwuFilter = "https://nomads.ncep.noaa.gov/cgi-bin/filter_glwu.pl"
    // Coastal waves: NOAA GFS-Wave global 0.16° grid (GLWU covers only the lakes); one file per forecast hour
    private val gfswFilter = "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfswave.pl"
    private val gfswHours = (0..72 step 3).toList()

    /** Time of the last successful update cycle (ms), 0 if never (written on the download thread, read on the main one). */
    @Volatile var lastUpdateMs: Long = 0
        private set

    init { readMeta()?.let { lastUpdateMs = it.optLong("lastUpdate", 0) } }

    // ------------------------------------------------------------------ download

    /**
     * One update cycle for the given lakes. Wind files are only downloaded when NOAA has a newer copy
     * (If-Modified-Since); waves only when a new model run is out. Returns a short status line.
     */
    fun update(lakes: List<String>): String = synchronized(UPDATE_LOCK) { updateLocked(lakes) }

    private fun updateLocked(lakes: List<String>): String {
        val meta = readMeta() ?: JSONObject()
        var got = 0
        var errors = 0
        // Wind: one set of files per needed sector
        for (sec in lakes.flatMap { sectors[it] ?: emptyList() }.distinct()) {
            for (el in listOf("wspd", "wdir", "wgust")) {
                val file = File(dir, "ndfd_${sec}_$el.bin")
                val key = "lm_ndfd_${sec}_$el"
                when (val r = fetch("$ndfdBase/AR.$sec/VP.001-003/ds.$el.bin", file, meta.optString(key).ifEmpty { null })) {
                    is Fetch.Ok -> { got++; r.lastModified?.let { meta.put(key, it) } }
                    is Fetch.NotModified -> {}
                    is Fetch.Failed -> errors++
                }
            }
        }
        // Waves: newest complete GLWU short-range run, per lake (coastal zones: GFS-Wave below)
        val greatLakes = lakes.filter { it in GREAT_LAKES }
        val zones = lakes.filter { it !in GREAT_LAKES && LAKE_BOX.containsKey(it) }
        if (greatLakes.isNotEmpty()) runCatching { latestGlwuRun() }.getOrNull()?.let { (day, file) ->
            for (lake in greatLakes) {
                val box = LAKE_BOX[lake] ?: continue
                val out = File(dir, "glwu_$lake.grib2")
                val key = "glwu_run_$lake"
                if (meta.optString(key) == "$day/$file" && out.exists()) continue
                val q = linkedMapOf(
                    "dir" to "/glwu.$day", "file" to file, "var_HTSGW" to "on", "var_DIRPW" to "on", "var_PERPW" to "on",
                    "all_lev" to "on", "subregion" to "", "leftlon" to "${box[0]}", "rightlon" to "${box[2]}",
                    "toplat" to "${box[3]}", "bottomlat" to "${box[1]}",
                )
                val url = glwuFilter + "?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                when (fetch(url, out, null, ::isGrib)) {
                    is Fetch.Ok -> { got++; meta.put(key, "$day/$file") }
                    is Fetch.NotModified -> {}
                    is Fetch.Failed -> errors++
                }
            }
        } ?: run { errors++ }
        // Coastal waves: GFS-Wave, one cut-out per coast (the installed zones on it together) per forecast hour,
        // joined into one file per coast. One request a second at most: NOMADS blocks clients that ask too often.
        val coasts = zones.groupBy { coastOf(it) }
        /** Wave files with these names (without "gfsw_" and ".grib2") are dropped. */
        fun dropWaveFiles(drop: (String) -> Boolean) = dir.listFiles()?.filter { f ->
            f.name.startsWith("gfsw_") && f.name.endsWith(".grib2") && drop(f.name.removePrefix("gfsw_").removeSuffix(".grib2"))
        }?.forEach { it.delete() }
        // files of coasts and zones that are no longer installed (a per-zone file from an older version is kept until
        // its coast's file has downloaded, see below, so an update without a connection doesn't lose the saved waves)
        dropWaveFiles { it !in coasts.keys && it !in zones }
        if (zones.isNotEmpty()) runCatching { latestGfswRun() }.getOrNull()?.let { (day, hh) ->
            for ((coast, ids) in coasts) {
                val box = boxFor(ids) ?: continue
                val out = File(dir, "gfsw_$coast.grib2")
                val key = "gfsw_run_$coast"
                // the model run and the box it was cut for: installing another zone on the same coast changes the
                // box, and the file is downloaded again for it (not only when the next run is out)
                val run = "$day/gfswave.t${hh}z|" + box.joinToString(",")
                if (meta.optString(key) == run && out.exists()) continue
                val joined = File(dir, "gfsw_$coast.grib2.join")
                val hour = File(dir, "gfsw_$coast.hour")
                var ok = true
                joined.outputStream().use { o ->
                    for (h in gfswHours) {
                        val q = linkedMapOf(
                            "dir" to "/gfs.$day/$hh/wave/gridded", "file" to "gfswave.t${hh}z.global.0p16.f%03d.grib2".format(h),
                            "var_HTSGW" to "on", "var_DIRPW" to "on", "var_PERPW" to "on", "subregion" to "",
                            "leftlon" to "${box[0] - 0.25}", "rightlon" to "${box[2] + 0.25}",
                            "toplat" to "${box[3] + 0.25}", "bottomlat" to "${box[1] - 0.25}",
                        )
                        val url = gfswFilter + "?" + q.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                        // must be a GRIB file (an over-quota or error page can come back as an ordinary web page)
                        if (fetch(url, hour, null) !is Fetch.Ok || !isGrib(hour)) { ok = false; break }
                        hour.inputStream().use { it.copyTo(o) }
                        Thread.sleep(1_000)                               // gentle on NOMADS (rate-limited)
                    }
                }
                hour.delete()
                if (ok && (joined.renameTo(out) || (out.delete() && joined.renameTo(out)))) {
                    got++; meta.put(key, run)
                    dropWaveFiles { it in ids }                           // per-zone files from older versions
                } else { joined.delete(); errors++ }
            }
        } ?: run { errors++ }
        if (errors == 0 || got > 0) {
            lastUpdateMs = System.currentTimeMillis()
            meta.put("lastUpdate", lastUpdateMs)
        }
        // written to a temporary file and renamed, so a crash mid-write can't lose the saved state
        val tmp = File(metaFile.path + ".tmp")
        runCatching { tmp.writeText(meta.toString()); if (!tmp.renameTo(metaFile)) { metaFile.delete(); tmp.renameTo(metaFile) } }
        synchronized(this) { cache.clear(); loaded = null }
        return when {
            errors == 0 -> "Weather updated"
            got > 0 -> "Weather partly updated ($errors failed)"
            else -> "Weather update failed (no connection?)"
        }
    }

    /**
     * (day, file name) of the second-newest GLWU short-range run; the newest may still be writing. A new day's folder
     * with only one run (00-01Z) doesn't count yet: then the newest run of the day before is used.
     */
    private fun latestGlwuRun(): Pair<String, String>? {
        val page = text(glwuFilter) ?: return null
        val days = Regex("glwu\\.(\\d{8})").findAll(page).map { it.groupValues[1] }.distinct().sortedDescending().toList()
        var skippedNewest = false
        for (day in days) {
            val p = text("$glwuFilter?dir=%2Fglwu.$day") ?: continue
            val files = Regex("glwu\\.grlc_2p5km_sr\\.t(\\d\\d)z\\.grib2").findAll(p).map { it.value }.distinct().sorted().toList()
            if (files.isEmpty()) continue
            if (skippedNewest) return day to files.last()                  // the newest of the day before
            if (files.size > 1) return day to files[files.size - 2]
            skippedNewest = true                                           // only one run so far: may still be writing
        }
        return null
    }

    /**
     * (day, cycle hour) of the newest GFS cycle whose global 0.16° wave grids are out through the last hour needed
     * (NOMADS lists files as they are written, so a cycle still running lacks the later hours).
     */
    private fun latestGfswRun(): Pair<String, String>? {
        val page = text(gfswFilter) ?: return null
        val days = Regex("gfs\\.(\\d{8})").findAll(page).map { it.groupValues[1] }.distinct().sortedDescending().take(2)
        val last = "global.0p16.f%03d.grib2".format(gfswHours.last())
        for (day in days) {
            val p = text("$gfswFilter?dir=%2Fgfs.$day") ?: continue
            val hours = Regex("gfs\\.$day%2F(\\d\\d)").findAll(p).map { it.groupValues[1] }.distinct().sortedDescending()
            for (hh in hours) {
                val files = text("$gfswFilter?dir=%2Fgfs.$day%2F$hh%2Fwave%2Fgridded") ?: continue
                if (files.contains("gfswave.t${hh}z.$last")) return day to hh
            }
        }
        return null
    }

    /** Result of one download: new file (with the server's Last-Modified), unchanged since last time, or failed. */
    private sealed class Fetch {
        data class Ok(val lastModified: String?) : Fetch()
        object NotModified : Fetch()
        object Failed : Fetch()
    }

    /**
     * GET to a file (via .part), with If-Modified-Since when known. Up to 3 tries, but only for errors that may pass
     * (no connection, timeouts, server errors 5xx); "not found" and other refusals fail at once, with no waiting.
     */
    private fun fetch(url: String, out: File, ifModifiedSince: String?, check: ((File) -> Boolean)? = null): Fetch {
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(2000L * attempt)
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000
                    setRequestProperty("User-Agent", ua)
                    if (ifModifiedSince != null && out.exists()) setRequestProperty("If-Modified-Since", ifModifiedSince)
                }
                try {
                    when (c.responseCode) {
                        304 -> return Fetch.NotModified
                        200 -> {
                            val part = File(out.path + ".part")
                            // forecast files are a few MB: anything over MAX_FORECAST_BYTES is refused
                            c.inputStream.use { i -> part.outputStream().use { o -> CatalogTrust.copyCapped(i, o, MAX_FORECAST_BYTES) } }
                            // not what was asked for (e.g. an error page sent with 200): keep the old file
                            if (check != null && !check(part)) { part.delete(); return Fetch.Failed }
                            if (!part.renameTo(out)) { out.delete(); if (!part.renameTo(out)) return Fetch.Failed }
                            return Fetch.Ok(c.getHeaderField("Last-Modified"))
                        }
                        in 500..599 -> {}                                    // server trouble: try again
                        else -> return Fetch.Failed                          // 404 and the like: no point retrying
                    }
                } finally { c.disconnect() }
            } catch (_: java.io.IOException) { }                             // no connection or timeout: try again
        }
        return Fetch.Failed
    }

    /** True when the file starts with "GRIB". */
    private fun isGrib(f: File): Boolean = runCatching {
        f.inputStream().use { i -> val b = ByteArray(4); i.read(b) == 4 && String(b, Charsets.US_ASCII) == "GRIB" }
    }.getOrDefault(false)

    /** GET a small text page (a NOMADS directory listing), or null on any failure. */
    private fun text(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; setRequestProperty("User-Agent", ua)
        }
        // directory listings are a few KB: read at most MAX_LISTING_BYTES
        try { if (c.responseCode == 200) String(c.inputStream.use { CatalogTrust.readCapped(it, MAX_LISTING_BYTES) }, Charsets.UTF_8) else null }
        finally { c.disconnect() }
    }.getOrNull()

    /** Saved update state (last update time, Last-Modified per file), or null before the first update. */
    private fun readMeta(): JSONObject? = runCatching { JSONObject(metaFile.readText()) }.getOrNull()

    // ------------------------------------------------------------------ reading the downloaded files

    /** One forecast message: its quantity, valid time, source file name and bytes. */
    private class Entry(val kind: Wx, val validMs: Long, val src: String, val bytes: ByteArray, val msg: Grib2.Message)

    private var loaded: List<Entry>? = null
    private val cache = object : LinkedHashMap<Entry, FloatArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Entry, FloatArray>?) = size > 32
    }

    /** Which forecast quantity a GRIB2 message holds (by WMO discipline, category and parameter number), or null for ones the app doesn't use. */
    private fun kindOf(m: Grib2.Message): Wx? = when {
        m.discipline == 0 && m.category == 2 && m.number == 1 -> Wx.WIND_SPEED
        m.discipline == 0 && m.category == 2 && m.number == 0 -> Wx.WIND_DIR
        m.discipline == 0 && m.category == 2 && m.number == 22 -> Wx.GUST
        m.discipline == 10 && m.category == 0 && m.number == 3 -> Wx.WAVE_HEIGHT
        m.discipline == 10 && m.category == 0 && m.number == 10 -> Wx.WAVE_DIR
        m.discipline == 10 && m.category == 0 && m.number == 11 -> Wx.WAVE_PERIOD
        else -> null
    }

    /** Every usable forecast message in the downloaded files, indexed once and kept until the next update. */
    @Synchronized
    private fun entries(): List<Entry> {
        loaded?.let { return it }
        val list = mutableListOf<Entry>()
        val all = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }
        // GLWU (finer, lake-trimmed) first, then GFS-Wave (coastal zones); NDFD sectors in name order so speed and
        // direction come from the same sector
        val files = all.filter { it.name.startsWith("glwu_") && it.name.endsWith(".grib2") } +
                all.filter { it.name.startsWith("gfsw_") && it.name.endsWith(".grib2") } +
                all.filter { it.name.startsWith("ndfd_") && it.name.endsWith(".bin") }
        for (f in files) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            val perRegion = f.name.startsWith("glwu_") || f.name.startsWith("gfsw_")
            val src = f.name.substringBeforeLast('_')                 // ndfd_crgrlake (wave files keep their own name)
            for (m in runCatching { Grib2.index(bytes) }.getOrDefault(emptyList())) {
                kindOf(m)?.let { list += Entry(it, m.validTimeMs, if (perRegion) f.name else src, bytes, m) }
            }
        }
        loaded = list
        return list
    }

    /** Decoded grid values of one message (kept in a small least-recently-used cache), or null if it can't be decoded. */
    @Synchronized
    private fun values(e: Entry): FloatArray? =
        cache[e] ?: runCatching { Grib2.decode(e.bytes, e.msg) }.getOrNull()?.also { cache[e] = it }

    /** Forecast hours available (wind or waves), sorted. */
    fun times(): List<Long> = entries().map { it.validMs }.distinct().sorted()

    /**
     * A decoded forecast quantity at one time, ready for fast repeated sampling (map overlays).
     * Holds one grid per source file, nearest forecast time within 3 h; the first source with a value wins.
     * Directions use the nearest grid point so 350 and 10 are never averaged to 180.
     */
    class Field(private val parts: List<Pair<Grib2.Grid, FloatArray>>, val kind: Wx, val validMs: Long) {
        private val nearest = kind == Wx.WIND_DIR || kind == Wx.WAVE_DIR
        fun at(lat: Double, lon: Double): Float {
            for ((g, v) in parts) {
                val x = if (nearest) g.sampleNearest(v, lat, lon) else g.sample(v, lat, lon)
                if (!x.isNaN()) return x
            }
            return Float.NaN
        }

        /** True when any source has a value at the nearest grid point (for the wave grid: the point is on the water). */
        fun hasNearest(lat: Double, lon: Double): Boolean = parts.any { (g, v) -> !g.sampleNearest(v, lat, lon).isNaN() }
    }

    /** Field for a quantity at a time, or null when nothing is forecast within 3 h. Decoding can take a moment: call off the main thread. */
    fun field(kind: Wx, timeMs: Long): Field? {
        val near = entries().filter { it.kind == kind && kotlin.math.abs(it.validMs - timeMs) <= 3 * 3_600_000L }
        if (near.isEmpty()) return null
        val picks = near.groupBy { it.src }.values.map { l -> l.minBy { kotlin.math.abs(it.validMs - timeMs) } }
        // a message that fails to decode is left out; the other sources still show
        val parts = picks.mapNotNull { e -> values(e)?.let { e.msg.grid to it } }
        if (parts.isEmpty()) return null
        return Field(parts, kind, picks.first().validMs)
    }

    /** Raw value of one quantity at a point and time, NaN when unknown. */
    fun sample(kind: Wx, timeMs: Long, lat: Double, lon: Double): Float = field(kind, timeMs)?.at(lat, lon) ?: Float.NaN

    /** Wind at a point: (speed kn, direction from deg true) or null. */
    fun wind(timeMs: Long, lat: Double, lon: Double): Pair<Double, Double>? {
        val s = sample(Wx.WIND_SPEED, timeMs, lat, lon)
        val d = sample(Wx.WIND_DIR, timeMs, lat, lon)
        if (s.isNaN() || d.isNaN()) return null
        return s * MS_TO_KN to ((d % 360) + 360) % 360.0
    }

    /** Forecast gust (knots) at a point and time, or null. */
    fun gustKn(timeMs: Long, lat: Double, lon: Double): Double? =
        sample(Wx.GUST, timeMs, lat, lon).takeIf { !it.isNaN() }?.let { it * MS_TO_KN }

    /** Waves: (height ft, direction from deg, period s) or null. */
    fun waves(timeMs: Long, lat: Double, lon: Double): Triple<Double, Double, Double>? {
        val h = sample(Wx.WAVE_HEIGHT, timeMs, lat, lon)
        if (h.isNaN()) return null
        val d = sample(Wx.WAVE_DIR, timeMs, lat, lon)
        val p = sample(Wx.WAVE_PERIOD, timeMs, lat, lon)
        return Triple(h * FT_PER_M, if (d.isNaN()) Double.NaN else d.toDouble(), if (p.isNaN()) Double.NaN else p.toDouble())
    }

    /** Wave model run in use, e.g. "Oct 12 12z" (newest across lakes), or null before the first download. */
    fun waveRun(): String? {
        val meta = readMeta() ?: return null
        val runs = meta.keys().asSequence().filter { it.startsWith("glwu_run_") || it.startsWith("gfsw_run_") }.map { meta.optString(it) }.toList()
        val best = runs.mapNotNull { r ->
            val m = Regex("(\\d{8})/[^|]*\\.t(\\d\\d)z").find(r) ?: return@mapNotNull null
            m.groupValues[1] + m.groupValues[2]
        }.maxOrNull() ?: return null
        val date = runCatching { SimpleDateFormat("yyyyMMdd", Locale.US).parse(best.take(8)) }.getOrNull() ?: return null
        return SimpleDateFormat("MMM d", Locale.US).format(date) + " " + best.drop(8) + "z"
    }

    companion object {
        /**
         * One weather update at a time, across every WeatherRepo instance: when the screen is rebuilt (back, reopen)
         * a second update could start while the first was still downloading, and the later one wrote back an older
         * copy of meta.json (wave run lost: "Model run -", and the waves downloaded again at every start).
         * The second one now waits, then finds everything current and skips it.
         */
        private val UPDATE_LOCK = Any()

        /** The five lakes (GLWU waves); every other region is a coastal zone (GFS-Wave). */
        val GREAT_LAKES = setOf("superior", "michigan", "huron", "erie", "ontario")

        /** Region boxes (lakes and coastal zones, same as 1_Chart_Build\regions.py): lon_min, lat_min, lon_max, lat_max. */
        val LAKE_BOX = mapOf(
            "superior" to doubleArrayOf(-92.3, 46.2, -84.2, 49.1), "michigan" to doubleArrayOf(-88.3, 41.5, -84.6, 46.3),
            "huron" to doubleArrayOf(-84.9, 42.9, -79.5, 46.6), "erie" to doubleArrayOf(-83.7, 41.3, -78.8, 43.1),
            "ontario" to doubleArrayOf(-80.0, 42.9, -75.7, 44.4),
            "maine" to doubleArrayOf(-71.20, 42.45, -66.80, 45.35), "mass_ri" to doubleArrayOf(-71.95, 40.95, -69.60, 42.75),
            "long_island" to doubleArrayOf(-74.45, 40.35, -71.75, 41.45), "new_jersey" to doubleArrayOf(-75.70, 38.35, -73.70, 40.55),
            "chesapeake" to doubleArrayOf(-77.50, 36.75, -74.60, 39.70), "north_carolina" to doubleArrayOf(-77.95, 33.70, -75.00, 36.95),
            "carolina_georgia" to doubleArrayOf(-81.65, 30.60, -77.70, 34.30), "florida_east" to doubleArrayOf(-81.75, 25.60, -79.70, 30.80),
            "florida_keys" to doubleArrayOf(-83.10, 24.35, -79.90, 25.95), "florida_west" to doubleArrayOf(-85.15, 25.70, -81.20, 30.25),
            "northern_gulf" to doubleArrayOf(-90.60, 28.85, -85.00, 30.80), "louisiana" to doubleArrayOf(-93.95, 28.40, -88.80, 30.10),
            "texas" to doubleArrayOf(-97.90, 25.80, -93.60, 30.00),
            "southern_california" to doubleArrayOf(-121.00, 32.45, -116.95, 34.60),
            "central_california" to doubleArrayOf(-123.95, 34.40, -120.30, 38.95),
            "norcal_oregon" to doubleArrayOf(-124.85, 38.80, -123.40, 46.30), "washington" to doubleArrayOf(-125.00, 46.00, -121.90, 49.05),
            "hawaii" to doubleArrayOf(-160.90, 18.60, -154.40, 22.60),
        )

        /** Which coast a zone is on, for the GFS-Wave downloads (one cut-out per coast). */
        fun coastOf(zone: String): String {
            val b = LAKE_BOX[zone] ?: return zone
            return when {
                b[2] < -150 -> "hawaii"
                b[2] < -115 -> "pacific"
                b[0] < -82.5 && b[3] < 31.0 -> "gulf"                     // the Keys and west: the Gulf of Mexico side
                else -> "atlantic"
            }
        }

        /** Box covering all the given regions, or null when none are known. */
        fun boxFor(ids: List<String>): DoubleArray? {
            val boxes = ids.mapNotNull { LAKE_BOX[it] }
            if (boxes.isEmpty()) return null
            return doubleArrayOf(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] })
        }

        fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
        fun dayHour(ms: Long): String = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(ms))
        fun stamp(ms: Long): String = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(ms))
    }
}
