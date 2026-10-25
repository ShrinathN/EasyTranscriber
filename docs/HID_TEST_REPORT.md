# Stage 2 — Bluetooth HID Proof of Concept: Physical Test Report

**Status: PASS on Motorola moto g45 5G → Linux desktop (2026-10-25), after enabling the HID
Device role in Developer options.** AC-02 is met for this phone/host pair.

### Out-of-the-box result: blocked
- Stock configuration: `bluetooth.profile.hid.device.enabled = false`. `dumpsys bluetooth_manager`
  listed `HidHostService` but no `HidDeviceService`.
- App result: `getProfileProxy(HID_DEVICE)` returned `true`, but `onServiceConnected` never fired
  (requested at 22:35:21.672, still nothing at 22:35:46). The app showed its timeout message.

### Unblocking
- The user enabled the HID device option in **Developer options** (exact menu label: to confirm).
- That alone changed nothing until **Bluetooth was restarted** (`cmd bluetooth_manager disable/enable`).
  After the restart, `HidDeviceService` appeared in `dumpsys`. **`getprop` still reports `false`**,
  so that property is not a reliable capability check; the app correctly relies on the proxy instead.
- **Implication:** Motorola users must enable a developer option and toggle Bluetooth. This must go
  into the README and the in-app error message (done in the message).

## Environment
| Item | Value |
|---|---|
| Android device / model | Motorola moto g45 5G |
| Android version / API / build | Android 15 / API 35 / V1UGS35H.75-14-9-3-1-4 |
| Host device | Linux desktop PC with a built-in Bluetooth adapter (BlueZ) |
| Host OS version | Linux Mint 22.1, BlueZ 5.72 |
| Host keyboard layout | us (`setxkbmap -query`) |
| App build | `0.1.0-hid-poc` debug |

## Procedure (Linux host)
1. Install: `./gradlew installDebug` with the phone on USB, then open EasyTranscriber.
2. Grant the Bluetooth permission, then tap **Start keyboard** and expect "Keyboard registered, no host connected".
3. Tap **Make discoverable** and accept the dialog.
4. On the host: `bluetoothctl` → `scan on` → wait for the phone → `scan off` →
   `pair <MAC>` (confirm the passkey on both sides) → `trust <MAC>` → `connect <MAC>`.
5. `bluetoothctl info <MAC>` should list UUID `Human Interface Device (00001124-…)`.
   The phone should show "Connected to <host name>".
6. Focus a text editor on the host. Tap **Type text**, then **Type all ASCII**.
7. Compare the output character by character with the expected text (see below).
8. Disconnect/reconnect: on the host, `disconnect <MAC>`, then `connect <MAC>`, then type again.
   Also test reconnecting from the phone with **Connect:** under Paired devices.
9. Partial-delivery test: start **Type all ASCII** and switch host Bluetooth off mid-way.
   Record the app's "Submitted N/M" message and the characters the host actually received.
10. Unsupported-character test: type `café — नमस्ते`. The app must refuse it and list é, — and
    each Devanagari code point.
11. Collect logs: `adb logcat -s EasyTranscriber` and `journalctl -u bluetooth --since -10min` on the host.

Expected "Type all ASCII" output (one line):
```
 !"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~
```

## Results (after enabling the role)
Host-side text was captured with `tools/host_capture.py` (an X11 window holding a global keyboard
grab, logging each character with a timestamp). Phone-side figures come from the app log.

| Check | Result |
|---|---|
| HID profile proxy obtained | PASS: 34 ms after request (22:40:42.507 → .541) |
| `registerApp` result | PASS: `true`, `onAppStatusChanged registered=true` |
| Host pairing | PASS: host-initiated `bluetoothctl pair`; numeric comparison 742681 confirmed on both sides |
| Host sees HID service | PASS: UUID 00001124 listed; kernel created input device "moto g45 5G" (uhid, `kbd leds`) |
| HID connection | PASS: phone shows "Connected to <host name>"; host sent LED output report (NumLock) |
| Default test string (69 chars) | PASS: exact match. Phone: 69/69 in 2011 ms (29.1 ms/char). Host span 1788 ms, inter-char 1.4–79 ms (bursty) |
| All printable ASCII + Enter (96 keys) | PASS: exact match. Phone: 96/96 in 2835 ms (29.5 ms/char) |
| Stuck modifiers / repeated chars | PASS: shifted/unshifted alternation exact; "ll" in "Hello" typed twice |
| Disconnect + reconnect (host-initiated) | PASS: phone state → Registered → Connected; reconnect in ~5 s incl. `bluetoothctl` |
| Reconnect (phone-initiated) | PASS: `connect()` → CONNECTED in 126 ms |
| Partial delivery (host disconnect at ~1.3 s) | See finding below |
| Unsupported characters rejected | Unit tests only (`TextEncoderTest`). Not exercised on device: the PoC can't inject non-ASCII into its own text field over adb |

### Finding: `sendReport()==true` does not mean the host received the key
In the interrupted test, the phone reported **51/96 keystrokes accepted** by the Bluetooth stack.
The host received **43**, an exact, uncorrupted prefix of the expected text with no duplicates.
The last 8 accepted keystrokes (~240 ms of typing) were lost in the local stack's buffers when the link
dropped. The HID interrupt channel gives no delivery acknowledgement to the app.

Consequence for Stage 4: on a disconnect, text submitted in roughly the last few hundred ms is
**uncertain**, not delivered. Re-sending it automatically risks duplicates, and not re-sending
risks loss. Planned policy: keep the uncertain tail in the queue, marked "may not have arrived",
and let the user choose to resend it or discard it after reconnecting. Never resend it automatically.

### Incidental observation
In test 1 the host also captured "em" ~1 s *before* the phone started typing. That came from a
local keyboard, not the phone (the phone logged exactly 69 keystrokes, and the 69 chars that
arrived after the tap match exactly). The capture tool cannot tell keyboards apart; tests must
be run without touching the host keyboard.

## Known limitations (by design, PoC)
- US QWERTY only; the host layout must be US.
- The HID registration lives in the app process. Stage 4 moves it into a foreground service.
- Delivery queueing and resume after disconnect are Stage 4. The PoC reports how far it got.
