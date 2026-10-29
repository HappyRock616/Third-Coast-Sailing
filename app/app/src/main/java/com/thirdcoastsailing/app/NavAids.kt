package com.thirdcoastsailing.app

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.abs

/*
 * Navigation aids on the chart: the tap card's content for a light, buoy, beacon, landmark or Coast Guard station,
 * and the animated light glow (zoom 13+, after sunset or in night mode). The data come from the chart files' light,
 * aid and landmark layers (chart build 1.4+); see NavStyle for the symbols and labels.
 */

/** Card content for a navigation aid. `pattern` is set for lights: the lamp dot blinks and the strip plays it. */
data class NavInfo(
    val kindLine: String,
    val abbrev: String? = null,
    val lampColours: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    val warn: String? = null,
    val pattern: LightPattern? = null,
)

/** Glow color per S-57 light colour code: 1 white (drawn warm yellow-white), 3 red, 4 green, 6 yellow. */
private val GLOW = mapOf("1" to Color(0xFFFFF2A0), "3" to Color(0xFFFF3B30), "4" to Color(0xFF3BE36B), "6" to Color(0xFFFFD60A))
/** Glow color for a colour code (white when unknown). */
private fun glowColour(code: String) = GLOW[code] ?: GLOW.getValue("1")
/** Fill of the yellow warning note on a nav-aid card. */
private val WARN_YELLOW = Color(0xFFF6D44A)

