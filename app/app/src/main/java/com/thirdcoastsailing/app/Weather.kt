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
 *  - waves: NOAA GLWU Great Lakes wave model via the NOMADS filter, trimmed to each imported lake
 * Files are kept in files/weather/ so the last forecast works offline.
 */
class WeatherRepo(private val context: Context) {

    private val dir = File(context.filesDir, "weather").apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")
    private val ua = "ThirdCoastSailing/0.4 (Android)"

    // NDFD sectors needed to cover each lake (from the coverage trial)
    private val sectors = mapOf(
        "superior" to listOf("umissvly", "nplains", "crgrlake"),
        "michigan" to listOf("crgrlake", "umissvly"),
        "huron" to listOf("crgrlake"),
        "erie" to listOf("crgrlake"),
        "ontario" to listOf("ergrlake"),
    )
    private val ndfdBase = "https://tgftp.nws.noaa.gov/SL.us008001/ST.opnl/DF.gr2/DC.ndfd"
    private val glwuFilter = "https://nomads.ncep.noaa.gov/cgi-bin/filter_glwu.pl"

    /** Time of the last successful update cycle (ms), 0 if never. */
    var lastUpdateMs: Long = 0
        private set

    init { readMeta()?.let { lastUpdateMs = it.optLong("lastUpdate", 0) } }

    // ------------------------------------------------------------------ download

    /**
     * One update cycle for the given lakes. Wind files are only downloaded when NOAA has a newer copy
     * (If-Modified-Since); waves only when a new model run is out. Returns a short status line.
     */
    fun update(lakes: List<String>): String {
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
        // Waves: newest complete GLWU short-range run, per lake
        runCatching { latestGlwuRun() }.getOrNull()?.let { (day, file) ->
            for (lake in lakes) {
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
                when (fetch(url, out, null)) {
                    is Fetch.Ok -> { got++; meta.put(key, "$day/$file") }
                    is Fetch.NotModified -> {}
                    is Fetch.Failed -> errors++
                }
            }
        } ?: run { errors++ }
        if (errors == 0 || got > 0) {
            lastUpdateMs = System.currentTimeMillis()
            meta.put("lastUpdate", lastUpdateMs)
        }
        metaFile.writeText(meta.toString())
        synchronized(this) { cache.clear(); loaded = null }
        return when {
            errors == 0 -> "Weather updated"
            got > 0 -> "Weather partly updated ($errors failed)"
            else -> "Weather update failed (no connection?)"
        }
    }

    /** (day, file name) of the second-newest GLWU short-range run; the newest may still be writing. */
    private fun latestGlwuRun(): Pair<String, String>? {
        val page = text(glwuFilter) ?: return null
        val days = Regex("glwu\\.(\\d{8})").findAll(page).map { it.groupValues[1] }.distinct().sortedDescending().toList()
        for (day in days) {
            val p = text("$glwuFilter?dir=%2Fglwu.$day") ?: continue
            val files = Regex("glwu\\.grlc_2p5km_sr\\.t(\\d\\d)z\\.grib2").findAll(p).map { it.value }.distinct().sorted().toList()
            if (files.isNotEmpty()) return day to (if (files.size > 1) files[files.size - 2] else files.last())
        }
        return null
    }

    private sealed class Fetch {
        data class Ok(val lastModified: String?) : Fetch()
        object NotModified : Fetch()
        object Failed : Fetch()
    }

