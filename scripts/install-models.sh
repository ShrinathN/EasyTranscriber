#!/usr/bin/env bash
# Downloads speech models on this computer (explicit, one-time) and copies the files the app
# needs to a USB-connected phone. The app itself has no network access.
#
# Usage: scripts/install-models.sh [model-id ...]
#   default: the recommended set (NeMo 80 ms streaming, Moonshine base, Silero VAD)
#   all:     every model in the app's catalog
#
# Open the app once before running this: Android only lets the app read files inside folders
# it created itself, and the app creates one folder per known model at startup.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.easytranscriber
DEST=/sdcard/Android/data/$PKG/files/models
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models

RECOMMENDED=(sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8
             sherpa-onnx-moonshine-base-en-quantized-2026-02-27 silero-vad)
ALL=(sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8
     sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8
     sherpa-onnx-streaming-zipformer-en-2023-06-26
     sherpa-onnx-moonshine-base-en-quantized-2026-02-27
     asr4all-s asr4all-m asr4all-l
     sherpa-onnx-whisper-base sherpa-onnx-whisper-tiny silero-vad)

# ASR4ALL is published as separate files on Hugging Face; revisions match the app's catalog.
asr4all_revision() {
  case "$1" in
    asr4all-s) echo 30cb61728e7a0e333422fc3c8902bf2410e7df01 ;;
    asr4all-m) echo 9b1506ca35ebfdc04be6b87868cc2f95eff10210 ;;
    asr4all-l) echo f9c5157d220fc3cfc71b97baf9970325ff7d4ae5 ;;
  esac
}

if [ $# -eq 0 ]; then MODELS=("${RECOMMENDED[@]}")
elif [ "$1" = all ]; then MODELS=("${ALL[@]}")
else MODELS=("$@"); fi

files_for() {
  case "$1" in
    sherpa-onnx-streaming-zipformer-en-2023-06-26)
      echo encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx \
           joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx tokens.txt ;;
    sherpa-onnx-moonshine-base-en-quantized-2026-02-27)
      echo encoder_model.ort decoder_model_merged.ort tokens.txt ;;
    sherpa-onnx-whisper-base) echo base-encoder.int8.onnx base-decoder.int8.onnx base-tokens.txt ;;
    sherpa-onnx-whisper-tiny) echo tiny-encoder.int8.onnx tiny-decoder.int8.onnx tiny-tokens.txt ;;
    silero-vad) echo silero_vad.onnx ;;
    asr4all-*) echo asr4all.onnx.data stream_c256r32.onnx pcec_c64.onnx pcec_c64_flush.onnx metadata.json ;;
    *) echo encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt ;;
  esac
}

mkdir -p models
for m in "${MODELS[@]}"; do
  if [ ! -d "models/$m" ]; then
    echo "Downloading $m"
    if [ "$m" = silero-vad ]; then
      mkdir -p models/silero-vad && curl -fL -o models/silero-vad/silero_vad.onnx "$BASE/silero_vad.onnx"
    elif [[ "$m" == asr4all-* ]]; then
      mkdir -p "models/$m.tmp"
      for f in $(files_for "$m"); do
        curl -fL -o "models/$m.tmp/$f" "https://huggingface.co/futo-org/$m/resolve/$(asr4all_revision "$m")/onnx/$f"
      done
      mv "models/$m.tmp" "models/$m"
    else
      curl -fL "$BASE/$m.tar.bz2" | tar xj -C models
    fi
  fi
  if ! adb shell "[ -d $DEST/$m ]"; then
    echo "Folder $DEST/$m does not exist. Open the app once (it creates model folders), then rerun." >&2
    exit 1
  fi
  (cd "models/$m" && adb push $(files_for "$m") "$DEST/$m/")
done
echo "Done. In the app: Settings -> Rescan models."