/** A text property of a map feature, trimmed, or null when missing or blank. */
private fun Feature.str(key: String): String? =
    runCatching { getProperty(key)?.takeIf { !it.isJsonNull }?.asString?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
/** A number property of a map feature, or null. */
private fun Feature.num(key: String): Double? =
    runCatching { getProperty(key)?.takeIf { !it.isJsonNull }?.asDouble }.getOrNull()

/** Info cards for lights, buoys, beacons, landmarks and Coast Guard stations tapped on the chart. */
object NavAids {
    /** True for the map layers whose taps open a navigation-aid card. */
    fun isNavLayer(layerId: String) = NAV_SUFFIXES.any { layerId.endsWith(it) }
    private val NAV_SUFFIXES = listOf("_light1", "_light2", "_light3", "_aid", "_lmk1", "_lmk2", "_cg")

    /** Title and card content for a tapped feature. */
    fun card(f: Feature, layerId: String): Pair<String, NavInfo> = when {
        layerId.contains("_light") -> lightCard(f)
        layerId.endsWith("_aid") -> aidCard(f)
        layerId.endsWith("_cg") -> (f.str("name") ?: "Coast Guard station") to NavInfo("Coast Guard station")
        else -> landmarkCard(f)
    }

    /** Card for a light: title, the pattern in chart and plain words, height and range, sectors and fog signal. */
    private fun lightCard(f: Feature): Pair<String, NavInfo> {
        val chr = f.str("chr") ?: "F"
        val colours = f.str("colours") ?: "1"
        val per = f.num("sigper"); val seq = f.str("sigseq")
        val kind = when (f.str("kind")) {
            "lighthouse" -> "Lighthouse"; "beacon" -> "Light on a beacon"; "buoy" -> "Lighted buoy"
            "on landmark" -> "Light on a tower"; "aero" -> "Aviation light"; "obstruction" -> "Obstruction light"
            else -> "Light"
        }
        val lines = ArrayList<String>()
        lines += LightPattern.plainWords(f.str("label_short")?.takeIf { it.startsWith("AERO") }?.let { "AERO $chr" } ?: chr, colours, per, seq)
        val h = f.num("height_m")?.let { "${Math.round(it / 0.3048)} ft high" }
        val r = f.num("range_nm")?.let { "seen ${Math.round(it)} nm" }
        listOfNotNull(h, r).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString("  ·  ") }
        f.str("fog_kind")?.let { fog ->
            val every = f.num("fog_per")?.let { " every ${fmtS(it)} s" } ?: ""
            val mrass = if (f.str("fog_note")?.contains("MRASS", ignoreCase = true) == true) " · radio-activated (MRASS)" else ""
            lines += "Fog ${fog.lowercase()}$every$mrass"
        }
        f.str("sectors")?.let { lines += "Sectors: $it" }
        f.str("status")?.let { lines += it.replaceFirstChar { c -> c.uppercase() } }
        val note = f.str("note")
        return (f.str("name") ?: "Light") to NavInfo(
            kindLine = kind, abbrev = f.str("label_short") ?: chr,
            lampColours = colours.split(',').map { it.trim() }, lines = lines,
            warn = note, pattern = LightPattern.of(chr, colours, per, seq),
        )
    }

    /** Card for a buoy or beacon: color, shape, purpose and number. */
    private fun aidCard(f: Feature): Pair<String, NavInfo> {
        val colour = LightPattern.colourWord(f.str("colour") ?: "1").replaceFirstChar { it.uppercase() }
        val what = when (f.str("category")) {
            "special" -> "special-purpose ${f.str("kind") ?: "mark"}"
            "safe water" -> "safe-water ${f.str("kind") ?: "mark"}"
            else -> f.str("kind") ?: "mark"
        }
        val num = f.str("number")?.let { " \"$it\"" } ?: ""
        val lines = ArrayList<String>()
        f.str("light")?.let { lines += "Light: $it" }
        f.str("status")?.let { lines += it.replaceFirstChar { c -> c.uppercase() } }
        return (f.str("name") ?: "$colour $what") to NavInfo("$colour $what$num", lines = lines)
    }

    /** Card for a landmark: the chart word in plain English ("STACK" → "Smokestack") and whether it's conspicuous. */
    private fun landmarkCard(f: Feature): Pair<String, NavInfo> {
        val word = f.str("word") ?: "LMK"
        val plain = when (word) {
            "TOWER" -> "Tower"; "R TOWER" -> "Radio tower"; "TV TOWER" -> "TV tower"; "STACK" -> "Smokestack"
            "TANK" -> "Tank"; "MAST" -> "Mast"; "R MAST" -> "Radio mast"; "SPIRE" -> "Spire"; "FS" -> "Flagpole"
            "DOME" -> "Dome"; "BLDG" -> "Building"; "SILO" -> "Silo"; "ELEVATOR" -> "Grain elevator"
            "MONUMENT" -> "Monument"; "WINDMILL" -> "Windmill"; "WINDMOTOR" -> "Wind turbine"
            else -> word.lowercase().replaceFirstChar { it.uppercase() }
        }
        val kind = "Landmark" + if (f.num("conspicuous") == 1.0) "  ·  conspicuous" else ""
        val lines = listOfNotNull(f.num("height_m")?.let { "${Math.round(it / 0.3048)} ft high" })
        return (f.str("name") ?: plain) to NavInfo(kind, lines = listOf(plain) + lines)
    }

    /** Seconds as "5" or "2.5". */
    private fun fmtS(s: Double) = if (abs(s - Math.round(s)) < 0.05) Math.round(s).toString() else "%.1f".format(s)

    /** Phase of one light (lights are not in step with each other): from its position, the same every time. */
    fun phase(lat: Double, lon: Double, period: Double): Double = ((abs(lat * 7919.0 + lon * 104729.0)) % period)
}

