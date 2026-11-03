# JARVIS Universal Device Platform

The Cloudflare Worker is the control plane. A native connector on each device performs local operations through platform APIs and permissions.

## Modules
- `src/device/protocol.js`: platform/capability vocabulary and strict Android command validation.
- `src/device/auth.js`: HMAC-signed, expiring account and device bearer tokens.
- `src/device/policy.js`: per-device capability authorization and confirmation gates.
- `src/device/registry.js`: account ownership, device registration, account indexes and heartbeats.
- `src/device/router.js`: account creation, enrollment, account device listing, capabilities and command queue.

## Cloudflare setup
Create KV namespaces and bind them:
- `DEVICE_REGISTRY`: account and device metadata plus account-to-device indexes.
- `DEVICE_COMMANDS`: command queue with five-minute TTL.

Configure Worker Secrets:
- `DEVICE_ENROLLMENT_SECRET`: high-entropy bootstrap credential used only to create a JARVIS account. Never embed it in a public APK.
- `DEVICE_TOKEN_SECRET`: high-entropy HMAC signing secret for account and device tokens.

Existing deployments must have both KV bindings and both secrets configured. Account tokens are signed for one year; device tokens are signed for 30 days. Rotate `DEVICE_TOKEN_SECRET` to invalidate all issued tokens. Account tokens must be treated as passwords and shared only with devices belonging to the same owner.

## Account and device enrollment
1. POST `/v1/accounts/create` with Authorization Bearer `DEVICE_ENROLLMENT_SECRET`. The response contains an `accountId` and one-year `accountToken`.
2. Store the account token securely. It is required to enroll additional devices and list devices belonging to the account.
3. POST `/v1/devices/enroll` with Authorization Bearer `accountToken` and device metadata. The Worker assigns the account ID from the signed token; it ignores any client-supplied account ownership.
4. The response returns a device-scoped token. Each device stores only its own token in Android Keystore.
5. GET `/v1/devices` with the account token returns devices owned by that account. Account tokens cannot poll or acknowledge device command queues.

## Routes
- POST `/v1/accounts/create` — create an account using the bootstrap secret.
- POST `/v1/devices/enroll` — enroll a device using its account token.
- GET `/v1/devices` — list devices owned by the account token.
- GET `/v1/devices/:deviceId` — read a device record using that device's token.
- POST `/v1/devices/:deviceId/heartbeat` — update last-seen using that device's token.
- PUT `/v1/devices/:deviceId/capabilities` — update that device's declared capabilities.
- POST `/v1/device/command` — enqueue a validated command; account tokens may target only devices owned by the same account.
- GET `/v1/device/commands?deviceId=...` — device-scoped connector polls its own queue.
- POST `/v1/device/commands/ack` — device-scoped connector acknowledges its own command.

## Android command allowlist
Only these actions are accepted by the backend and Android connector:
- `apps.list`
- `apps.launch` (requires a syntactically valid Android package name)
- `system.status`
- `notifications.list`
- `files.list`
- `files.search` (query limited to 200 characters)
- `files.read` (SAF document ID limited to 1024 characters; text-only content capped at 64 KB)

Unknown actions and unknown parameters are rejected before a command is queued. Commands are not arbitrary shell execution.

## Safety and current limitations
The backend never executes shell commands, arbitrary code, or direct filesystem operations. It checks declared capabilities and queues typed requests. A queued command is not proof of execution.

Android SAF folder selection and Notification Access remain explicit user-granted permissions. The current capability update endpoint accepts self-reported capabilities from the paired device; this is not hardware attestation and must not be treated as proof that a permission is genuine.

Sensitive operations remain blocked until a proper in-app confirmation flow is implemented. Account ownership, account-scoped listing/enrollment, encrypted Android account-token storage, typed command dispatch, and command acknowledgement lookup are implemented. Account-token revocation, device removal, multi-user roles, QR-based pairing, native Windows/macOS/iOS agents, and a broad device-control permission/confirmation console are not implemented yet.

Android/iOS sandboxing and consent remain in force. File, screen, camera, microphone, contacts, notification, location and app access require platform-specific permissions or supported APIs.
