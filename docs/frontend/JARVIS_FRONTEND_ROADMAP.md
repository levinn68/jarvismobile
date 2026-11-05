# JARVIS Frontend Engineering Roadmap
> Status: implementation specification and audit checklist  
> Target: Android app for ASUS ROG Phone 6 / Android 14, unrooted  
> Source layout note: the repository README currently describes the Android source as `JarvisMobile-source.zip`. This document specifies the frontend work inside that source bundle; it does not assume a top-level `frontend/` directory exists.

## 1. Product contract

JARVIS has exactly two primary interaction modes:

- **Voice Mode** — voice-first command and response experience.
- **Chat Mode** — complete text conversation and rich result experience.

Do not introduce a third Home mode. Device management, account settings, history and preferences should be contextual panels or destinations reachable from the two primary modes, not competing primary modes.

The app must remain usable with denied permissions, no network, expired sessions, unavailable AI providers and disconnected paired devices. Explain unavailable capabilities and provide a recovery path; never imply an action succeeded when it did not.

## 2. Experience architecture

### 2.1 Voice Mode

Required hierarchy:

1. Compact top bar: JARVIS identity, account/session state, connection indicator and access to settings/history.
2. Central holographic orb with state-driven animation.
3. Human-readable activity label and optional concise transcript/voice summary.
4. Contextual visual cards, maximum five at once.
5. Bottom controls: mute, stop/end voice session, and switch to Chat Mode.

Orb state must be driven by actual application state, not timers or decorative animation:
- `standby`: session inactive and waiting for explicit activation.
- `listening`: microphone capture is active and permitted.
- `thinking`: request accepted and waiting for reasoning/provider response.
- `executing`: an approved device action is in progress.
- `speaking`: TTS playback is active.
- `error`: a recoverable or terminal error is being surfaced.

Use a single state owner (ViewModel/state holder) and explicit transitions. Cancel stale jobs when a new request, stop action, lifecycle transition or session end occurs. Do not display `executing` before a command is actually dispatched.

### 2.2 Voice session and wake phrase

Expected flow:
1. User explicitly activates with “Jarvis, open” or the visible Voice Mode control.
2. App confirms activation once.
3. While the session remains active, follow-up utterances do not need to repeat “Jarvis”.
4. Stop/end action closes the session and returns to standby.
5. Reopening requires a new explicit activation.

Do not claim always-on wake-word detection unless the implementation, foreground-service behavior, battery impact, microphone indicator and Android policy compliance have been verified on the target device. Prefer push-to-talk or a user-started foreground session where continuous detection is not reliable or permitted.

### 2.3 Chat Mode

Required capabilities:
- Complete conversation thread with user and assistant messages.
- Streaming/thinking indicator where supported.
- Retry for transient failures, preserving the original user message.
- Copy/share actions where appropriate.
- Rich cards and citations/source links when the backend supplies them.
- Composer with send, microphone shortcut, attachment affordance only when implemented.
- Clear distinction between pending, sent, failed and persisted messages.

Do not fabricate assistant messages on network failure. Preserve drafts and unsent messages through configuration changes and process recreation where feasible.

### 2.4 Mode transitions

Use a short, restrained transition (fade/scale/shared-element where supported). Preserve the active conversation, scroll position, draft and voice session state. Avoid restarting a request or TTS playback merely because the user changed mode. Respect reduced-motion/system animation settings.

## 3. Visual system and interaction quality

Maintain the established dark graphite identity with cyan/violet accents. Use the existing design system and orb components rather than creating parallel palettes or duplicate widgets.

Quality requirements:
- Consistent spacing, typography, icon sizing and corner radii.
- Readable contrast in dark mode and no oversized white panels.
- Touch targets appropriate for mobile use (prefer at least 48 dp).
- Clear focus and accessibility semantics for buttons and state changes.
- Dynamic type/font scaling without clipped controls.
- Loading, empty, offline, permission-denied and error states for every major surface.
- Animations should be smooth but bounded; avoid continuous high-cost effects when the screen is idle.
- Avoid decorative status lights that imply connectivity or microphone activity when those systems are inactive.

## 4. Holographic orb implementation

Keep the orb as a visual representation of the real state machine. Verify:
- Listening animation reacts to available audio-level data without retaining raw audio.
- Thinking and executing are visually distinct.
- Speaking ends when TTS ends or is cancelled.
- Error state has a text explanation and recovery action.
- Standby animation is low-power and stops/reduces when not visible.
- Canvas animation is lifecycle-aware and does not leak coroutines or audio resources.

Do not use audio amplitude as a proxy for successful speech recognition.

## 5. Voice and speech lifecycle

Audit SpeechRecognizer, TextToSpeech, Fish Audio playback, AudioManager/audio focus, microphone permission and lifecycle integration.

Acceptance criteria:
- Indonesian and English speech recognition can be selected or detected with an explicit fallback.
- Language selection is stable for the current session and visible to the user.
- Only one recognizer and one active TTS playback are owned at a time.
- Mute prevents playback but does not silently cancel or discard the assistant response.
- Stop cancels active playback and, where requested, active recognition/request work.
- Audio focus is requested and abandoned correctly.
- Recognizer/TTS resources are released on teardown and recreated safely.
- Permission denial is handled without crash or repeated permission prompts.
- Background behavior is explicit and user-visible; no hidden microphone capture.
- TTS errors do not mark the assistant response as failed if the text answer was already received.

