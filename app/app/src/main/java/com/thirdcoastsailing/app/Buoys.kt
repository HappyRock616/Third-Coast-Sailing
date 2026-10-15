package com.thirdcoastsailing.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max

/** A buoy / station from the chart packages. */
data class BuoyStation(val id: String, val name: String, val owner: String, val type: String, val lat: Double, val lon: Double)

/** One NDBC observation (null = not reported). Wind in knots, waves in feet, temperatures in °F. */
data class BuoyObs(
    val timeMs: Long, val wdir: Double?, val wspdKn: Double?, val gustKn: Double?,
    val wvhtFt: Double?, val dpdS: Double?, val mwd: Double?, val presHpa: Double?, val ptdyHpa: Double?,
    val airF: Double?, val waterF: Double?,
)

const val BUOY_SOURCE = "buoys_live"
const val BUOY_LAYER = "buoys_live_layer"
private const val STALE_MS = 2 * 3_600_000L                 // no report for 2 h = gray marker
private const val HISTORY_BYTES = 25_000                     // newest ~24 h of a station's 10-minute file

/**
 * NDBC buoy and shore-station data, fetched by the phone:
 *  - latest observations for every station: one ~100 KB file, refreshed with each weather update
 *  - last 24 h for one station: the newest part of its realtime file (~25 KB), fetched when tapped
 * Kept in files/weather/ so the last readings show offline.
 */
class BuoyRepo(context: Context) {

    private val dir = File(context.filesDir, "weather").apply { mkdirs() }
    private val latestFile = File(dir, "ndbc_latest.txt")
    private val lmFile = File(dir, "ndbc_latest.lm")
    private val ua = "ThirdCoastSailing/0.6 (Android)"

    @Volatile private var latest: Map<String, BuoyObs>? = null

