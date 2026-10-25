# Stage 1 — Feasibility Report

Date: 2026-10-25. Status: desk research complete. HID verified on hardware (moto g45 5G → Linux), see `HID_TEST_REPORT.md`.

## 1. Bluetooth HID keyboard role

### API availability
- `android.bluetooth.BluetoothHidDevice` (profile `BluetoothProfile.HID_DEVICE`) was added in
  **API 28 (Android 9)**. Earlier versions have no public API for the phone to act as a HID
  device over Bluetooth Classic. **minSdk = 28.**
- Flow: `getProfileProxy(HID_DEVICE)` → `registerApp(sdpSettings, inQos, outQos, executor, callback)`
  → host connects (or `connect(device)` toward a paired host) → `sendReport(device, id, bytes)`.
- **Only one app per phone can hold the HID Device registration.** Other keyboard/mouse
  emulator apps will make `registerApp` fail or later unregister us; the app reports this.

### Permissions
| Android | Required |
|---|---|
| 9–11 (API 28–30) | `BLUETOOTH`, `BLUETOOTH_ADMIN` (install-time) |
| 12+ (API 31+) | `BLUETOOTH_CONNECT` (runtime) for register/connect/sendReport/device names; `BLUETOOTH_ADVERTISE` (runtime) to request discoverability |

Discovery/scanning is not needed: the host scans for the phone, or the phone connects to an
already-paired host. So no location or `BLUETOOTH_SCAN` permission is requested.

### Device and OEM risk (the main unresolved risk)
- The HID Device role is implemented in AOSP, but OEMs can disable it in their Bluetooth
  configuration. When it's disabled, `getProfileProxy` either returns false or the proxy never
  connects. The app treats both as "unsupported" or "registration failed" with an explanation,
  and uses a 5 s timeout so a missing proxy can't hang forever.
- Community reports say Pixel, Samsung (One UI) and most AOSP-based ROMs support the role, and
  some older or heavily customized OEM builds don't. **This is unverified for our test device
  until Stage 2 runs.**
- BLE-peripheral support (HID-over-GATT) is a different mechanism. We don't rely on it, and BLE
  peripheral capability doesn't imply that `BluetoothHidDevice` works.
- Android doesn't change the phone's Class of Device to "keyboard". Most hosts (Linux/BlueZ,
  Windows, macOS) find the HID service through SDP anyway. Some embedded hosts (TVs, consoles)
  may filter by CoD. **Untested.**
- The connection is lost if the process dies or Bluetooth toggles. Stage 4 moves the HID manager
  into a foreground service of type `connectedDevice` (plus `microphone` while dictating).

### Text entry constraints
- A HID keyboard sends **key positions, not characters**. The host maps positions to characters
  using its own layout. The phone must encode text for the layout configured on the host.
  Implemented: **US QWERTY**, which covers printable ASCII, space, tab and Enter.
- Arbitrary Unicode can't be sent in ordinary keyboard reports. Text with unsupported characters
  is **rejected and itemized, never substituted**.
- **Accented Latin** (é, ñ…): possible with a matching host layout (e.g. US-International dead
  keys, or a French/Spanish layout table). Each needs its own layout table and host
  configuration. Not implemented yet.
- **Hindi (Devanagari)**: possible through HID only if the host uses the **Hindi InScript** layout,
  which is standard on Windows, Linux and macOS. In that case the phone maps each Devanagari code
  point to its InScript key. Conjuncts are code-point sequences, so they type correctly. This is
  feasible as an additional layout table but needs per-host verification. OS-specific
  Unicode-entry tricks are host-dependent and fragile, and would be a separate, opt-in fallback:
  Linux `Ctrl+Shift+U`+hex (GTK/IBus only), Windows `Alt+X` (Word only), and macOS "Unicode Hex
  Input" (layout switch required).

## 2. Offline recognition engines

| Candidate | Modes | Android integration | License (code) | Notes |
|---|---|---|---|---|
| **sherpa-onnx** (k2-fsa), v2.0.0 | Streaming (Zipformer/Paraformer transducers, CTC) **and** offline (Whisper, SenseVoice, Moonshine, Paraformer); built-in Silero VAD | Prebuilt `sherpa-onnx-2.0.0.aar` on GitHub releases (verified to exist); Kotlin API; no NDK build needed | Apache-2.0 | One runtime can cover both modes. Model licenses vary per model and must be recorded per model. |
| **whisper.cpp** v1.9.5 | Batch only (30 s windows; long audio needs chunking) | JNI build via NDK/CMake (NDK not installed here; installable) | MIT; Whisper weights MIT | Good batch quality and multilingual, including Hindi. Not a genuine streaming engine. |
| **Vosk** (Kaldi) | Streaming | Maven AAR `com.alphacephei:vosk-android` | Apache-2.0 | Small models, including a **Hindi small model**. Older architecture, so accuracy is probably lower than Zipformer. Candidate for Hindi streaming. |
| Android on-device `SpeechRecognizer` (API 31+) | Streaming partials | Platform API | n/a | Offline only if the OEM provides an on-device model. Not swappable or measurable in the same way. Possible optional engine; not core. |

### Initial selection (to be validated in Stages 3 and 5)
- **Real-Time Dictation:** sherpa-onnx streaming Zipformer transducer (English first). It's a
  genuine incremental decoder that keeps state across frames and has endpoint detection.
- **Record, Then Transcribe:** sherpa-onnx offline Whisper (tiny/base, int8), with VAD
  segmentation for recordings over 30 s. whisper.cpp stays the alternative adapter if
  quality or speed measurements favor it.
- **Hindi:** whether a sherpa-onnx streaming model exists is **unverified**. Fallbacks: Vosk Hindi
  for streaming, Whisper for batch. Hindi output also needs the InScript layout table (§1).

Model sizes, RAM, real-time factor and WER will be **measured on the test device** and are
deliberately not quoted here.

## 3. Required hardware
1. **Android phone, Android 9+**, with the HID Device role enabled. It must be connected over
   USB with developer options and USB debugging on, so we can install APKs and collect logcat.
   Ideally two phones (low-end and high-end) for performance work.
2. **Host:** a Linux desktop PC (BlueZ) is available now.
   A Windows or macOS host is recommended for a second data point.

## 4. Unresolved risks
| Risk | Impact | Mitigation |
|---|---|---|
| OEM disables HID Device role by default | **Confirmed on Motorola**: needs a Developer-options toggle plus a Bluetooth restart; `getprop` stays `false` even after enabling | Detect via proxy timeout; in-app instructions; BLE HOGP transport as a possible fallback |
| `sendReport` success ≠ host receipt | **Confirmed**: 8 keystrokes lost on disconnect | Stage 4: uncertain-tail policy, never auto-resend |
| Other app holds HID registration | Registration fails | Detected; user instructions |
| Host drops reports at high rate | Lost or duplicated keys | 10 ms inter-report delay, measured and tunable |
| Partial delivery on disconnect | Ambiguous host state | Sender reports exact completed count plus an "ambiguous" flag; Stage 4 queue never auto-retries an ambiguous key |
| Streaming model quality/latency on low-end phones | Misses 200–500 ms target | Measure; int8 models; smaller chunk size |
| Hindi streaming model availability | AC-11 scope | Document; Vosk fallback |
