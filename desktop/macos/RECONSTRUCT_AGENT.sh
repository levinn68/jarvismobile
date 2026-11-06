#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

sha256_file() {
  local target="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$target" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$target" | awk '{print $1}'
  else
    node -e '
      const fs = require("node:fs");
      const crypto = require("node:crypto");
      const data = fs.readFileSync(process.argv[1]);
      process.stdout.write(crypto.createHash("sha256").update(data).digest("hex") + "\n");
    ' "$target"
  fi
}

if [[ -f ../unix/src/agent.js && -f ../unix/src/credentials.js ]]; then
  cp ../unix/src/agent.js src/agent.js
  cp ../unix/src/credentials.js src/credentials.js

  UNIX_AGENT_SHA="$(sha256_file ../unix/src/agent.js)"
  MAC_AGENT_SHA="$(sha256_file src/agent.js)"
  if [[ "$UNIX_AGENT_SHA" != "$MAC_AGENT_SHA" ]]; then
    echo "Error: SHA-256 mismatch for src/agent.js ($MAC_AGENT_SHA != $UNIX_AGENT_SHA)" >&2
    exit 1
  fi

  UNIX_CRED_SHA="$(sha256_file ../unix/src/credentials.js)"
  MAC_CRED_SHA="$(sha256_file src/credentials.js)"
  if [[ "$UNIX_CRED_SHA" != "$MAC_CRED_SHA" ]]; then
    echo "Error: SHA-256 mismatch for src/credentials.js ($MAC_CRED_SHA != $UNIX_CRED_SHA)" >&2
    exit 1
  fi

  echo "Synchronized desktop/macos from canonical desktop/unix:"
  echo "  src/agent.js       ($(wc -c < src/agent.js) bytes, sha256=$MAC_AGENT_SHA)"
  echo "  src/credentials.js ($(wc -c < src/credentials.js) bytes, sha256=$MAC_CRED_SHA)"
elif [[ -f src/agent.js && -f src/credentials.js ]]; then
  MAC_AGENT_SHA="$(sha256_file src/agent.js)"
  MAC_CRED_SHA="$(sha256_file src/credentials.js)"
  echo "Verified existing desktop/macos source files:"
  echo "  src/agent.js       ($(wc -c < src/agent.js) bytes, sha256=$MAC_AGENT_SHA)"
  echo "  src/credentials.js ($(wc -c < src/credentials.js) bytes, sha256=$MAC_CRED_SHA)"
else
  echo "Error: canonical ../unix/src/* and local src/* are missing" >&2
  exit 1
fi

node --check src/agent.js
node --check src/credentials.js
node --test test/*.js