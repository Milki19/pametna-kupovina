#!/usr/bin/env bash
# Na serveru (ops/start.sh ga zove): jednom preuzima model za značenje naziva
# (multilingual-e5-small, kvantizovan ONNX, ~120 MB, MIT licenca) u models/.
# Backend ga čita iz /models (compose.production.yaml). Bez modela sve radi,
# samo se predlozi „Isti proizvodi?“ boduju po slovima.
set -euo pipefail
dir="$(cd "$(dirname "$0")/.." && pwd -P)/models/multilingual-e5-small"
base=https://huggingface.co/Xenova/multilingual-e5-small/resolve/main
if [ -s "$dir/model_quantized.onnx" ] && [ -s "$dir/tokenizer.json" ]; then
    exit 0
fi
mkdir -p "$dir"
echo "Preuzimam model za značenje naziva u $dir"
curl -fsSL --retry 3 -o "$dir/model_quantized.onnx.part" "$base/onnx/model_quantized.onnx"
curl -fsSL --retry 3 -o "$dir/tokenizer.json.part" "$base/tokenizer.json"
mv "$dir/model_quantized.onnx.part" "$dir/model_quantized.onnx"
mv "$dir/tokenizer.json.part" "$dir/tokenizer.json"
