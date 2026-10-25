package com.easytranscriber.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.easytranscriber.BuildConfig
import com.easytranscriber.bluetooth.HidConnectionState
import com.easytranscriber.ui.theme.EasyTranscriberTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val benchmarkVm: RecognitionTestViewModel by viewModels()
    private var hasMicPermission by mutableStateOf(false)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    private val startupPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsMaybeChanged()
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionsMaybeChanged()
    }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestStartupPermissions()
        setContent {
            EasyTranscriberTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // The Diagnostics tab (benchmarks, test input, event log) exists only in debug builds.
                val titles = listOf("Dictate", "Keyboard", "Settings") + if (BuildConfig.DEBUG) listOf("Diagnostics") else emptyList()
                Scaffold(
                    topBar = { TopBar(if (tab == 0) "EasyTranscriber" else titles[tab]) },
                    bottomBar = {
                        NavigationBar {
                            listOf(Icons.Rounded.Mic, Icons.Rounded.Keyboard, Icons.Rounded.Settings, Icons.Rounded.Troubleshoot)
                                .take(titles.size).forEachIndexed { i, icon ->
                                    NavigationBarItem(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        icon = { Icon(icon, null) },
                                        label = { Text(titles[i]) },
                                    )
                                }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding).fillMaxSize()
                    when (tab) {
                        0 -> DictateScreen(
                            vm, hasMicPermission,
                            onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onOpenKeyboard = { tab = 1 },
                            onOpenSettings = { tab = 2 },
                            modifier = m,
                        )
                        1 -> KeyboardScreen(
                            vm,
                            onRequestBluetoothPermission = ::requestStartupPermissions,
                            onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                            onMakeDiscoverable = {
                                discoverable.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                                )
                            },
                            modifier = m,
                        )
                        2 -> SettingsScreen(vm, m)
                        else -> if (BuildConfig.DEBUG) DiagnosticsScreen(
                            vm, benchmarkVm, hasMicPermission,
                            onRequestMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = m,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun TopBar(title: String) {
        CenterAlignedTopAppBar(title = { Text(title) })
    }

    override fun onResume() {
        super.onResume()
        onPermissionsMaybeChanged()
    }

    private fun onPermissionsMaybeChanged() {
        hasMicPermission = granted(Manifest.permission.RECORD_AUDIO)
        vm.hid.refreshPreconditions()
        vm.controller.refreshModels()
        val autoStart = vm.settings.value.autoStartKeyboard
        if (autoStart && !vm.hid.stoppedByUser && vm.hid.state.value == HidConnectionState.Idle) vm.hid.start()
    }

    private fun requestStartupPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::granted)
        if (wanted.isNotEmpty()) startupPermissions.launch(wanted.toTypedArray())
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