    /** Refresh the all-stations file if NOAA has a newer one. True if the data changed. */
    fun update(): Boolean {
        val lm = runCatching { lmFile.readText() }.getOrNull()
        val c = (URL("https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 40_000
            setRequestProperty("User-Agent", ua)
            if (lm != null && latestFile.exists()) setRequestProperty("If-Modified-Since", lm)
        }
        try {
            if (c.responseCode != 200) return false
            val part = File(latestFile.path + ".part")
            c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
            part.renameTo(latestFile)
            c.getHeaderField("Last-Modified")?.let { lmFile.writeText(it) }
            latest = null
            return true
        } finally { c.disconnect() }
    }

    /** Latest observation per station id. */
    fun latest(): Map<String, BuoyObs> {
        latest?.let { return it }
        val text = runCatching { latestFile.readText() }.getOrNull() ?: return emptyMap()
        val map = parseTable(text).mapNotNull { (stn, o) -> stn?.let { it.uppercase() to o } }.toMap()
        latest = map
        return map
    }

    /**
     * Last 24 h for one station, oldest first. Downloads when allowed and the saved copy is over 10 minutes old;
     * otherwise uses the saved copy. Null when there is nothing to show.
     */
    fun history(station: String, network: Boolean): List<BuoyObs>? {
        val f = File(dir, "ndbc_hist_${station.uppercase()}.txt")
        if (network && (!f.exists() || System.currentTimeMillis() - f.lastModified() > 10 * 60_000L)) {
            runCatching {
                val c = (URL("https://www.ndbc.noaa.gov/data/realtime2/${station.uppercase()}.txt").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Range", "bytes=0-${HISTORY_BYTES - 1}")
                }
                try {
                    if (c.responseCode == 200 || c.responseCode == 206) {
                        // read at most HISTORY_BYTES even if the server ignores the range, then drop the cut-off last line
                        val buf = ByteArray(HISTORY_BYTES)
                        var n = 0
                        c.inputStream.use { i -> while (n < buf.size) { val r = i.read(buf, n, buf.size - n); if (r < 0) break; n += r } }
                        val text = String(buf, 0, n, Charsets.US_ASCII)
                        f.writeText(if (n >= HISTORY_BYTES) text.substringBeforeLast('\n') else text)
                    }
                } finally { c.disconnect() }
            }
        }
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        return parseTable(text).map { it.second }.filter { it.timeMs >= cutoff }.sortedBy { it.timeMs }.ifEmpty { null }
    }

    /** Buoy points from the imported chart packages (routing files). */
    fun stations(lakes: List<Lake>): List<BuoyStation> {
        val out = linkedMapOf<String, BuoyStation>()
        for (lake in lakes) {
            val db = Gpkg.open(File(lake.pmtiles.parentFile, "${lake.id}_hazards.gpkg")) ?: continue
            try {
                db.rawQuery("SELECT geom, station, name, owner, stype FROM buoy", null).use { c ->
                    while (c.moveToNext()) {
                        val p = c.getBlob(0)?.let { point(it) }
                        val id = c.getString(1)?.trim()?.uppercase()
                        if (p != null && !id.isNullOrEmpty())
                            out[id] = BuoyStation(id, c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", p.second, p.first)
                    }
                }
            } catch (_: Exception) {
            } finally { db.close() }
        }
        return out.values.toList()
    }

    /** (lon, lat) of a GeoPackage point blob. */
    private fun point(blob: ByteArray): Pair<Double, Double>? = runCatching {
        val flags = blob[3].toInt()
        val env = when ((flags shr 1) and 7) { 1 -> 32; 2, 3 -> 48; 4 -> 64; else -> 0 }
        val b = ByteBuffer.wrap(blob, 8 + env, blob.size - 8 - env)
        b.order(if (b.get() == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        if (b.int % 1000 != 1) return null
        b.double to b.double
    }.getOrNull()

    companion object {
        /**
         * Parse an NDBC text table (latest_obs or a station realtime file) using its header row, so either
         * column layout works. "MM" = missing. Returns (station id or null, observation).
         */
        fun parseTable(text: String): List<Pair<String?, BuoyObs>> {
            val lines = text.lines()
            val header = lines.firstOrNull { it.startsWith("#") } ?: return emptyList()
            val cols = header.removePrefix("#").trim().split(Regex("\\s+"))
            val idx = cols.withIndex().associate { it.value to it.index }
            val out = mutableListOf<Pair<String?, BuoyObs>>()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val t = line.trim().split(Regex("\\s+"))
                if (t.size < cols.size) continue
                fun raw(name: String) = idx[name]?.let { t.getOrNull(it) }?.takeIf { it != "MM" }
                fun num(name: String) = raw(name)?.toDoubleOrNull()
                val year = (num("YYYY") ?: num("YY"))?.toInt() ?: continue
                cal.clear()
                cal.set(year, (num("MM")?.toInt() ?: continue) - 1, num("DD")?.toInt() ?: continue,
                    num("hh")?.toInt() ?: continue, num("mm")?.toInt() ?: 0, 0)
                val kn = 1.943844
                fun f(c: Double?) = c?.let { it * 9 / 5 + 32 }
                out += raw("STN") to BuoyObs(
                    timeMs = cal.timeInMillis,
                    wdir = num("WDIR"), wspdKn = num("WSPD")?.times(kn), gustKn = num("GST")?.times(kn),
                    wvhtFt = num("WVHT")?.times(3.28084), dpdS = num("DPD"), mwd = num("MWD"),
                    presHpa = num("PRES"), ptdyHpa = num("PTDY"), airF = f(num("ATMP")), waterF = f(num("WTMP")),
                )
            }
            return out
        }

        /** Hex color of a wind speed on the overlay scale. */
        private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

        /**
         * Map features for the buoy layer. Fill = wind speed (gray when no wind report in the last 2 h);
         * ring = yellow for waves over 3 ft, red over 5 ft, otherwise a thin dark edge.
         */
        fun features(stations: List<BuoyStation>, obs: Map<String, BuoyObs>, nowMs: Long): FeatureCollection =
            FeatureCollection.fromFeatures(stations.map { s ->
                val o = obs[s.id]?.takeIf { nowMs - it.timeMs <= STALE_MS }
                val f = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
                f.addStringProperty("station", s.id)
                f.addStringProperty("name", s.name)
                f.addStringProperty("owner", s.owner)
                f.addStringProperty("stype", s.type)
                f.addStringProperty("color", o?.wspdKn?.let { hex(windColor(it)) } ?: "#9AA5B1")
                val wave = o?.wvhtFt
                val (ring, w) = when {
                    wave != null && wave > 5 -> "#E5484D" to 3.5
                    wave != null && wave > 3 -> "#F2D028" to 3.5
                    else -> "#0F2742" to 1.2
                }
                f.addStringProperty("ring", ring)
                f.addNumberProperty("ringW", w)
                f
            })
    }
}

/** Text lines for a buoy's info window from its latest observation. */
fun buoyLines(s: String, o: BuoyObs?, nowMs: Long): List<String> {
    if (o == null) return listOf("Station $s", "No recent report from NOAA")
    val ageMin = ((nowMs - o.timeMs) / 60_000).coerceAtLeast(0)
    val age = if (ageMin < 120) "$ageMin min ago" else "${ageMin / 60} h ago"
    val out = mutableListOf("Station $s · report ${clockText(o.timeMs)} ($age)")
    if (o.wspdKn != null) out += "Wind %s %.0f kt%s".format(
        o.wdir?.let { "%03.0f°".format(it) } ?: "", o.wspdKn, o.gustKn?.let { ", gust %.0f kt".format(it) } ?: "")
    if (o.wvhtFt != null) out += "Waves %.1f ft%s%s".format(o.wvhtFt,
        o.dpdS?.let { " · %.0f s".format(it) } ?: "", o.mwd?.let { " · from %03.0f°".format(it) } ?: "")
    val temps = listOfNotNull(o.airF?.let { "Air %.0f °F".format(it) }, o.waterF?.let { "Water %.0f °F".format(it) })
    if (temps.isNotEmpty()) out += temps.joinToString(" · ")
    if (o.presHpa != null) out += "Pressure %.1f hPa%s".format(o.presHpa, o.ptdyHpa?.let { " (%+.1f in 3 h)".format(it) } ?: "")
    if (nowMs - o.timeMs > STALE_MS) out += "Old report: buoy may be out for the season"
    return out
}

private val WaveLine = Color(0xFF7FD3F0)

/**
 * 24-hour graph: wind (solid) and gust (dashed) in knots on the left axis, wave height in feet (blue) on the right.
 */
@Composable
fun BuoyGraph(history: List<BuoyObs>, nowMs: Long, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 6.dp)) {
            val left = 26.dp.toPx(); val right = 26.dp.toPx(); val top = 4.dp.toPx(); val bottom = 14.dp.toPx()
            val w = size.width - left - right; val h = size.height - top - bottom
            val t0 = nowMs - 24 * 3_600_000L
            val maxKn = max(15.0, history.mapNotNull { it.gustKn ?: it.wspdKn }.maxOrNull() ?: 0.0)
            val maxFt = max(4.0, history.mapNotNull { it.wvhtFt }.maxOrNull() ?: 0.0)
            fun x(t: Long) = left + w * ((t - t0).toFloat() / (24 * 3_600_000f))
            fun yKn(v: Double) = top + h * (1 - (v / maxKn).toFloat())
            fun yFt(v: Double) = top + h * (1 - (v / maxFt).toFloat())
            // frame and 6-hour grid
            for (k in 0..4) {
                val gx = left + w * k / 4
                drawLine(NavyLight, Offset(gx, top), Offset(gx, top + h), strokeWidth = 1f)
            }
            drawLine(NavyLight, Offset(left, top + h), Offset(left + w, top + h), strokeWidth = 1.5f)
            fun series(get: (BuoyObs) -> Double?, y: (Double) -> Float, color: Color, dashed: Boolean) {
                val p = Path(); var started = false; var lastT = 0L
                for (o in history) {
                    val v = get(o) ?: continue
                    val px = x(o.timeMs); val py = y(v)
                    if (!started || o.timeMs - lastT > 2 * 3_600_000L) p.moveTo(px, py) else p.lineTo(px, py)
                    started = true; lastT = o.timeMs
                }
                drawPath(p, color, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
            }
            series({ it.wvhtFt }, { yFt(it) }, WaveLine, false)
            series({ it.gustKn }, { yKn(it) }, Foam.copy(alpha = 0.6f), true)
            series({ it.wspdKn }, { yKn(it) }, SignalOrange, false)
            val small = TextStyle(color = Mist, fontSize = 9.sp)
            drawText(tm, "%.0f".format(maxKn), Offset(2f, top - 2f), small)
            drawText(tm, "0", Offset(2f, top + h - 10.dp.toPx()), small)
            drawText(tm, "%.0f".format(maxFt), Offset(left + w + 4f, top - 2f), small.copy(color = WaveLine))
            drawText(tm, "-24h", Offset(left, top + h + 1f), small)
            drawText(tm, "-12h", Offset(left + w / 2 - 10.dp.toPx(), top + h + 1f), small)
            drawText(tm, "now", Offset(left + w - 16.dp.toPx(), top + h + 1f), small)
        }
        Row(Modifier.padding(top = 2.dp)) {
            Text("— wind kt   ", color = SignalOrange, fontSize = 10.sp)
            Text("- - gust kt   ", color = Foam, fontSize = 10.sp)
            Text("— waves ft", color = WaveLine, fontSize = 10.sp)
        }
    }
}
