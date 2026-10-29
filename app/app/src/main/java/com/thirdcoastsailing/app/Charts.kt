package com.thirdcoastsailing.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Where the app finds the list of downloadable lakes (a file on the GitHub release tagged "charts"). */
const val CATALOG_URL = "https://github.com/HappyRock616/Third-Coast-Sailing/releases/download/charts/catalog.json"

/**
 * One downloadable file of a lake package. name = file name on the phone; gzip = unpack after download;
 * sizeRaw / sha256Raw = size and checksum after unpacking (newer catalogs; null for older ones).
 */
data class CatalogFile(val name: String, val url: String, val size: Long, val sha256: String, val gzip: Boolean,
                       val sizeRaw: Long? = null, val sha256Raw: String? = null)

/** One lake as offered for download. built = package build time (newer = update available). */
data class CatalogLake(val id: String, val name: String, val chartsAsOf: String, val built: String, val files: List<CatalogFile>) {
    /** Download size in bytes. */
    val size: Long get() = files.sumOf { it.size }
}

/** Short size text, e.g. "71 MB". */
fun mbText(bytes: Long) = "%.0f MB".format(bytes / 1_000_000.0)

/** True when the current connection is metered (mobile data or a hotspot). */
fun meteredNetwork(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/**
 * Chart downloads from the GitHub release:
 *  - the catalog (a few KB) lists each lake's files, sizes, SHA-256 checksums and build time; it must carry a
 *    valid signature (catalog.json.sig, see CatalogTrust), so the checksums can be trusted
 *  - each file downloads into files/staging/, resuming where it left off after a dropped connection
 *  - every file is checked against its checksum, then unpacked if compressed
 *  - only when the whole lake is complete and verified is it swapped into files/packages/<lake>/,
 *    so the old charts stay usable until then
 */
class ChartRepo(context: Context) {

    private val packages = File(context.filesDir, "packages").apply { mkdirs() }
    private val staging = File(context.filesDir, "staging").apply { mkdirs() }
    private val cacheFile = File(context.filesDir, "catalog.json")
    private val cacheSig = File(context.filesDir, "catalog.json.sig")
    private val ua = "ThirdCoastSailing/0.7 (Android)"

    class Stopped : IOException("Download stopped")

    /** Download the catalog (and keep a copy for offline use). Throws on failure. */
    fun fetchCatalog(): List<CatalogLake> {
        val bytes = get(CATALOG_URL, CatalogTrust.MAX_CATALOG_BYTES)
        val sig = String(get("$CATALOG_URL.sig", CatalogTrust.MAX_SIG_BYTES), Charsets.US_ASCII)
        if (!CatalogTrust.verifyCatalog(bytes, sig)) throw IOException("Chart list failed its signature check")
        val lakes = parse(String(bytes, Charsets.UTF_8))           // throws if it is not a valid catalog
        cacheFile.writeBytes(bytes)
        cacheSig.writeText(sig)
        return lakes
    }

    /** Last catalog fetched (signature checked again), or empty. */
    fun cachedCatalog(): List<CatalogLake> = runCatching {
        val bytes = cacheFile.readBytes()
        if (!CatalogTrust.verifyCatalog(bytes, cacheSig.readText())) emptyList() else parse(String(bytes, Charsets.UTF_8))
    }.getOrDefault(emptyList())

    /** Parse the catalog; every id, file name, link, size and checksum must pass CatalogTrust's checks or it throws. */
    private fun parse(text: String): List<CatalogLake> {
        val arr = JSONObject(text).getJSONArray("lakes")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            if (!CatalogTrust.validId(id)) throw IOException("Bad lake id in chart list")
            val fa = o.getJSONArray("files")
            CatalogLake(
                id = id, name = o.optString("name", id).take(40),
                chartsAsOf = o.optString("charts_as_of"), built = o.optString("built"),
                files = (0 until fa.length()).map { j ->
                    val f = fa.getJSONObject(j)
                    val file = CatalogFile(f.getString("name"), f.getString("url"), f.getLong("size"), f.getString("sha256"),
                        f.optBoolean("gzip"),
                        sizeRaw = if (f.has("size_raw")) f.getLong("size_raw") else null,
                        sha256Raw = if (f.has("sha256_raw")) f.getString("sha256_raw") else null)
                    val ok = CatalogTrust.validName(file.name) && CatalogTrust.validUrl(file.url) &&
                        file.size in 1..CatalogTrust.MAX_CHART_FILE_BYTES && CatalogTrust.validSha(file.sha256) &&
                        (file.sizeRaw == null || file.sizeRaw in 1..CatalogTrust.MAX_CHART_FILE_BYTES) &&
                        (file.sha256Raw == null || CatalogTrust.validSha(file.sha256Raw))
                    if (!ok) throw IOException("Bad file entry in chart list")
                    file
                },
            )
        }
    }

    /** Build time of the installed copy of a lake ("" if not installed or unknown). */
    fun installedBuilt(id: String): String =
        runCatching { JSONObject(File(File(packages, id), "manifest.json").readText()).optString("built_utc") }.getOrDefault("")

    fun isInstalled(id: String): Boolean =
        File(packages, id).listFiles()?.any { it.name.endsWith("_chart.pmtiles") } == true

    /** True when the catalog has a newer build of an installed lake. */
    fun updateAvailable(lake: CatalogLake): Boolean = isInstalled(lake.id) && lake.built > installedBuilt(lake.id)

    /**
     * Download, verify and install one lake.
     * @param progress bytes done and total, called often
     * @param beforeSwap called just before the new files replace the old (close open files there)
     */
    fun install(lake: CatalogLake, progress: (Long, Long) -> Unit, beforeSwap: () -> Unit, cancel: () -> Boolean) {
        val stamp = lake.built.filter { it.isDigit() }.ifEmpty { "0" }
        val stage = File(staging, "${lake.id}_$stamp").apply { mkdirs() }
        // drop partial downloads of older builds of this lake
        staging.listFiles()?.filter { it.name.startsWith("${lake.id}_") && it != stage }?.forEach { it.deleteRecursively() }

        val total = lake.size
        var before = 0L
        for (f in lake.files) {
            val done = File(stage, f.name)
            if (done.exists() && File(stage, f.name + ".ok").exists()) { before += f.size; continue }
            val dl = File(stage, f.name + ".dl")
            val base = before
            fetchResumable(f.url, dl, f.size, { n -> progress(base + n, total) }, cancel)
            if (CatalogTrust.sha256(dl) != f.sha256) {
                dl.delete()
                throw IOException("${f.name}: download damaged (checksum mismatch). Try again.")
            }
            if (f.gzip) {
                val tmp = File(stage, f.name + ".tmp")
                // unpacked size is capped (catalog size, or a fixed limit for older catalogs) and checked when known
                val cap = f.sizeRaw ?: CatalogTrust.MAX_CHART_FILE_BYTES
                GZIPInputStream(dl.inputStream().buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> CatalogTrust.copyCapped(i, o, cap) } }
                if (f.sha256Raw != null && CatalogTrust.sha256(tmp) != f.sha256Raw) {
                    tmp.delete(); dl.delete()
                    throw IOException("${f.name}: unpacked file damaged (checksum mismatch). Try again.")
                }
                tmp.renameTo(done)
                dl.delete()
            } else dl.renameTo(done)
            File(stage, f.name + ".ok").writeText("ok")
            before += f.size
            progress(before, total)
        }
        stage.listFiles()?.filter { it.name.endsWith(".ok") }?.forEach { it.delete() }

        // swap: old package aside, new one in, old one removed
        beforeSwap()
        val dest = File(packages, lake.id)
        val old = File(packages, ".${lake.id}.old")
        old.deleteRecursively()
        if (dest.exists() && !dest.renameTo(old)) throw IOException("Could not replace the old ${lake.name} charts")
        if (!stage.renameTo(dest)) {
            old.renameTo(dest)                                          // put the old charts back
            throw IOException("Could not install ${lake.name}")
        }
        old.deleteRecursively()
    }

    /** GET a small file (at most `max` bytes), following redirects. */
    private fun get(url: String, max: Int): ByteArray {
        val c = open(url, 0)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { CatalogTrust.readCapped(it, max) }
        } finally { c.disconnect() }
    }

    /**
     * Open a connection, following GitHub's redirect to its file servers by hand so the Range header
     * (resume point) is sent to the final server too.
     */
    private fun open(url: String, from: Long): HttpURLConnection {
        var target = url
        repeat(6) {
            val c = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", ua)
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            if (c.responseCode in 301..308 && c.responseCode != 304) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                target = URL(URL(target), loc).toString()
            } else return c
        }
        throw IOException("Too many redirects")
    }

    /** Download to `dest`, resuming from what is already there; up to 4 attempts. */
    private fun fetchResumable(url: String, dest: File, expected: Long, onBytes: (Long) -> Unit, cancel: () -> Boolean) {
        var attempt = 0
        while (true) {
            var have = if (dest.exists()) dest.length() else 0L
            if (have > expected) { dest.delete(); have = 0 }
            if (have == expected) return
            try {
                val c = open(url, have)
                try {
                    val append = when (c.responseCode) {
                        206 -> true
                        200 -> false                                     // server ignored the resume point: start over
                        416 -> { dest.delete(); throw IOException("Restarting download") }
                        else -> throw IOException("HTTP ${c.responseCode}")
                    }
                    if (!append) have = 0
                    RandomAccessFile(dest, "rw").use { raf ->
                        raf.setLength(have)
                        raf.seek(have)
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        c.inputStream.use { inp ->
                            while (true) {
                                if (cancel()) throw Stopped()
                                val n = inp.read(buf)
                                if (n < 0) break
                                if (have + n > expected) throw IOException("Download larger than expected")
                                raf.write(buf, 0, n)
                                have += n
                                if (have - last > 256_000) { onBytes(have); last = have }
                            }
                        }
                    }
                    onBytes(have)
                    if (have != expected) throw IOException("Incomplete download")
                    return
                } finally { c.disconnect() }
            } catch (e: Stopped) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw IOException("Download failed: ${e.message}")
                Thread.sleep(2000L * attempt)
            }
        }
    }

}
