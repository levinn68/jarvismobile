# J.A.R.V.I.S. Mobile — Master Design System

Source basis: UI/UX Pro Max Skill 2.15.0, product pattern AI/Chatbot Platform, AI-Native UI + Minimalism & Swiss Style, plus the existing J.A.R.V.I.S. cyan holographic identity.

## Product mode

**Operate + Read.** The primary job is to ask, understand, execute, and inspect. Chat content and voice controls take priority over decorative chrome. The holographic core is the product identity, not a replacement for readable content.

## Visual direction

- OLED-first dark interface with deep navy-black surfaces.
- Cyan is the established J.A.R.V.I.S. brand accent; do not replace it with the generic AI-purple palette from the reference database.
- Use cyan for primary actions, active mode, focus and important system state only.
- Green is reserved for connected/ready/success; amber for processing or attention; red for errors and destructive actions.
- Use subtle HUD geometry and glass-like surface separation. Do not put every paragraph inside a card.
- Keep decorative scanlines, grids and glows low contrast behind content.

## Color tokens

| Token | Value | Use |
|---|---|---|
| background | #050A12 | Main OLED background |
| surface | #0B1722 | Dialogs and primary surfaces |
| surface-raised | #102331 | Elevated panels and controls |
| brand-cyan | #69E4F5 | Active states and primary accent |
| brand-cyan-bright | #B9F7FF | High-emphasis hologram detail |
| text-primary | #EAF8FC | Primary readable text |
| text-muted | #7895A4 | Secondary labels and metadata |
| success | #62DDB1 | Connected / ready |
| warning | #FFC56D | Processing / attention |
| error | #FF8A8A | Failure / destructive |

Use the Kotlin source of truth in JarvisDesign.kt for the existing palette. When a token changes, update the design system and Kotlin palette together.

## Typography

- Use native system sans-serif for interface text.
- Use monospace only for telemetry, timestamps, technical identifiers and compact system labels.
- Body content should remain comfortably readable at 14–16sp or larger when possible.
- Avoid letter spacing on long body copy. Use tracking sparingly for short HUD labels.
- Support Android font scaling; avoid fixed-height text containers that clip enlarged text.

## Spacing and shape

- Base spacing: 4dp; preferred rhythm: 8dp.
- Main screen horizontal inset: 16dp.
- Primary control height: at least 48dp; icon hit targets: at least 48dp.
- Standard surface radius: 14–20dp; pill controls use full radius.
- Keep 8dp or more between independent touch targets.

## Information architecture

Exactly two primary modes:
1. **CHAT** — full conversation, readable long-form responses, inline visuals, attachments, history and composer.
2. **VOICE** — holographic orb, live state, transcript, concise voice response, up to the supported visual-card limit, and microphone controls.

Account and history are secondary actions. There is no HOME primary mode. A new-chat action clears the current conversation only after the corresponding history action is invoked.

## Interaction states

- Standby: mic does not submit speech; show the wake phrase hint.
- Activating: show a short acknowledgement and listening state.
- Listening: expose clear mic state and audio level.
- Processing: show progress feedback without freezing the screen.
- Speaking: preserve access to stop/mute controls.
- Error: explain the cause and provide retry or recovery action.
- Account sign-in: disable repeated taps while the operation is in progress and show the exact recoverable error.

## Motion

- Motion communicates state changes; decorative motion stays secondary.
- Keep transitions interruptible and never block input.
- Prefer opacity and scale/translation for transitions; avoid repeatedly changing layout dimensions.
- Use one dominant animated focal element per screen.
- Stagger only intentional first-run content; never delay frequently used controls.
- Respect system animation preferences and verify performance on the actual target device.

## Accessibility and quality gates

- Contrast target: 4.5:1 for normal text.
- All icon-only controls require meaningful content descriptions.
- All interactive touch targets should be at least 48dp.
- Account, history, composer, mic and mode controls must remain reachable with the keyboard open.
- No horizontal overflow or nested scroll containers that compete for touch.
- Verify loading, empty, error, offline, signed-in and signed-out states.
- Verify 1x and enlarged system font sizes.
- Run Android build/tests and inspect screenshots on the ASUS ROG Phone 6 before calling the redesign complete.

## Implementation boundary

Native implementation uses Kotlin and Jetpack Compose. Do not add React Bits, GSAP, Scroll World or Canvas UI browser runtimes to the Android APK. Recreate only useful interaction concepts with Compose-native components and measure their real-device cost.
