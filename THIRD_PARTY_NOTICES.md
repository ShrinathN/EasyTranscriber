# Third-Party Notices

EasyTranscriber's own code is licensed under the MIT License (see `LICENSE`). The app also includes
or downloads the third-party components listed below, each governed by its own license. This file
is provided for attribution; it doesn't change any of these licenses.

## Software included in the app

### sherpa-onnx
- Project: https://github.com/k2-fsa/sherpa-onnx (Next-gen Kaldi / k2-fsa)
- Version: 2.0.0 (prebuilt Android library)
- License: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0

### ONNX Runtime (bundled in the sherpa-onnx Android library; Java API from `onnxruntime-android` 1.28.0)
- Project: https://github.com/microsoft/onnxruntime
- Copyright (c) Microsoft Corporation
- License: MIT License, https://github.com/microsoft/onnxruntime/blob/main/LICENSE

### Android Jetpack (AndroidX), Jetpack Compose, Material Components and Material Icons
- Project: https://developer.android.com/jetpack
- Copyright The Android Open Source Project
- License: Apache License 2.0

### Kotlin standard library and kotlinx.coroutines
- Project: https://kotlinlang.org, https://github.com/Kotlin/kotlinx.coroutines
- Copyright JetBrains s.r.o. and Kotlin Programming Language contributors
- License: Apache License 2.0

### Apache Commons Compress
- Project: https://commons.apache.org/proper/commons-compress/
- Version: 1.27.1
- Copyright The Apache Software Foundation
- License: Apache License 2.0

## Speech models (downloaded at the user's request; not distributed with the app)

### NVIDIA NeMo FastConformer streaming transducers (80 ms and 480 ms lookahead)
- Original model: `nvidia/stt_en_fastconformer_hybrid_large_streaming_multi`,
  https://huggingface.co/nvidia/stt_en_fastconformer_hybrid_large_streaming_multi
- Author: NVIDIA
- License: Creative Commons Attribution 4.0 International (CC-BY-4.0),
  https://creativecommons.org/licenses/by/4.0/
- Changes: converted to ONNX and quantized to int8 by the sherpa-onnx project
  (`sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8` and `-480ms-int8`).

### ASR4ALL small, medium and large (English)
- Models: https://huggingface.co/futo-org/asr4all-s, https://huggingface.co/futo-org/asr4all-m,
  https://huggingface.co/futo-org/asr4all-l (ONNX release, pinned revisions)
- Author: FUTO Holdings, Inc.
- License: FUTO Model Weights License (Restricted Use) 1.0, included with each model as `LICENSE`.
  The weights must not be used for surveillance, profiling, intelligence gathering, monitoring
  people in custody, or targeting protected activity; dictation and personal use are permitted.
  The models are used unmodified and are not distributed with this app.

### Moonshine base (English)
- Project: https://github.com/moonshine-ai/moonshine
- Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)
- License: MIT License (applies to the English-language models; models for other languages
  use a separate non-commercial license and are not offered by this app)
- Distributed via sherpa-onnx as `sherpa-onnx-moonshine-base-en-quantized-2026-02-27`.

### OpenAI Whisper (tiny, base)
- Project: https://github.com/openai/whisper
- Copyright (c) 2022 OpenAI
- License: MIT License
- Changes: exported to ONNX and quantized to int8 by the sherpa-onnx project.

### Silero VAD
- Project: https://github.com/snakers4/silero-vad
- Copyright (c) Silero Team
- License: MIT License

## Models available only in debug builds
- icefall streaming Zipformer (English, LibriSpeech), https://github.com/k2-fsa/icefall: Apache License 2.0
