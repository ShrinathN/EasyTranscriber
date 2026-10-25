package com.easytranscriber.bluetooth

/**
 * State of the phone's Bluetooth HID keyboard role.
 *
 * Pairing (bonding) is tracked separately by the system and is not a state here: a host can be
 * paired while the HID app is unregistered, and the HID app can be registered with no host.
 */
sealed interface HidConnectionState {
    /** The device cannot act as a HID keyboard; [reason] is shown to the user. */
    data class Unsupported(val reason: String) : HidConnectionState

    data object PermissionRequired : HidConnectionState
    data object BluetoothOff : HidConnectionState

    /** HID role not started (or stopped by the user). */
    data object Idle : HidConnectionState

    /** Waiting for the HID Device profile proxy and app registration to complete. */
    data object Registering : HidConnectionState

    data class RegistrationFailed(val reason: String) : HidConnectionState

    /** Registered as a keyboard, no host connected. Hosts can now connect to the phone. */
    data object Registered : HidConnectionState

    data class Connecting(val host: HostDevice) : HidConnectionState
    data class Connected(val host: HostDevice) : HidConnectionState
}

enum class HostKind(val label: String) { COMPUTER("Computer"), PHONE("Phone or tablet"), OTHER("Other device") }

data class HostDevice(val address: String, val name: String?, val kind: HostKind = HostKind.OTHER) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: address
}
