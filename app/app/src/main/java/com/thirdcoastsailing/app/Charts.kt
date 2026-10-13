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
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Where the app finds the list of downloadable lakes (a file on the GitHub release tagged "charts"). */
const val CATALOG_URL = "https://github.com/HappyRock616/Third-Coast-Sailing/releases/download/charts/catalog.json"

/** One downloadable file of a lake package. name = file name on the phone; gzip = unpack after download. */
data class CatalogFile(val name: String, val url: String, val size: Long, val sha256: String, val gzip: Boolean)

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
 *  - the catalog (a few KB) lists each lake's files, sizes, SHA-256 checksums and build time
 *  - each file downloads into files/staging/, resuming where it left off after a dropped connection
 *  - every file is checked against its checksum, then unpacked if compressed
 *  - only when the whole lake is complete and verified is it swapped into files/packages/<lake>/,
 *    so the old charts stay usable until then
 */
class ChartRepo(context: Context) {

    private val packages = File(context.filesDir, "packages").apply { mkdirs() }
    private val staging = File(context.filesDir, "staging").apply { mkdirs() }
    private val cacheFile = File(context.filesDir, "catalog.json")
    private val ua = "ThirdCoastSailing/0.7 (Android)"

    class Stopped : IOException("Download stopped")

    /** Download the catalog (and keep a copy for offline use). Throws on failure. */
    fun fetchCatalog(): List<CatalogLake> {
        val text = String(get(CATALOG_URL), Charsets.UTF_8)
        val lakes = parse(text)                                     // throws if it is not a valid catalog
        cacheFile.writeText(text)
        return lakes
    }

    /** Last catalog fetched, or empty. */
    fun cachedCatalog(): List<CatalogLake> = runCatching { parse(cacheFile.readText()) }.getOrDefault(emptyList())

    private fun parse(text: String): List<CatalogLake> {
        val arr = JSONObject(text).getJSONArray("lakes")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val fa = o.getJSONArray("files")
            CatalogLake(
                id = o.getString("id"), name = o.optString("name", o.getString("id")),
                chartsAsOf = o.optString("charts_as_of"), built = o.optString("built"),
                files = (0 until fa.length()).map { j ->
                    val f = fa.getJSONObject(j)
                    CatalogFile(f.getString("name"), f.getString("url"), f.getLong("size"), f.getString("sha256"), f.optBoolean("gzip"))
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
            if (sha256(dl) != f.sha256) {
                dl.delete()
                throw IOException("${f.name}: download damaged (checksum mismatch). Try again.")
            }
            if (f.gzip) {
                val tmp = File(stage, f.name + ".tmp")
                GZIPInputStream(dl.inputStream().buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
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

    /** GET a small file, following redirects. */
    private fun get(url: String): ByteArray {
        val c = open(url, 0)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
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

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
