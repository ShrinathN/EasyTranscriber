package com.easytranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easytranscriber.pipeline.PipelineStatus

@Composable
fun RecognitionTestScreen(
    vm: RecognitionTestViewModel,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val models by vm.models.collectAsStateWithLifecycle()
    val testSets by vm.testSets.collectAsStateWithLifecycle()
    val selected by vm.selectedModelId.collectAsStateWithLifecycle()
    val threads by vm.threads.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val engineStatus by vm.engineStatus.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val report by vm.lastReport.collectAsStateWithLifecycle()

    Column(
        modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Streaming recognition test", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Model", style = MaterialTheme.typography.titleMedium)
                models.filter { com.easytranscriber.recognition.TranscriptionMode.REAL_TIME in it.entry.modes }.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = selected == m.entry.id,
                            enabled = m.isInstalled && !running,
                            role = Role.RadioButton,
                            onClick = { vm.selectModel(m.entry.id) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == m.entry.id, onClick = null, enabled = m.isInstalled && !running)
                        Column {
                            Text(m.entry.displayName)
                            Text(
                                if (m.isInstalled) "%.0f MB · %s · %s".format(
                                    m.sizeBytes / 1e6, m.entry.languages.joinToString { it.displayName }, m.entry.license
                                ) else "Not installed (missing ${m.missingFiles.size} files)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (models.none { it.isInstalled }) {
                    Text("Install models under ${vm.modelsPath}/<model id>/", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Threads")
                    listOf(1, 2, 4).forEach { n ->
                        FilterChip(selected = threads == n, onClick = { vm.selectThreads(n) }, label = { Text("$n") }, enabled = !running)
                    }
                }
                Text(
                    when (val s = engineStatus) {
                        EngineStatus.NotLoaded -> "Model not loaded (loads on start)"
                        EngineStatus.Loading -> "Loading model…"
                        is EngineStatus.Loaded -> "Model loaded in ${s.loadMs} ms"
                        is EngineStatus.Failed -> "Model failed to load: ${s.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Audio input", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = input == InputChoice.Microphone,
                        enabled = !running,
                        role = Role.RadioButton,
                        onClick = { vm.selectInput(InputChoice.Microphone) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = input == InputChoice.Microphone, onClick = null, enabled = !running)
                    Text("Microphone")
                }
                testSets.forEach { t ->
                    val isSelected = (input as? InputChoice.Playback)?.testSet?.name == t.name
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = isSelected,
                            enabled = !running,
                            role = Role.RadioButton,
                            onClick = { vm.selectInput(InputChoice.Playback(t)) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null, enabled = !running)
                        Text("Test playback: ${t.name} (${t.files.size} files${if (t.combinedReference != null) ", with reference" else ""})")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val needsMic = input == InputChoice.Microphone && !hasMicPermission
            if (needsMic) {
                Button(onClick = onRequestMicPermission) { Text("Grant microphone permission") }
            } else if (!running) {
                Button(onClick = vm::start, enabled = selected != null) { Text("Start") }
            } else {
                Button(onClick = vm::stop) { Text("Stop") }
            }
            OutlinedButton(onClick = vm::refresh, enabled = !running) { Text("Rescan") }
        }

        session?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val status = when (val st = s.status) {
                        PipelineStatus.Running -> "Listening"
                        PipelineStatus.Stopping -> "Finishing…"
                        PipelineStatus.Finished -> "Finished"
                        is PipelineStatus.Failed -> "Failed: ${st.message}"
                    }
                    Text(
                        "$status · audio %.1f s · lag %s ms · dropped %d".format(
                            s.audioSec, s.lastLagMs?.let { "%.0f".format(it) } ?: "-", s.framesDropped
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        buildAnnotatedString {
                            append(s.finalized.joinToString(" "))
                            if (s.partial.isNotEmpty()) {
                                if (s.finalized.isNotEmpty()) append(" ")
                                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.outline)) {
                                    append(s.partial)
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        report?.let {
            Text("Last session", style = MaterialTheme.typography.titleMedium)
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
