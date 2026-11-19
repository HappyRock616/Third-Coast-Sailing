package com.thirdcoastsailing.app

import android.content.Context
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/*
 * Great Lakes surface currents from NOAA GLERL's lake models (GLCFS, FVCOM; free, public).
 *
 * The models work on a fixed mesh of triangles. Where each triangle is (its centre) is built into the lake packages
 * (table current_grid in <lake>_hazards.gpkg, made by 1_Chart_Build/currents_grid.py), so the app only downloads the
 * surface current for the current hour: two numbers per triangle, about 0.1-1.4 MB per model, from GLERL's OPeNDAP
 * server, which can cut the surface layer at one hour out of the 1.7 GB model file.
 *
 * Models: superior, michgan-huron (GLERL's spelling; Lakes Michigan and Huron), erie, hec (Huron-Erie Corridor:
 * St. Clair River, Lake St. Clair, Detroit River), ontario (Lake Ontario).
 * Coastal zones have no current grid (tidal currents are a later step).
 */

/** Current at a point: east and north components (m/s). */
class CurrentVec(val u: Float, val v: Float) {
    val knots get() = hypot(u, v) * MS_TO_KN.toFloat()
    /** Direction the water flows TOWARD, degrees true. */
    val towardDeg get() = ((Math.toDegrees(kotlin.math.atan2(u.toDouble(), v.toDouble())) + 360) % 360)
}

/**
 * The surface current field for the installed lakes at one time: triangle centres with their current, and a grid
 * index for nearest-point lookups (the arrows and the predictor line ask for thousands of points).
 */
class CurrentField(private val lon: FloatArray, private val lat: FloatArray, private val u: FloatArray,
                   private val v: FloatArray, val validMs: Long) {
    private val cell = 0.02                                       // degrees; ~2 km
    private val index = HashMap<Long, IntArray>()

    init {
        val lists = HashMap<Long, MutableList<Int>>()
        for (i in lon.indices) lists.getOrPut(key(lat[i].toDouble(), lon[i].toDouble())) { mutableListOf() }.add(i)
        for ((k, l) in lists) index[k] = l.toIntArray()
    }

    private fun key(la: Double, lo: Double): Long {
        val r = floor(la / cell).toLong()
        val c = floor(lo / cell).toLong()
        return (r shl 32) xor (c and 0xffffffffL)
    }

    val size get() = lon.size

    /**
     * Current at a point: the nearest triangle centre within `maxDeg` (about 4 km by default; big offshore triangles
     * are up to ~3 km across), or null off the model (land, or a lake without a package).
     */
    fun at(la: Double, lo: Double, maxDeg: Double = 0.04): CurrentVec? {
        val r0 = floor(la / cell).toLong()
        val c0 = floor(lo / cell).toLong()
        val reach = (maxDeg / cell).toInt().coerceAtLeast(1)
        val cl = cos(Math.toRadians(la)).toFloat()
        var best = -1
        var bestD = (maxDeg * maxDeg).toFloat()
        for (dr in -reach..reach) for (dc in -reach..reach) {
            val ids = index[((r0 + dr) shl 32) xor ((c0 + dc) and 0xffffffffL)] ?: continue
            for (i in ids) {
                val dy = lat[i] - la.toFloat()
                val dx = (lon[i] - lo.toFloat()) * cl
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = i }
            }
        }
        return if (best < 0) null else CurrentVec(u[best], v[best])
    }
}

/** Downloads the current for the installed lakes and builds the field. Network and file work: call off the main thread. */
class CurrentsRepo(context: Context) {
    private val dir = File(context.filesDir, "currents").apply { mkdirs() }
    private val server = "https://apps.glerl.noaa.gov/thredds"

    /** Time of the last successful download (ms), 0 if never. */
    var lastUpdateMs: Long = File(dir, "updated").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
        private set

    /** Models and their triangle counts listed in the installed lake packages (lake files without a grid: none). */
    fun models(lakes: List<Lake>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (lake in lakes) {
            if (lake.id !in WeatherRepo.GREAT_LAKES) continue
            val db = Gpkg.open(File(lake.pmtiles.parentFile, "${lake.id}_hazards.gpkg")) ?: continue
            runCatching {
                db.rawQuery("SELECT model, nele FROM current_models", null).use { c ->
                    while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
                }
            }
            db.close()
        }
        return out
    }

