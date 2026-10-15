package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.celestial.AlmanacTables
import com.thirdcoastsailing.app.celestial.Check
import com.thirdcoastsailing.app.celestial.Coach
import com.thirdcoastsailing.app.celestial.DEFAULT_HEIGHT_OF_EYE_FT
import com.thirdcoastsailing.app.celestial.DailyPage
import com.thirdcoastsailing.app.celestial.EyePosition
import com.thirdcoastsailing.app.celestial.FieldKind
import com.thirdcoastsailing.app.celestial.Guidance
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.Limb
import com.thirdcoastsailing.app.celestial.Shot
import com.thirdcoastsailing.app.celestial.SightPlanner
import com.thirdcoastsailing.app.celestial.SightWork
import com.thirdcoastsailing.app.celestial.SkillLevel
import com.thirdcoastsailing.app.celestial.SkyBody
import com.thirdcoastsailing.app.celestial.Suggestion
import com.thirdcoastsailing.app.celestial.Twilight
import com.thirdcoastsailing.app.celestial.WorkLine
import com.thirdcoastsailing.app.celestial.Workbook
import com.thirdcoastsailing.app.celestial.WorkbookSight
import com.thirdcoastsailing.app.celestial.distanceNm
import com.thirdcoastsailing.app.celestial.formatLat
import com.thirdcoastsailing.app.celestial.formatLon
import com.thirdcoastsailing.app.celestial.kind
import com.thirdcoastsailing.app.ui.theme.Alarm
import com.thirdcoastsailing.app.ui.theme.Caution
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Celestial navigation trainer: Plan · Workbook · Almanac, plus the 3D star finder.
 *
 *  Plan      today's twilight times and (BEGINNER / INTERMEDIATE) what to shoot and when; BEGINNER also gets the
 *            sextant steps with pictures and tips. ADVANCED: times only.
 *  Workbook  one card per sight: MARK records the time and position, then type the sextant reading; the working is
 *            laid out line by line with each equation. BEGINNER: filled in, tap "From the almanac" to see where the
 *            numbers came from. INTERMEDIATE: filled in, with the almanac rows shown. ADVANCED: type every line
 *            yourself from the Almanac tab; wrong lines are outlined red, never corrected.
 *            Lines of position and the fix go on the chart; the reset arrow (↺) clears them.
 *  Almanac   printed-almanac style tables for any day: hourly GHA / Dec, stars, increments, dip, refraction.
 *
 * The star finder opens from "Sky" (top bar), from a sight card, or from a suggestion; ✕ or Back returns here.
 */

private const val DEFAULT_LAT = 44.0          // middle of Lake Michigan, when there's no GPS and no DR typed in
private const val DEFAULT_LON = -87.0
private const val MB_PER_INHG = 33.8639        // millibars per inch of mercury

/** Star finder request: which body to point at (BEGINNER arrow) and what a pick does. */
private data class FinderRequest(val target: SkyBody?, val forSightId: Long?, val pickToAdd: Boolean)

