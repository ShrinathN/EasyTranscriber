package com.easytranscriber.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easytranscriber.bluetooth.HidConnectionState
import com.easytranscriber.dictation.EngineState
import com.easytranscriber.dictation.SessionState
import com.easytranscriber.dictation.isActive
import com.easytranscriber.output.DeliveryState
import com.easytranscriber.recognition.TranscriptionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictateScreen(
    vm: AppViewModel,
    hasMicPermission: Boolean,
    onRequestMic: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val keyboard by vm.keyboard.collectAsStateWithLifecycle()
    val delivery by vm.delivery.collectAsStateWithLifecycle()
    val last by vm.lastSession.collectAsStateWithLifecycle()
    val testInput by vm.testInput.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val selectedId = if (settings.mode == TranscriptionMode.REAL_TIME) settings.streamingModelId else settings.batchModelId
    val missingModel = models.firstOrNull { it.entry.id == selectedId }?.takeIf { !it.isInstalled }
    val c = vm.controller
    val busy = session.isActive() || session is SessionState.Review

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardChip(keyboard, onOpenKeyboard)
            EngineChip(engine, onOpenSettings)
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(
                Triple(TranscriptionMode.REAL_TIME, "Live typing", Icons.Rounded.GraphicEq),
                Triple(TranscriptionMode.RECORD_THEN_TRANSCRIBE, "Record first", Icons.Rounded.FiberManualRecord),
            )
            modes.forEachIndexed { i, (mode, label, icon) ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { c.updateSettings { it.copy(mode = mode) } },
                    enabled = !busy,
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    icon = { Icon(icon, null, Modifier.size(18.dp)) },
                ) { Text(label) }
            }
        }
        Text(
            if (settings.mode == TranscriptionMode.REAL_TIME) "Words are typed on your device while you speak."
            else "Speak freely, then the whole recording is transcribed${if (settings.reviewBeforeSending) " for you to review" else " and typed"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        testInput?.let {
            Banner(Icons.Rounded.Science, "Test input: playing “${it.name}” instead of the microphone", MaterialTheme.colorScheme.tertiaryContainer) {
                TextButton(onClick = { c.setTestInput(null) }, enabled = !busy) { Text("Use microphone") }
            }
        }

        (session as? SessionState.Failed)?.let { f ->
            Banner(Icons.Rounded.ErrorOutline, f.message + (f.hint?.let { "\n$it" } ?: ""), MaterialTheme.colorScheme.errorContainer) {
                TextButton(onClick = c::dismissError) { Text("Dismiss") }
            }
        }

        DeliveryBanner(delivery, keyboard is HidConnectionState.Connected, c)

        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                if (missingModel != null && !busy) {
                    GetModelCard(
                        missingModel, downloads[missingModel.entry.id],
                        onDownload = { c.downloader.download(missingModel.entry) },
                        onCancel = { c.downloader.cancel(missingModel.entry.id) },
                    )
                } else {
                    TranscriptArea(session, last, onSend = c::sendReviewed, onDiscard = c::discardReview)
                }
            }
        }

        if (session !is SessionState.Review) MicControls(
            mode = settings.mode,
            session = session,
            canStart = engine !is EngineState.NotInstalled,
            hasMicPermission = hasMicPermission,
            keyboardConnected = keyboard is HidConnectionState.Connected,
            onRequestMic = onRequestMic,
            onStart = { if (settings.mode == TranscriptionMode.REAL_TIME) c.startDictation() else c.startRecording() },
            onStop = { if (settings.mode == TranscriptionMode.REAL_TIME) c.stopDictation() else c.stopRecording() },
            onCancel = c::cancel,
        )
    }
}