    /**
     * One update: for each model the installed lakes use, the surface current at the hour nearest now from the newest
     * forecast run. A model whose triangle count no longer matches the package is skipped (charts need an update).
     */
    fun update(lakes: List<Lake>): String = synchronized(LOCK) {
        val models = models(lakes)
        if (models.isEmpty()) return "Currents: update your lake charts (no current data in them yet)"
        var got = 0
        val problems = mutableListOf<String>()
        for ((model, nele) in models) {
            runCatching {
                val cat = text("$server/catalog/glcfs/$model/forecast/catalog.xml") ?: error("no catalog")
                val path = Regex("urlPath=\"([^\"]+\\.nc)\"").findAll(cat).map { it.groupValues[1] }.sorted().lastOrNull()
                    ?: error("no forecast file")
                val base = "$server/dodsC/$path"
                val dds = text("$base.dds") ?: error("no description")
                val serverNele = Regex("u\\[time = \\d+\\]\\[siglay = \\d+\\]\\[nele = (\\d+)\\]").find(dds)?.groupValues?.get(1)?.toInt()
                    ?: error("no currents in file")
                if (serverNele != nele) { problems += "$model mesh changed"; return@runCatching }
                val nt = Regex("Itime\\[time = (\\d+)\\]").find(dds)?.groupValues?.get(1)?.toInt() ?: error("no times")
                // valid times of the file's hours: whole days + milliseconds since 1970 (UTC)
                val tb = bytes("$base.dods?" + enc("Itime[0:1:${nt - 1}],Itime2[0:1:${nt - 1}]")) ?: error("no times")
                val (days, msec) = dapInts(tb, 2)
                val now = System.currentTimeMillis()
                var k = 0
                var bestDt = Long.MAX_VALUE
                for (i in 0 until nt) {
                    val t = days[i] * 86_400_000L + msec[i]
                    if (abs(t - now) < bestDt) { bestDt = abs(t - now); k = i }
                }
                val validMs = days[k] * 86_400_000L + msec[k]
                val uv = bytes("$base.dods?" + enc("u[$k:1:$k][0:1:0][0:1:${nele - 1}],v[$k:1:$k][0:1:0][0:1:${nele - 1}]"))
                    ?: error("download failed")
                val arrays = dapFloats(uv, 2)
                if (arrays[0].size != nele || arrays[1].size != nele) error("short download")
                // file: validMs, nele, u[nele], v[nele] (little work to read back)
                val tmp = File(dir, "$model.tmp")
                java.io.DataOutputStream(tmp.outputStream().buffered()).use { o ->
                    o.writeLong(validMs); o.writeInt(nele)
                    for (x in arrays[0]) o.writeFloat(x)
                    for (x in arrays[1]) o.writeFloat(x)
                }
                // rename over the old file (the good copy is only removed if the plain rename fails)
                val bin = File(dir, "$model.bin")
                if (!tmp.renameTo(bin) && !(bin.delete() && tmp.renameTo(bin))) error("could not save")
                got++
            }.onFailure { problems += "$model ${it.message}" }
        }
        if (got > 0) {
            lastUpdateMs = System.currentTimeMillis()
            File(dir, "updated").writeText("$lastUpdateMs")
        }
        return when {
            problems.isEmpty() -> "Currents updated"
            got > 0 -> "Currents partly updated"
            problems.any { "mesh changed" in it } -> "Currents: update your lake charts (NOAA changed its model)"
            else -> "Currents update failed (no connection?)"
        }
    }

    /** Growable float array (no boxing of every value as an ArrayList<Float> would). */
    private class Floats {
        var a = FloatArray(4096); var n = 0
        fun add(x: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = x }
        fun array() = a.copyOf(n)
    }

