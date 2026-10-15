package com.thirdcoastsailing.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.celestial.AlmanacTables
import com.thirdcoastsailing.app.celestial.DailyPage
import com.thirdcoastsailing.app.celestial.Field
import com.thirdcoastsailing.app.celestial.SightReduction
import com.thirdcoastsailing.app.celestial.SkyBody
import com.thirdcoastsailing.app.celestial.Workbook
import com.thirdcoastsailing.app.celestial.WorkbookSight
import com.thirdcoastsailing.app.celestial.norm360
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/*
 * The almanac as a book: pages laid out like the printed Nautical Almanac (paper color, serif type, hours in
 * groups of six, the yellow increments pages), one UT day per page, with a search to jump straight to a page.
 *
 *  Sections: Sun & Moon · Aries & planets · Stars · Increments · Dip & refraction. ◀ ▶ turn the day.
 *  Opened from the workbook ("Open the page ›" or › on an almanac line) it lands on the page and row the number came
 *  from, highlighted. Pinch to change the type size; drag to scroll; turn the phone sideways for wider pages.
 */

enum class AlmanacSection(val title: String) {
    SUN_MOON("Sun & Moon"), ARIES_PLANETS("Aries & planets"), STARS("Stars"), INCREMENTS("Increments"), CORRECTIONS("Dip & refraction")
}

/** Where to open the book and what to highlight. */
data class AlmanacTarget(
    val dayStartUtc: Long,
    val section: AlmanacSection,
    val hour: Int? = null,          // daily pages: the hour row
    val star: String? = null,       // star list: the star
    val minute: Int? = null,        // increments: the page (minutes) ...
    val second: Int? = null,        // ... and the row (seconds)
    val dipFt: Double? = null,      // dip table row
)

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** The page and row a workbook line's value comes from (null for lines that aren't read from the almanac). */
fun almanacTarget(body: SkyBody, timeMs: Long, field: Field): AlmanacTarget? {
    val day = timeMs - Math.floorMod(timeMs, DAY_MS)
    val hour = ((timeMs - day) / HOUR_MS).toInt()
    val past = Math.floorMod(timeMs, HOUR_MS)
    val minute = (past / 60_000).toInt(); val second = ((past / 1000) % 60).toInt()
    val dailySection = when (body) {
        SkyBody.Sun, SkyBody.Moon -> AlmanacSection.SUN_MOON
        else -> AlmanacSection.ARIES_PLANETS                       // planets, and Aries for the stars
    }
    return when (field) {
        Field.INCREMENT, Field.V_CORR, Field.D_CORR -> AlmanacTarget(day, AlmanacSection.INCREMENTS, minute = minute, second = second)
        Field.SHA -> AlmanacTarget(day, AlmanacSection.STARS, star = body.name)
        Field.DEC -> if (body is SkyBody.Star) AlmanacTarget(day, AlmanacSection.STARS, star = body.name)
                     else AlmanacTarget(day, dailySection, hour = hour)
        Field.GHA_HOUR, Field.DEC_HOUR, Field.V, Field.D -> AlmanacTarget(day, dailySection, hour = hour)
        Field.DIP -> AlmanacTarget(day, AlmanacSection.CORRECTIONS)
        Field.REFRACTION -> AlmanacTarget(day, AlmanacSection.CORRECTIONS)
        else -> null
    }
}

private val PAPER = Color(0xFFF6F1E2)
private val YELLOW = Color(0xFFF7ECBE)
private val INK = Color(0xFF191919)
private val RULE = Color(0xFF787364)
private val MARK = Color(0xFFFFD696)

/** 25°30.9′ as the book prints it: "25 30.9". */
private fun g(deg: Double): String = Workbook.ang(deg).replace("°", " ").replace("′", "")
/** Declination as printed: "S 9 10.6". */
private fun dc(deg: Double): String = Workbook.decText(deg).replace("°", " ").replace("′", "")

