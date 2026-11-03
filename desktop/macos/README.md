# JARVIS Desktop Agent — macOS

Node.js agent for macOS. Pairing, heartbeat, file/app commands with path containment.

## Quick start

```bash
# Option A: use shared unix agent (already supports darwin/macos)
cd ../unix && npm start

# Option B: reconstruct full agent.js if src/agent.js is a stub
bash RECONSTRUCT_AGENT.sh
npm start
```

Pair:
```bash
npm run pair -- <PAIRING_CODE>
```

Credentials are stored under `~/.config/jarvis/device-credentials.json` (mode 0600).
