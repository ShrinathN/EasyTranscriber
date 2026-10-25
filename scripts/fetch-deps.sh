#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android library (Apache-2.0) and verifies its checksum.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=2.0.0
SHA256=f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc
OUT=app/libs/sherpa-onnx-$VERSION.aar
mkdir -p app/libs
if [ ! -f "$OUT" ]; then
  curl -fL -o "$OUT" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
fi
echo "$SHA256  $OUT" | sha256sum -c -
