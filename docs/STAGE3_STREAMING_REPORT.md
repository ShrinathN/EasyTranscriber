# Stage 3 — Streaming Recognition Proof of Concept

Date: 2026-10-25. Device: Motorola moto g45 5G (Snapdragon SM6375: 2× Cortex-A78 @ 2.3 GHz + 6× A55
@ 2.0 GHz, 7.3 GB RAM), Android 15. Engine: sherpa-onnx 2.0.0 (prebuilt AAR, CPU provider, int8 models).

## What was built
| Component | File |
|---|---|
| Engine interface (`RecognitionEngine`, `StreamingSession`, `StreamingUpdate`) | `recognition/RecognitionEngine.kt` |
| Model catalog + install check | `recognition/ModelCatalog.kt` |
| sherpa-onnx streaming transducer adapter | `recognition/SherpaOnnxStreamingEngine.kt` |
| Microphone capture (AudioRecord, 16 kHz, `VOICE_RECOGNITION`) | `audio/MicrophoneAudioSource.kt` |
| Real-time-paced WAV playback (**test input**) | `audio/WavFileAudioSource.kt`, `audio/WavReader.kt` |
| Capture → bounded queue → inference thread, metrics | `pipeline/StreamingRecognitionPipeline.kt`, `pipeline/StreamingMetrics.kt` |
| WER (English, word-level) | `recognition/WordErrorRate.kt` |
| CPU/PSS sampling | `diagnostics/AndroidResourceSampler.kt` |
| Test screen + per-session JSON report | `ui/RecognitionTestScreen.kt`, `ui/RecognitionTestViewModel.kt` |

Design points:
- **Genuine streaming:** 50 ms frames go to one `OnlineStream` whose encoder cache and decoder
  state persist across frames. sherpa-onnx decodes each chunk once, as soon as it has enough
  frames. Nothing re-transcribes growing audio.
- **Partial vs final:** the session reports the active segment's *partial* hypothesis, plus a
  *finalized* segment when sherpa-onnx's endpoint rules fire (1.2 s trailing silence after speech,
  or a 20 s maximum segment). Committing text to the keyboard is Stage 4.
- **Backpressure:** capture never blocks on inference. The queue holds at most 2 s (40 frames).
  On overflow a frame is dropped and counted, and the count is shown and reported.
- **Model files** live in the app's external files folder (`Android/data/com.easytranscriber/files/models/<id>/`),
  installed with `scripts/install-models.sh` over adb. The app does no downloading.