@Composable
fun CelestialScreen(store: CelestialStore, gps: BoatFix?, onClose: () -> Unit) {
    val st = store.state
    val set = st.settings
    val plot = remember(st) { celestialPlot(st) }
    var tab by remember { mutableStateOf(1) }
    var finder by remember { mutableStateOf<FinderRequest?>(null) }
    var guide by remember { mutableStateOf<Pair<SkyBody, String?>?>(null) }      // body, preset / bearing note
    var bookTarget by remember { mutableStateOf<AlmanacTarget?>(null) }
    val gpsPos = gps?.let { LatLon(it.lat, it.lon) }
    val position = gpsPos ?: st.manualDr ?: LatLon(DEFAULT_LAT, DEFAULT_LON)

    Dialog(onDismissRequest = { if (finder != null) finder = null else if (guide != null) guide = null else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(NavyDeep)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // ---- title bar: name, Sky (star finder), Done
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Celestial", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Foam, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { finder = FinderRequest(null, null, pickToAdd = true) }) {
                        Text("★ Sky", color = SignalOrange)
                    }
                    TextButton(onClick = onClose) { Text("Done", color = SignalOrange, fontSize = 16.sp) }
                }
                Text("Training · not for navigation", color = NavyDeep, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Caution, RoundedCornerShape(8.dp)).padding(vertical = 4.dp))
                // ---- skill level (applies to every tab)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    for (l in SkillLevel.entries) FilterChip(
                        selected = set.level == l,
                        onClick = { store.update { it.copy(settings = it.settings.copy(level = l)) } },
                        label = { Text(l.title) },
                    )
                }
                TabRow(selectedTabIndex = tab, containerColor = Navy, contentColor = SignalOrange) {
                    for ((i, t) in listOf("Plan", "Workbook", "Almanac").withIndex())
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, color = if (tab == i) SignalOrange else Mist) })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> PlanTab(set.level, position, gpsPos != null, set.heightOfEyeFt,
                            onFind = { b -> finder = FinderRequest(b, null, pickToAdd = false) },
                            onAdd = { b -> store.addSight(b); tab = 1 },
                            onGuide = { b, note -> guide = b to note })
                        1 -> WorkbookTab(store, plot, gps, position, gpsPos != null,
                            onFind = { b, id -> finder = FinderRequest(b, id, pickToAdd = false) },
                            onPickNew = { finder = FinderRequest(null, null, pickToAdd = true) },
                            onGuide = { b -> guide = b to presetNote(b, position) },
                            onAlmanac = { t -> bookTarget = t; tab = 2 })
                        else -> AlmanacBook(bookTarget, st.sights)
                    }
                }
            }

            // ---- sextant guide over the tabs; ✕ / Back come back here
            guide?.let { (b, note) ->
                SightGuide(b, includeIndexError = st.sights.none { it.shots.isNotEmpty() }, note = note, onClose = { guide = null })
            }

            // ---- star finder over everything; ✕ / Back come back here
            finder?.let { req ->
                val beginnerTarget = remember(req, set.level) {
                    if (set.level == SkillLevel.BEGINNER) req.target ?: nextSuggested(set.level, position) else null
                }
                StarFinderScreen(
                    position = position, positionIsGps = gpsPos != null,
                    boatHeadingDeg = gps?.cogDeg?.toDouble(),
                    suggested = beginnerTarget,
                    pickLabel = if (req.forSightId != null) "Use" else "Add sight:",
                    onPick = { b ->
                        val id = req.forSightId
                        if (id != null) store.updateSight(id) { s -> if (s.shots.isEmpty()) s.copy(body = b, limb = com.thirdcoastsailing.app.celestial.defaultLimb(b)) else s }
                        else store.addSight(b)
                        finder = null; tab = 1
                    },
                    onClose = { finder = null },
                )
            }
        }
    }
}

/** The next suggested body from today's plan (the first one still ahead, else the last). */
private fun nextSuggested(level: SkillLevel, pos: LatLon): SkyBody? = runCatching {
    val g = Coach.guidance(level, pos, localDayStart(System.currentTimeMillis()))
    val now = System.currentTimeMillis()
    (g.suggestions.firstOrNull { it.target.timeMs >= now - 30 * 60_000L } ?: g.suggestions.lastOrNull())?.target?.body
}.getOrNull()

// ==================================================================== Plan