@Composable
fun AlmanacBook(target: AlmanacTarget?, sights: List<WorkbookSight>) {
    val now = System.currentTimeMillis()
    var day by remember { mutableStateOf(now - Math.floorMod(now, DAY_MS)) }
    var section by remember { mutableStateOf(AlmanacSection.SUN_MOON) }
    var hour by remember { mutableStateOf<Int?>(((now - day) / HOUR_MS).toInt()) }
    var star by remember { mutableStateOf<String?>(null) }
    var minute by remember { mutableStateOf(0) }
    var second by remember { mutableStateOf<Int?>(null) }
    var dipFt by remember { mutableStateOf<Double?>(null) }
    var searching by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }

    fun open(t: AlmanacTarget) {
        day = t.dayStartUtc; section = t.section; hour = t.hour; star = t.star
        t.minute?.let { minute = it }; second = t.second; dipFt = t.dipFt; searching = false
    }
    LaunchedEffect(target) { target?.let { open(it) } }

    val page by produceState<DailyPage?>(null, day) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { AlmanacTables.dailyPage(day) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- sections and search
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in AlmanacSection.entries) FilterChip(selected = section == s, onClick = { section = s; searching = false }, label = { Text(s.title) })
            }
            TextButton(onClick = { searching = !searching }) { Text(if (searching) "Close" else "Search", color = SignalOrange) }
        }
        if (searching) { AlmanacSearch(day, sights) { open(it) }; return@Column }

        // ---- day
        if (section != AlmanacSection.INCREMENTS && section != AlmanacSection.CORRECTIONS) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day -= DAY_MS }) { Text("◀ ${dayShort(day - DAY_MS)}", color = SignalOrange) }
            Text(dayLong(day) + " (UT)", color = Foam, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            TextButton(onClick = { day += DAY_MS }) { Text("${dayShort(day + DAY_MS)} ▶", color = SignalOrange) }
        }
        if (section == AlmanacSection.INCREMENTS) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { minute = (minute + 59) % 60; second = null }) { Text("◀ ${(minute + 59) % 60}m", color = SignalOrange) }
            Text("${minute}m", color = Foam, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            TextButton(onClick = { minute = (minute + 1) % 60; second = null }) { Text("${(minute + 1) % 60}m ▶", color = SignalOrange) }
        }

        // ---- the page
        val vs = rememberScrollState()
        var markY by remember { mutableStateOf(-1f) }
        val zoom = rememberTransformableState { z, _, _ -> scale = (scale * z).coerceIn(0.7f, 2.2f) }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)
                .background(if (section == AlmanacSection.INCREMENTS) YELLOW else PAPER, RoundedCornerShape(6.dp))
                .transformable(zoom, canPan = { false })
                .verticalScroll(vs).horizontalScroll(rememberScrollState()).padding(12.dp)
        ) {
            val book = BookStyle(scale)
            when (section) {
                AlmanacSection.SUN_MOON, AlmanacSection.ARIES_PLANETS, AlmanacSection.STARS -> {
                    val p = page
                    if (p == null) Text("Working…", color = INK)
                    else when (section) {
                        AlmanacSection.SUN_MOON -> SunMoonPage(p, book, hour) { markY = it }
                        AlmanacSection.ARIES_PLANETS -> AriesPlanetsPage(p, book, hour) { markY = it }
                        else -> StarsPage(p, book, star) { markY = it }
                    }
                }
                AlmanacSection.INCREMENTS -> IncrementsPage(minute, book, second) { markY = it }
                AlmanacSection.CORRECTIONS -> CorrectionsPage(book, dipFt) { markY = it }
            }
            Text("Pinch to change the size · turn the phone for wider pages", color = RULE, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
        // bring the highlighted row into view
        LaunchedEffect(markY, section, day, minute) { if (markY > 0f) vs.animateScrollTo((markY - 160f).toInt().coerceAtLeast(0)) }
    }
}

/** Type sizes and column widths for the current zoom. */
private class BookStyle(val scale: Float) {
    val body = (13f * scale).sp
    val head = (12f * scale).sp
    val title = (17f * scale).sp
    fun w(dp: Int): Dp = (dp * scale).dp
}

@Composable
private fun PageTitle(left: String, right: String, b: BookStyle) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Text(left, color = INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = b.title, modifier = Modifier.width(b.w(260)))
        Text(right, color = INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = b.title)
    }
}

/** Column headings: (top, sub) pairs over fixed widths. */
@Composable
private fun Heads(heads: List<Pair<String, String>>, widths: List<Int>, b: BookStyle) {
    Row {
        for ((i, h) in heads.withIndex()) Column(Modifier.width(b.w(widths[i])), horizontalAlignment = Alignment.End) {
            Text(h.first, color = INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = b.head)
            Text(h.second, color = INK, fontFamily = FontFamily.Serif, fontSize = b.head)
        }
    }
    HorizontalDivider(color = INK, thickness = 1.5.dp, modifier = Modifier.width(b.w(widths.sum())).padding(vertical = 3.dp))
}

