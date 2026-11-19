package com.thirdcoastsailing.app

import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.travel
import com.thirdcoastsailing.app.ui.theme.Alarm
import com.thirdcoastsailing.app.ui.theme.Caution
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.hypot
import kotlin.math.roundToInt

/* Anchor watch on screen: the chart overlay, the yellow box, the menu row and the setup screen (see AnchorWatch.kt). */

/** Anchor symbol, bold red alarm circle and the dashed swing trace over the chart. */
@Composable
fun AnchorOverlay(map: MapLibreMap?, tick: Int, night: Boolean) {
    val s = AnchorWatch.state
    Canvas(Modifier.fillMaxSize()) {
        tick                                                             // read so camera moves redraw
        val m = map ?: return@Canvas
        val a = s.anchor ?: return@Canvas
        fun xy(lat: Double, lon: Double) = m.projection.toScreenLocation(LatLng(lat, lon)).let { Offset(it.x, it.y) }
        val c = xy(a.lat, a.lon)
        // swing trace: dashed line through the watch's GPS fixes
        if (s.trace.size > 1) {
            val path = Path()
            s.trace.forEachIndexed { i, p -> val o = xy(p.lat, p.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, if (night) Foam else Navy, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        }
        // alarm radius: bold red circle
        val edge = travel(LatLon(a.lat, a.lon), 0.0, s.radiusFt / FT_PER_NM).let { xy(it.lat, it.lon) }
        val r = hypot(edge.x - c.x, edge.y - c.y)
        drawCircle(Alarm, r, c, style = Stroke(3.5.dp.toPx()))
        // the anchor
        drawAnchorSymbol(c, 13.dp.toPx())
    }
}

/** Orange anchor on a navy disc, centered at `c`, radius `r`. */
private fun DrawScope.drawAnchorSymbol(c: Offset, r: Float) {
    drawCircle(Navy, r, c); drawCircle(SignalOrange, r, c, style = Stroke(r * 0.14f))
    val k = r / 13f; val w = 2.4f * k
    drawCircle(SignalOrange, 2.2f * k, c + Offset(0f, -7f * k), style = Stroke(1.8f * k))
    drawLine(SignalOrange, c + Offset(0f, -4.8f * k), c + Offset(0f, 8f * k), w, StrokeCap.Round)
    drawLine(SignalOrange, c + Offset(-4.5f * k, -2f * k), c + Offset(4.5f * k, -2f * k), w, StrokeCap.Round)
    drawArc(SignalOrange, 20f, 140f, false, c + Offset(-7.5f * k, -2.5f * k),
        androidx.compose.ui.geometry.Size(15f * k, 11f * k), style = Stroke(w, cap = StrokeCap.Round))
}

/**
 * Large yellow box at the top while the watch is on: distance and true bearing to the anchor, or "GPS fix lost".
 * In an alarm the border turns red and it reads "Alarm Silence" (tap it, or press a volume button).
 */
@Composable
fun AnchorBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = AnchorWatch.state
    if (!s.armed) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.snoozedUntilMs) { while (now < s.snoozedUntilMs) { delay(15_000); now = System.currentTimeMillis() } }
    val snoozed = s.snoozedUntilMs > now
    val red = s.alarming || snoozed
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Caution, shape)
            .border(if (red) 5.dp else 2.dp, if (red) Alarm else NavyDeep, shape)
            .clickable(enabled = s.alarming) { AnchorService.silence(context) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s.alarming) Text("Alarm Silence", color = NavyDeep, fontSize = 26.sp, fontWeight = FontWeight.Black)
        val d = s.toAnchor()
        when {
            s.fixLost -> Text("GPS fix lost", color = NavyDeep, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            d == null -> Text("Waiting for GPS", color = NavyDeep, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            else -> Text("⚓ %d ft · %03.0f°T".format(d.first.roundToInt(), d.second), color = NavyDeep,
                fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            if (snoozed && !s.alarming) "Silenced · %d min · radius %d ft".format(((s.snoozedUntilMs - now) / 60_000 + 1).toInt(), s.radiusFt.roundToInt())
            else "radius %d ft".format(s.radiusFt.roundToInt()),
            color = NavyDeep, fontSize = 13.sp,
        )
    }
}

/** Menu row: Anchor button (opens setup), hold-to-reset while an anchor is saved, and the watch on/off switch. */
@Composable
fun AnchorMenuRow(onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val s = AnchorWatch.state
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { MenuButton(MenuIcon.ANCHOR, "Anchor", onClick = onOpen) }
        if (s.anchor != null) HoldToResetArrow(onReset = { AnchorWatch.reset(context) }, boxSize = 40.dp)
        Switch(checked = s.armed, onCheckedChange = onToggle, modifier = Modifier.padding(start = 4.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = SignalOrange, checkedThumbColor = Foam,
                uncheckedTrackColor = NavyLight, uncheckedThumbColor = Mist, uncheckedBorderColor = NavyLight))
    }
}

/**
 * Anchor setup: alarm radius, and where the anchor is. "Drop here" marks it at the boat (0 ft, from the bow as it
 * goes down). Or point the top of the phone at the anchor, type the distance, and "Set ahead" uses the compass.
 * Below: any phone setting that could stop or delay the watch, with a button to fix it.
 */
