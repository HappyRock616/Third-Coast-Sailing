package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.celestial.BodyKind
import com.thirdcoastsailing.app.celestial.SextantProcedure
import com.thirdcoastsailing.app.celestial.SextantStep
import com.thirdcoastsailing.app.celestial.SkyBody
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.launch

/*
 * Sextant guide: one step per screen (big picture, large text), for the body being shot.
 *  - Swipe or Back / Next; the dots and the "Steps ▾" list jump straight to any step.
 *  - The step you were on is remembered for 15 minutes (and forgotten when the app is closed), so switching between
 *    the workbook and the guide comes back to the same step.
 *  - ✕ or Back returns to where you came from. Times are marked in the workbook, like on paper.
 */

/** The last guide step, kept in memory only (a new app session starts fresh). */
object GuideMemory {
    private const val KEEP_MS = 15 * 60_000L
    private var kind: BodyKind? = null
    private var step = 0
    private var atMs = 0L

    /** The step last shown for this kind of body, if it was within KEEP_MS (so reopening the guide continues where it was). */
    fun recall(k: BodyKind): Int? =
        if (kind == k && System.currentTimeMillis() - atMs < KEEP_MS) step else null

    /** Remember the step being shown for this kind of body. */
    fun save(k: BodyKind, s: Int) { kind = k; step = s; atMs = System.currentTimeMillis() }
}

/**
 * Step-by-step sextant guide for one sight, one page per step, with a jump list.
 * @param includeIndexError include the index-error step (beginner level)
 * @param note optional line shown above the steps (e.g. the suggested preset altitude)
 */
@SuppressLint("DiscouragedApi")
@Composable
fun SightGuide(body: SkyBody, includeIndexError: Boolean, note: String?, onClose: () -> Unit) {
    val steps = remember(body, includeIndexError) { SextantProcedure.steps(body.kind, includeIndexError) }
    val start = remember(body) { (GuideMemory.recall(body.kind) ?: 0).coerceIn(0, steps.size - 1) }
    val pager = rememberPagerState(initialPage = start) { steps.size }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { GuideMemory.save(body.kind, pager.currentPage) }
    BackHandler(onBack = onClose)
    /** Animate to step i (kept within range). */
    fun go(i: Int) { scope.launch { pager.animateScrollToPage(i.coerceIn(0, steps.size - 1)) } }

    Column(Modifier.fillMaxSize().background(NavyDeep).safeDrawingPadding()) {
        // ---- top: close, which sight, which step, jump list
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Navy).border(2.dp, SignalOrange, CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = SignalOrange, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("${body.name} sight", color = Foam, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Step ${pager.currentPage + 1} of ${steps.size}" + (note?.let { " · $it" } ?: ""), color = Mist, fontSize = 13.sp)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("Steps ▾", color = SignalOrange) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for ((i, st) in steps.withIndex()) DropdownMenuItem(
                        text = { Text("${i + 1}. ${st.title}", fontWeight = if (i == pager.currentPage) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { menu = false; go(i) })
                }
            }
        }

        // ---- the step
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i -> StepPage(steps[i]) }

        // ---- dots (tap to jump), Back / Next
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            for (i in steps.indices) Box(
                Modifier.padding(5.dp).size(if (i == pager.currentPage) 14.dp else 11.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) SignalOrange else if (i < pager.currentPage) Mist else NavyLight)
                    .clickable { go(i) }
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { go(pager.currentPage - 1) }, enabled = pager.currentPage > 0) { Text("◀ Back", color = SignalOrange) }
            Spacer(Modifier.weight(1f))
            val last = pager.currentPage == steps.size - 1
            Button(
                onClick = { if (last) onClose() else go(pager.currentPage + 1) },
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy),
            ) { Text(if (last) "Done ✓" else "Next ▶", fontWeight = FontWeight.Bold) }
        }
    }
}

/** One guide page: picture(s), title and text. */
@SuppressLint("DiscouragedApi")
@Composable
private fun StepPage(step: SextantStep) {
    val context = LocalContext.current
    /** Drawable resource id from its name (0 if missing). */
    fun res(name: String) = context.resources.getIdentifier(name, "drawable", context.packageName)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        val pics = listOfNotNull(step.graphic, step.graphic2).map { res(it) }.filter { it != 0 }
        if (pics.size == 1) Image(painterResource(pics[0]), contentDescription = step.title,
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f))
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (id in pics) Image(painterResource(id), contentDescription = step.title, modifier = Modifier.weight(1f).aspectRatio(1f))
        }
        if (pics.size > 1) Row {
            Text("the whole reading", color = Mist, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("close-up: drum and vernier", color = Mist, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        Text(step.title, color = Foam, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        for (para in step.text.split("\n\n")) Text(para, color = Mist, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(bottom = 10.dp))
        Spacer(Modifier.height(12.dp))
    }
}
