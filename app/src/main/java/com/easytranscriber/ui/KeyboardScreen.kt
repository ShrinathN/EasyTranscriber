package com.easytranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.easytranscriber.bluetooth.HidConnectionState
import com.easytranscriber.hid.UsQwertyLayout

@Composable
fun KeyboardScreen(
    vm: AppViewModel,
    onRequestBluetoothPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.keyboard.collectAsStateWithLifecycle()
    val delivered by vm.deliveredChars.collectAsStateWithLifecycle()
    val paired = remember(state) { vm.pairedDevices() }
    val hid = vm.hid

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = if (state is HidConnectionState.Connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        )) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (state) {
                        is HidConnectionState.Connected -> Icons.Rounded.BluetoothConnected
                        HidConnectionState.Registered, is HidConnectionState.Connecting -> Icons.Rounded.Bluetooth
                        else -> Icons.Rounded.BluetoothDisabled
                    }
                    Icon(icon, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title(state), style = MaterialTheme.typography.titleMedium)
                        Text(description(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state) {
                        HidConnectionState.PermissionRequired -> Button(onClick = onRequestBluetoothPermission) { Text("Allow Bluetooth") }
                        HidConnectionState.BluetoothOff -> Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
                        HidConnectionState.Idle, is HidConnectionState.RegistrationFailed -> Button(onClick = hid::start) { Text("Turn on keyboard") }
                        HidConnectionState.Registering -> CircularProgressIndicator(Modifier.size(24.dp))
                        HidConnectionState.Registered, is HidConnectionState.Connecting -> {
                            Button(onClick = onMakeDiscoverable) { Text("Pair a new device") }
                            OutlinedButton(onClick = hid::stop) { Text("Turn off") }
                        }
                        is HidConnectionState.Connected -> {
                            OutlinedButton(onClick = hid::disconnect) { Text("Disconnect") }
                            OutlinedButton(onClick = hid::stop) { Text("Turn off") }
                        }
                        is HidConnectionState.Unsupported -> Unit
                    }
                }
            }
        }

        if (state == HidConnectionState.Registered || state is HidConnectionState.Connected || state is HidConnectionState.Connecting) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Paired devices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (paired.isEmpty()) {
                        Text("None yet. Tap “Pair a new device”.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    val connectedAddress = (state as? HidConnectionState.Connected)?.host?.address
                    paired.forEach { host ->
                        ListItem(
                            headlineContent = { Text(host.label) },
                            supportingContent = { Text(if (host.address == connectedAddress) "Connected" else host.kind.label) },
                            leadingContent = {
                                Icon(
                                    when (host.kind) {
                                        com.easytranscriber.bluetooth.HostKind.COMPUTER -> Icons.Rounded.Computer
                                        com.easytranscriber.bluetooth.HostKind.PHONE -> Icons.Rounded.Smartphone
                                        com.easytranscriber.bluetooth.HostKind.OTHER -> Icons.Rounded.DevicesOther
                                    },
                                    null,
                                )
                            },
                            trailingContent = {
                                if (host.address != connectedAddress && state == HidConnectionState.Registered) {
                                    TextButton(onClick = { hid.connect(host.address) }) { Text("Connect") }
                                }
                            },
                        )
                    }
                    Text(
                        "Connect only to the device you want to type on. Computers are listed first.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state is HidConnectionState.Connected) TestTypingCard(vm)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Keyboard, null)
                    Spacer(Modifier.width(8.dp))
                    Text("How it works", style = MaterialTheme.typography.titleSmall)
                }
                Text("1. Turn on the keyboard and tap “Pair a new device”.", style = MaterialTheme.typography.bodyMedium)
                Text("2. On your computer or tablet, open Bluetooth settings and pair with this phone.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Click into any text field there. Dictated text is typed like a real keyboard.", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Set the device's keyboard layout to ${UsQwertyLayout.displayName}. Accented letters and non-Latin " +
                        "scripts can't be typed this way; the app tells you instead of typing something wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Characters typed this session: $delivered", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TestTypingCard(vm: AppViewModel) {
    var text by remember { mutableStateOf("Hello from EasyTranscriber!") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Test typing", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { vm.controller.delivery.enqueue(text) }, enabled = text.isNotEmpty()) {
                        Icon(Icons.Rounded.Send, "Type on device")
                    }
                },
            )
            if (com.easytranscriber.BuildConfig.DEBUG) {
                TextButton(onClick = { vm.controller.delivery.enqueue((0x20..0x7E).map { it.toChar() }.joinToString("") + "\n") }) {
                    Text("Type all ASCII characters")
                }
            }
        }
    }
}

private fun title(s: HidConnectionState) = when (s) {
    is HidConnectionState.Connected -> "Connected to ${s.host.label}"
    is HidConnectionState.Connecting -> "Connecting to ${s.host.label}…"
    HidConnectionState.Registered -> "Keyboard ready"
    HidConnectionState.Registering -> "Starting keyboard…"
    HidConnectionState.Idle -> "Keyboard off"
    HidConnectionState.BluetoothOff -> "Bluetooth is off"
    HidConnectionState.PermissionRequired -> "Bluetooth permission needed"
    is HidConnectionState.RegistrationFailed -> "Keyboard couldn't start"
    is HidConnectionState.Unsupported -> "Not supported on this phone"
}

private fun description(s: HidConnectionState) = when (s) {
    is HidConnectionState.Connected -> "Text you dictate is typed on this device."
    is HidConnectionState.Connecting -> "Waiting for the device to accept."
    HidConnectionState.Registered -> "Connect to a paired device, or pair a new one."
    HidConnectionState.Registering -> "Registering as a Bluetooth keyboard."
    HidConnectionState.Idle -> "Turn it on to type on a computer or tablet."
    HidConnectionState.BluetoothOff -> "Bluetooth must be on to act as a keyboard."
    HidConnectionState.PermissionRequired -> "Needed to act as a Bluetooth keyboard."
    is HidConnectionState.RegistrationFailed -> s.reason
    is HidConnectionState.Unsupported -> s.reason
}