@Composable
fun AnchorScreen(fix: BoatFix?, onClose: () -> Unit) {
    val context = LocalContext.current
    val s = AnchorWatch.state
    var radiusText by remember { mutableStateOf(s.radiusFt.roundToInt().toString()) }
    var offsetText by remember { mutableStateOf(if (s.offsetFt > 0) s.offsetFt.roundToInt().toString() else "") }
    val heading = rememberPhoneHeading(fix)
    val freshFix = fix?.takeIf { it.fresh }
    // the typed radius is applied on leaving (kept in range; a value below the minimum or an empty box changes nothing)
    val typed by rememberUpdatedState(radiusText)
    DisposableEffect(Unit) {
        onDispose { AnchorRules.typedRadius(typed)?.let { if (it != AnchorWatch.state.radiusFt) AnchorWatch.setRadius(context, it) } }
    }
    fun setRadius(v: Double) { radiusText = v.roundToInt().toString(); AnchorWatch.setRadius(context, v) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Navy).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuIconView(MenuIcon.ANCHOR, Modifier.size(30.dp))
                Text("Anchor", color = Foam, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).weight(1f))
                TextButton(onClick = onClose) { Text("Done ✓", color = SignalOrange, fontWeight = FontWeight.Bold) }
            }

            // ---- radius
            Text("ALARM RADIUS", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { setRadius(((radiusText.toDoubleOrNull() ?: s.radiusFt) - 25).coerceAtLeast(25.0)) }) {
                    Text("−25", color = SignalOrange, fontSize = 18.sp)
                }
                // typed values are applied when the screen closes (Done), not key by key: "25" on the way to
                // "250" must not become the radius of a watch that is already on
                OutlinedTextField(radiusText, { t -> radiusText = t.filter { it.isDigit() }.take(4) },
                    singleLine = true, suffix = { Text("ft", color = Mist) }, modifier = Modifier.width(120.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Foam, fontSize = 20.sp))
                OutlinedButton(onClick = { setRadius((radiusText.toDoubleOrNull() ?: s.radiusFt) + 25) }) {
                    Text("+25", color = SignalOrange, fontSize = 18.sp)
                }
            }

            // ---- where the anchor is (needs a fresh, precise fix)
            Text("ANCHOR POINT", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(when {
                !hasFineLocation(context) -> "Needs precise location: allow it for this app in Settings"
                fix == null -> "Waiting for GPS…"
                freshFix == null -> "Waiting for a fresh GPS fix (last one ${fix.ageMs() / 1000} s old)…"
                else -> latLonLine(fix.lat, fix.lon)
            }, color = Mist, fontSize = 13.sp)
            Button(onClick = { freshFix?.let { if (AnchorWatch.drop(context, it, 0.0, 0.0)) onClose() } }, enabled = freshFix != null,
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy),
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("⚓  Drop here  (0 ft)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Point phone top at anchor", color = Foam, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(heading?.let { "%03.0f°T".format(it) } ?: "—", color = Foam, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(offsetText, { t -> offsetText = t.filter { it.isDigit() }.take(4) }, singleLine = true,
                    label = { Text("Distance", color = Mist) }, suffix = { Text("ft", color = Mist) }, modifier = Modifier.width(150.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Foam, fontSize = 20.sp))
                val off = offsetText.toDoubleOrNull() ?: 0.0
                OutlinedButton(onClick = { val f = freshFix; val h = heading; if (f != null && h != null && AnchorWatch.drop(context, f, off, h)) onClose() },
                    enabled = freshFix != null && heading != null && off > 0, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Set ahead", color = SignalOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (s.anchor != null) Text("Anchor set %s · hold ↺ in the menu to clear it".format(
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(s.setMs))), color = Mist, fontSize = 12.sp)

            WatchSettingsWarnings()
        }
    }
}

/**
 * Phone settings that can stop or delay the anchor watch, each with a button to the right settings page:
 * notifications off (the status and Silence button can't show), battery optimisation (the phone may stop the watch
 * overnight), and "Alarms & reminders" (without it the lost-fix check can run up to about 10 minutes late).
 * Checked again each time the screen is shown.
 */
@Composable
private fun WatchSettingsWarnings() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    refresh                                                          // read so a return from Settings re-checks
    val items = buildList {
        if (!notificationsEnabled(context)) add("Notifications are off: the watch status and Silence button won't show." to
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        if (batteryOptimized(context)) add("Battery optimisation may stop the watch overnight. Set this app to “Unrestricted”." to
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        if (!canScheduleExact(context)) add("Allow “Alarms & reminders” so a lost GPS fix is reported on time." to
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }
    if (items.isEmpty()) return
    Text("PHONE SETTINGS", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    for ((text, intent) in items) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Foam, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { runCatching { context.startActivity(intent) } }) { Text("Fix", color = SignalOrange) }
    }
}

/** "43.12345° N  86.12345° W". */
private fun latLonLine(lat: Double, lon: Double) =
    "%.5f° %s  %.5f° %s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

/** True heading (°) of the top of the phone from the compass (magnetic variation applied), or null without one. */
@Composable
private fun rememberPhoneHeading(fix: BoatFix?): Double? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Double?>(null) }
    val decl = remember(fix == null) {
        fix?.let { GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() } ?: 0.0
    }
    DisposableEffect(decl) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)                  // ori[0]: azimuth of the phone's top edge
                val h = (Math.toDegrees(ori[0].toDouble()) + decl + 360.0) % 360.0
                val prev = heading
                // light smoothing across north
                heading = if (prev == null) h else (prev + (((h - prev + 540.0) % 360.0) - 180.0) * 0.2 + 360.0) % 360.0
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return heading
}
