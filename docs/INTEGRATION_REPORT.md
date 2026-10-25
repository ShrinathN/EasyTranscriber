# Stages 4–6 — Integration, Record-Then-Transcribe, UI and Settings

Date: 2026-10-26. Phone: Motorola moto g45 5G, Android 15 (HID Device role enabled in Developer
options). Host: Linux Mint 22.1 desktop, BlueZ 5.72, US layout. Raw per-session measurement files
were used during development and are not included in the repository; the results are summarized here.

## What was built
| Area | Files |
|---|---|
| Commit policy (partial → committed) | `transcript/StreamingTranscriptManager.kt` |
| Text formatting (spacing, spoken punctuation, capitalization) | `transcript/TextFormatter.kt` |
| Shared keyboard output queue (both modes) | `output/DeliveryQueue.kt` |
| HID typing with rollover | `hid/KeystrokeSender.kt` |
| Batch engine (Whisper, Moonshine) | `recognition/SherpaOnnxOfflineEngine.kt` |
| Recording to disk, VAD segmentation, batch transcription | `audio/AudioRecorder.kt`, `audio/WavWriter.kt`, `batch/BatchTranscriber.kt`, `batch/SileroSegmenter.kt` |
| Session state machine, engine lifecycle | `dictation/DictationController.kt` |
| Foreground service (keyboard + microphone) | `dictation/DictationService.kt` |
| Persistent settings (DataStore) | `settings/SettingsRepository.kt` |
| UI: Dictate, Keyboard, Settings, Diagnostics | `ui/*.kt` |
| Measurement tools | `tools/host_capture.py`, `scripts/e2e_latency.py`, `scripts/clock_offset.sh` |

### State model
Each subsystem has one sealed state type, so the UI can't show contradictory combinations:
`HidConnectionState` (keyboard), `SessionState` (Idle / Preparing / Streaming / Recording /
Transcribing / Review / Failed), `EngineState` (NotInstalled / Loading / Ready / Failed) and
`DeliveryState` (Idle / Sending / WaitingForHost / WaitingToResume / Blocked).

### Real-Time Dictation: what gets typed and when
- Every word except the last is committed when a later word appears.
- The last word is committed when it has been unchanged for the **commit delay** (Settings:
  Fast 120 ms, the default; Balanced 300 ms; Cautious = wait for the next word or the endpoint).
  One-letter words and spoken-command words are never committed early.
- If the recognizer later **extends** the most recent committed word ("un" → "unbelievable"), only
  the suffix is typed, so the result is still correct.
- Any other change to committed text is **not applied**. It is counted and reported
  ("N later recognizer corrections were not applied"). Nothing is erased with backspace.
- Spoken punctuation (optional): "comma", "period"/"full stop", "question mark", "exclamation
  mark/point", "colon", "semicolon", "new line", "new paragraph". Two-word commands hold their first
  word until the next word arrives. No other punctuation is invented.

### Keyboard output (shared by both modes)
- Text is queued in order and sent in 8-character chunks.
- **HID rollover:** a different key with the same modifiers replaces the previous key in one report.
  Explicit releases are sent for repeated keys, modifier changes and at the end.
- **Disconnect policy:** anything submitted within 1.5 s before a disconnect is marked
  *may not have arrived* (Stage 2 showed `sendReport()` success ≠ host receipt). After any disconnect
  or reconnect, delivery waits for the user to choose **Resend** (risking duplicates), **Skip**
  (risking loss) or **Clear all**. Nothing is resent automatically.
- Text queued while no device is connected is held. It is only sent after connecting **and**
  confirming, so it can't be typed into a device the user didn't expect.
- Characters the US layout can't type block delivery at that point and are listed.
  The user removes them or clears; they are never substituted.

### Record, Then Transcribe
- Audio is streamed to a temporary WAV file in the app cache, so memory doesn't depend on recording
  length. Before recording, the app checks free storage against the maximum length (2/5/10/20 min).
