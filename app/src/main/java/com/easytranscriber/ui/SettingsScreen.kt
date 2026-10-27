package com.easytranscriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easytranscriber.BuildConfig
import com.easytranscriber.dictation.SessionState
import com.easytranscriber.dictation.isActive
import com.easytranscriber.recognition.TranscriptionMode
import com.easytranscriber.settings.AppSettings
import com.easytranscriber.settings.TypingSpeed
import com.easytranscriber.transcript.TextFormatter

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val c = vm.controller

    val locked = session.isActive() || session is SessionState.Review
    val update: ((AppSettings) -> AppSettings) -> Unit = c::updateSettings
    var showModels by rememberSaveable { mutableStateOf(false) }
    if (showModels) {
        ModelsScreen(vm, onBack = { showModels = false }, modifier)
        return
    }
    fun summary(id: String): String {
        val m = models.firstOrNull { it.entry.id == id } ?: return "None"
        return m.entry.displayName + if (m.isInstalled) "" else " · not downloaded"
    }

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

        Section("Models") {
            NavRow("Live typing", summary(s.streamingModelId)) { showModels = true }
            NavRow("Record first", summary(s.batchModelId)) { showModels = true }
        }

        Section("Record first") {
            SwitchRow(
                "Transcribe when I stop talking",
                "Recording ends by itself after a pause, then transcription starts. Turn off to stop recording with the button.",
                s.autoStopOnPause,
            ) { update { st -> st.copy(autoStopOnPause = it) } }
            if (s.autoStopOnPause) {
                Text("Pause length", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                ChipRow {
                    listOf(1_000, 1_500, 2_000, 3_000).forEach { ms ->
                        FilterChip(selected = s.pauseToStopMs == ms, onClick = { update { it.copy(pauseToStopMs = ms) } }, label = { Text(formatSeconds(ms / 1000.0)) })
                    }
                }
                Text("Longer pauses leave room to think mid-sentence; you can always tap stop sooner.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
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
                    "choose, from the sherpa-onnx project on GitHub or (ASR4ALL) FUTO's page on Hugging Face; each file " +
                    "is checked before it is used. " +
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
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
            content()
        }
    }
}

@Composable
internal fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
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

/** A row that opens a sub-page: title, current value, chevron. */
@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
