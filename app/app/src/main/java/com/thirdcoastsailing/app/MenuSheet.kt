package com.thirdcoastsailing.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** GPS state shown in the menu. */
data class GpsInfo(val hasPermission: Boolean, val locationOn: Boolean, val fix: BoatFix?,
                   val sats: Pair<Int, Int>, val avgSogKn: Double?)

/** Weather layer switches in the menu, and what changing them does. */
data class WxSwitches(
    val color: String, val barbs: Boolean, val waves: Boolean, val currents: Boolean, val hasCurrentLakes: Boolean,
    val onColor: (String) -> Unit, val onBarbs: (Boolean) -> Unit, val onWaves: (Boolean) -> Unit, val onCurrents: (Boolean) -> Unit,
)

/** What the menu's buttons open or do. */
class MenuActions(
    val onImport: () -> Unit, val onCharts: () -> Unit, val onLakeCheck: (String, Boolean) -> Unit, val onUpdateLake: (String) -> Unit,
    val onShowLake: (String) -> Unit, val onBoats: () -> Unit, val onCelestial: () -> Unit, val onAbout: () -> Unit,
    val onAnchorOpen: () -> Unit, val onAnchorToggle: (Boolean) -> Unit, val onMob: () -> Unit, val onMobReset: () -> Unit,
    val onBaroOpen: () -> Unit, val onBaroToggle: (Boolean) -> Unit, val onNight: (Boolean) -> Unit,
    val onOffline: (Boolean) -> Unit, val onGauge: (Boolean) -> Unit, val onTrip: (Boolean) -> Unit,
    val onTripReset: () -> Unit, val onGpsPermission: () -> Unit, val onLocationSettings: () -> Unit,
)

/** 42.77330° N  86.20340° W */
fun latLonText(lat: Double, lon: Double) =
    "%.5f° %s  %.5f° %s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

