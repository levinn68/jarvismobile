# Reference Skills Audit — J.A.R.V.I.S. Mobile

Audit date: 2026-11-05  
Target: Android app, Kotlin + Jetpack Compose, Android 14 / ASUS ROG Phone 6  
Primary reference: https://github.com/nextlevelbuilder/ui-ux-pro-max-skill

## Decision

J.A.R.V.I.S. is a native Android assistant, not a React website. The references are divided into native implementation guidance, transferable design and motion principles, agent/codebase workflow tools, and items not applicable to this native surface.

Do not add a dependency just because a repository is popular. Porting React, WebGL, GSAP, or browser-only components into a native Android app would add a second rendering stack and increase performance and maintenance risk.

## Repository-by-repository review

| Repository | What the source provides | J.A.R.V.I.S. decision |
|---|---|---|
| [UI/UX Pro Max Skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | Design intelligence: styles, product patterns, palettes, typography, UX rules and stack-specific guidance including Jetpack Compose | **Primary design source.** Use AI-native UI + minimal chrome, tokenized surfaces, accessible touch targets, explicit states and Compose-native layout/animation. |
| [Awesome DESIGN.md](https://github.com/VoltAgent/awesome-design-md) | Curated brand design-system files, including Apple and other products | Use as a reference for hierarchy, surface contrast, typography and token structure. Do not copy another brand's identity. |
| [OpenDesign](https://github.com/nexu-io/open-design) | Collaborative AI design workspace, design systems, skills, prototypes and review workflow | Useful as a design-review workflow reference. It is a separate application, not an Android component library. |
| [Taste Skill](https://github.com/Leonxlnx/taste-skill) | Anti-generic design direction, real product context, art direction and browser checks for websites | Transfer the principle: show actual J.A.R.V.I.S. product UI and useful content; avoid generic dashboard filler. HTML/browser assets are not native Compose components. |
| [Impeccable](https://github.com/pbakaus/impeccable) | Design, critique, audit, polish, accessibility, motion and native iOS/Android UI guidance | **Native UI review source.** Preserve product behavior while improving Operate-mode hierarchy, empty states, error recovery and platform affordances. |
| [React Bits](https://github.com/DavidHDev/react-bits) | Animated React / Next.js components | Not copied: React components cannot be dropped into Jetpack Compose. Recreate only a specific interaction natively if it has a clear user benefit. |
| [Emil Kowalski Skills](https://github.com/emilkowalski/skills) | Animation design, choreography and performance skills | Transfer motion principles: fewer simultaneous animations, non-blocking transitions, interruptible sequences, transform/opacity-first where applicable, and test on mid-range phones. Web-specific Motion/React recipes are not copied. |
| [Hallmark](https://github.com/Nutlope/hallmark) | Anti-generic design floor and consistency rules, primarily web/CSS | Transfer the anti-slop and consistency checks. CSS-specific constraints are not treated as Android rules. |
| [Google DESIGN.md](https://github.com/google-labs-code/design.md) | Structured format for design-system context consumed by coding agents | Use a durable design contract: colors, typography, spacing, surfaces, component behavior and motion tokens. |
| [GSAP Skills](https://github.com/greensock/gsap-skills) | GSAP animation, timelines, ScrollTrigger and browser performance | Not installed: GSAP is a web animation runtime. Transfer only the performance principle of avoiding layout-heavy animation and batching work. Use Compose animation APIs. |
| [Scroll World](https://github.com/oso95/scroll-world) | Scroll-driven 3D website / landing-page skill | Not applicable to the core Android assistant. Avoid scroll-jacking and unnecessary 3D in a task-oriented voice/chat surface. |
| [Canvas UI](https://github.com/DavidHDev/canvas-ui) | WebGL/WebGPU effects layered over interactive HTML | Not copied: it targets browser rendering, not Android Canvas/Compose. Native Canvas can be used selectively without importing the web renderer. |
| [Superpowers](https://github.com/obra/superpowers) | Software-development methodology, systematic debugging and test-driven development | **Workflow source.** Diagnose GSI by tracing the complete flow, form one hypothesis at a time, add focused tests, and verify after each change. |
| [Ponytail](https://github.com/DietrichGebert/ponytail) | Agent workflow for reducing unnecessary code and reviewing changes | Use as scope discipline: avoid redundant abstractions and preserve working behavior; no runtime dependency. |
| [Graphify](https://github.com/Graphify-Labs/graphify) | Codebase knowledge graph and dependency exploration | Useful for mapping MainActivity, auth, worker, Supabase and device-control relationships before refactors. Not required at runtime. |
| [Caveman](https://github.com/JuliusBrussee/caveman) | Token-minimization proxy/agent skill | Not integrated into app source; it changes agent communication, not product functionality. |
| [Addy Osmani Agent Skills](https://github.com/addyosmani/agent-skills) | Production engineering skills, including browser testing and interface design | Use the verification mindset and engineering checklists. Browser-only tooling is not a substitute for Android instrumentation or real-device tests. |
| [Understand Anything](https://github.com/Egonex-AI/Understand-Anything) | Interactive knowledge graph and codebase onboarding | Useful for future repository onboarding and dependency tracing. It does not change the app runtime. |
| [Awesome Claude Skills](https://github.com/ComposioHQ/awesome-claude-skills) | Curated directory of Claude skills and tools | Discovery catalogue only; individual skills need their own applicability and license review before adoption. |
| [Archify](https://github.com/tt-a1i/archify) | Agent skill that turns codebases and system descriptions into validated architecture, workflow, sequence, data-flow and lifecycle diagrams | Useful for architecture documentation and flow review. It outputs self-contained HTML diagrams; it is not a native Android UI dependency. |

## Vendored UI/UX Pro Max source

The official skill source is bundled in the repository at .claude/skills/ui-ux-pro-max/ with its MIT license, instructions, search script, design databases (styles, products, colors, typography, UI reasoning and UX guidelines), and the Jetpack Compose stack database. This preserves the upstream source and allows the project team to query the full dataset locally. It is a developer-time reference; it is not shipped inside the APK and does not become a runtime dependency.

## Applied to the current UI branch

The current implementation branch applies a first screen-level pass:

1. Replace the duplicated background drawing with a reusable J.A.R.V.I.S. HUD backdrop.
2. Use a reusable top bar and keep account/history actions accessible.
3. Keep CHAT / VOICE as the only primary mode switcher.
4. Replace the dense nine-category home grid with an AI-native welcome hero and four focused quick actions.
5. Preserve the current voice state machine, wake phrase, chat history, device actions and backend callbacks.

This is a first pass, not a claim that the entire UI redesign is finished. The message composer, response renderer, visual cards, account dialogs, history panel, and Voice screen still require a dedicated visual and interaction audit on a real device.

## Applied to native motion

- Motion must communicate state: standby, listening, processing, speaking, success and error.
- Avoid decorative motion competing with the active task.
- Transitions must be interruptible and must not block taps or typing.
- Prefer Compose animation APIs and Canvas drawing over adding GSAP or browser libraries.
- Profile on the target ASUS ROG Phone 6 and a mid-range Android device; source review alone cannot prove frame-time performance.
- Respect reduced-motion / system animation settings where feasible.

## Google Sign-In findings

The current native flow uses Android Credential Manager to obtain a Google ID token and sends it to Supabase Auth. The app's GOOGLE_WEB_CLIENT_ID is injected from the GOOGLE_WEB_CLIENT_ID environment variable; the Android Gradle default is empty. The GitHub Android workflow reads that value from a repository Actions secret and previously only warned when it was absent.

Required configuration, independently of code:
- A Google OAuth **Web application** client ID must be used as the server client ID in Credential Manager.
- An Android OAuth client must match package com.jarvis.mobile.assistant and the SHA-1 certificate fingerprint of the APK actually installed.
- Debug, locally signed, and release APKs can have different SHA-1 fingerprints.
- Supabase Google provider must be enabled and include the relevant client IDs in its allowed client ID configuration.
- GitHub Actions must provide the Web Client ID secret for its APK build.

The native sign-in code now generates a fresh cryptographically random nonce, sends its SHA-256 hash to Google, and sends the raw nonce alongside the ID token to Supabase Auth. This improves replay protection; it does not fix an incorrect OAuth client ID, package name, SHA-1 fingerprint, or missing secret.

## Validation status

- PR #19: merged into main (merge commit effb44f2147ab7a5bd51e1b2b1b28a9e67f05081).
- PR #20: contains the first UI/UX Pro Max screen pass and GSI nonce hardening; CI is running.
- No real-device interaction test has been performed in this audit.
- Google OAuth Cloud Console and Supabase provider settings must be verified separately from the source code.