/** One printed row; highlighted rows report their position so the page can scroll to them. */
@Composable
private fun BookRow(cells: List<String>, widths: List<Int>, b: BookStyle, highlight: Boolean, onMark: (Float) -> Unit, bold: Boolean = false) {
    Row(
        Modifier.background(if (highlight) MARK else Color.Transparent)
            .then(if (highlight) Modifier.onGloballyPositioned { onMark(it.positionInParent().y) } else Modifier)
    ) {
        for ((i, c) in cells.withIndex()) Text(c, color = INK, fontFamily = FontFamily.Serif, fontSize = b.body,
            fontWeight = if (bold && i == 0) FontWeight.Bold else FontWeight.Normal,
            textAlign = if (i == 1 && widths.size == 4 && widths[1] > 100) TextAlign.Start else TextAlign.End,
            modifier = Modifier.width(b.w(widths[i])).padding(end = 6.dp))
    }
}

@Composable
private fun GroupGap(i: Int, every: Int = 6) { if (i > 0 && i % every == 0) Spacer(Modifier.height(6.dp)) }

@Composable
private fun SunMoonPage(p: DailyPage, b: BookStyle, hour: Int?, onMark: (Float) -> Unit) {
    PageTitle("SUN · MOON", bookDate(p.dayStartUtcMs), b)
    val w = listOf(36, 80, 76, 80, 40, 80, 40, 44)
    Heads(listOf("UT" to "h", "SUN" to "GHA", "" to "Dec", "MOON" to "GHA", "" to "v", "" to "Dec", "" to "d", "" to "HP"), w, b)
    for ((i, h) in p.hours.withIndex()) {
        GroupGap(i)
        BookRow(listOf("%02d".format(h.hourUtc), g(h.sunGha), dc(h.sunDec), g(h.moonGha), "%.1f".format(h.moonV), dc(h.moonDec),
            "%.1f".format(h.moonD), "%.1f".format(h.moonHp)), w, b, h.hourUtc == hour, onMark, bold = h.hourUtc % 6 == 0)
    }
    HorizontalDivider(color = INK, thickness = 1.5.dp, modifier = Modifier.width(b.w(w.sum())).padding(vertical = 3.dp))
    Text("Sun  SD %.1f′   d %.1f′        Moon  SD %.1f′   age %.1f d   %.0f%% lit".format(p.sunSd, p.sunD, p.moonSd, p.moonAgeDays, p.moonIllumPct),
        color = INK, fontFamily = FontFamily.Serif, fontSize = b.head)
}

@Composable
private fun AriesPlanetsPage(p: DailyPage, b: BookStyle, hour: Int?, onMark: (Float) -> Unit) {
    PageTitle("ARIES · PLANETS", bookDate(p.dayStartUtcMs), b)
    val names = listOf("Venus", "Mars", "Jupiter", "Saturn")
    val w = listOf(36, 80) + names.flatMap { listOf(80, 76) }
    Heads(listOf("UT" to "h", "ARIES" to "GHA") + names.flatMap { listOf(it.uppercase() to "GHA", "" to "Dec") }, w, b)
    for ((i, h) in p.hours.withIndex()) {
        GroupGap(i)
        BookRow(listOf("%02d".format(h.hourUtc), g(h.ghaAries)) + names.flatMap { n -> h.planets[n]?.let { listOf(g(it.first), dc(it.second)) } ?: listOf("", "") },
            w, b, h.hourUtc == hour, onMark, bold = h.hourUtc % 6 == 0)
    }
    HorizontalDivider(color = INK, thickness = 1.5.dp, modifier = Modifier.width(b.w(w.sum())).padding(vertical = 3.dp))
    Text(names.joinToString("      ") { "$it  v %.1f  d %.1f".format(p.planetV[it] ?: 0.0, p.planetD[it] ?: 0.0) },
        color = INK, fontFamily = FontFamily.Serif, fontSize = b.head)
}

@Composable
private fun StarsPage(p: DailyPage, b: BookStyle, star: String?, onMark: (Float) -> Unit) {
    PageTitle("STARS", bookDate(p.dayStartUtcMs), b)
    val w = listOf(36, 150, 80, 80)
    Heads(listOf("No." to "", "Name" to "", "SHA" to "", "Dec" to ""), w, b)
    for ((i, s) in p.stars.withIndex()) {
        GroupGap(i, 5)
        BookRow(listOf(if (s.number == 0) "" else "${s.number}", s.name, g(s.sha), dc(s.dec)), w, b, s.name == star, onMark)
    }
}