/** Side menu: logo header, MOB, GPS, track, weather, display, key, then anchor, boat, celestial, charts and about. */
@Composable
fun MenuSheet(
    night: Boolean, offline: Boolean, gaugeOn: Boolean, boatName: String, appVersion: String,
    trackEnabled: Boolean, wx: WxSwitches, wxInfo: WxMenuInfo,
    gps: GpsInfo, skyPos: Pair<Double, Double>?, nowMs: Long, actions: MenuActions,
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),   // kept by the caller: the menu reopens where it was
) {
    ModalDrawerSheet(drawerContainerColor = Navy, modifier = Modifier.widthIn(max = 300.dp)) {
        Column(Modifier.verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 14.dp)) {
            // ---- logo and name, in the app's colors
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.tcs_logo), null,
                    Modifier.size(58.dp).clip(CircleShape).border(2.dp, SignalOrange, CircleShape))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("THIRD COAST", fontSize = 21.sp, fontWeight = FontWeight.Black, color = SignalOrange, letterSpacing = 1.5.sp)
                    Text("S A I L I N G", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Foam, letterSpacing = 2.sp)
                }
            }
            Spacer(Modifier.height(14.dp))

            // ---- man overboard: just above the GPS box, the same height as the Boat button
            MobBox(gps.fix, onActivate = actions.onMob, onReset = actions.onMobReset, modifier = Modifier.padding(vertical = 4.dp))
            Spacer(Modifier.height(10.dp))

            MenuHeader("GPS")
            GpsBox(gps, skyPos, nowMs, actions)

            // Trip track: the switch starts/stops recording; the reset arrow (shown once there is a track) clears it when held
            // greyed out outside the Great Lakes: the track can't be shown on a chart there
            // (while recording it can always be switched off, also outside)
            IconSwitchRow(MenuIcon.TRACK, "Track", TripTrack.on, actions.onTrip, enabled = trackEnabled || TripTrack.on) {
                if (TripTrack.points.isNotEmpty() && trackEnabled) HoldToResetArrow(onReset = actions.onTripReset)
            }

            MenuDivider()
            MenuHeader("Weather")
            // Wind and gust color fields are linked: turning one on turns the other off
            // each switch, when on, sits in an orange box with its legend (the legends are not on the chart screen)
            WxMenuGroup(wx.color == "wind", { IconSwitchRow(MenuIcon.WIND, "Wind", wx.color == "wind", { wx.onColor(if (it) "wind" else "none") }) }) { WindLegend() }
            WxMenuGroup(wx.color == "gust", { IconSwitchRow(MenuIcon.GUST, "Gusts", wx.color == "gust", { wx.onColor(if (it) "gust" else "none") }) }) { WindLegend() }
            WxMenuGroup(wx.barbs, { IconSwitchRow(MenuIcon.BARBS, "Barbs", wx.barbs, wx.onBarbs) }) { BarbLegend() }
            WxMenuGroup(wx.waves, { IconSwitchRow(MenuIcon.WAVES, "Waves", wx.waves, wx.onWaves) }) { WaveLegend(wxInfo.waveRun) }
            if (wx.hasCurrentLakes) WxMenuGroup(wx.currents, { IconSwitchRow(MenuIcon.CURRENT, "Currents", wx.currents, wx.onCurrents) }) {   // Great Lakes only
                CurrentLegend(wxInfo)
            }
            BaroMenuRow(onOpen = actions.onBaroOpen, onToggle = actions.onBaroToggle)

            MenuDivider()
            MenuHeader("Display")
            IconSwitchRow(MenuIcon.NIGHT, "Night", night, actions.onNight)
            IconSwitchRow(MenuIcon.GAUGE, "Gauge", gaugeOn, actions.onGauge)
            IconSwitchRow(MenuIcon.OFFLINE, "Offline", offline, actions.onOffline)

            MenuDivider()
            MenuHeader("Key")
            ChartKey()

            MenuDivider()
            AnchorMenuRow(onOpen = actions.onAnchorOpen, onToggle = actions.onAnchorToggle)
            MenuButton(MenuIcon.BOAT, "Boat", boatName, onClick = actions.onBoats)
            MenuButton(MenuIcon.SEXTANT, "Celestial", onClick = actions.onCelestial)
            MenuButton(MenuIcon.CHARTS, "Charts", if (ChartManager.updates.isNotEmpty()) "update" else null, onClick = actions.onCharts)
            MenuButton(MenuIcon.INFO, "About", "v$appVersion", onClick = actions.onAbout)
        }
    }
}

/** The GPS box: permission and Location switch first, then the fix, course and speed, satellites, sun and moon. */
@Composable
private fun GpsBox(gps: GpsInfo, skyPos: Pair<Double, Double>?, nowMs: Long, actions: MenuActions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NavyLight).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val f = gps.fix
        when {
            !gps.hasPermission -> {
                Text("Location permission needed", color = Foam, fontSize = 15.sp)
                TextButton(onClick = actions.onGpsPermission) { Text("Allow", color = SignalOrange) }
            }
            !gps.locationOn -> {
                Text("Phone Location is off", color = Foam, fontSize = 15.sp)
                TextButton(onClick = actions.onLocationSettings) { Text("Turn on", color = SignalOrange) }
            }
            f == null -> {
                Text("Searching for a fix…", color = Foam, fontSize = 15.sp)
                SatsSunMoonRow("Sats ${gps.sats.first}/${gps.sats.second}", skyPos, nowMs)
            }
            else -> {
                Text(latLonText(f.lat, f.lon), color = Foam, fontSize = 16.sp)
                Text("COG %s  SOG %s  avg %s".format(
                    f.cogDeg?.let { "%03.0f°T".format(it) } ?: "—",
                    f.sogKn?.let { "%.1f kn".format(it) } ?: "—",
                    gps.avgSogKn?.let { "%.1f kn".format(it) } ?: "—"), color = Mist, fontSize = 12.5.sp)
                val age = (f.ageMs() / 1000).coerceAtLeast(0)
                SatsSunMoonRow("Sats ${gps.sats.first}/${gps.sats.second} · ${age} s", f.lat to f.lon, nowMs)
            }
        }
    }
}

