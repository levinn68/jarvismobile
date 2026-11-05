#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f agent.js.b64.part0 && -f agent.js.b64.part1 && -f agent.js.b64.part2 ]]; then
  TMP_FILE="$(mktemp)"
  cat agent.js.b64.part0 agent.js.b64.part1 agent.js.b64.part2 | base64 -d > "$TMP_FILE"
  if [[ $(wc -c < "$TMP_FILE") -gt 5000 ]]; then
    mv "$TMP_FILE" src/agent.js
    echo "Reconstructed $(wc -c < src/agent.js) bytes from base64 parts"
  else
    rm -f "$TMP_FILE"
    echo "Warning: base64 parts are incomplete placeholders; keeping canonical src/agent.js"
  fi
elif [[ -f ../unix/src/agent.js ]]; then
  cp ../unix/src/agent.js src/agent.js
  echo "Synchronized src/agent.js ($(wc -c < src/agent.js) bytes) from ../unix/src/agent.js"
elif [[ -f src/agent.js ]]; then
  echo "Using existing src/agent.js ($(wc -c < src/agent.js) bytes)"
else
  echo "Error: src/agent.js and ../unix/src/agent.js are both missing" >&2
  exit 1
fi

node --check src/agent.js
node --test test/*.js