package com.easytranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.easytranscriber.BuildConfig
import com.easytranscriber.models.DownloadState
import com.easytranscriber.recognition.InstalledModel

fun megabytes(bytes: Long) = "%.0f MB".format(bytes / 1e6)

/** A selectable model with its install status and download / cancel / delete actions. */
@Composable
fun ModelRow(
    model: InstalledModel,
    download: DownloadState?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val e = model.entry
    var confirmDelete by remember { mutableStateOf(false) }
    val canSelect = enabled && model.isInstalled
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected, enabled = canSelect, role = Role.RadioButton, onClick = onSelect)
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected && model.isInstalled, onClick = null, enabled = canSelect)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(e.displayName, style = MaterialTheme.typography.bodyLarge)
                val status = if (model.isInstalled) "Installed · ${megabytes(model.sizeBytes)}" else "${megabytes(e.downloadBytes)} download"
                Text(
                    "$status · ${e.languages.filter { it.code.isNotEmpty() }.joinToString { it.displayName }} · ${e.license.substringBefore(" (")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(e.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (BuildConfig.DEBUG) e.measured?.let {
                    Text("Measured: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                download is DownloadState.Running -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel download") }
                model.isInstalled -> IconButton(onClick = { confirmDelete = true }, enabled = enabled) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete ${e.displayName}")
                }
                else -> FilledTonalButton(onClick = onDownload, enabled = enabled) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (download is DownloadState.Failed) "Retry" else "Download")
                }
            }
        }
        when (download) {
            is DownloadState.Running -> Column(Modifier.padding(horizontal = 56.dp, vertical = 4.dp)) {
                LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${megabytes(download.bytes)} of ${megabytes(download.total)}",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
                )
            }
            is DownloadState.Failed -> Text(
                download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 56.dp),
            )
            null -> Unit
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${e.displayName}?") },
            text = { Text("This frees ${megabytes(model.sizeBytes)}. You can download it again later.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Shown on the Dictate tab when the selected mode's model isn't installed yet. */
@Composable
fun GetModelCard(model: InstalledModel, download: DownloadState?, onDownload: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Download, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Download the speech model to get started", style = MaterialTheme.typography.titleMedium)
        Text(
            "${model.entry.displayName} · ${megabytes(model.entry.downloadBytes)}. Downloaded once; after that, " +
                "speech recognition works without an internet connection.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (download) {
            is DownloadState.Running -> {
                LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth(0.8f))
                Text("${megabytes(download.bytes)} of ${megabytes(download.total)}", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is DownloadState.Failed -> {
                Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = onDownload) { Text("Try again") }
            }
            null -> FilledTonalButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download")
            }
        }
    }
}