@Composable
private fun PlanTab(level: SkillLevel, pos: LatLon, isGps: Boolean, heightOfEyeFt: Double,
                    onFind: (SkyBody) -> Unit, onAdd: (SkyBody) -> Unit, onGuide: (SkyBody, String?) -> Unit) {
    val day = localDayStart(System.currentTimeMillis())
    val key = "%.1f %.1f".format(pos.lat, pos.lon)
    val guidance by produceState<Guidance?>(null, level, key, day, heightOfEyeFt) {
        value = withContext(Dispatchers.Default) { runCatching { Coach.guidance(level, pos, day, heightOfEyeFt) }.getOrNull() }
    }
    val twilight by produceState<Twilight?>(null, key, day) {
        value = withContext(Dispatchers.Default) { runCatching { SightPlanner.twilight(pos, day) }.getOrNull() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Today at ${formatLat(pos.lat)} ${formatLon(pos.lon)}${if (isGps) " (GPS)" else ""}", color = Mist, fontSize = 13.sp)
        Card {
            Text("Sun and twilight (local time)", color = Foam, fontWeight = FontWeight.Bold)
            val t = twilight
            if (t == null) Text("Working…", color = Mist)
            else for ((name, ms) in listOf("Nautical dawn" to t.nauticalDawn, "Civil dawn" to t.civilDawn, "Sunrise" to t.sunrise,
                "Noon (Sun highest)" to t.localApparentNoon, "Sunset" to t.sunset, "Civil dusk" to t.civilDusk, "Nautical dusk" to t.nauticalDusk))
                KeyValue(name, ms?.let { hhmm(it) } ?: "—")
            Text("Star sights: between civil and nautical twilight, when stars and a sharp horizon are both visible.",
                color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (level == SkillLevel.ADVANCED) {
            Text("Advanced: no suggestions. Plan from the Almanac tab and your own judgement.", color = Mist, fontSize = 13.sp)
            return@Column
        }
        val g = guidance
        if (g == null) { Text("Planning today's sights…", color = Mist); return@Column }
        if (g.suggestions.isEmpty()) Text("No good sights found for today here.", color = Mist)
        for (s in g.suggestions) SuggestionCard(s, level, onFind, onAdd, onGuide)
        if (g.tips.isNotEmpty()) Card {
            Text("Tips", color = Foam, fontWeight = FontWeight.Bold)
            for (t in g.tips) Text("• $t", color = Mist, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, level: SkillLevel, onFind: (SkyBody) -> Unit, onAdd: (SkyBody) -> Unit,
                           onGuide: (SkyBody, String?) -> Unit) {
    Card {
        Text(hhmm(s.target.timeMs), color = SignalOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(s.headline, color = Foam, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { onFind(s.target.body) }) { Text("Find in sky", color = SignalOrange) }
            OutlinedButton(onClick = { onAdd(s.target.body) }) { Text("Add to workbook", color = SignalOrange) }
        }
        // BEGINNER: the step-by-step guide, one step per screen
        if (level == SkillLevel.BEGINNER) TextButton(onClick = {
            onGuide(s.target.body, "preset %.0f°, face %03.0f° %s".format(s.target.altitudeDeg, s.target.azimuthDeg, Coach.compassPoint(s.target.azimuthDeg)))
        }) { Text("How to take this sight ›", color = SignalOrange) }
    }
}

/** "preset 32°, face 140° SE" for a body right now (for the guide's heading). */
private fun presetNote(b: SkyBody, pos: LatLon): String? = runCatching {
    val h = com.thirdcoastsailing.app.celestial.SightReduction.altAz(b, pos, System.currentTimeMillis())
    if (h.hcDeg < 0) "below the horizon now" else "now about %.0f°, face %03.0f° %s".format(h.hcDeg, h.znDeg, Coach.compassPoint(h.znDeg))
}.getOrNull()

// ==================================================================== Workbook

@Composable
private fun WorkbookTab(store: CelestialStore, plot: CelestialPlot, gps: BoatFix?, position: LatLon, isGps: Boolean,
                        onFind: (SkyBody, Long) -> Unit, onPickNew: () -> Unit,
                        onGuide: (SkyBody) -> Unit, onAlmanac: (AlmanacTarget) -> Unit) {
    val st = store.state
    val set = st.settings
    var confirmClear by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }              // index of a saved fix's workbook; null = current
    var pickMenu by remember { mutableStateOf(false) }
    val typed = remember { mutableStateMapOf<String, String>() }        // what's in each text box (sight:field)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- which workbook: the current one, or a saved fix's (by fix time); ↺ clears everything
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { pickMenu = true }) {
                    Text("Workbook: " + (viewing?.let { st.saved.getOrNull(it) }?.let { "Fix ${fixLabel(it.timeMs)}" } ?: "Current") + "  ▾", color = Foam)
                }
                DropdownMenu(expanded = pickMenu, onDismissRequest = { pickMenu = false }) {
                    DropdownMenuItem(text = { Text("Current", fontWeight = if (viewing == null) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { pickMenu = false; viewing = null })
                    for (i in st.saved.indices.reversed()) DropdownMenuItem(
                        text = { Text("Fix ${fixLabel(st.saved[i].timeMs)}", fontWeight = if (viewing == i) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { pickMenu = false; viewing = i })
                }
            }
            // ↺ appears once anything is entered: clears the sights, saved fixes and the fix log on the chart
            if (st.sights.isNotEmpty() || st.saved.isNotEmpty()) TextButton(onClick = { confirmClear = true }) {
                Text("↺", color = SignalOrange, fontSize = 24.sp)
            }
        }

        // ---- a saved fix's workbook: read-only
        val v = viewing?.takeIf { it < st.saved.size }                    // gone if everything was cleared
        if (v != null) {
            val f = st.saved[v]
            val sp = remember(f) { savedPlot(f) }
            Text("Saved ${fixLabel(f.timeMs)} · ${f.settings.level.title} · eye ${"%.0f".format(f.settings.heightOfEyeFt)} ft · read-only", color = Mist, fontSize = 12.sp)
            for (w in sp.works) SightCard(w, f.settings.level, gps, position, store, typed, onFind, onGuide, onAlmanac, readOnly = true)
            FixCard(sp, onSave = null)
            Spacer(Modifier.height(40.dp))
            return@Column
        }

        // ---- settings for every sight
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sight settings", color = Foam, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            if (set.level == SkillLevel.BEGINNER) EyeDropdown(set.heightOfEyeFt) { h ->
                store.update { it.copy(settings = it.settings.copy(heightOfEyeFt = h)) }
            } else NumberBox("Height of eye (ft)", "%.0f".format(set.heightOfEyeFt), "hoe") { t ->
                t.toDoubleOrNull()?.takeIf { it in 0.0..200.0 }?.let { h -> store.update { it.copy(settings = it.settings.copy(heightOfEyeFt = h)) } }
            }
            NumberBox("Index error (′, + on the arc, − off)", if (set.indexErrorMin == 0.0) "0" else "%+.1f".format(set.indexErrorMin), "ie") { t ->
                t.replace("−", "-").toDoubleOrNull()?.let { v -> store.update { it.copy(settings = it.settings.copy(indexErrorMin = v)) } }
            }
            // air temperature and pressure change refraction a little (mostly for low sights); not at Beginner level
            if (set.level != SkillLevel.BEGINNER) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    NumberBox("Air temp (°F)", "%.0f".format(set.temperatureC * 9.0 / 5.0 + 32.0), "temp") { t ->
                        t.toDoubleOrNull()?.takeIf { it in -40.0..120.0 }?.let { f ->
                            store.update { it.copy(settings = it.settings.copy(temperatureC = (f - 32.0) * 5.0 / 9.0)) }
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    NumberBox("Pressure (inHg)", "%.2f".format(set.pressureMb / MB_PER_INHG), "press") { t ->
                        t.toDoubleOrNull()?.takeIf { it in 27.0..32.0 }?.let { p ->
                            store.update { it.copy(settings = it.settings.copy(pressureMb = p * MB_PER_INHG)) }
                        }
                    }
                }
            }
            if (!isGps) {
                Text("No GPS: type your DR position", color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        NumberBox("DR lat (43 04.0N)", st.manualDr?.let { Workbook.format(it.lat, FieldKind.LAT) } ?: "", "drlat", numeric = false) { t ->
                            Workbook.parse(t, FieldKind.LAT)?.let { lat -> store.update { it.copy(manualDr = LatLon(lat, it.manualDr?.lon ?: DEFAULT_LON)) } }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        NumberBox("DR lon (87 03.5W)", st.manualDr?.let { Workbook.format(it.lon, FieldKind.LON) } ?: "", "drlon", numeric = false) { t ->
                            Workbook.parse(t, FieldKind.LON)?.let { lon -> store.update { it.copy(manualDr = LatLon(it.manualDr?.lat ?: DEFAULT_LAT, lon)) } }
                        }
                    }
                }
            } else Text("Position from GPS: ${formatLat(position.lat)} ${formatLon(position.lon)}", color = Mist, fontSize = 12.sp)
        }

        // ---- the sights
        if (st.sights.isEmpty()) Text(
            when (set.level) {
                SkillLevel.BEGINNER -> "Add a sight to start: pick one from the Plan tab, tap ★ Sky and touch a star, or use + Add sight."
                else -> "No sights yet. + Add sight, or ★ Sky to pick a body."
            }, color = Mist, fontSize = 13.sp)
        for (w in plot.works) SightCard(w, set.level, gps, position, store, typed, onFind, onGuide, onAlmanac)

        // ---- add a sight
        Box {
            Button(onClick = { addMenu = true }, colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy)) {
                Text("+ Add sight", fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("Pick in the sky…") }, onClick = { addMenu = false; onPickNew() })
                for (b in listOf(SkyBody.Sun, SkyBody.Moon) + SkyBody.planets + SkyBody.stars.sortedBy { it.name })
                    DropdownMenuItem(text = { Text(b.name) }, onClick = { addMenu = false; store.addSight(b) })
            }
        }

        // ---- result; Save fix keeps it in the log and starts a fresh workbook
        FixCard(plot, onSave = { store.saveFix(); typed.clear() })
        Spacer(Modifier.height(40.dp))
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear everything?") },
        text = { Text("This removes the current sights, every saved fix and its workbook, and the fix log from the chart.") },
        confirmButton = { TextButton(onClick = { store.clearAll(); typed.clear(); viewing = null; confirmClear = false }) { Text("Clear", color = SignalOrange) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** BEGINNER: where you stand, instead of a number. */
@Composable
private fun EyeDropdown(current: Double, onPick: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val match = EyePosition.entries.firstOrNull { it.heightFt == current }
    Box(Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Text("Eye height: " + (match?.let { "${it.title} (${it.heightFt.toInt()} ft)" } ?: "%.0f ft".format(current)) + "  ▾", color = Foam)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in EyePosition.entries) DropdownMenuItem(
                text = { Text("${p.title} · ${p.heightFt.toInt()} ft") },
                onClick = { open = false; onPick(p.heightFt) })
        }
    }
}

@Composable
private fun SightCard(w: SightWork, level: SkillLevel, gps: BoatFix?, position: LatLon, store: CelestialStore,
                      typed: MutableMap<String, String>, onFind: (SkyBody, Long) -> Unit,
                      onGuide: (SkyBody) -> Unit, onAlmanac: (AlmanacTarget) -> Unit, readOnly: Boolean = false) {
    val s = w.sight
    var showExtract by remember(s.id) { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.body.name, color = Foam, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!readOnly) {
                if (level != SkillLevel.ADVANCED) TextButton(onClick = { onGuide(s.body) }) { Text("Guide", color = SignalOrange) }
                TextButton(onClick = { onFind(s.body, s.id) }) { Text("★ Find", color = SignalOrange) }
                TextButton(onClick = { store.removeSight(s.id) }) { Text("Remove", color = Mist, fontSize = 12.sp) }
            }
        }
        if (readOnly && s.limb != Limb.CENTER) Text(if (s.limb == Limb.LOWER) "Lower limb" else "Upper limb", color = Mist, fontSize = 12.sp)
        if (!readOnly && (s.body == SkyBody.Sun || s.body == SkyBody.Moon)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in listOf(Limb.LOWER, Limb.UPPER)) FilterChip(selected = s.limb == l,
                onClick = { store.updateSight(s.id) { it.copy(limb = l) } },
                label = { Text(if (l == Limb.LOWER) "Lower limb" else "Upper limb") })
        }

        // ---- shots taken: time, sextant reading, kept (✓) or dropped (struck through, with why)
        val valid = s.shots.filter { !it.hsDeg.isNaN() }
        val kept = Workbook.keptShots(valid)
        val res = Workbook.residuals(valid)
        for ((i, sh) in s.shots.withIndex()) {
            val vi = valid.indexOf(sh)
            val used = vi >= 0 && sh in kept
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Mist, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                val strike = if (used) TextDecoration.None else TextDecoration.LineThrough
                Text(hhmmss(sh.timeMs), color = if (used) Foam else Mist, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    textDecoration = strike, modifier = Modifier.width(92.dp))
                Text(if (sh.hsDeg.isNaN()) "—" else Workbook.format(sh.hsDeg, FieldKind.ALTITUDE), color = if (used) Foam else Mist,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp, textDecoration = strike, modifier = Modifier.weight(1f))
                if (used) Text("✓", color = Color(0xFF6FCF97), fontWeight = FontWeight.Bold)
                if (!readOnly) TextButton(onClick = { store.updateSight(s.id) { x -> x.copy(shots = x.shots.filterIndexed { j, _ -> j != i }) } }) {
                    Text("✕", color = Mist)
                }
            }
            val r = if (vi >= 0) res.getOrNull(vi) else null
            if (!used && r != null) Text(Workbook.dropNote(r), color = Alarm, fontSize = 12.sp, modifier = Modifier.padding(start = 22.dp))
        }

        // ---- the next shot, always ready: MARK TIME writes the time into its box; either box can be typed
        if (!readOnly) NextShot(s, typed, gps, position, store, beginner = level == SkillLevel.BEGINNER)

        val shot = w.shot ?: return@Card
        Text("Using ${w.shotsUsed} of ${valid.size} shots: ${hhmmss(shot.timeMs)} (UT ${utc(shot.timeMs)}), Hs ${Workbook.format(shot.hsDeg, FieldKind.ALTITUDE)}",
            color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        // ---- where the almanac numbers came from, and a way into the book at that page
        val bookTarget = almanacTarget(s.body, shot.timeMs, com.thirdcoastsailing.app.celestial.Field.GHA_HOUR)
        when (level) {
            SkillLevel.BEGINNER -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showExtract = !showExtract }, modifier = Modifier.weight(1f)) {
                        Text(if (showExtract) "From the almanac ▴" else "From the almanac ▾", color = SignalOrange)
                    }
                    if (showExtract && bookTarget != null) TextButton(onClick = { onAlmanac(bookTarget) }) { Text("Open the page ›", color = SignalOrange) }
                }
                if (showExtract) Extract(w)
            }
            SkillLevel.INTERMEDIATE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From the almanac", color = SignalOrange, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (bookTarget != null) TextButton(onClick = { onAlmanac(bookTarget) }) { Text("Open the page ›", color = SignalOrange) }
                }
                Extract(w)
            }
            SkillLevel.ADVANCED -> Text("Work each line from the Almanac tab.", color = Mist, fontSize = 12.sp)
        }
        HorizontalDivider(color = NavyLight, modifier = Modifier.padding(vertical = 6.dp))

        // ---- the working, line by line
        for (line in w.lines) WorkRow(s, line, level, store, typed, readOnly,
            onAlmanac = if (level == SkillLevel.ADVANCED) null else almanacTarget(s.body, shot.timeMs, line.field)?.let { t -> { onAlmanac(t) } })

        val lop = w.lop
        if (lop != null) Text(
            "Line of position: Zn %05.1f°, %.1f nm %s".format(lop.znDeg, kotlin.math.abs(lop.interceptNm), if (lop.interceptNm >= 0) "toward" else "away"),
            color = Foam, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        else if (level == SkillLevel.ADVANCED) Text("Fill in every line to plot this sight.", color = Mist, fontSize = 12.sp)
    }
}

