package com.thirdcoastsailing.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.NavyLine
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlin.math.cos
import kotlin.math.sin

private const val CRUISE_FACTOR = 0.85      // cruise mode uses 85% of the active polar

/** Full-screen Boat & Polars editor. */
@Composable
fun BoatScreen(store: BoatStore, onClose: () -> Unit) {
    var state by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(state.activeId ?: state.boats.first().id) }
    val boat = state.boats.firstOrNull { it.id == selectedId } ?: state.boats.first()
    var confirmDelete by remember { mutableStateOf(false) }
    var showTable by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun update(newState: BoatStore.State) { state = newState; store.save(newState) }
    fun edit(b: Boat) = update(state.copy(boats = state.boats.map { if (it.id == b.id) b else it }))

    val polar = remember(boat) { store.polar(boat) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(polar.toPol().toByteArray()) }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Boat & Polars", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done", color = SignalOrange, fontSize = 16.sp) }
            }

            // ---- boats
            Section("Boats") {
                for (b in state.boats) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = b.id == selectedId, onClick = { selectedId = b.id })
                        Text(b.name, modifier = Modifier.weight(1f))
                        if (b.id == state.activeId) Text("ACTIVE", color = SignalOrange, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val nb = Boat(name = "Boat ${state.boats.size + 1}")
                        update(state.copy(boats = state.boats + nb)); selectedId = nb.id
                    }) { Text("+ New boat") }
                    if (boat.id != state.activeId) Button(onClick = { update(state.copy(activeId = boat.id)) }) {
                        Text("Use this boat")
                    }
                }
            }

            // ---- selected boat settings
            Section("Boat settings") {
                NumberOrText("Name", boat.name, boat.id, numeric = false) { edit(boat.copy(name = it.ifBlank { "Boat" })) }
                NumberOrText("Draft (ft)", "%.1f".format(boat.draftFt), boat.id) { s ->
                    s.toDoubleOrNull()?.let { edit(boat.copy(draftFt = it)) }
                }
                MinDepthPicker(boat.minDepthFt) { edit(boat.copy(minDepthFt = it)) }
                Text("Starting template (by length)", color = Mist, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in PolarTemplates.names) FilterChip(
                        selected = boat.template == t, onClick = { edit(boat.copy(template = t)) },
                        label = { Text(t.replace("ft", " ft")) })
                }
                ToggleRow("Symmetric spinnaker", boat.symSpinnaker) { edit(boat.copy(symSpinnaker = it)) }
                ToggleRow("Asymmetric spinnaker", boat.asymSpinnaker) { edit(boat.copy(asymSpinnaker = it)) }
            }

            // ---- polar view
            Section("Polar") {
                PolarPlot(polar, boat.manual, Modifier.fillMaxWidth().aspectRatio(1.1f))
                Text("Curves: true wind 6 / 10 / 14 / 20 kn. Orange dots: your measurements. " +
                        "Cruise mode uses ${(CRUISE_FACTOR * 100).toInt()}% of these speeds.", color = Mist, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showTable = !showTable }) { Text(if (showTable) "Hide table" else "Show table") }
                    OutlinedButton(onClick = { export.launch("${boat.name}.pol") }) { Text("Export .pol") }
                }
                if (showTable) PolarTable(polar)
            }

            // ---- manual measurements
            Section("Your measurements (apparent wind)") {
                Text("Enter what your instruments show; the polar is refit around each entry.",
                    color = Mist, fontSize = 12.sp)
                var aws by remember(boat.id) { mutableStateOf("") }
                var awa by remember(boat.id) { mutableStateOf("") }
                var bsp by remember(boat.id) { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallField("AWS kn", aws, Modifier.weight(1f)) { aws = it }
                    SmallField("AWA °", awa, Modifier.weight(1f)) { awa = it }
                    SmallField("Boat kn", bsp, Modifier.weight(1f)) { bsp = it }
                }
                val ok = aws.toDoubleOrNull() != null && awa.toDoubleOrNull() != null && bsp.toDoubleOrNull() != null
                Button(enabled = ok, onClick = {
                    edit(boat.copy(manual = boat.manual + ManualPoint(aws.toDouble(), awa.toDouble(), bsp.toDouble())))
                    aws = ""; awa = ""; bsp = ""
                }) { Text("Add measurement") }
                for ((i, m) in boat.manual.withIndex()) {
                    val (tws, twa) = m.toTrue()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AWS %.1f  AWA %.0f°  →  %.1f kn   (true %.1f kn @ %.0f°)".format(
                            m.awsKn, m.awaDeg, m.bspKn, tws, twa), fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { edit(boat.copy(manual = boat.manual.filterIndexed { j, _ -> j != i })) }) {
                            Text("Remove", color = SignalOrange)
                        }
                    }
                }
            }

            if (state.boats.size > 1) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete this boat") }
            Spacer(Modifier.height(24.dp))
        }

        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${boat.name}?") },
            text = { Text("Its settings and measurements will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    val rest = state.boats.filter { it.id != boat.id }
                    update(state.copy(boats = rest, activeId = if (state.activeId == boat.id) rest.first().id else state.activeId))
                    selectedId = rest.first().id; confirmDelete = false
                }) { Text("Delete", color = SignalOrange) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(NavyLight, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title.uppercase(), color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Dropdown for the boat's minimum depth: shallower water is shaded red and avoided by the router. */
@Composable
private fun MinDepthPicker(valueFt: Double, onPick: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Minimum depth (shaded red, avoided by routing)", color = Mist, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { open = true }) { Text("%.0f ft  ▾".format(valueFt)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (ft in MIN_DEPTH_CHOICES_FT) DropdownMenuItem(
                    text = { Text("%.0f ft".format(ft)) },
                    onClick = { open = false; onPick(ft) })
            }
        }
    }
}

