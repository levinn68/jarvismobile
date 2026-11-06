# MainActivity recovery / assembly

## Why MainActivity.kt may be missing on the branch

The polished `MainActivity.kt` (~194 KB) exceeds the GitHub MCP push payload limit used by the automation agent. It was temporarily removed while switching to a part-based assembly flow.

## Restore immediately (pre-polish baseline)

```bash
curl -fsSL -o app/src/main/java/com/jarvis/mobile/MainActivity.kt \
  "https://raw.githubusercontent.com/levinn68/jarvismobile/04c33d43a5b0dcc60d31b12a99f659fc68878c9d/app/src/main/java/com/jarvis/mobile/MainActivity.kt"
```

## Prefer: install polished MainActivity from release ZIP

Copy from the final ZIP:

`app/src/main/java/com/jarvis/mobile/MainActivity.kt`

Markers that must be present:

- requestMicFocus
- clientRequestId
- registerConnectivityFlush
- HolographicOrb
- JarvisChatComposer
- JarvisUserBubble

## Assemble from parts (when all partXX files are present)

```bash
bash scripts/assemble-mainactivity.sh
# or: ./gradlew generateMainActivity
```