/**
 * The next shot's entry, always shown under the shots taken: "Shot n", a large MARK TIME · SHOT n button that writes
 * the time (to the second) into the time box, the time box and the angle box (either can be typed by hand), and
 * Enter ✓ to save the shot. After Enter the boxes clear for the next shot.
 */
@Composable
private fun NextShot(s: WorkbookSight, typed: MutableMap<String, String>, gps: BoatFix?, position: LatLon,
                     store: CelestialStore, beginner: Boolean) {
    val n = s.shots.size + 1
    val kTime = "${s.id}:next:time"; val kHs = "${s.id}:next:hs"; val kMs = "${s.id}:next:ms"
    val timeText = typed[kTime] ?: ""
    val hsText = typed[kHs] ?: ""
    val markedMs = typed[kMs]?.toLongOrNull()
    var problem by remember(s.id, n) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(Navy, RoundedCornerShape(14.dp))
            .border(2.dp, SignalOrange, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shot $n", color = SignalOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("  MARK TIME fills the time; either box can be typed", color = Mist, fontSize = 12.sp)
        }
        if (beginner && s.shots.isEmpty()) Text(
            "Tap MARK TIME the instant the ${if (s.body.kind == com.thirdcoastsailing.app.celestial.BodyKind.STAR) "star" else s.body.name} touches the horizon, then type the sextant reading (degrees and minutes, e.g. 38 25.8). Take 3 to 5.",
            color = Mist, fontSize = 12.sp)
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                typed[kMs] = now.toString(); typed[kTime] = hhmmss(now); problem = ""
            },
            colors = if (markedMs == null) ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy)
            else ButtonDefaults.outlinedButtonColors(contentColor = SignalOrange),
            border = if (markedMs == null) null else androidx.compose.foundation.BorderStroke(2.dp, SignalOrange),
            shape = RoundedCornerShape(40.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(if (markedMs == null) "MARK TIME · SHOT $n" else "Marked ${hhmmss(markedMs)} · tap to re-mark",
                fontWeight = FontWeight.Bold, fontSize = if (markedMs == null) 20.sp else 16.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(120.dp)) {
                TypedBox("hh:mm:ss", timeText, Check.AUTO) { t -> typed[kTime] = t; problem = "" }
            }
            Box(Modifier.weight(1f)) {
                TypedBox("angle 48 34.4", hsText, Check.AUTO) { t -> typed[kHs] = t; problem = "" }
            }
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    // the marked instant (to the millisecond) unless the time box was changed by hand
                    val t = if (markedMs != null && timeText == hhmmss(markedMs)) markedMs
                    else Workbook.parseTimeOfDay(timeText, now, TimeZone.getDefault().getOffset(now).toLong())
                    val hs = Workbook.parse(hsText, FieldKind.ALTITUDE)
                    when {
                        t == null -> problem = "Type the time as 19:46:12, or tap MARK TIME."
                        hs == null || hs < 0.0 || hs > 90.0 -> problem = "Type the sextant reading as degrees and minutes, e.g. 48 34.4."
                        else -> {
                            val dr = gps?.let { LatLon(it.lat, it.lon) } ?: position
                            store.updateSight(s.id) { x -> x.copy(shots = (x.shots + Shot(t, hs, dr, gps != null)).sortedBy { it.timeMs }) }
                            typed.remove(kTime); typed.remove(kHs); typed.remove(kMs); problem = ""
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy),
            ) { Text("Enter ✓", fontWeight = FontWeight.Bold) }
        }
        if (problem.isNotEmpty()) Text(problem, color = Alarm, fontSize = 12.sp)
    }
}

