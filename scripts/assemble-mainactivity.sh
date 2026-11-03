#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PARTS="$ROOT/app/src/main/java/com/jarvis/mobile/mainactivity_parts"
OUT="$ROOT/app/src/main/java/com/jarvis/mobile/MainActivity.kt"
cat "$PARTS"/MainActivity.part*.txt > "$OUT"
echo "Assembled $OUT ($(wc -c < "$OUT") bytes)"
grep -E "requestMicFocus|clientRequestId|registerConnectivityFlush|HolographicOrb|JarvisChatComposer|JarvisUserBubble" "$OUT" | head
