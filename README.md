# JARVIS Mobile

Android assistant prototype for ASUS ROG Phone 6, built with Kotlin and Jetpack Compose.

## Build APK

GitHub Actions builds a debug APK and stores it as an artifact. Open the **Actions** tab, select **Build JARVIS Mobile APK**, and run the workflow.

## Current project setup

The Android source project is provided in `JarvisMobile-source.zip`. The workflow extracts it and runs `gradle assembleDebug` using Java 17 and Android SDK 35.

Firebase AI features require your own Firebase project configuration. Do not commit private API keys or credentials.

## Cloudflare Worker

### Required deployment secrets

The GitHub Actions deployment workflow requires these repository secrets:

- `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` for deployment.
- `FISH_API_KEY` for Fish Audio TTS.- `SUPABASE_ANON_KEY` to validate the signed-in Supabase user before device-account enrollment.
- `DEVICE_TOKEN_SECRET` to sign and verify account/device tokens and authorize pairing and device commands. Use a cryptographically random secret (at least 32 random bytes); never reuse an OAuth or Supabase key.

Add them under GitHub → Settings → Secrets and variables → Actions. The workflow uploads the runtime secrets to the Cloudflare Worker and fails before deployment if the required device-authentication secrets are missing. AI provider keys (`GEMINI_API_KEY_1`, `DEEPSEEK_API_KEY_1`, `GROQ_API_KEY_1`, `OPENAI_API_KEY_1`, and `OPENROUTER_API_KEY_1`) are configured separately as Worker Secrets when those providers are used.

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

The Android app includes a user-started foreground sync service and a permission-gated connector. Supported actions include device/battery status, installed-app discovery and launch by unique visible name, approved SAF folder listing/search/text reads, notification listing after Notification Access, on-demand screen-text reads after Accessibility consent, and contacts/calendar reads after runtime permission grants. Screen content is not collected in the background.

Open JARVIS Settings → **Akun Google & Akses Perangkat** to link the Google account and pair each Android device. Configure permissions on each target, refresh its capabilities, and start Device Sync. Device Setup also provides a one-hour desktop pairing token and an owner-confirmed device revocation control.

### Windows, macOS and Linux desktop connectors

Native Node.js companion agents are included under `desktop/windows/` and `desktop/unix/`. They support device status, installed-app discovery and launch by exact visible name, and listing/searching/reading text files inside folders explicitly approved through `JARVIS_ALLOWED_DIRS`. Desktop text reads are capped at 64 KB; path traversal and symlink escapes are rejected. The Windows connector uses Start Menu shortcuts; the shared Unix connector supports macOS `.app` bundles and Linux `.desktop` launchers. Pairing uses the one-hour account token copied explicitly from Android Device Setup and a device-scoped token supplied through environment variables; keep tokens in a local secret manager and never commit them.

See [worker/DEVICE_PLATFORM.md](worker/DEVICE_PLATFORM.md) for pairing, setup, API routes, CI and security boundaries. iOS/iPadOS, Wear OS, web and IoT remain registry-only; native command agents for those platforms are not included.

### Google Sign-In (Credential Manager + Supabase)

The Android app uses Credential Manager's explicit **Sign in with Google** button flow and exchanges the Google ID token with Supabase Auth. This is separate from JARVIS device pairing: signing into an account does not automatically pair a device, and local app launching does not require pairing.

Configure OAuth once:

1. In Google Cloud Console, select the same project used for this app and configure the OAuth consent screen / Google Auth Platform.
2. Create an OAuth client with application type **Android**. Set package name to `com.jarvis.mobile.assistant` and add the SHA-1 signing-certificate fingerprint printed by the **Print APK OAuth fingerprint** step in the Android Build workflow. Repeat this for every signing certificate used by an installed build (debug and release fingerprints are different).
3. Create another OAuth client with application type **Web application**. Copy its Client ID into the GitHub Actions repository secret `GOOGLE_WEB_CLIENT_ID`. Do not use the Android Client ID in this secret.
4. In Supabase Dashboard → Authentication → Sign In / Providers → Google, enable Google and enter the same Web Client ID and its corresponding Client Secret. Save the provider configuration.
5. Run the Android Build workflow again after setting the GitHub secret, download the APK artifact from that run, install it, and test Google sign-in.

The Android Build workflow prints only the APK certificate fingerprint; it does not print OAuth secrets. If Google returns `10: DEVELOPER_ERROR` or `28444`, first verify the package name, the SHA-1 for the exact installed APK, and that both OAuth clients belong to the same Google Cloud project. The build can compile without OAuth secrets, but sign-in cannot succeed until the Google Cloud and Supabase settings above are complete.

Android OS permissions remain authoritative. No arbitrary shell, remote code execution, unrestricted filesystem access, silent microphone/camera capture, screen taps/typing or high-risk system settings changes are exposed. The deployment workflow runs Worker protocol/security tests before deploying.


### Modular backend modules
The backend also contains modular core orchestration, provider routing/key rotation helpers, device command lifecycle, platform connector capability metadata, skill registry, Fish Audio configuration helpers, visual-state/card normalization, and KV storage utilities under `worker/src/`. These modules provide reusable backend services; platform-native operations still require a separately implemented and permissioned connector on each OS.
