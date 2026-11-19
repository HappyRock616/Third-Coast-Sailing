package com.thirdcoastsailing.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** What the info card shows after tapping a harbor, buoy or navigation aid on the chart. */
data class Info(val title: String, val lines: List<String>, val phone: String = "", val website: String = "",
                val station: String? = null,          // set for buoys: live readings and graph are added
                val at: LatLng? = null,               // position of the tapped marker (blue ring while open)
                val nav: NavInfo? = null)             // set for lights, buoys, beacons, landmarks

/** Read a feature property as text ("" when missing). */
private fun Feature.text(key: String): String =
    runCatching { getProperty(key)?.takeIf { !it.isJsonNull }?.asString ?: "" }.getOrDefault("").trim()

/** Position of a point feature, or null. */
private fun Feature.position(): LatLng? = (geometry() as? Point)?.let { LatLng(it.latitude(), it.longitude()) }

/** Readable name of a harbor kind from the chart data ("yacht_club" → "Yacht club"). */
private fun kindLabel(k: String) = when (k.lowercase()) {
    "marina" -> "Marina"; "yacht_club", "club" -> "Yacht club"; "public_dock" -> "Public dock"
    "fuel_dock" -> "Fuel dock"; "mooring_field" -> "Mooring field"; "anchorage" -> "Anchorage"
    "harbor_of_refuge" -> "Harbor of refuge"; "dock" -> "Dock"
    else -> k.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Turn a tapped map feature into card content. */
fun toInfo(f: Feature, layerId: String): Info =
    if (NavAids.isNavLayer(layerId)) {
        val (title, nav) = NavAids.card(f, layerId)
        Info(title = title, lines = emptyList(), at = f.position(), nav = nav)
    } else if (layerId == BUOY_LAYER) {
        val id = f.text("station")
        Info(
            title = f.text("name").ifEmpty { "Station $id" },
            lines = listOfNotNull(f.text("owner").takeIf { it.isNotEmpty() }),
            station = id,
            at = f.position(),
        )
    } else {
        val vhf = f.text("vhf")
        Info(
            title = f.text("name").ifEmpty { "(unnamed)" },
            lines = listOfNotNull(kindLabel(f.text("kind")),
                if (vhf.isNotEmpty()) "VHF $vhf" else "VHF: not listed",
                if (f.text("phone").isEmpty()) "Phone: not listed" else null),
            phone = f.text("phone"), website = f.text("website"), at = f.position()
        )
    }

/** Bottom-right window for a tapped harbor or buoy; phone and website are tappable. ✕ closes it. */
@Composable
fun InfoCard(info: Info, modifier: Modifier, buoyObs: Map<String, BuoyObs>, buoyRepo: BuoyRepo,
             offline: Boolean, nowMs: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    // Buoy: last 24 h for the graph (downloaded on tap unless offline mode; else the saved copy)
    var history by remember(info.station) { mutableStateOf<List<BuoyObs>?>(null) }
    var historyLoaded by remember(info.station) { mutableStateOf(false) }
    LaunchedEffect(info.station) {
        val st = info.station ?: return@LaunchedEffect
        history = withContext(Dispatchers.IO) { runCatching { buoyRepo.history(st, network = !offline && online(context)) }.getOrNull() }
        historyLoaded = true
    }
    Box(
        modifier.safeDrawingPadding().padding(12.dp).widthIn(min = 260.dp, max = 340.dp)
            .shadow(8.dp, RoundedCornerShape(14.dp)).background(Navy, RoundedCornerShape(14.dp))
            .border(1.dp, SignalOrange, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Foam,
                modifier = Modifier.padding(end = 36.dp))          // room for the close icon
            info.nav?.let { NavCardBody(it) }
            for (l in info.lines) Text(l, color = Mist, fontSize = 14.sp)
            info.station?.let { st ->
                for (l in buoyLines(st, buoyObs[st], nowMs)) Text(l, color = Mist, fontSize = 14.sp)
                val h = history
                when {
                    h != null -> BuoyGraph(h, nowMs)
                    !historyLoaded -> Text("Loading 24 h graph…", color = Mist, fontSize = 12.sp)
                    offline -> Text("Graph: none saved (offline mode)", color = Mist, fontSize = 12.sp)
                    else -> Text("Graph: no data from NOAA", color = Mist, fontSize = 12.sp)
                }
            }
            if (info.phone.isNotBlank() || info.website.isNotBlank()) Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)
            ) {
                if (info.phone.isNotBlank()) Button(onClick = {
                    // no phone app on some tablets: do nothing rather than crash
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${info.phone.substringBefore(" x")}"))) }
                }) { Text("Call ${info.phone}", fontSize = 13.sp) }
                if (info.website.isNotBlank()) OutlinedButton(onClick = {
                    // only web links: anything that isn't http:// or https:// is treated as a bare address
                    val w = info.website.trim()
                    val url = if (w.startsWith("https://", ignoreCase = true) || w.startsWith("http://", ignoreCase = true)) w else "https://$w"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text("Website", color = SignalOrange, fontSize = 13.sp) }
            }
        }
        // Close icon in the top-right corner
        Text(
            "✕", color = SignalOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).clickable(onClick = onClose)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
