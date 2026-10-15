package com.thirdcoastsailing.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.ui.theme.Caution
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange

/*
 * About & credits (menu, bottom) and the one-time disclaimer shown on first launch.
 * Texts here are drafts for the owner to review; the README carries the same name-origin note and credits table.
 */

const val SOURCE_URL = "https://github.com/HappyRock616/Third-Coast-Sailing"

/** Where the app's name comes from, and that it isn't affiliated with anyone. */
const val NAME_ORIGIN =
    "Third Coast Sailing is an independent project. " +
        "It is a personal, non-commercial project and isn't affiliated with, endorsed by or connected to any club, " +
        "school, business or organization with a similar name."

/** The not-for-navigation statement, used on first launch and in About. */
const val NOT_FOR_NAVIGATION =
    "Third Coast Sailing is for reference only. It is not for navigation.\n\n" +
        "Charts are converted from NOAA electronic chart data; converted charts are not NOAA products, may be " +
        "incomplete or out of date, and don't meet chart-carriage requirements. Forecasts, buoy reports, routes, " +
        "depths and celestial fixes can be wrong or late.\n\n" +
        "Always use official NOAA charts, Local Notices to Mariners, Coast Guard broadcasts and your own judgement. " +
        "You are responsible for the safe navigation of your boat."

/** Data and software the app uses, with their terms: (what, from whom, terms, link or ""). */
private val CREDITS = listOf(
    listOf("Charts (ENC)", "NOAA Office of Coast Survey", "US Government work, public domain", "https://nauticalcharts.noaa.gov"),
    listOf("Lake bathymetry", "NOAA NCEI", "Public domain", ""),
    listOf("Wind forecasts", "NOAA National Weather Service (NDFD)", "Public domain", ""),
    listOf("Wave forecasts", "NOAA NCEP (GLWU)", "Public domain", ""),
    listOf("Buoy observations", "NOAA National Data Buoy Center", "Public domain", "https://www.ndbc.noaa.gov"),
    listOf("Marinas, clubs, docks, anchorages", "© OpenStreetMap contributors", "Open Database License (ODbL) 1.0", "https://www.openstreetmap.org/copyright"),
    listOf("Map engine", "MapLibre Native", "BSD 2-Clause", "https://maplibre.org"),
    listOf("Almanac calculations", "Astronomy Engine, Don Cross", "MIT", "https://github.com/cosinekitty/astronomy"),
    listOf("Star positions", "Yale Bright Star Catalogue, 5th ed.", "Public domain", ""),
    listOf("Constellation figures and names", "d3-celestial, Olaf Frohn", "BSD 3-Clause", "https://github.com/ofrohn/d3-celestial"),
)

/** Full-screen About & credits. */
@Composable
fun AboutScreen(version: String, onClose: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(NavyDeep).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.tcs_logo), null,
                    Modifier.size(56.dp).clip(CircleShape).border(2.dp, SignalOrange, CircleShape))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Third Coast Sailing", color = Foam, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Version $version", color = Mist, fontSize = 12.sp)
                }
                TextButton(onClick = onClose) { Text("Done", color = SignalOrange, fontSize = 16.sp) }
            }

            Text("Not for navigation · Reference use only", color = NavyDeep, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Caution, RoundedCornerShape(8.dp)).padding(vertical = 6.dp))
            Section("Safety") { Text(NOT_FOR_NAVIGATION, color = Mist, fontSize = 13.sp) }
            Section("The name") { Text(NAME_ORIGIN, color = Mist, fontSize = 13.sp) }
            Section("Free and open source") {
                Text("Free, with no ads, accounts or tracking, and no Google services. The source code is published under " +
                    "the GNU Affero General Public License v3.0.", color = Mist, fontSize = 13.sp)
                Text("Source code ›", color = SignalOrange, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp).clickable { open(SOURCE_URL) })
            }
            Section("Data and credits") {
                for ((what, who, terms, url) in CREDITS) Column(Modifier.padding(vertical = 4.dp)
                    .then(if (url.isNotEmpty()) Modifier.clickable { open(url) } else Modifier)) {
                    Text(what, color = Foam, fontSize = 14.sp)
                    Text("$who · $terms", color = if (url.isNotEmpty()) SignalOrange else Mist, fontSize = 12.sp)
                }
                Text("Harbour data derived from OpenStreetMap is available under the ODbL.", color = Mist, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(12.dp)).border(1.dp, NavyLight, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(title.uppercase(), color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

/** Shown once, on first launch, before anything else; must be accepted. */
@Composable
fun FirstLaunchDisclaimer(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Before you start") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(NOT_FOR_NAVIGATION, fontSize = 14.sp) } },
        confirmButton = { TextButton(onClick = onAccept) { Text("I understand", color = SignalOrange) } },
    )
}