## Measurement definitions
| Metric | Definition |
|---|---|
| Model load | Wall time of `OnlineRecognizer` construction (file read + ONNX session init) |
| Frame lag | `System.nanoTime()` after `accept()` returns − frame end time. Frame end time is the scheduled real-time instant for playback, or when `AudioRecord.read` returned for the mic (excludes hardware input latency, typically tens of ms) |
| **Token emission latency** | Wall time when a token first appears in the hypothesis − wall time at which that audio was spoken (stream start + the model's token timestamp). Includes chunking, lookahead, queueing and decode. **Excludes** HID typing and host display (Stage 4) |
| RTF | Total decode time / audio duration (decode work only) |
| Revisions | Changes to already-displayed words other than the last word of the previous partial |
| CPU | Process utime+stime / wall time; 100% = one core fully busy |
| PSS | `Debug.getPss()` every 2 s |

Assumption check: token timestamps are treated as relative to the segment start (from reading the
sherpa-onnx result conversion code). If that were wrong, latencies in segments after the first
would be off by seconds and could be negative. Across all runs the minimum is +52 ms or more,
which is consistent with the assumption.

## Results
Input "test" = `librispeech-2`: two LibriSpeech utterances (6.6 s + 16.7 s, 66 words) played at
real-time pace with 0.5 s leading and 1 s inter-file silence (25.9 s total).

| Run | Model | Thr | Load ms | Input | Audio s | RTF | Tok min | Tok p50 | Tok p90 | Tok max | Lag p90 | Drop | WER % | Peak PSS / CPU (1 core=100%) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | nemo-fc-en-80ms-int8 | 2 | 2198 | mic | 135.9 | 0.329 | - | - | - | - | 53 | 0 | - | 315 MB / 114% |
| 2 | nemo-fc-en-80ms-int8 | 2 | 2145 | test | 25.9 | 0.335 | - | 208 | 337 | 368 | 55 | 0 | 1.5 | 313 MB / 130% |
| 3 | zipformer-en-2023-06-26 | 2 | 4566 | test | 25.9 | 0.122 | 52 | 252 | 471 | 540 | 40 | 0 | 0.0 | 233 MB / 85% |
| 4 | nemo-fc-en-80ms-int8 | 1 | 2097 | test | 25.9 | 0.396 | 112 | 215 | 350 | 378 | 65 | 0 | 1.5 | 319 MB / 73% |
| 5 | nemo-fc-en-80ms-int8 | 2 | 2072 | test | 25.9 | 0.326 | 102 | 209 | 338 | 364 | 54 | 0 | 1.5 | 313 MB / 116% |
| 6 | nemo-fc-en-80ms-int8 | 4 | 2075 | test | 25.9 | 0.555 | 105 | 249 | 389 | 422 | 110 | 0 | 1.5 | 323 MB / 333% |
| 7 | nemo-fc-en-480ms-int8 | 2 | 2074 | test | 25.9 | 0.122 | 109 | 438 | 664 | 769 | 23 | 0 | 1.5 | 314 MB / 70% |
| 8 | nemo-fc-en-480ms-int8 | 4 | 2074 | test | 25.9 | 0.221 | 121 | 510 | 703 | 843 | 94 | 0 | 1.5 | 314 MB / 155% |
| 9 | zipformer-en-2023-06-26 | 4 | 4449 | test | 25.9 | 0.263 | 63 | 307 | 515 | 598 | 94 | 0 | 0.0 | 244 MB / 189% |

(Run 1 is a 136 s *live-microphone* session in a quiet room, with no speech, so it has no token latency. "Tok" = token emission latency in ms; "Lag p90" = frame lag p90 in ms.)

### Observations
- **Latency target (200–500 ms) is met at the recognition layer** by NeMo 80 ms with 1–2 threads:
  p50 ≈ 210 ms, p90 ≈ 340 ms, max 378 ms. HID typing adds ~30 ms per character (Stage 2).
  End-to-end visible-on-host latency will be measured in Stage 4.
- **2 threads is the best setting for latency; 1 thread is the most efficient.** 4 threads is *worse*
  for every model (RTF 0.33 → 0.56 for NeMo 80 ms), most likely because the extra threads land on
  the slower A55 cores and add synchronization cost.
- **The 480 ms lookahead variant roughly doubles latency** (p50 ≈ 440 ms) at the same WER on this
  set. It isn't worth it for dictation.
- **Zipformer's 0% WER is not comparable:** it was trained on LibriSpeech, so this test set is
  in-domain for it. It also has higher latency (p50 252 ms) and a 4.5 s load time.
- **No revisions** of earlier words occurred. Greedy transducer decoding only appended on this
  input. Revision behavior on spontaneous speech still has to be measured (live mic test).
- **Silence:** a 136 s microphone session in a quiet room produced no text (no hallucinated words).
- **Memory:** about 315 MB PSS with NeMo loaded, 235–245 MB with Zipformer.

## Offline / privacy
- The APK requests no `INTERNET` permission. Requested permissions: `BLUETOOTH_CONNECT`,
  `BLUETOOTH_ADVERTISE`, `RECORD_AUDIO` (and the system-generated receiver permission). Without
  `INTERNET`, Android doesn't let the app open sockets.
- `dumpsys netstats detail` has no traffic history buckets for the app's UID (10368).
- Not yet done: a run with Wi-Fi and mobile data disabled (Stage 7 offline acceptance test). Since
  the app can't open sockets, the result can't depend on connectivity, but the test is still required.

## 10-minute continuous soak (AC-13 / AC-14 pre-check)
NeMo 80 ms, 2 threads. Test input `soak-10min`: the two LibriSpeech utterances repeated 24 times
(48 utterances, 1 s gaps), played at real-time pace for **608.7 s in one uninterrupted session**.

| Metric | Result |
|---|---|
| Duration / wall time | 608.7 s audio / 609.2 s wall: no restart, no stall |
| Frames | 12 174 received, **0 dropped**, max queue depth 2 |
| Segments | 48 finalized; all 24 repetitions of each utterance produced **identical** text |
| WER | 1.5% (24 subs / 1584 words; each one is "dishonoured" → "dishonored") |
| Token emission latency | min 90, p50 **191**, p90 234, p99 272, max 365 ms |
| Frame lag | p50 4, p90 54, max 101 ms |
| RTF / CPU | 0.326 / 114% of one core |
| PSS | 303 MB at start, then a plateau; **mean per quarter of the run: 373, 375, 378, 376 MB**; max 423 MB |

PSS rises in the first quarter and then stays flat, with **no growth trend over 10 minutes**. Part
of the early rise is the test source itself: it holds the whole 10-minute timeline in memory (≈39 MB of
float samples). The microphone source doesn't. Latency didn't degrade. The p50 (191 ms) is
slightly lower than the short runs (209 ms), probably because the CPU frequency had ramped up.

Limitation: this verifies pipeline and recognizer-state stability over 10 minutes. It does not
verify long acoustic sessions through the microphone (a live 10-minute dictation is still needed for AC-13).

## Known limitations / not yet tested
- **Accuracy on real dictation is untested.** The only quality data is 66 words of read
  audiobook speech. A live-microphone test with a known script is next.
- NeMo and Zipformer output has no punctuation or capitalization (NeMo is lowercase, Zipformer
  uppercase). Punctuation and casing are a Stage 4 text-normalization decision
  (sherpa-onnx `OnlinePunctuation` is available).
- Only English streaming models exist in sherpa-onnx's release. **No Hindi streaming model was found.**
- No foreground service yet, so recognition stops if the app process is killed in the background (Stage 4).
- Model installation is adb-only (developer workflow). A user-facing import is Stage 6.