/** Charts: download, update and remove regions (its own screen, to keep the menu short). */
@Composable
fun ChartsScreen(actions: MenuActions, onClose: () -> Unit) {
    val rows = ChartManager.rows()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Navy).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuIconView(MenuIcon.CHARTS, Modifier.size(28.dp))
                Text("Charts", color = Foam, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).weight(1f))
                TextButton(onClick = onClose) { Text("Done ✓", color = SignalOrange, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(10.dp))
            // Disclaimer banner, always shown with the chart downloads
            Text(
                "Not for navigation · Reference use only",
                color = NavyDeep, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(Caution).padding(vertical = 6.dp, horizontal = 10.dp),
            )
            // Checked = on this phone. Tick to download, untick to remove.
            if (rows.isEmpty()) Text("Tap Check for updates to see the lakes and coastal regions", color = Mist, fontSize = 13.sp)
            for (r in rows) LakeRowView(r, actions, onClose)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { ChartManager.checkCharts() }, enabled = !ChartManager.checking) {
                    Text(if (ChartManager.checking) "Checking…" else "Check for updates", color = SignalOrange, fontSize = 13.sp)
                }
            }
            if (ChartManager.catalogMsg.isNotEmpty()) Text(ChartManager.catalogMsg, color = Mist, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = actions.onImport) { Text("Import from a folder…", color = Mist, fontSize = 12.sp) }
        }
    }
}

/** One region in the Charts screen: tick box, name and status (or download progress), Update and Show buttons. */
@Composable
private fun LakeRowView(r: LakeRow, actions: MenuActions, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = r.installed || r.progress != null, enabled = r.progress == null,
            onCheckedChange = { actions.onLakeCheck(r.id, it) })
        Column(Modifier.weight(1f)) {
            Text(r.name, color = Foam, fontSize = 15.sp)
            val size = r.sizeBytes?.let { mbText(it) }
            val p = r.progress
            val sub = when {
                p != null && p <= 0f -> "Waiting…"                       // queued (or just starting)
                p != null -> "Downloading… ${(p * 100).toInt()}%"
                r.installed && r.update -> "Charts ${r.chartsAsOf} · update available (${size ?: "?"})"
                r.installed -> "Charts ${r.chartsAsOf.ifEmpty { "?" }}"
                else -> size ?: ""
            }
            Text(sub, color = if (r.update) SignalOrange else Mist, fontSize = 11.5.sp)
            if (p != null) LinearProgressIndicator(progress = { p },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp), color = SignalOrange, trackColor = NavyLight)
        }
        if (r.update && r.progress == null)
            TextButton(onClick = { actions.onUpdateLake(r.id) }) { Text("Update", color = SignalOrange, fontSize = 12.sp) }
        if (r.installed && r.progress == null)
            TextButton(onClick = { onClose(); actions.onShowLake(r.id) }) { Text("Show", color = SignalOrange, fontSize = 12.sp) }
    }
}

/** An orange section heading in the menu. */
@Composable
private fun MenuHeader(text: String) =
    Text(text.uppercase(), color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp))

/** The GPS box's last line: satellites on the left, today's sunrise, sunset and the moon on the right. */
@Composable
private fun SatsSunMoonRow(sats: String, pos: Pair<Double, Double>?, nowMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(sats, color = Mist, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (pos != null) SunMoonRow(pos.first, pos.second, nowMs)
    }
}

/** A thin divider between menu sections. */
@Composable
private fun MenuDivider() = HorizontalDivider(color = NavyLight, modifier = Modifier.padding(vertical = 14.dp))
