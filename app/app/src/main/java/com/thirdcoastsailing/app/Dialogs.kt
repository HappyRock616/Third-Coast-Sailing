package com.thirdcoastsailing.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.SignalOrange

/* The chart dialogs: first-launch region picker, mobile-data warning, unverified import, and remove charts. */

/** First-launch pop-up: pick which regions to download, with each one's download size. Lake Michigan is ticked to start. */
@Composable
fun SetupDialog(catalog: List<CatalogLake>, loading: Boolean, error: String, onRetry: () -> Unit,
                onDownload: (List<CatalogLake>) -> Unit, onLater: () -> Unit) {
    var picked by remember { mutableStateOf(setOf("michigan")) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Choose your waters") },
        text = {
            Column {
                Text("Charts are stored on the phone so they work with no signal. You can add or remove lakes and coastal regions later in the menu.",
                    color = Mist, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                when {
                    catalog.isEmpty() && loading -> Text("Loading the chart list…", color = Foam)
                    catalog.isEmpty() -> {
                        Text(error.ifEmpty { "Couldn't load the chart list." }, color = Foam)
                        TextButton(onClick = onRetry) { Text("Try again", color = SignalOrange) }
                    }
                    else -> for (l in catalog) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { picked = if (l.id in picked) picked - l.id else picked + l.id }) {
                            Checkbox(checked = l.id in picked, onCheckedChange = { picked = if (it) picked + l.id else picked - l.id })
                            Text(l.name, color = Foam, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(mbText(l.size), color = Mist, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val chosen = catalog.filter { it.id in picked }
            TextButton(onClick = { onDownload(chosen) }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.isEmpty()) "Download" else "Download (${mbText(chosen.sumOf { it.size })})", color = SignalOrange)
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

/** Mobile data: confirm before a big download. */
@Composable
fun MeteredDialog(list: List<CatalogLake>, onDownload: () -> Unit, onWait: () -> Unit) {
    AlertDialog(
        onDismissRequest = onWait,
        title = { Text("Use mobile data?") },
        text = { Text("You're not on Wi-Fi. Download ${mbText(list.sumOf { it.size })} of charts now?") },
        confirmButton = { TextButton(onClick = onDownload) { Text("Download", color = SignalOrange) } },
        dismissButton = { TextButton(onClick = onWait) { Text("Wait for Wi-Fi") } },
    )
}

/** Imported charts that don't match the signed chart list: install only if the user says so. */
@Composable
fun UnverifiedImportDialog(s: LakeStore.StagedImport, onImport: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unverified charts") },
        text = { Text("${s.unverified.joinToString { regionName(it) }}: these files don't match " +
            "the published Third Coast charts. They may be charts you built yourself, an older or newer build, or " +
            "altered files. Import them anyway?") },
        confirmButton = { TextButton(onClick = onImport) { Text("Import anyway", color = SignalOrange) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Confirm removing a region's charts. */
@Composable
fun RemoveChartsDialog(lake: Lake, onRemove: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Remove ${lake.name} charts?") },
        text = { Text("This frees its storage on the phone. You can download it again later.") },
        confirmButton = { TextButton(onClick = onRemove) { Text("Remove", color = SignalOrange) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
