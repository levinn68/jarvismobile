---
name: android-mobile-engineering
description: Diagnose and implement Android application behavior, Kotlin, lifecycle, permissions, audio, networking, and UI integration.
---

# Android & Mobile Engineering

## Inspect
- Confirm package structure, Gradle/AGP/Kotlin versions, min/target SDK, manifest, permissions, and architecture.
- Read existing Activity, ViewModel, service, navigation, and networking code before editing.
- Preserve the current navigation model unless redesign is requested.

## Implementation
- Respect Activity/Fragment lifecycle and configuration changes.
- Keep network and long-running work off the main thread.
- Handle runtime permissions and denial states.
- Manage audio focus, microphone permissions, recording/playback lifecycle, and interruptions explicitly.
- Avoid leaking Activity, Context, listeners, or coroutines.
- Use secure storage for tokens; never embed provider API keys in the APK.
- Provide loading, empty, error, and retry states.

## Verification
- Build with the project's actual Gradle wrapper.
- Test relevant Android versions and device form factors when available.
- Verify real microphone, audio playback, network loss, and app background/foreground transitions.
