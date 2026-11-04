#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cat agent.js.b64.part0 agent.js.b64.part1 agent.js.b64.part2 | base64 -d > src/agent.js
echo "Reconstructed $(wc -c < src/agent.js) bytes"
node --test test/*.js
