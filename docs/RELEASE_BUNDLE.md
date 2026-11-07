# ALL ACCESS JARVIS — BATCH 7 FINAL RELEASE BUNDLE

## Verification & Build Commands
1. **Cloudflare Worker Tests**:
   ```bash
   npm test --prefix worker
   ```
2. **Windows Desktop Agent Tests**:
   ```bash
   npm test --prefix desktop/windows
   ```
3. **macOS Desktop Agent Sync & Tests**:
   ```bash
   bash desktop/macos/RECONSTRUCT_AGENT.sh
   ```
4. **Linux / Unix Desktop Agent Tests**:
   ```bash
   npm test --prefix desktop/unix
   ```
5. **Android APK Build (requires Android SDK + JDK 17 + network access to Google Maven)**:
   ```bash
   ./gradlew assembleDebug testDebugUnitTest
   ```