    /** The field for the installed lakes from the last download, or null if nothing is downloaded. */
    fun field(lakes: List<Lake>): CurrentField? {
        val lon = Floats(); val lat = Floats(); val u = Floats(); val v = Floats()
        var validMs = 0L
        val loaded = HashMap<String, Pair<FloatArray, FloatArray>?>()
        for (lake in lakes) {
            if (lake.id !in WeatherRepo.GREAT_LAKES) continue
            val db = Gpkg.open(File(lake.pmtiles.parentFile, "${lake.id}_hazards.gpkg")) ?: continue
            runCatching {
                db.rawQuery("SELECT model, idx, lon, lat FROM current_grid", null).use { c ->
                    while (c.moveToNext()) {
                        val model = c.getString(0)
                        val data = loaded.getOrPut(model) { read(model)?.also { validMs = maxOf(validMs, it.first) }?.second } ?: continue
                        val i = c.getInt(1)
                        if (i !in data.first.indices) continue
                        val uu = data.first[i]; val vv = data.second[i]
                        if (uu.isNaN() || vv.isNaN() || abs(uu) > 20 || abs(vv) > 20) continue   // fill values
                        lon.add(c.getDouble(2).toFloat()); lat.add(c.getDouble(3).toFloat()); u.add(uu); v.add(vv)
                    }
                }
            }
            db.close()
        }
        if (lon.n == 0) return null
        return CurrentField(lon.array(), lat.array(), u.array(), v.array(), validMs)
    }

    /** (validMs, (u, v)) of one downloaded model, or null. */
    private fun read(model: String): Pair<Long, Pair<FloatArray, FloatArray>>? = runCatching {
        DataInputStream(File(dir, "$model.bin").inputStream().buffered()).use { s ->
            val t = s.readLong(); val n = s.readInt()
            if (n !in 0..MAX_VALUES) return@runCatching null
            val u = FloatArray(n) { s.readFloat() }; val v = FloatArray(n) { s.readFloat() }
            t to (u to v)
        }
    }.getOrNull()

    // --- OPeNDAP helpers (DAP2 binary: the DDS text, "Data:\n", then per array two big-endian counts and the values)

    private fun enc(q: String) = q.replace("[", "%5B").replace("]", "%5D").replace(":", "%3A").replace(",", "%2C")

    private fun dataStart(b: ByteArray): Int {
        val marker = "\nData:\n".toByteArray()
        outer@ for (i in 0..b.size - marker.size) {
            for (j in marker.indices) if (b[i + j] != marker[j]) continue@outer
            return i + marker.size
        }
        error("bad reply")
    }

    /** `n` float arrays from a DAP2 reply; a count that doesn't fit the reply is refused (damaged or wrong reply). */
    private fun dapFloats(b: ByteArray, n: Int): List<FloatArray> {
        val start = dataStart(b)
        val s = DataInputStream(b.inputStream(start, b.size))
        return List(n) { val k = dapCount(s, b.size - start); FloatArray(k) { s.readFloat() } }
    }

    /** `n` int arrays from a DAP2 reply (as [dapFloats]). */
    private fun dapInts(b: ByteArray, n: Int): List<IntArray> {
        val start = dataStart(b)
        val s = DataInputStream(b.inputStream(start, b.size))
        return List(n) { val k = dapCount(s, b.size - start); IntArray(k) { s.readInt() } }
    }

    /** An array's value count (sent twice in DAP2), checked against the bytes in the reply. */
    private fun dapCount(s: DataInputStream, bytes: Int): Int {
        val k = s.readInt(); s.readInt()
        if (k < 0 || k > MAX_VALUES || k.toLong() * 4 > bytes) error("bad reply")
        return k
    }

    private fun ByteArray.inputStream(from: Int, to: Int) = java.io.ByteArrayInputStream(this, from, to - from)

    private fun bytes(url: String): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "ThirdCoastSailing/${BuildConfig.VERSION_NAME} (Android)")
        try {
            // replies are a few hundred KB (one hour of one lake model): anything over MAX_REPLY_BYTES is refused
            if (c.responseCode != 200) null
            else c.inputStream.use { CatalogTrust.readCapped(it, MAX_REPLY_BYTES) }
        } finally { c.disconnect() }
    }.getOrNull()

    private fun text(url: String): String? = bytes(url)?.toString(Charsets.UTF_8)

    companion object {
        /** One update at a time (screen rebuilds could otherwise start a second one). */
        private val LOCK = Any()
        /** Automatic refresh interval while Currents is on. */
        const val REFRESH_MS = 4 * 3_600_000L
        /** Largest reply accepted (bytes); a model hour is well under 2 MB. */
        private const val MAX_REPLY_BYTES = 16 * 1024 * 1024
        /** Most values in one array (the largest lake mesh has about 30,000 triangles). */
        private const val MAX_VALUES = 2_000_000
    }
}
