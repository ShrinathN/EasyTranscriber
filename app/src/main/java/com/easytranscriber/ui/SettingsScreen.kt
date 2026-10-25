package com.easytranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import com.easytranscriber.settings.TypingSpeed
import com.easytranscriber.transcript.TextFormatter

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
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
        if (locked) {
            Text("Stop the current dictation to change the mode or model.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Section("Transcription mode") {
            ChoiceRow("Live typing", "Text is typed while you speak (streaming models).", s.mode == TranscriptionMode.REAL_TIME, !locked) {
                update { it.copy(mode = TranscriptionMode.REAL_TIME) }
            }
            ChoiceRow("Record first", "Record, then transcribe the whole recording (more accurate, punctuated).", s.mode == TranscriptionMode.RECORD_THEN_TRANSCRIBE, !locked) {
                update { it.copy(mode = TranscriptionMode.RECORD_THEN_TRANSCRIBE) }
            }
        }

        Section("Live typing model") {
            models.filter { TranscriptionMode.REAL_TIME in it.entry.modes && it.entry.id in visible }.forEach { m ->
                ModelChoice(m, s.streamingModelId == m.entry.id) { update { it.copy(streamingModelId = m.entry.id) } }
            }
        }

        Section("Record-first model") {
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
            SwitchRow("Review before sending", "Check and edit the transcript before it is typed.", s.reviewBeforeSending) {
                update { st -> st.copy(reviewBeforeSending = it) }
            }
            Text("Maximum recording length", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            ChipRow {
                listOf(2, 5, 10, 20).forEach { m ->
                    FilterChip(selected = s.maxRecordingMinutes == m, onClick = { update { it.copy(maxRecordingMinutes = m) } }, label = { Text("$m min") })
                }
            }
        }

        Section("Live typing speed vs. accuracy") {
            com.easytranscriber.settings.CommitDelay.entries.forEach { d ->
                ChoiceRow(d.label, d.description, s.commitDelay == d, !locked) { update { it.copy(commitDelay = d) } }
            }
        }

        Section("Text") {
            SwitchRow(
                "Spoken punctuation",
                "Say " + TextFormatter.COMMAND_HELP.joinToString { "“${it.first}”" } + " in live typing.",
                s.spokenPunctuation,
            ) { update { st -> st.copy(spokenPunctuation = it) } }
            SwitchRow("Capitalize sentences", "Capital letter after . ? ! and for “I”.", s.autoCapitalize) {
                update { st -> st.copy(autoCapitalize = it) }
            }
            SwitchRow("Space between dictations", "Start each new dictation with a space after earlier text.", s.spaceBetweenDictations) {
                update { st -> st.copy(spaceBetweenDictations = it) }
            }
        }

        Section("Keyboard") {
            Text("Layout: English (US) QWERTY. Set the same layout on the receiving device.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Text("Typing speed", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            ChipRow {
                TypingSpeed.entries.forEach { sp ->
                    FilterChip(selected = s.typingSpeed == sp, onClick = { update { it.copy(typingSpeed = sp) } }, label = { Text(sp.label.substringBefore(" (")) })
                }
            }
            Text("If characters go missing on your device, choose Careful.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow("Turn on keyboard when the app opens", null, s.autoStartKeyboard) {
                update { st -> st.copy(autoStartKeyboard = it) }
            }
        }

        if (BuildConfig.DEBUG) Section("Performance (debug build)") {
            Text("Processor threads", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
            ChipRow {
                listOf(1, 2, 4).forEach { n ->
                    FilterChip(selected = s.threads == n, onClick = { update { it.copy(threads = n) } }, label = { Text("$n") }, enabled = !locked)
                }
            }
            Text("2 was fastest on the test phone; 4 was slower.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }

        Section("Privacy") {
            Text(
                "Speech is recognized only on this phone. The internet is used only to download the models you " +
                    "choose above, from the sherpa-onnx project on GitHub; each file is checked before it is used. " +
                    "Recordings are stored temporarily and deleted as soon as transcription finishes, fails or is " +
                    "cancelled. Audio and text are never sent anywhere except as keystrokes to your connected device.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (BuildConfig.DEBUG) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Model folder (debug): ${c.modelsDir.absolutePath}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                OutlinedButton(onClick = c::refreshModels, enabled = !locked, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Rescan models") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
            content()
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
