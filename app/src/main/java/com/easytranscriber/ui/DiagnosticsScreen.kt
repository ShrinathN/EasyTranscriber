package com.easytranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easytranscriber.dictation.isActive
import com.easytranscriber.recognition.TestSet

@Composable
fun DiagnosticsScreen(
    vm: AppViewModel,
    benchmarkVm: RecognitionTestViewModel,
    hasMicPermission: Boolean,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tools", "Streaming benchmark", "Event log").forEachIndexed { i, label ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
            }
        }
        when (tab) {
            0 -> ToolsTab(vm)
            1 -> RecognitionTestScreen(benchmarkVm, hasMicPermission, onRequestMic)
            else -> LogTab(vm)
        }
    }
}

@Composable
private fun ToolsTab(vm: AppViewModel) {
    val testInput by vm.testInput.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val sets = remember { TestSet.loadAll(vm.controller.testSetsDir) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Dictation input", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                Text(
                    "For repeatable end-to-end tests, the Dictate tab can play a recorded test set through the full " +
                        "pipeline (recognition → keyboard) instead of using the microphone.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val options: List<TestSet?> = listOf(null) + sets
                options.forEach { set ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = testInput?.name == set?.name,
                            enabled = !session.isActive(),
                            role = Role.RadioButton,
                            onClick = { vm.controller.setTestInput(set) },
                        ).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = testInput?.name == set?.name, onClick = null)
                        Text(set?.let { "Test set: ${it.name} (${it.files.size} files)" } ?: "Microphone (normal use)")
                    }
                }
                if (sets.isEmpty()) {
                    Text("No test sets in ${vm.controller.testSetsDir.absolutePath}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Session reports", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Each session writes a JSON report (timings, latency, memory; no audio) to " +
                        "${vm.controller.modelsDir.parentFile?.absolutePath}/reports.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LogTab(vm: AppViewModel) {
    val log by vm.log.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row {
            TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy") }
            TextButton(onClick = vm.log::clear) { Text("Clear") }
        }
        Text(log.takeLast(200).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
