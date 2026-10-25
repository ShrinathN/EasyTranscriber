package com.easytranscriber.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.easytranscriber.diagnostics.EventLog
import com.easytranscriber.hid.HidKeyboardDescriptor
import com.easytranscriber.hid.HidReportSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Owns the phone's Bluetooth HID Device (keyboard) role.
 *
 * Lifecycle: [start] obtains the HID_DEVICE profile proxy and registers a keyboard SDP record;
 * hosts may then pair with / connect to the phone, or [connect] can initiate a connection to an
 * already-paired host. [stop] unregisters and releases the proxy.
 *
 * Only one app on the device can hold the HID Device role at a time.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is guarded by hasConnectPermission().
class BluetoothHidManager(
    private val context: Context,
    private val log: EventLog,
) : HidReportSink {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<HidConnectionState>(HidConnectionState.Idle)
    val state: StateFlow<HidConnectionState> = _state.asStateFlow()

    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var connectedHost: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var wantRunning = false

    /** True after the user turned the keyboard off; suppresses automatic start until [start]. */
    @Volatile var stoppedByUser = false
        private set

    private val proxyTimeout = Runnable {
        if (hidDevice == null && wantRunning) {
            setState(
                HidConnectionState.RegistrationFailed(
                    "The HID Device profile did not respond within ${PROXY_TIMEOUT_MS / 1000} s. " +
                        "The Bluetooth HID Device role is probably disabled on this phone. Some phones " +
                        "(e.g. Motorola) can enable it in Developer options; then turn Bluetooth " +
                        "off and on and try again."
                )
            )
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    log.add("Bluetooth turned off")
                    releaseProxy()
                    setState(HidConnectionState.BluetoothOff)
                }
                BluetoothAdapter.STATE_ON -> {
                    log.add("Bluetooth turned on")
                    if (wantRunning) start() else setState(HidConnectionState.Idle)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshPreconditions()
    }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Re-evaluates adapter/permission state without starting anything (e.g. after a permission grant). */
    fun refreshPreconditions() {
        val blocking = preconditionFailure()
        if (blocking != null) setState(blocking)
        else if (!wantRunning) setState(HidConnectionState.Idle)
    }

    private fun preconditionFailure(): HidConnectionState? = when {
        adapter == null -> HidConnectionState.Unsupported("This device has no Bluetooth adapter.")
        !hasConnectPermission() -> HidConnectionState.PermissionRequired
        !adapter.isEnabled -> HidConnectionState.BluetoothOff
        else -> null
    }

    fun start() {
        wantRunning = true
        stoppedByUser = false
        preconditionFailure()?.let { setState(it); return }
        val hid = hidDevice
        if (hid != null) {
            if (!registered) registerApp(hid)
            return
        }
        setState(HidConnectionState.Registering)
        log.add("Requesting HID_DEVICE profile proxy")
        val requested = adapter!!.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            setState(
                HidConnectionState.Unsupported(
                    "This phone's Bluetooth stack does not provide the HID Device profile."
                )
            )
            return
        }
        mainHandler.removeCallbacks(proxyTimeout)
        mainHandler.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
    }

    fun stop() {
        wantRunning = false
        stoppedByUser = true
        connectedHost?.let { host ->
            if (hasConnectPermission()) hidDevice?.disconnect(host)
        }
        releaseProxy()
        refreshPreconditions()
        log.add("HID keyboard stopped")
    }

    /** Paired devices. Pairing alone does not mean a device can or will act as a HID host. */
    fun bondedDevices(): List<HostDevice> {
        val a = adapter ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        return a.bondedDevices.orEmpty()
            .map { HostDevice(it.address, it.name, kindOf(it)) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
    }

    fun connect(address: String): Boolean {
        val hid = hidDevice
        if (hid == null || !registered) {
            log.add("Connect ignored: HID app not registered")
            return false
        }
        if (!hasConnectPermission()) return false
        val device = adapter!!.getRemoteDevice(address)
        val ok = hid.connect(device)
        log.add("connect(${device.address}) -> $ok")
        return ok
    }

    fun disconnect() {
        val host = connectedHost ?: return
        if (!hasConnectPermission()) return
        log.add("disconnect(${host.address}) -> ${hidDevice?.disconnect(host)}")
    }

    override fun sendReport(report: ByteArray): Boolean {
        val hid = hidDevice ?: return false
        val host = connectedHost ?: return false
        if (!hasConnectPermission()) return false
        return hid.sendReport(host, HidKeyboardDescriptor.REPORT_ID, report)
    }

    private fun registerApp(hid: BluetoothHidDevice) {
        if (!hasConnectPermission()) {
            setState(HidConnectionState.PermissionRequired); return
        }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            SDP_NAME,
            SDP_DESCRIPTION,
            SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HidKeyboardDescriptor.DESCRIPTOR,
        )
        setState(HidConnectionState.Registering)
        val ok = hid.registerApp(sdp, null, null, callbackExecutor, hidCallback)
        log.add("registerApp -> $ok")
        if (!ok) {
            setState(
                HidConnectionState.RegistrationFailed(
                    "registerApp was rejected. Another app may already hold the Bluetooth HID " +
                        "Device role; close it (or toggle Bluetooth) and try again."
                )
            )
        }
    }

    private fun releaseProxy() {
        mainHandler.removeCallbacks(proxyTimeout)
        val hid = hidDevice
        hidDevice = null
        connectedHost = null
        if (hid != null) {
            if (registered && hasConnectPermission()) hid.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        registered = false
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            mainHandler.removeCallbacks(proxyTimeout)
            log.add("HID_DEVICE proxy connected")
            val hid = proxy as BluetoothHidDevice
            hidDevice = hid
            if (wantRunning) registerApp(hid) else releaseProxy()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            log.add("HID_DEVICE proxy disconnected")
            hidDevice = null
            connectedHost = null
            registered = false
            if (wantRunning) {
                setState(preconditionFailure() ?: HidConnectionState.RegistrationFailed("The Bluetooth HID service stopped."))
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothHidManager.registered = registered
            log.add("onAppStatusChanged registered=$registered plugged=${pluggedDevice?.address}")
            if (registered) {
                if (connectedHost == null) setState(HidConnectionState.Registered)
            } else {
                connectedHost = null
                setState(
                    if (wantRunning) HidConnectionState.RegistrationFailed(
                        "The keyboard registration was removed by the system. Another app may have " +
                            "taken the Bluetooth HID Device role."
                    ) else HidConnectionState.Idle
                )
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val host = device.toHost()
            log.add("onConnectionStateChanged ${host.label} -> ${connectionStateName(state)}")
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> setState(HidConnectionState.Connecting(host))
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    setState(HidConnectionState.Connected(host))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost == null || connectedHost?.address == device.address) {
                        connectedHost = null
                        if (this@BluetoothHidManager.registered) setState(HidConnectionState.Registered)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val hid = hidDevice ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                hid.replyReport(device, type, id, ByteArray(HidKeyboardDescriptor.INPUT_REPORT_SIZE))
            } else {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            log.add("onSetProtocol $protocol")
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Host output report: keyboard LED state (Num/Caps/Scroll Lock).
            log.add("Host LED report id=$reportId data=${data.joinToString(",")}")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            log.add("onVirtualCableUnplug ${device.address}")
        }
    }

    private fun kindOf(d: BluetoothDevice): HostKind = when (d.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.COMPUTER -> HostKind.COMPUTER
        BluetoothClass.Device.Major.PHONE -> HostKind.PHONE
        else -> HostKind.OTHER
    }

    private fun BluetoothDevice.toHost() =
        HostDevice(address, if (hasConnectPermission()) name else null)

    private fun setState(s: HidConnectionState) {
        _state.value = s
    }

    private fun connectionStateName(state: Int) = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($state)"
    }

    companion object {
        private const val PROXY_TIMEOUT_MS = 5_000L
        private const val SDP_NAME = "EasyTranscriber Keyboard"
        private const val SDP_DESCRIPTION = "Offline speech-to-text keyboard"
        private const val SDP_PROVIDER = "EasyTranscriber"
    }
}