/** Text field that commits on every change (numeric keyboard unless numeric = false). */
@Composable
private fun NumberOrText(label: String, value: String, key: Any, numeric: Boolean = true, onCommit: (String) -> Unit) {
    var text by remember(label, key) { mutableStateOf(value) }      // reset when another boat is selected
    OutlinedTextField(
        value = text, onValueChange = { text = it; onCommit(it) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SmallField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier
    )
}

/** Half-polar plot: 0° (head to wind) at top, 180° at bottom, starboard side. */
@Composable
private fun PolarPlot(polar: Polar, manual: List<ManualPoint>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val curves = listOf(6.0 to Color(0xFF8FBCE0), 10.0 to Color(0xFF5FA3D8), 14.0 to Color(0xFF3C82C0), 20.0 to Foam)
    val maxSpeed = (polar.speed.flatten().maxOrNull() ?: 8.0).let { kotlin.math.ceil(it / 2) * 2 }
    Canvas(modifier.background(NavyDeep, RoundedCornerShape(8.dp)).border(1.dp, NavyLine, RoundedCornerShape(8.dp))) {
        val cx = size.width * 0.12f
        val cy = size.height / 2
        val r = minOf(size.width * 0.82f, size.height * 0.46f)
        fun pt(twa: Double, spd: Double): Offset {
            val a = Math.toRadians(twa)
            val k = (spd / maxSpeed).toFloat() * r
            return Offset(cx + k * sin(a).toFloat(), cy - k * cos(a).toFloat())
        }
        // speed rings every 2 kn and angle spokes every 30°
        var s = 2.0
        while (s <= maxSpeed) {
            val k = (s / maxSpeed).toFloat() * r
            drawArc(NavyLine, -90f, 180f, false, Offset(cx - k, cy - k), Size(2 * k, 2 * k), style = Stroke(1f))
            drawText(measurer, "${s.toInt()}", Offset(cx + 2f, cy - k - 14f),
                TextStyle(color = Mist, fontSize = 9.sp))
            s += 2.0
        }
        for (a in 0..180 step 30) {
            drawLine(NavyLine, Offset(cx, cy), pt(a.toDouble(), maxSpeed), 1f)
            drawText(measurer, "$a°", pt(a.toDouble(), maxSpeed * 1.04) - Offset(10f, 8f),
                TextStyle(color = Mist, fontSize = 9.sp))
        }
        // one curve per wind speed
        for ((tws, color) in curves) {
            val path = Path()
            for (a in 30..180 step 2) {
                val p = pt(a.toDouble(), polar.boatSpeed(tws, a.toDouble()))
                if (a == 30) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, color, style = Stroke(3f))
        }
        // owner's measurements (converted to true wind)
        for (m in manual) {
            val (_, twa) = m.toTrue()
            drawCircle(SignalOrange, 7f, pt(twa, m.bspKn))
        }
    }
}

/** Full grid as numbers: rows = true wind angle, columns = true wind speed. */
@Composable
private fun PolarTable(polar: Polar) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Foam)
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Text("TWA\\TWS", style = mono, color = SignalOrange, modifier = Modifier.width(72.dp))
            for (w in polar.tws) Text("%5.0f".format(w), style = mono, color = SignalOrange, modifier = Modifier.widthIn(48.dp))
        }
        HorizontalDivider(color = NavyLine)
        polar.twa.forEachIndexed { i, a ->
            if (a == 0.0) return@forEachIndexed
            Row {
                Text("%5.0f°".format(a), style = mono, modifier = Modifier.width(72.dp))
                for (v in polar.speed[i]) Text("%5.1f".format(v), style = mono, modifier = Modifier.widthIn(48.dp))
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