    /** GET to a file (via .part), with If-Modified-Since when known; up to 3 tries. */
    private fun fetch(url: String, out: File, ifModifiedSince: String?): Fetch {
        repeat(3) { attempt ->
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
                            c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                            part.renameTo(out)
                            return Fetch.Ok(c.getHeaderField("Last-Modified"))
                        }
                        else -> {}
                    }
                } finally { c.disconnect() }
            } catch (_: Exception) { }
            Thread.sleep(2000L * (attempt + 1))
        }
        return Fetch.Failed
    }

    private fun text(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; setRequestProperty("User-Agent", ua)
        }
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    private fun readMeta(): JSONObject? = runCatching { JSONObject(metaFile.readText()) }.getOrNull()

    // ------------------------------------------------------------------ reading the downloaded files

    /** One forecast message: its quantity, valid time, source file name and bytes. */
    private class Entry(val kind: Wx, val validMs: Long, val src: String, val bytes: ByteArray, val msg: Grib2.Message)

    private var loaded: List<Entry>? = null
    private val cache = object : LinkedHashMap<Entry, FloatArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Entry, FloatArray>?) = size > 32
    }

    private fun kindOf(m: Grib2.Message): Wx? = when {
        m.discipline == 0 && m.category == 2 && m.number == 1 -> Wx.WIND_SPEED
        m.discipline == 0 && m.category == 2 && m.number == 0 -> Wx.WIND_DIR
        m.discipline == 0 && m.category == 2 && m.number == 22 -> Wx.GUST
        m.discipline == 10 && m.category == 0 && m.number == 3 -> Wx.WAVE_HEIGHT
        m.discipline == 10 && m.category == 0 && m.number == 10 -> Wx.WAVE_DIR
        m.discipline == 10 && m.category == 0 && m.number == 11 -> Wx.WAVE_PERIOD
        else -> null
    }

    @Synchronized
    private fun entries(): List<Entry> {
        loaded?.let { return it }
        val list = mutableListOf<Entry>()
        val all = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }
        // GLWU (finer, lake-trimmed) first; NDFD sectors in name order so speed and direction come from the same sector
        val files = all.filter { it.name.startsWith("glwu_") && it.name.endsWith(".grib2") } +
                all.filter { it.name.startsWith("ndfd_") && it.name.endsWith(".bin") }
        for (f in files) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            val src = f.name.substringBeforeLast('_')                 // ndfd_crgrlake / glwu (per lake keeps its own name)
            for (m in runCatching { Grib2.index(bytes) }.getOrDefault(emptyList())) {
                kindOf(m)?.let { list += Entry(it, m.validTimeMs, if (f.name.startsWith("glwu_")) f.name else src, bytes, m) }
            }
        }
        loaded = list
        return list
    }

    @Synchronized
    private fun values(e: Entry): FloatArray = cache.getOrPut(e) { Grib2.decode(e.bytes, e.msg) }

    /** Forecast hours available (wind or waves), sorted. */
    fun times(): List<Long> = entries().map { it.validMs }.distinct().sorted()

    /**
     * A decoded forecast quantity at one time, ready for fast repeated sampling (map overlays).
     * Holds one grid per source file, nearest forecast time within 3 h; the first source with a value wins.
     * Directions use the nearest grid point so 350 and 10 are never averaged to 180.
     */
    class Field(private val parts: List<Pair<Grib2.Lambert, FloatArray>>, val kind: Wx, val validMs: Long) {
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
        return Field(picks.map { it.msg.grid to values(it) }, kind, picks.first().validMs)
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

    fun gustKn(timeMs: Long, lat: Double, lon: Double): Double? =
        sample(Wx.GUST, timeMs, lat, lon).takeIf { !it.isNaN() }?.let { it * MS_TO_KN }

    /** Waves: (height ft, direction from deg, period s) or null. */
    fun waves(timeMs: Long, lat: Double, lon: Double): Triple<Double, Double, Double>? {
        val h = sample(Wx.WAVE_HEIGHT, timeMs, lat, lon)
        if (h.isNaN()) return null
        val d = sample(Wx.WAVE_DIR, timeMs, lat, lon)
        val p = sample(Wx.WAVE_PERIOD, timeMs, lat, lon)
        return Triple(h * M_TO_FT, if (d.isNaN()) Double.NaN else d.toDouble(), if (p.isNaN()) Double.NaN else p.toDouble())
    }

    /** Wave model run in use, e.g. "Oct 12 12z" (newest across lakes), or null before the first download. */
    fun waveRun(): String? {
        val meta = readMeta() ?: return null
        val runs = meta.keys().asSequence().filter { it.startsWith("glwu_run_") }.map { meta.optString(it) }.toList()
        val best = runs.mapNotNull { r ->
            val m = Regex("(\\d{8})/.*\\.t(\\d\\d)z").find(r) ?: return@mapNotNull null
            m.groupValues[1] + m.groupValues[2]
        }.maxOrNull() ?: return null
        val date = runCatching { SimpleDateFormat("yyyyMMdd", Locale.US).parse(best.take(8)) }.getOrNull() ?: return null
        return SimpleDateFormat("MMM d", Locale.US).format(date) + " " + best.drop(8) + "z"
    }

    companion object {
        /** Lake boxes: lon_min, lat_min, lon_max, lat_max. */
        val LAKE_BOX = mapOf(
            "superior" to doubleArrayOf(-92.3, 46.2, -84.2, 49.1), "michigan" to doubleArrayOf(-88.3, 41.5, -84.6, 46.3),
            "huron" to doubleArrayOf(-84.9, 42.9, -79.5, 46.6), "erie" to doubleArrayOf(-83.7, 41.3, -78.8, 43.1),
            "ontario" to doubleArrayOf(-80.0, 42.9, -75.7, 44.4),
        )

        /** Box covering all the given lakes, or null when none are known. */
        fun boxFor(ids: List<String>): DoubleArray? {
            val boxes = ids.mapNotNull { LAKE_BOX[it] }
            if (boxes.isEmpty()) return null
            return doubleArrayOf(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] })
        }

        const val MS_TO_KN = 1.943844
        const val M_TO_FT = 3.28084
        fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
        fun dayHour(ms: Long): String = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(ms))
        fun stamp(ms: Long): String = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(ms))
    }
}
