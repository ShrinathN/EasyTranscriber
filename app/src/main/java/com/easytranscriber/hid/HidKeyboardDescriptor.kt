package com.easytranscriber.hid

/**
 * HID report descriptor for a standard boot-compatible keyboard, using report ID 1.
 *
 * Input report (8 bytes, report ID passed separately to BluetoothHidDevice.sendReport):
 *   byte 0: modifier bitmap (LCtrl, LShift, LAlt, LGui, RCtrl, RShift, RAlt, RGui)
 *   byte 1: reserved
 *   bytes 2..7: up to six simultaneously pressed key usages (Keyboard/Keypad page 0x07)
 *
 * Output report (1 byte): LED bitmap (NumLock, CapsLock, ScrollLock, Compose, Kana).
 */
object HidKeyboardDescriptor {
    const val REPORT_ID: Int = 1
    const val INPUT_REPORT_SIZE: Int = 8

    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01,                     // Usage Page (Generic Desktop)
        0x09, 0x06,                     // Usage (Keyboard)
        0xA1.toByte(), 0x01,            // Collection (Application)
        0x85.toByte(), REPORT_ID.toByte(), //   Report ID (1)
        0x05, 0x07,                     //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(),            //   Usage Minimum (Left Control)
        0x29, 0xE7.toByte(),            //   Usage Maximum (Right GUI)
        0x15, 0x00,                     //   Logical Minimum (0)
        0x25, 0x01,                     //   Logical Maximum (1)
        0x75, 0x01,                     //   Report Size (1)
        0x95.toByte(), 0x08,            //   Report Count (8)
        0x81.toByte(), 0x02,            //   Input (Data, Variable, Absolute) - modifiers
        0x95.toByte(), 0x01,            //   Report Count (1)
        0x75, 0x08,                     //   Report Size (8)
        0x81.toByte(), 0x01,            //   Input (Constant) - reserved byte
        0x95.toByte(), 0x05,            //   Report Count (5)
        0x75, 0x01,                     //   Report Size (1)
        0x05, 0x08,                     //   Usage Page (LEDs)
        0x19, 0x01,                     //   Usage Minimum (Num Lock)
        0x29, 0x05,                     //   Usage Maximum (Kana)
        0x91.toByte(), 0x02,            //   Output (Data, Variable, Absolute) - LEDs
        0x95.toByte(), 0x01,            //   Report Count (1)
        0x75, 0x03,                     //   Report Size (3)
        0x91.toByte(), 0x01,            //   Output (Constant) - LED padding
        0x95.toByte(), 0x06,            //   Report Count (6)
        0x75, 0x08,                     //   Report Size (8)
        0x15, 0x00,                     //   Logical Minimum (0)
        0x25, 0x65,                     //   Logical Maximum (101)
        0x05, 0x07,                     //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,                     //   Usage Minimum (0)
        0x29, 0x65,                     //   Usage Maximum (101)
        0x81.toByte(), 0x00,            //   Input (Data, Array) - key array
        0xC0.toByte(),                  // End Collection
    )
}

/** Modifier bits for byte 0 of the keyboard input report. */
object Modifier {
    const val NONE = 0x00
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT = 0x40
    const val RIGHT_GUI = 0x80
}

/** Selected usages from the HID Keyboard/Keypad usage page (0x07). */
object KeyUsage {
    const val A = 0x04
    const val DIGIT_1 = 0x1E
    const val DIGIT_0 = 0x27
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
}

/** One key press: a usage plus the modifiers that must be held while it is pressed. */
data class KeyStroke(val modifiers: Int, val usage: Int) {
    fun pressReport(): ByteArray = KeyboardReport.press(modifiers, usage)
}

object KeyboardReport {
    fun press(modifiers: Int, usage: Int): ByteArray {
        val report = ByteArray(HidKeyboardDescriptor.INPUT_REPORT_SIZE)
        report[0] = modifiers.toByte()
        report[2] = usage.toByte()
        return report
    }

    /** All keys and modifiers released. */
    fun release(): ByteArray = ByteArray(HidKeyboardDescriptor.INPUT_REPORT_SIZE)
}