@Composable
private fun Extract(w: SightWork) {
    Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(8.dp)) {
        for (l in w.extract) Text(l.text, color = if (l.highlight) SignalOrange else Mist, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/**
 * One line of working: label and value (or, ADVANCED, a box to type it), the equation, and the numbers.
 * ADVANCED: a wrong line is struck through and outlined red, with no explanation. `onAlmanac` (not ADVANCED) opens
 * the almanac book at the row this line's value came from.
 */
@Composable
private fun WorkRow(s: WorkbookSight, line: WorkLine, level: SkillLevel, store: CelestialStore, typed: MutableMap<String, String>,
                    readOnly: Boolean, onAlmanac: (() -> Unit)?) {
    val kind = line.field.kind()
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.label, color = Foam, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (level != SkillLevel.ADVANCED) {
                Text(Workbook.format(line.expected, kind), color = Foam, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (onAlmanac != null) Text(" ›", color = SignalOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onAlmanac).padding(horizontal = 6.dp))
            } else if (readOnly) {
                // a saved ADVANCED workbook: what was typed, struck through if it was wrong
                Text(line.value?.let { Workbook.format(it, kind) } ?: "—", color = if (line.check == Check.WRONG) Alarm else Foam,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    textDecoration = if (line.check == Check.WRONG) TextDecoration.LineThrough else TextDecoration.None)
            } else {
                val key = "${s.id}:${line.field.name}"
                Box(Modifier.widthIn(min = 150.dp, max = 180.dp)) {
                    TypedBox(hintFor(kind), typed[key] ?: s.entries[line.field]?.let { Workbook.format(it, kind) } ?: "", line.check) { t ->
                        typed[key] = t
                        val v = Workbook.parse(t, kind)
                        store.updateSight(s.id) { x -> x.copy(entries = if (v == null) x.entries - line.field else x.entries + (line.field to v)) }
                    }
                }
            }
        }
        Text(line.formula, color = Mist, fontSize = 12.sp)
        if (level != SkillLevel.ADVANCED) Text(line.worked, color = Mist, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun hintFor(k: FieldKind) = when (k) {
    FieldKind.ANGLE, FieldKind.ALTITUDE -> "d mm.m"
    FieldKind.DEC, FieldKind.LAT -> "N d mm.m"
    FieldKind.LON -> "d mm.m W"
    FieldKind.MINUTES -> "±m.m"
    FieldKind.BEARING -> "ddd.d"
    FieldKind.NM -> "+toward / −away"
}

@Composable
private fun FixCard(plot: CelestialPlot, onSave: (() -> Unit)?) {
    if (plot.lops.isEmpty()) return
    Card {
        val f = plot.fix
        if (f == null) {
            Text(if (plot.lops.size == 1) "One line of position (on the chart). Add another body for a fix."
            else "Lines of position on the chart. They're too close to parallel for a fix: add a body at a different bearing.",
                color = Foam)
            return@Card
        }
        Text("Fix  ${formatLat(f.position.lat)}  ${formatLon(f.position.lon)}", color = Foam, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("At ${hhmmss(f.timeMs)} from ${f.lops.size} lines · they cross within %.1f nm".format(f.residualNm), color = Mist, fontSize = 13.sp)

        // ---- how sure: the circle drawn round the fix on the chart, with the working
        val u = f.uncertainty
        if (u != null) {
            HorizontalDivider(color = NavyLight, modifier = Modifier.padding(vertical = 6.dp))
            Text("How sure is this fix?", color = Foam, fontWeight = FontWeight.Bold)
            val mono = FontFamily.Monospace
            if (u.spreadNm != null) {
                Text("Spread of the lines  s = √(Σr² ÷ (n − 2))", color = Mist, fontSize = 12.sp)
                Text("  = √(%.2f ÷ %d) = %.1f′".format(u.sumSqNm2, u.lines - 2, u.spreadNm), color = Mist, fontFamily = mono, fontSize = 12.sp)
            } else Text("Two lines always meet exactly, so their spread can't be measured; a third body would show it.", color = Mist, fontSize = 12.sp)
            Text("Sight error used  σ = the larger of s and 1.0′ (typical for a hand-held sextant)", color = Mist, fontSize = 12.sp)
            Text("  = %.1f′".format(u.sigmaNm), color = Mist, fontFamily = mono, fontSize = 12.sp)
            Text("Geometry  G = √(trace (AᵀA)⁻¹), from the lines' bearings (3 lines 120° apart: 1.15; bigger as they get closer to parallel)",
                color = Mist, fontSize = 12.sp)
            Text("  = %.2f".format(u.geometry), color = Mist, fontFamily = mono, fontSize = 12.sp)
            Text("Radius (about 95%)  R = 2 × σ × G", color = Mist, fontSize = 12.sp)
            Text("  = 2 × %.1f × %.2f = %.1f nm".format(u.sigmaNm, u.geometry, u.radius95Nm), color = Foam, fontFamily = mono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Drawn as a circle round the fix on the chart.", color = Mist, fontSize = 12.sp)
        }

        // ---- error against the GPS (or the typed DR when there was no GPS)
        HorizontalDivider(color = NavyLight, modifier = Modifier.padding(vertical = 6.dp))
        if (f.drIsGps) {
            Text("Error compared with GPS", color = Foam, fontWeight = FontWeight.Bold)
            Text("%.1f nm · GPS position to fix %03.0f°T".format(f.offsetNm, f.offsetBearing), color = SignalOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (u != null) Text(if (f.offsetNm <= u.radius95Nm) "Inside the %.1f nm circle: as good as the sights allow.".format(u.radius95Nm)
                else "Outside the %.1f nm circle: worth checking the time, the readings and the working.".format(u.radius95Nm),
                color = Mist, fontSize = 12.sp)
        } else {
            Text("No GPS at the time: compared with your typed DR", color = Foam, fontWeight = FontWeight.Bold)
            Text("%.1f nm · DR to fix %03.0f°T".format(f.offsetNm, f.offsetBearing), color = SignalOrange, fontSize = 15.sp)
        }
        if (onSave != null) {
            Text("Shown on the chart. Save it to keep it in the fix log and start the next workbook.", color = Mist, fontSize = 12.sp)
            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy),
                modifier = Modifier.padding(top = 6.dp)) { Text("Save fix ✓", fontWeight = FontWeight.Bold) }
        }
    }
}

// ==================================================================== small parts

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(12.dp)).border(1.dp, NavyLight, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row { Text(k, color = Mist, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(v, color = Foam, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
}

/** A text box for a number; keeps its own text while typing and reports each change. */
@Composable
private fun NumberBox(label: String, initial: String, key: String, numeric: Boolean = true, onChange: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onChange(it) }, singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** Entry box for a sextant reading or a workbook line: red outline when ADVANCED checking finds it wrong. */
@Composable
private fun TypedBox(hint: String, text: String, check: Check, onChange: (String) -> Unit) {
    val ok = Color(0xFF6FCF97)
    val outline = when (check) { Check.WRONG -> Alarm; Check.OK -> ok; else -> NavyLight }
    OutlinedTextField(
        value = text, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, fontSize = 12.sp, color = Mist) },
        isError = check == Check.WRONG,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Foam,
            textDecoration = if (check == Check.WRONG) TextDecoration.LineThrough else TextDecoration.None),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = outline, focusedBorderColor = if (check == Check.WRONG) Alarm else SignalOrange,
            errorBorderColor = Alarm),
        trailingIcon = { if (check == Check.OK) Text("✓", color = ok) },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ==================================================================== time helpers

/** "19:52 · 17 Oct" for a saved fix. */
private fun fixLabel(ms: Long) = SimpleDateFormat("HH:mm · d MMM", Locale.getDefault()).format(Date(ms))
private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun hhmmss(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
private fun utc(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
private fun dateUtc(ms: Long) = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
private fun utcDayStart(ms: Long) = ms - Math.floorMod(ms, 86_400_000L)

/** Midnight at the start of today, local time (UTC ms). */
fun localDayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
