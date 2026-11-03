# JARVIS Mobile

Android assistant prototype for ASUS ROG Phone 6, built with Kotlin and Jetpack Compose.

## Build APK

GitHub Actions builds a debug APK and stores it as an artifact. Open the **Actions** tab, select **Build JARVIS Mobile APK**, and run the workflow.

## Current project setup

The Android source project is provided in `JarvisMobile-source.zip`. The workflow extracts it and runs `gradle assembleDebug` using Java 17 and Android SDK 35.

Firebase AI features require your own Firebase project configuration. Do not commit private API keys or credentials.

## Cloudflare Worker

The JARVIS API runs from `worker/src/index.js`. Existing AI providers and Fish Audio TTS remain supported.

### Optional visual search provider

The Worker supports real product and image search through SerpApi. Configure the key as a **Worker Secret** named `SERPAPI_API_KEY` in the Cloudflare dashboard (Workers & Pages → your JARVIS Worker → Settings → Variables and Secrets). Do not put this key in Android, source code, or this repository.

After the secret is configured, these endpoints are available:

- `POST /v1/search/products` with JSON `{"query":"bola basket size 7","limit":6}`
- `POST /v1/search/images` with JSON `{"query":"gunung bromo sunrise","limit":6}`

Both return normalized JSON results with image URLs and source links. Product/image intents sent to `POST /v1/chat` are automatically enriched with search results when the secret is configured. The chat response includes a `visuals` object and a Markdown-compatible visual section for the current Android renderer.

If `SERPAPI_API_KEY` is absent, chat and all existing AI/TTS features continue working; visual search endpoints return HTTP 503 with a configuration hint.

### Health check

`GET /health` reports whether visual search is configured without exposing the secret value.


## JARVIS intelligence layer

The Worker includes a built-in Personality Core and dynamic Skill Router. Five reusable skill specifications are maintained in `worker/skills/`: personality, cognitive reasoning, research, software engineering, and executive/creative operations. The router selects relevant skills per request and injects them into every participating AI agent and the final synthesis prompt.

### Bilingual Fish Audio voice

The `POST /v1/tts` endpoint detects Indonesian or English text and selects a matching Fish Audio reference voice when configured. Optional Worker variables: `FISH_VOICE_ID_ID` and `FISH_VOICE_ID_EN`. If either is absent, the existing `FISH_VOICE_ID` remains the fallback. The Fish API key stays in Worker Secrets and is never committed.

The existing AI providers, visual search, API routes, Android client contract and default Fish voice remain compatible.


## Universal multi-device foundation

The Worker includes a device control plane under `worker/src/device/` for Android, Windows, iOS/iPadOS, Wear OS, web and IoT platform identities. It provides Google-linked account enrollment through Supabase Auth, HMAC account/device tokens, per-device capabilities, heartbeat, typed command validation, a short-lived KV command queue, acknowledgement and command-status lookup.

### Android device connector

The Android app includes a user-started foreground sync service and a permission-gated connector. Supported chat/voice actions include:
- Device status and battery information.
- List installed apps and launch a uniquely matched app by visible name or package name.
- List, search and read text files inside a folder explicitly selected through Android's Storage Access Framework.
- Read recent notifications after the user enables Notification Access.
- Read visible screen text only when explicitly requested and only after the user enables JARVIS Accessibility Service. The service does not monitor or retain screen text in the background.
- Read contacts and a bounded 30-day calendar window only after the user grants the corresponding Android runtime permissions.

Open JARVIS Settings → **Akun Google & Akses Perangkat** to link the current Google account and pair the device. Pair each Android device with the same Google account, configure the desired permissions on each target, update capabilities, and start Device Sync on the target device. See [worker/DEVICE_PLATFORM.md](worker/DEVICE_PLATFORM.md) for setup, API routes, Cloudflare KV bindings, secrets and security boundaries.

Google sign-in also requires a Google OAuth **Web Client ID** configured as the GitHub Actions secret `GOOGLE_WEB_CLIENT_ID`, plus the matching Google provider Client ID/Client Secret in Supabase Auth. Android CI intentionally fails early when the Web Client ID secret is absent; a successful Gradle build alone is not proof that OAuth is configured.

Android OS permissions remain authoritative. Camera capture, screen taps/typing, arbitrary shell, unrestricted filesystem access, silent microphone/camera access, and high-risk system settings changes are not exposed. Windows/macOS/iOS still require separate native companion agents; Worker platform identifiers alone do not constitute working connectors. The deployment workflow runs Worker protocol/security tests before deploying.


### Modular backend modules
The backend also contains modular core orchestration, provider routing/key rotation helpers, device command lifecycle, platform connector capability metadata, skill registry, Fish Audio configuration helpers, visual-state/card normalization, and KV storage utilities under `worker/src/`. These modules provide reusable backend services; platform-native operations still require a separately implemented and permissioned connector on each OS.