@Composable
private fun KeyboardChip(state: HidConnectionState, onClick: () -> Unit) {
    val (icon, label) = when (state) {
        is HidConnectionState.Connected -> Icons.Rounded.BluetoothConnected to "Typing on ${state.host.label}"
        is HidConnectionState.Connecting -> Icons.Rounded.Bluetooth to "Connecting…"
        HidConnectionState.Registered -> Icons.Rounded.Bluetooth to "Keyboard ready · not connected"
        HidConnectionState.Registering -> Icons.Rounded.Bluetooth to "Starting keyboard…"
        else -> Icons.Rounded.BluetoothDisabled to "Keyboard off"
    }
    val ok = state is HidConnectionState.Connected
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        colors = if (ok) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun EngineChip(state: EngineState, onClick: () -> Unit) {
    val label = when (state) {
        is EngineState.Ready -> "${state.modelName} · ready"
        is EngineState.Loading -> "Loading model…"
        is EngineState.NotInstalled -> "No model"
        is EngineState.Failed -> "Model error"
        EngineState.NotLoaded -> "Model idle"
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            if (state is EngineState.Loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(if (state is EngineState.Ready) Icons.Rounded.Memory else Icons.Rounded.WarningAmber, null, Modifier.size(18.dp))
        },
    )
}

@Composable
private fun Banner(icon: ImageVector, text: String, color: Color, actions: @Composable () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, Modifier.size(20.dp).padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

@Composable
private fun DeliveryBanner(state: DeliveryState, connected: Boolean, c: com.easytranscriber.dictation.DictationController) {
    val q = c.delivery
    when (state) {
        DeliveryState.Idle -> Unit
        is DeliveryState.Sending -> Column {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Typing… ${state.pendingChars} characters to go", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        }
        is DeliveryState.WaitingForHost -> Banner(
            Icons.Rounded.BluetoothDisabled,
            "${state.pendingChars} characters are waiting. Connect the keyboard to a device to send them.",
            MaterialTheme.colorScheme.secondaryContainer,
        ) { TextButton(onClick = q::clear) { Text("Clear") } }
        is DeliveryState.WaitingToResume -> {
            val text = buildString {
                append(if (connected) "Keyboard reconnected. " else "Keyboard connection lost. ")
                if (state.uncertain.isNotEmpty()) {
                    append("These characters may or may not have arrived: “${state.uncertain.takeLast(60)}”. ")
                }
                if (state.pending.isNotEmpty()) append("${state.pending.length} more characters are waiting. ")
                if (!connected) append("Reconnect to continue.") else append("Check the text on your device, then choose:")
            }
            Banner(Icons.Rounded.WarningAmber, text, MaterialTheme.colorScheme.secondaryContainer) {
                TextButton(onClick = q::clear) { Text("Clear all") }
                if (state.uncertain.isNotEmpty()) {
                    TextButton(onClick = { q.resume(false) }, enabled = connected) { Text("Skip them") }
                    TextButton(onClick = { q.resume(true) }, enabled = connected) { Text("Resend them") }
                } else {
                    TextButton(onClick = { q.resume(false) }, enabled = connected) { Text("Continue") }
                }
            }
        }
        is DeliveryState.Blocked -> Banner(
            Icons.Rounded.WarningAmber,
            "The US keyboard layout can't type: " +
                state.unsupported.distinctBy { it.codePoint }.joinToString { "“${it.display}”" } +
                ". Nothing is sent until you decide.",
            MaterialTheme.colorScheme.errorContainer,
        ) {
            TextButton(onClick = q::clear) { Text("Clear all") }
            TextButton(onClick = q::skipUnsupported) { Text("Remove them") }
        }
    }
}

@Composable
private fun TranscriptArea(
    session: SessionState,
    last: com.easytranscriber.dictation.LastSession?,
    onSend: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    when (session) {
        is SessionState.Streaming -> {
            val scroll = rememberScrollState()
            LaunchedEffect(session.committed.length, session.pending) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (session.committed.isEmpty() && session.pending.isEmpty()) {
                    Hint(if (session.finishing) "Finishing…" else "Listening… start speaking.")
                }
                Text(
                    buildAnnotatedString {
                        append(session.committed)
                        if (session.pending.isNotEmpty()) {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline, fontStyle = FontStyle.Italic)) {
                                append(if (session.committed.isEmpty() || session.committed.endsWith('\n')) "" else " ")
                                append(session.pending)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (session.droppedFrames > 0) {
                    Text("Warning: ${session.droppedFrames} audio frames were dropped (recognition fell behind).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        is SessionState.Preparing -> Centered {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Loading ${session.modelName}…", style = MaterialTheme.typography.bodyMedium)
        }
        is SessionState.Recording -> Centered {
            Text(formatTime(session.elapsedSec), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light)
            Text("of ${formatTime(session.maxSec.toDouble())} max", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { ((session.levelDb + 60f) / 60f).coerceIn(0f, 1f) }, modifier = Modifier.width(200.dp))
            Spacer(Modifier.height(16.dp))
            Text("Recording. Tap stop when you're done.", style = MaterialTheme.typography.bodyMedium)
        }
        is SessionState.Transcribing -> Centered {
            Text("Transcribing ${formatTime(session.recordedSec)} of audio", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { session.fraction }, modifier = Modifier.fillMaxWidth(0.8f))
            Spacer(Modifier.height(8.dp))
            Text("${(session.fraction * 100).toInt()}% · ${session.segmentsDone} segments done", style = MaterialTheme.typography.labelMedium)
        }
        is SessionState.Review -> ReviewArea(session, onSend, onDiscard)
        else -> {
            if (last != null && last.text.isNotBlank()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("Last dictation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(last.text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(last.details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Centered {
                    Icon(Icons.Rounded.Mic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Hint("Tap the microphone to start. Your speech is recognized on this phone and never leaves it.")
                }
            }
        }
    }
}

@Composable
private fun ReviewArea(review: SessionState.Review, onSend: (String) -> Unit, onDiscard: () -> Unit) {
    var text by remember(review) { mutableStateOf(review.text) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Review before sending", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(review.summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (review.unsupported.isNotEmpty()) {
            Text(
                "Contains characters the US keyboard layout can't type (" +
                    review.unsupported.distinctBy { it.codePoint }.take(8).joinToString { it.display } +
                    "). Edit them out, or sending will pause at the first one.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDiscard) { Text("Discard") }
            Button(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send to device") }
        }
    }
}

@Composable
private fun MicControls(
    mode: TranscriptionMode,
    session: SessionState,
    canStart: Boolean,
    hasMicPermission: Boolean,
    keyboardConnected: Boolean,
    onRequestMic: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val listening = session is SessionState.Streaming || session is SessionState.Recording
    val level = (session as? SessionState.Streaming)?.levelDb ?: (session as? SessionState.Recording)?.levelDb ?: -60f
    val ring by animateFloatAsState(((level + 60f) / 60f).coerceIn(0f, 1f), tween(90), label = "level")
    val enabled = when (session) {
        is SessionState.Preparing, is SessionState.Transcribing, is SessionState.Review -> false
        is SessionState.Streaming -> !session.finishing
        else -> listening || canStart
    }
    val label = when {
        session is SessionState.Review -> "Send or discard the transcript first"
        session is SessionState.Transcribing -> "Transcribing…"
        session is SessionState.Preparing -> "Getting ready…"
        listening -> "Tap to stop"
        !canStart -> "Download the speech model first"
        !hasMicPermission -> "Tap to allow microphone access"
        !keyboardConnected -> "Tap to start · text will wait until a device is connected"
        mode == TranscriptionMode.REAL_TIME -> "Tap to start dictating"
        else -> "Tap to start recording"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
            val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            if (listening) {
                Canvas(Modifier.size(132.dp)) {
                    drawCircle(ringColor, radius = size.minDimension / 2 * (0.68f + 0.32f * ring))
                    drawCircle(ringColor, radius = size.minDimension / 2 * 0.68f, style = Stroke(2.dp.toPx()))
                }
            }
            FilledIconButton(
                onClick = {
                    when {
                        listening -> onStop()
                        !hasMicPermission -> onRequestMic()
                        else -> onStart()
                    }
                },
                enabled = enabled,
                modifier = Modifier.size(88.dp).semantics { contentDescription = label },
                shape = CircleShape,
                colors = if (listening) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                else IconButtonDefaults.filledIconButtonColors(),
            ) {
                when {
                    session is SessionState.Preparing || session is SessionState.Transcribing ->
                        CircularProgressIndicator(Modifier.size(36.dp), color = MaterialTheme.colorScheme.onPrimary)
                    listening -> Icon(Icons.Rounded.Stop, null, Modifier.size(40.dp))
                    mode == TranscriptionMode.RECORD_THEN_TRANSCRIBE -> Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(40.dp))
                    else -> Icon(Icons.Rounded.Mic, null, Modifier.size(40.dp))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        // Fixed-height slot so the microphone button never moves when Cancel appears.
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            if (session is SessionState.Recording || session is SessionState.Transcribing) {
                TextButton(onClick = onCancel) { Text(if (session is SessionState.Recording) "Cancel and discard" else "Cancel") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

private fun formatTime(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
