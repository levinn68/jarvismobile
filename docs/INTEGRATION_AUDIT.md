# ALL ACCESS JARVIS — BATCH 7 INTEGRATION AUDIT & ARCHITECTURE

## 1. Architecture Overview
- **Android Mobile Application (`app/src/main/java/com/jarvis/mobile/`)**:
  - `MainActivity.kt` (3,900+ lines): Full Jetpack Compose Sci-Fi HUD with **Chat Mode** (structured Markdown, tables, code blocks, max 5 visual/product cards, attachment analysis) and **Voice Mode** (Holographic Orb + waveform + Fish Audio / Android TTS + max 5 visual/product cards without long text explanation panels).
  - Configurable Worker URL via `BuildConfig.JARVIS_WORKER_URL` and `jarvis_secure_settings` override.
  - Remote platform detection (`windows`, `macos`, `linux`, `desktop`, `laptop`, `pc`, `komputer`) routes cross-device commands through Worker `/v1/chat` (`executeDeviceIntent`), while local Android app launches execute directly via `PackageManager`.
  - `JarvisAccessibilityService.kt` & `ScreenActionApprovalActivity.kt`: Uses a dedicated background `HandlerThread("JarvisGestureCallback")` and off-main-thread execution so `screen.tap`, `screen.read`, and `screen.type` never block or deadlock the Android UI thread.
  - `JarvisDeviceSyncService.kt`: Foreground `dataSync` service with bounded 30-second `PowerManager.PARTIAL_WAKE_LOCK` per poll cycle and exponential backoff (`15s -> 30s -> 60s -> 120s`).
- **Cloudflare Worker (`worker/src/`)**:
  - Multi-provider AI Council (`gemini-2.5-flash` with `gemini-2.0-flash` fallback, DeepSeek, Groq, OpenAI, OpenRouter) with KV-persisted circuit breaker & key rotation (`keyRotator.loadFromKv` / `saveToKv`).
  - All 29 skill instructions bundled locally inside `worker/src/skills/skill-registry.js` (zero runtime GitHub network fetches, bounded `MAX_SYSTEM_PROMPT_CHARS = 18000`).
  - Guaranteed HTTPS visual fallback engine (`buildTopicalFallbackVisuals`) and strict `MAX_VISUAL_CARDS = 5` enforcement across Chat, Voice, and error responses.
  - KV-backed command idempotency (`cmd-idemp:`) and duplicate suppression (`cmd-dedupe:`), plus `POST /v1/pairing/sessions/cancel`.
- **Desktop Companion Agents (`desktop/windows/`, `desktop/macos/`, `desktop/unix/`)**:
  - Windows `launchApp` uses `Start-Process -FilePath $env:JARVIS_TARGET` (compatible with Windows PowerShell 5.1 and PowerShell 7+).
  - 60 KB file read cap (`MAX_FILE_BYTES = 60 * 1024`) so JSON ACK envelopes stay safely below the 64 KB Worker limit.
  - OS Credential Manager integration (Windows DPAPI, macOS Keychain, Linux `libsecret`) with per-credential random-salt `scrypt` + AES-256-GCM fallback and `--print-service-config` service registration helper.
- **Supabase PostgreSQL Migrations (`supabase/migrations/`)**:
  - 6 ordered SQL migrations enforcing `FORCE ROW LEVEL SECURITY`, composite foreign keys, monotonic message ordering, `SECURITY INVOKER` triggers with `search_path = pg_catalog, public`, and `levpay_*` cleanup.