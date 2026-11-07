# JARVIS Universal Device Platform

Cloudflare Worker is the account and command control plane. Each paired device runs a native connector that performs local operations through platform APIs and user-approved permissions.

## Connector coverage

| Platform | Connector | Supported operations |
| --- | --- | --- |
| Android | Kotlin app connector | Status, installed-app listing/launch, user-selected SAF file listing/search/text reads, notifications, on-demand visible screen text, contacts and calendar (permission-gated) |
| Windows | Node.js desktop connector | Status, Start Menu app listing/launch by app ID or unique visible name, approved-folder listing/search/text reads |
| macOS | Node.js desktop connector | Status, Applications listing/launch by app ID or unique visible name, approved-folder listing/search/text reads |
| Linux | Node.js desktop connector | Status, .desktop app listing/launch by app ID or unique visible name, approved-folder listing/search/text reads |
| iOS/iPadOS, Wear OS, web, IoT | Registry vocabulary only | No native command connector shipped |

## Modules

- `src/device/protocol.js`: strict platform-specific command schemas.
- `src/device/auth.js`: HMAC-signed, expiring account and device bearer tokens.
- `src/device/policy.js`: capability authorization and confirmation gates.
- `src/device/registry.js`: account ownership, device registration, account indexes and heartbeats.
- `src/device/command-service.js`: typed command queue and acknowledgement/status lookup.
- `src/device/router.js`: account enrollment, pairing, device listing and command routes.
- `../desktop/windows/`: Windows companion agent and path-boundary tests.
- `../desktop/unix/`: macOS/Linux companion agent and path-boundary tests.

## Android setup

1. Sign in through Google/Supabase in the Android app.
2. Open Settings → **Akun Google & Akses Perangkat**.
3. Pair each Android device using the same Google account.
4. Select an approved folder through Android's Storage Access Framework.
5. Enable Notification Access, Accessibility, Contacts or Calendar only when needed.
6. Refresh device capabilities and start the user-visible foreground Device Sync service.

Android capabilities are limited to what the operating system and user permissions allow. Screen text is read only after an explicit request; the Accessibility Service does not collect or retain screen events in the background.

## Desktop setup

### Windows

Run the connector from `desktop/windows` using Node.js 22:

- In Android JARVIS Device Setup, tap **Salin token pairing desktop (berlaku 1 jam)**.
- Set the copied value as `JARVIS_ACCOUNT_TOKEN` only for the pairing command.
- Pair: `npm run pair`
- Start: `npm start`.
- Configure `JARVIS_DEVICE_ID` and `JARVIS_DEVICE_TOKEN` for the running agent.
- Configure `JARVIS_ALLOWED_DIRS` with one or more approved folder paths separated by the Windows path delimiter.

### macOS and Linux

Run the shared connector from `desktop/unix` using Node.js 22:

- Copy the one-hour account token from Android JARVIS Device Setup using **Salin token pairing desktop (berlaku 1 jam)**.
- Set it as `JARVIS_ACCOUNT_TOKEN` only for the pairing command.
- Pair: `npm run pair`
- Start: `npm start`
- Configure the same device/account variables as Windows.
- Set `JARVIS_ALLOWED_DIRS` to the exact directories the user approves.

The desktop agent prints the device token once during pairing. Store it in the operating system's credential manager or another local secret manager; do not paste it into source files, commit it, or share it in chat. The current connector reads its runtime token from environment variables.

## Desktop command allowlist

- `system.status`
- `apps.list`
- `apps.launch` using an app ID returned by `apps.list`
- `apps.launch_label` using an exact, unique visible application name
- `files.list`
- `files.search`
- `files.read` (text-only content capped at 64 KB)

Desktop file access is restricted to `JARVIS_ALLOWED_DIRS`. Path traversal and resolved-path escapes are rejected. Windows launches Start Menu shortcuts through a fixed PowerShell invocation with the target passed as an environment variable. macOS and Linux use fixed OS launcher commands with argument arrays. Arbitrary shell commands and remote script execution are not exposed.

## Account and command security

Google sign-in validates the current Supabase session at `POST /v1/accounts/google` and derives a stable JARVIS account ID from the verified Supabase user UUID. The account token is short-lived (one hour) and is refreshed from the active Supabase session. Device tokens are scoped to one device. Device enrollment assigns account ownership from the signed account token, not client-supplied metadata.

The account owner can revoke a paired device with `DELETE /v1/devices/:deviceId`. The endpoint checks account ownership, removes the device registry and account index entries, and the connector's next poll/acknowledgement is rejected because the device is no longer registered. This is exposed in Android Device Setup as **Cabut akses perangkat target dari akun**.\n\nThe Worker uses these KV bindings:

- `DEVICE_REGISTRY`: account and device metadata plus account-to-device indexes.
- `DEVICE_COMMANDS`: short-lived typed command queue.

The deployment workflow requires `DEVICE_TOKEN_SECRET` and `SUPABASE_ANON_KEY` as GitHub Actions secrets and installs them as Cloudflare Worker Secrets. `DEVICE_ENROLLMENT_SECRET` is only used by the legacy `/v1/accounts/create` bootstrap endpoint; Google/Supabase account enrollment does not require it. OAuth client secrets are configured in Google Cloud and Supabase, not in this Worker.

A queued command is not proof of execution. Capability declarations are reported by the paired connector and are not hardware attestation. Account-token revocation and multi-user roles remain future work. Device revocation and QR/manual pairing are implemented; pairing codes expire after five minutes.

## Platform and privacy boundaries

No connector exposes arbitrary shell execution, remote code execution, unrestricted filesystem access, silent microphone/camera capture or hidden screen interaction. Android screen taps/typing and settings mutation are not implemented. Camera capture requires a dedicated visible foreground confirmation flow and is not yet available. iOS/iPadOS, Wear OS, web and IoT are not native connectors in this release.
