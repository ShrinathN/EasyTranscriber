package com.easytranscriber.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easytranscriber.BuildConfig
import com.easytranscriber.dictation.SessionState
import com.easytranscriber.dictation.isActive
import com.easytranscriber.recognition.InstalledModel
import com.easytranscriber.recognition.ModelArchitecture
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.TranscriptionMode
import com.easytranscriber.settings.AppSettings

/** Settings → Models: choose, download and delete the models for each transcription mode. */
@Composable
fun ModelsScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val s by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val c = vm.controller

    val locked = session.isActive() || session is SessionState.Review
    val update: ((AppSettings) -> AppSettings) -> Unit = c::updateSettings
    val visible = ModelCatalog.visible(includeDeveloper = BuildConfig.DEBUG).map { it.id }.toSet()
    @Composable
    fun ModelChoice(m: InstalledModel, selected: Boolean, select: () -> Unit) = ModelRow(
        model = m, download = downloads[m.entry.id], selected = selected, enabled = !locked,
        onSelect = select,
        onDownload = { c.downloader.download(m.entry) },
        onCancel = { c.downloader.cancel(m.entry.id) },
        onDelete = { c.deleteModel(m.entry) },
    )

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") }
            Text("Models", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Download a model once; after that it works offline. Only the selected model of the current mode is loaded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (locked) {
            Text("Stop the current dictation to change the model.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Section("Live typing") {
            models.filter { TranscriptionMode.REAL_TIME in it.entry.modes && it.entry.id in visible }.forEach { m ->
                ModelChoice(m, s.streamingModelId == m.entry.id) { update { it.copy(streamingModelId = m.entry.id) } }
            }
        }

        Section("Record first") {
            models.filter { TranscriptionMode.RECORD_THEN_TRANSCRIBE in it.entry.modes && it.entry.id in visible }.forEach { m ->
                ModelChoice(m, s.batchModelId == m.entry.id) { update { it.copy(batchModelId = m.entry.id) } }
            }
            val batch = ModelCatalog.byId(s.batchModelId)
            if (batch?.architecture == ModelArchitecture.WHISPER) {
                Text("Language", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                ChipRow {
                    batch.languages.forEach { lang ->
                        FilterChip(selected = s.batchLanguage == lang.code, onClick = { update { it.copy(batchLanguage = lang.code) } },
                            label = { Text(lang.displayName) }, enabled = !locked)
                    }
                }
                if (s.batchLanguage == "hi") {
                    Text(
                        "Hindi is transcribed in Devanagari, which the US keyboard layout cannot type. You can read and " +
                            "edit it in review, but sending will stop at the first Devanagari character. Hindi accuracy " +
                            "of this model has not been evaluated.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else if (batch != null) {
                Text("Language: ${batch.languages.joinToString { it.displayName }} only", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