@Composable
private fun IncrementsPage(minute: Int, b: BookStyle, second: Int?, onMark: (Float) -> Unit) {
    PageTitle("${minute}m", "INCREMENTS AND CORRECTIONS", b)
    val w = listOf(36, 80, 80, 80)
    Heads(listOf("$minute" to "s", "SUN" to "PLANETS", "ARIES" to "", "MOON" to ""), w, b)
    for ((i, r) in AlmanacTables.increments(minute).withIndex()) {
        GroupGap(i, 5)
        BookRow(listOf("%02d".format(r.second), g(r.sunPlanets), g(r.aries), g(r.moon)), w, b, r.second == second, onMark)
    }
    Spacer(Modifier.height(10.dp))
    Text("v or d correction for ${minute}m", color = INK, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = b.head)
    val w2 = listOf(60, 60)
    Heads(listOf("v or d" to "", "corr" to ""), w2, b)
    for (k in 0..20) {
        val v = k * 0.3
        BookRow(listOf("%.1f".format(v), "%.1f".format(Workbook.m1(v * minute / 60.0))), w2, b, false, onMark)
    }
}

@Composable
private fun CorrectionsPage(b: BookStyle, dipFt: Double?, onMark: (Float) -> Unit) {
    PageTitle("DIP", "height of eye → dip (subtract)", b)
    val heights = (2..60 step 1).map { it.toDouble() }
    val pick = dipFt?.let { d -> heights.minBy { abs(it - d) } }
    val w = listOf(70, 70)
    Heads(listOf("Ht. of eye" to "ft", "Dip" to "′"), w, b)
    for ((i, h) in heights.withIndex()) {
        GroupGap(i, 5)
        BookRow(listOf("%.0f".format(h), "%.1f".format(Workbook.m1(-SightReduction.dipMin(h)))), w, b, h == pick, onMark)
    }
    Spacer(Modifier.height(12.dp))
    PageTitle("REFRACTION", "apparent altitude → R (subtract), 10 °C 1010 mb", b)
    Heads(listOf("Ha" to "°", "R" to "′"), w, b)
    val alts = listOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 25.0, 30.0, 35.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0)
    for ((i, a) in alts.withIndex()) {
        GroupGap(i, 5)
        BookRow(listOf("%.0f".format(a), "%.1f".format(Workbook.m1(SightReduction.refractionMin(a)))), w, b, false, onMark)
    }
}

// ==================================================================== search

private data class Hit(val title: String, val sub: String, val target: AlmanacTarget)

/**
 * Search: a body ("rasal", "venus"), a date ("Oct 18", "18 Oct", "10/18", "2026-10-18"), a UT time ("23:46"),
 * an increments page ("46m"), "dip 9", "refraction", "increments", "aries". Tapping a result opens that page.
 */
