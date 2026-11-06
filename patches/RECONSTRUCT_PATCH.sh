#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
MAIN_KT="../app/src/main/java/com/jarvis/mobile/MainActivity.kt"
if ls 0663a96-MainActivity.patch.part* >/dev/null 2>&1; then
  cat 0663a96-MainActivity.patch.part0 0663a96-MainActivity.patch.part1 0663a96-MainActivity.patch.part2 > 0663a96-MainActivity.patch
  echo "Reconstructed patches/0663a96-MainActivity.patch ($(wc -c < 0663a96-MainActivity.patch) bytes)"
elif [[ -f "$MAIN_KT" ]]; then
  echo "MainActivity lifecycle patch is already integrated directly in $MAIN_KT ($(wc -c < "$MAIN_KT") bytes)."
else
  echo "Error: Neither patch parts nor $MAIN_KT found." >&2
  exit 1
fi