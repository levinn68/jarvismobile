# JARVIS Desktop Agent — macOS

**Recommended:** run the shared Linux/macOS agent:

```bash
cd ../unix
npm start
# or pair: npm run pair -- <CODE>
```

`desktop/unix/src/agent.js` already returns `platform: macos` on Darwin.

Alternatively reconstruct `src/agent.js` from the release ZIP (`desktop/macos/src/agent.js`) which contains the full poll/execute loop.

Credentials: `~/.config/jarvis/device-credentials.json` (mode 0600).