@Composable
private fun AlmanacSearch(day: Long, sights: List<WorkbookSight>, onOpen: (AlmanacTarget) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search a body, a date, a time or a page", color = Mist) })
        val hits = remember(q, day, sights) { search(q, day, sights) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (q.isBlank()) {
                Text("Try:", color = Foam, fontWeight = FontWeight.Bold)
                for ((ex, what) in listOf("Oct 18" to "the daily page for that date", "23:46" to "that UT hour's row, and the 46m increments",
                    "46m" to "the increments page", "Venus" to "Venus's column, today", "dip 9" to "dip for a 9 ft eye height"))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { q = ex }) {
                        Text(ex, color = SignalOrange, modifier = Modifier.border(1.5.dp, SignalOrange, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp))
                        Text("  $what", color = Mist, fontSize = 13.sp)
                    }
            } else if (hits.isEmpty()) Text("Nothing found for \"$q\"", color = Mist)
            for (h in hits) Column(
                Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(12.dp)).border(1.dp, NavyLight, RoundedCornerShape(12.dp))
                    .clickable { onOpen(h.target) }.padding(12.dp)
            ) {
                Text(h.title, color = Foam, fontWeight = FontWeight.Bold)
                Text(h.sub, color = Mist, fontSize = 13.sp)
            }
        }
    }
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private fun search(query: String, day: Long, sights: List<WorkbookSight>): List<Hit> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    val out = mutableListOf<Hit>()
    val now = System.currentTimeMillis()
    val nowHour = ((now - (now - Math.floorMod(now, DAY_MS))) / HOUR_MS).toInt()

    // bodies, and the user's own sights of them
    for (b in SkyBody.all.filter { it.name.lowercase().startsWith(q) || (q.length >= 3 && it.name.lowercase().contains(q)) }.take(6)) {
        when (b) {
            is SkyBody.Star -> {
                val e = com.thirdcoastsailing.app.celestial.Almanac.entry(b, day + 12 * HOUR_MS)
                out += Hit(b.name, "Star list · ${dayLong(day)} · SHA ${Workbook.ang(e.sha ?: 0.0)} · Dec ${Workbook.decText(e.dec)}",
                    AlmanacTarget(day, AlmanacSection.STARS, star = b.name))
            }
            SkyBody.Sun, SkyBody.Moon -> out += Hit(b.name, "Sun & Moon page · ${dayLong(day)} · ${"%02d".format(nowHour)}h",
                AlmanacTarget(day, AlmanacSection.SUN_MOON, hour = nowHour))
            else -> out += Hit(b.name, "Aries & planets page · ${dayLong(day)} · ${"%02d".format(nowHour)}h",
                AlmanacTarget(day, AlmanacSection.ARIES_PLANETS, hour = nowHour))
        }
        for (s in sights.filter { it.body.name == b.name && it.shots.isNotEmpty() }) {
            val t = s.shots.first().timeMs
            almanacTarget(s.body, t, Field.GHA_HOUR)?.let { tg ->
                out += Hit("${b.name} · your sight ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))}",
                    "Daily page ${dayLong(tg.dayStartUtc)}, ${"%02d".format(tg.hour ?: 0)}h UT", tg)
            }
        }
    }
    if ("aries".startsWith(q) || q == "♈") out += Hit("Aries", "Aries & planets page · ${dayLong(day)}", AlmanacTarget(day, AlmanacSection.ARIES_PLANETS, hour = nowHour))

    // dates
    parseDate(q)?.let { d -> out += Hit("Daily page · ${dayLong(d)}", "Sun & Moon, Aries & planets, stars", AlmanacTarget(d, AlmanacSection.SUN_MOON, hour = 0)) }

    // a UT time: that hour's row, and the increments for its minutes
    Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(q)?.let { m ->
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); val se = m.groupValues[3].toIntOrNull()
        if (h in 0..23 && mi in 0..59) {
            out += Hit("${"%02d".format(h)}h UT row · ${dayLong(day)}", "Sun & Moon page", AlmanacTarget(day, AlmanacSection.SUN_MOON, hour = h))
            out += Hit("Increments · ${mi}m${se?.let { " ${"%02d".format(it)}s" } ?: ""}", "Yellow pages", AlmanacTarget(day, AlmanacSection.INCREMENTS, minute = mi, second = se))
        }
    }
    // increments page
    Regex("^(\\d{1,2})\\s*m$").find(q)?.let { m ->
        val mi = m.groupValues[1].toInt()
        if (mi in 0..59) out += Hit("Increments · ${mi}m", "Yellow pages", AlmanacTarget(day, AlmanacSection.INCREMENTS, minute = mi))
    }
    if ("increments".startsWith(q)) out += Hit("Increments", "Yellow pages", AlmanacTarget(day, AlmanacSection.INCREMENTS, minute = 0))
    // dip and refraction
    Regex("^dip\\s*(\\d+(?:\\.\\d+)?)?").find(q)?.let { m ->
        val h = m.groupValues[1].toDoubleOrNull()
        out += Hit(if (h != null) "Dip for ${"%.0f".format(h)} ft" else "Dip", "Dip & refraction page", AlmanacTarget(day, AlmanacSection.CORRECTIONS, dipFt = h))
    }
    if ("refraction".startsWith(q)) out += Hit("Refraction", "Dip & refraction page", AlmanacTarget(day, AlmanacSection.CORRECTIONS))
    return out
}

/** "oct 18", "18 oct", "10/18", "2026-10-18" -> 00:00 UT of that day (this year unless given). */
private fun parseDate(q: String): Long? {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    val year = cal.get(Calendar.YEAR)
    fun at(y: Int, m: Int, d: Int): Long? {
        if (m !in 1..12 || d !in 1..31) return null
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis
    }
    Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(q)?.let { return at(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    Regex("^(\\d{1,2})/(\\d{1,2})$").find(q)?.let { return at(year, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    Regex("^([a-z]{3})[a-z]*\\.?\\s+(\\d{1,2})$").find(q)?.let { m ->
        val mo = MONTHS.indexOf(m.groupValues[1]); if (mo >= 0) return at(year, mo + 1, m.groupValues[2].toInt())
    }
    Regex("^(\\d{1,2})\\s+([a-z]{3})[a-z]*$").find(q)?.let { m ->
        val mo = MONTHS.indexOf(m.groupValues[2]); if (mo >= 0) return at(year, mo + 1, m.groupValues[1].toInt())
    }
    return null
}

private fun utcFmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private fun dayLong(ms: Long) = utcFmt("EEE d MMM yyyy").format(Date(ms))
private fun dayShort(ms: Long) = utcFmt("d MMM").format(Date(ms))
private fun bookDate(ms: Long) = utcFmt("yyyy MMMM d (EEE.)").format(Date(ms)).uppercase()
