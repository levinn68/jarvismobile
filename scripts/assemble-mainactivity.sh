#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PARTS="$ROOT/app/src/main/java/com/jarvis/mobile/mainactivity_parts"
OUT="$ROOT/app/src/main/java/com/jarvis/mobile/MainActivity.kt"

shopt -s nullglob
TXT_PARTS=("$PARTS"/MainActivity.part*.txt)
if [[ ${#TXT_PARTS[@]} -ge 2 ]]; then
  TMP_OUT="$(mktemp)"
  cat "${TXT_PARTS[@]}" > "$TMP_OUT"
  if [[ $(wc -c < "$TMP_OUT") -ge 150000 ]]; then
    mv "$TMP_OUT" "$OUT"
    echo "Assembled $OUT ($(wc -c < "$OUT") bytes) from ${#TXT_PARTS[@]} text parts"
  else
    rm -f "$TMP_OUT"
    echo "Warning: assembled text parts are incomplete (<150 KB); preserving existing $OUT"
  fi
else
  echo "Single partial MainActivity.part00.txt detected; preserving canonical $OUT ($(wc -c < "$OUT") bytes)"
fi

grep -E "requestMicFocus|clientRequestId|registerConnectivityFlush|HolographicOrb|JarvisChatComposer|JarvisUserBubble" "$OUT" | head -n 10