/** The navigation-aid part of the tap card: kind, the pattern with a blinking lamp, plain lines, a warning, the strip. */
@Composable
fun NavCardBody(nav: NavInfo) {
    var nowMs by remember { mutableLongStateOf(0L) }
    val pattern = nav.pattern
    LaunchedEffect(pattern) { if (pattern != null) while (true) { withFrameMillis { nowMs = it }; delay(50) } }
    val t = nowMs / 1000.0
    Text(nav.kindLine, color = Mist, fontSize = 14.sp)
    nav.abbrev?.let { ab ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            if (pattern != null) Canvas(Modifier.size(18.dp)) {
                val lit = pattern.colourAt(t)
                val c = glowColour(lit ?: nav.lampColours.firstOrNull() ?: "1")
                val level = if (lit != null) pattern.levelAt(t) else 0f
                drawCircle(c.copy(alpha = 0.25f + 0.35f * level), size.minDimension / 2)
                drawCircle(if (lit != null) c else c.copy(alpha = 0.35f), size.minDimension / 4)
            }
            Text(ab, color = Foam, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
    }
    for (l in nav.lines) Text(l, color = Mist, fontSize = 14.sp)
    nav.warn?.let { Text("⚠ $it", color = WARN_YELLOW, fontSize = 14.sp) }
    if (pattern != null && pattern.periodS > 0.0 && pattern.on.any { it.to - it.from < pattern.periodS }) {
        // two periods of the pattern, with a playhead
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 6.dp)) {
            val span = 2 * pattern.periodS
            val sc = size.width / span.toFloat()
            drawRect(Color(0xFF2E3440), Offset.Zero, Size(size.width, size.height))
            for (rep in 0..1) for (o in pattern.on) {
                drawRect(glowColour(o.colour), Offset(((rep * pattern.periodS + o.from) * sc).toFloat(), 0f),
                    Size(((o.to - o.from) * sc).toFloat(), size.height))
            }
            val x = ((t % span) * sc).toFloat()
            drawLine(Color.White, Offset(x, -2f), Offset(x, size.height + 2f), 2.dp.toPx())
        }
    }
}

/**
 * Animated light glow over the chart: from zoom 13, only while `active` (after sunset or in night mode).
 * The lights in view are read from the map when the camera stops; each frame (about 20 a second) draws a glow in
 * the light's colour while its pattern is on. Not in step with the real lights.
 */
@Composable
fun NavLightGlow(map: MapLibreMap?, tick: Int, idleTick: Int, active: Boolean, lightLayerIds: List<String>) {
    val m = map ?: return
    val zoomOk = m.cameraPosition.zoom >= GLOW_MIN_ZOOM
    val on = active && zoomOk && lightLayerIds.isNotEmpty()
    val lights = remember(idleTick, on, lightLayerIds) {
        if (!on) emptyList()
        else {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val feats = runCatching {
                m.queryRenderedFeatures(RectF(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat()), *lightLayerIds.toTypedArray())
            }.getOrDefault(emptyList())
            feats.mapNotNull { f ->
                val p = f.geometry() as? Point ?: return@mapNotNull null
                val pat = LightPattern.of(f.str("chr") ?: "F", f.str("colours") ?: "1", f.num("sigper"), f.str("sigseq"))
                Lit(LatLng(p.latitude(), p.longitude()), pat, NavAids.phase(p.latitude(), p.longitude(), pat.periodS))
            }.distinctBy { it.at }
        }
    }
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lights.isNotEmpty()) { if (lights.isNotEmpty()) while (true) { withFrameMillis { nowMs = it }; delay(GLOW_FRAME_MS) } }
    if (lights.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        tick                                                          // read so camera moves redraw
        val t = nowMs / 1000.0
        val r = 16.dp.toPx()
        for (l in lights) {
            val code = l.pattern.colourAt(t + l.phase) ?: continue
            val level = l.pattern.levelAt(t + l.phase)
            val s = m.projection.toScreenLocation(l.at)
            val c = glowColour(code)
            val center = Offset(s.x, s.y)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.85f * level), c.copy(alpha = 0f)), center, r), r, center)
            drawCircle(Color.White.copy(alpha = level), 3.dp.toPx(), center)
            drawCircle(c.copy(alpha = level), 2.dp.toPx(), center)
        }
    }
}

/** One light in view for the glow: position, timing, and its own phase. */
private data class Lit(val at: LatLng, val pattern: LightPattern, val phase: Double)

/** Lights animate only from this zoom in (closer, so few lights are on screen at once). */
const val GLOW_MIN_ZOOM = 13.0
/** Glow animation frame time (20 frames a second). */
private const val GLOW_FRAME_MS = 50L