- After stopping, the file is read in 0.5 s chunks. Silero VAD splits it into speech segments of at
  most 28 s (Whisper's window is 30 s; a forced cut can split a word). Each segment is transcribed
  as soon as it's complete, with progress shown.
- The model loads while the user is still speaking.
- Review (default on): the transcript can be edited, then sent or discarded. Transcripts with
  untypeable characters always go to review.
- **Retention:** the recording is deleted as soon as transcription succeeds, fails or is cancelled.
  Any leftovers are deleted at app start. Cancelling a recording discards it without transcribing.

### Background operation
`DictationService` runs in the foreground with type `connectedDevice` while the keyboard is
registered, and adds `microphone` while listening or recording. Its notification has a Stop action.

## Results on hardware

### End-to-end latency: spoken word → visible on the host (Real-Time Dictation)
Method:
- Test playback `librispeech-2` (2 utterances, 66 words) goes through the full pipeline.
- Host arrival times come from `tools/host_capture.py`. The phone/host clock offset is measured
  over adb (±18 ms).
- "Spoken" = audio time of the word's last recognizer token.
- Analysis: `scripts/e2e_latency.py`.

| Commit delay | Typing speed | Spoken→commit p50 | Commit→host p50 | **Spoken→host p50 / p90 / max** | Host text = committed text | Revisions |
|---|---|---|---|---|---|---|
| (initial: next-word only) | Normal, no rollover | 452 | 189 | 646 / 876 / 1752 ms | yes | 0 |
| Balanced (300 ms) | Normal + rollover | 454 | 106 | 578 / 748 / 942 ms | yes | 0 |
| Cautious | Normal + rollover | 451 | 126 | 599 / 852 / 1634 ms | yes | 0 |
| **Fast (120 ms, default)** | Normal + rollover | 374 | 131 | **523 / 636 / 739 ms** | yes | 0 (3 words typed in two parts) |
| Fast | Fast + rollover | 375 | 111 | **500 / 635 / 775 ms** | yes | 0 (3 extensions) |

- The 200–500 ms target is met at the median only with Fast commit **and** Fast typing on this
  phone/host. With the defaults (Normal typing) the median is 523 ms.
- Remaining latency: about 225 ms model emission (80 ms lookahead plus chunking), 120 ms
  commit-stability wait, and about 100–130 ms typing and Bluetooth.
- **AC-07:** text appears on the host while the sentence is still being spoken (first words about
  0.4–0.5 s after they're spoken; the utterances last 6.6 s and 16.7 s).
- **AC-08:** in all 5 runs (330 words) the host received exactly the committed text. 0 duplicated or
  corrupted words, 0 post-commit revisions. 6 words were typed in two parts by the extension
  mechanism, and the final text was still correct.

### Typing correctness and speed
| Test | Result |
|---|---|
| All printable ASCII + Enter, rollover, Normal speed | exact; 15.5 ms/char on host (was 29.5 without rollover) |
| Same, Fast speed (5 ms between reports), 3 runs | 3/3 exact; 19.4, 10.3, 9.6 ms/char |

Fast typing has only been verified on this Linux host, so the default stays Normal.

### Record, Then Transcribe (same 25.8 s test audio, 2 threads, review on)
| Model | Transcribe time | RTF | Model load | PSS after | Output |
|---|---|---|---|---|---|
| Moonshine base (English) | 3.3 s | 0.13 | 1.3 s | 424 MB | punctuated, correct (British "dishonoured") |
| Whisper tiny | 4.6 s | 0.18 | 0.7 s | 457 MB | punctuated, correct |
| Whisper base | 8.7 s | 0.34 | 1.1 s | 621 MB | punctuated, correct; language detected "en" |

- The Moonshine transcript was sent after review and arrived on the host exactly, preceded by the
  intended single space (the "space between dictations" setting).
- Not run: long-recording memory test (dropped at the user's request), cancellation on hardware
  (unit-tested only), and Hindi audio.

### Disconnect / reconnect during dictation (AC-12)
1. Host disconnected the phone about 7 s into live dictation.
2. The banner read "connection lost. These characters may or may not have arrived:
   ' squalid quarter of the'". 101, then 258, characters were queued as dictation continued.
3. Reconnected from the phone, then chose **Resend**.
4. Result: all committed text reached the host, nothing was lost. The uncertain text had actually
   arrived, so Resend typed it twice. **Skip** would have given exact text. The user needs to look
   at the host before choosing; the banner says so.

An earlier attempt showed the opposite case. The host had received only
" there the squalid" of the uncertain " there the squalid quarter of". So " quarter of" was accepted
by the phone's Bluetooth stack but never arrived. That's why neither choice can be automatic.

### Background and persistence
| Test | Result |
|---|---|
| Home pressed during live dictation (test playback) | service foreground, types `0x90` (microphone + connectedDevice); dictation completed, host text = committed text |
| Home pressed during live dictation (real microphone) | audio service still reports the recording as "not silenced" 10 s after Home; 16.25 s captured, 0 dropped frames |
| Mode + model survive force-stop and relaunch (AC-05) | "Record first" still checked; Whisper tiny still selected |

### Unit tests
57 tests, all passing (`./gradlew testDebugUnitTest`). They cover:
- formatter: commands, capitalization, spacing across segments and sessions
- commit policy: duplicates, revisions, extensions, early commit rules
- delivery queue: ordering, disconnect uncertainty, resend/skip, unsupported characters, clear
- keystroke sender: rollover and failure accounting
- WAV writer/reader round trip
- batch transcriber: ordering, no-speech, cancellation
- pipeline wiring

## Incidents during testing (all caused by the test harness, not the app)
1. **Wrong device connected.** A UI-automation fallback tapped the first "Connect" in the
   paired-device list and connected the keyboard to another paired phone for 28 s.
   Nothing was queued, so nothing was typed. Changes made: the harness now taps only the Connect
   button on the named row; the app labels device types (computer / phone / other) and lists
   computers first.
2. **About 270 characters typed into a browser window.** In the first reconnect test, the host
   capture window closed on its 25 s idle timeout during the reconnect gap, and the resent text went
   to whichever desktop window had focus (a web browser). Changes made: the capture tool can run with no idle timeout
   (`--idle 0 --stop-file`), and every tap that can cause typing is guarded by a check that the
   capture window is the active desktop window.
3. **Host `bluetoothd` crashed (SIGSEGV)** once, on BlueZ 5.72, during a messy reconnect sequence
   (aborted LE connection attempts, a hung CONNECTING state, and a trust change). systemd restarted
   it and it hasn't recurred. Related host behavior:
   - `bluetoothctl connect` from the host sometimes tries LE and fails
     (`le-connection-abort-by-local`); connecting from the phone works.
   - The phone must be **trusted** on the host (`bluetoothctl trust`), otherwise incoming HID
     connections hang in CONNECTING.

## Acceptance criteria status
| ID | Status | Evidence / note |
|---|---|---|
| AC-01 Build | ✅ | `./gradlew assembleDebug` |
| AC-02 Bluetooth keyboard | ✅ moto g45 → Linux | HID_TEST_REPORT.md; needs the Developer-options toggle on Motorola |
| AC-03 Offline recognition | ⚠️ partial | no INTERNET permission; no traffic for the app's UID. The formal Wi-Fi/data-off run hasn't been done |
| AC-04 Mode selection | ✅ | Dictate toggle and Settings |
| AC-05 Persistence | ✅ | force-stop/relaunch test |
| AC-06 Genuine streaming | ✅ | Stage 3: per-frame decoding with persistent state |
| AC-07 Early text | ✅ | words visible about 0.5 s after being spoken, mid-sentence |
| AC-08 Transcript correctness | ✅ in tested scenarios | 330/330 words exact over 5 runs (read speech). Spontaneous speech: no revision data on host |
| AC-09 Batch transcription | ✅ | 3 models on hardware |
| AC-10 Shared output | ✅ | both modes enqueue into `DeliveryQueue` |
| AC-11 Language transparency | ✅ in UI | streaming is English only; Hindi only via Whisper, can't be typed with US layout (shown in Settings and review); Hindi accuracy not evaluated |
| AC-12 Recovery | ✅ Bluetooth | committed text never silently lost; uncertain text surfaced. Microphone-error recovery is code-path only (not induced on hardware) |
| AC-13 10-minute live dictation | ⚠️ | 10-minute pipeline soak passed in Stage 3 (test playback); a live 10-minute run with typing was dropped at the user's request |
| AC-14 Resource handling | ⚠️ | Stage 3 soak: flat PSS over 10 min. Long-recording memory test dropped at the user's request (the design streams to disk) |
| AC-15 Privacy | ✅ | no INTERNET permission; recordings deleted after transcription |
| AC-16 Evidence | ✅ | this report and the other reports in `docs/` |

## Known limitations
- US English QWERTY output only. Accented and non-Latin characters are blocked, never substituted.
- Live typing has no automatic punctuation (spoken commands only). Record-first output is punctuated.
- One phone and one host tested. Windows/macOS/ChromeOS/iPad hosts are untested.
- Motorola needs a Developer-options toggle plus a Bluetooth restart for the HID role.
- Models are installed over adb (`scripts/install-models.sh`); there's no in-app import.
- Debug APK is about 110 MB (native libraries for two ABIs plus unshrunk Compose icons). A release
  build with R8 would be much smaller; not measured.