Voice Mode should consume `voice_summary` from the backend, falling back to a safely derived short excerpt from `answer` only for older response versions. Chat Mode should render `answer`.

## 6. Visual cards

Support the backend card types that the Android renderer actually implements: text, image, product, map, chart, table, file, progress and device. Do not advertise unsupported types.

Rules:
- Maximum five cards in Voice Mode.
- Validate required fields and URLs before rendering.
- Provide loading, unavailable-image and malformed-card fallbacks.
- Never render a blank card or invented data.
- Keep source attribution and external links where available.
- Avoid blocking the conversation if one card fails.
- Use stable IDs so recomposition does not duplicate cards or reset their state.

## 7. Account, conversations and local persistence

Google Sign-In must be an explicit user action. Exchange the Google ID token with Supabase Auth using the configured Web Client ID. Do not confuse account login with device pairing.

Verify:
- Session restoration and refresh.
- Sign-out and local cache cleanup.
- User identity is derived from validated auth state, not client-supplied IDs.
- Conversation and message queries are scoped to the authenticated user.
- Offline messages have a clear pending state and safe retry semantics.
- Conversation ordering and last-updated metadata remain consistent.
- Switching accounts cannot expose cached data from the previous account.
- Database errors do not silently drop local drafts.

Separate conversation history from long-term user preferences. Provide user-facing controls for conversation deletion and memory/preferences management if those features are enabled.

## 8. Device Hub and permissions

Device Hub should be a contextual panel, not a third primary mode. Show:
- Paired device name/platform and online/offline/last-seen state.
- Declared capabilities and their permission state.
- Pair/revoke controls.
- Last command status and error detail.

Capabilities must be discovered from the paired connector and revalidated; do not infer that a permission is granted merely because a capability name exists.

Android operations must use platform APIs and user-granted permissions. Keep sensitive actions behind a clear confirmation screen. Do not implement arbitrary shell execution, unrestricted filesystem access, silent screen interaction or hidden microphone/camera capture.

## 9. Frontend architecture and maintainability

Use a unidirectional state flow:
- UI emits intent.
- ViewModel/use-case validates intent and current session.
- Repository performs network/database/device operation.
- Result updates immutable UI state.
- UI renders state and never invents operation success.

Recommended boundaries:
- Presentation: screens, composables, navigation and UI state.
- Domain: use cases, models, validation and capability policy.
- Data: API, Supabase, persistence and device connector adapters.
- Platform: Android permissions, audio, accessibility and lifecycle services.

Avoid putting network calls, database access or command policy inside composables. Use structured concurrency, cancellation, bounded retries and typed errors. Keep API models versioned and tolerate unknown optional fields.

## 10. Accessibility, performance and resilience

Test:
- Small and large screen sizes, landscape and display scaling.
- TalkBack labels and logical focus order.
- Keyboard and hardware back behavior.
- Battery saver and process recreation.
- Slow/unstable network, airplane mode and backend 5xx.
- Long conversations and large card payloads.
- Repeated rapid taps and duplicate send prevention.
- Expired Supabase session and revoked device.
- Permission granted, denied and revoked while app is running.

Use pagination or bounded loading for conversation history. Avoid rendering an unbounded message/card list. Do not log tokens, message content or sensitive screen data by default.

## 11. Frontend acceptance checklist

- [ ] Only Chat Mode and Voice Mode are primary modes.
- [ ] Mode transition preserves conversation and draft.
- [ ] Orb state reflects real recognizer/request/TTS/command state.
- [ ] Wake phrase activates a session; follow-ups work without repeating “Jarvis”.
- [ ] Voice output uses `voice_summary`; chat uses full `answer`.
- [ ] Voice cards never exceed five.
- [ ] TTS mute/stop/audio-focus behavior is correct.
- [ ] Google login, session refresh, logout and account switching are tested.
- [ ] Conversation history is user-scoped and survives app restart.
- [ ] Device Hub displays actual connector status and capabilities.
- [ ] Sensitive device operations require explicit approval.
- [ ] Empty, offline, permission-denied and error states are implemented.
- [ ] Build and automated tests pass.
- [ ] Physical-device verification is recorded for ASUS ROG Phone 6 / Android 14.

## 12. Known repository constraints to keep visible

The current root README describes the Android source project as `JarvisMobile-source.zip`, rather than a top-level `frontend/` directory. Keep this document as the frontend specification until the actual source layout is inspected in the release bundle. Do not create a duplicate Android app tree merely to match the word “frontend”.

The repository documentation also states that iOS/iPadOS, Wear OS, web and IoT are registry-only and do not have native command connectors shipped. The Android app must not present these as supported native control targets.

## 13. Definition of done

A frontend feature is complete only when its UI, state transitions, permission boundaries, error paths, persistence behavior and tests are implemented. A successful Gradle build alone is not proof of runtime correctness. Record the exact device/OS, build variant, test steps and observed result for physical-device checks.
