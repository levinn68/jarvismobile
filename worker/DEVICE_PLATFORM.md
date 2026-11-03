# JARVIS Universal Device Platform

The Cloudflare Worker is the control plane. Each paired device runs a native connector that performs local operations through platform APIs and permissions.

## Current connector coverage

| Platform | Connector | Implemented actions |
| --- | --- | --- |
| Android | Kotlin app connector | App listing/launch, system status, notification listing (with Notification Access), SAF folder listing/search/text read |
| Windows | Node.js desktop connector | Start Menu app listing/launch, system status, approved-folder listing/search/text read |
| macOS | Shared Node.js desktop connector | Application listing/launch, system status, approved-folder listing/search/text read |
| Linux | Shared Node.js desktop connector | .desktop app listing/launch, system status, approved-folder listing/search/text read |

The registry accepts additional platform identifiers (iOS/iPadOS, Wear OS, web and IoT) for forward compatibility. Those identifiers do not mean a native connector currently exists. The command validator rejects commands for platforms without a registered connector.

## Backend modules

- \`src/device/protocol.js\`: platform/capability vocabulary and strict platform-specific command validation.
- \`src/device/auth.js\`: HMAC-signed, expiring account and device bearer tokens.
- \`src/device/policy.js\`: per-device capability authorization and confirmation gates.
- \`src/device/registry.js\`: account ownership, device registration, account indexes and heartbeats.
- \`src/device/command-service.js\`: typed command queue and idempotent acknowledgements.
- \`src/device/router.js\`: account creation, enrollment, account device listing, capabilities and command routes.

## Cloudflare setup

Create KV namespaces and bind them:

- \`DEVICE_REGISTRY\`: account and device metadata plus account-to-device indexes.
- \`DEVICE_COMMANDS\`: command queue with short TTL.

Configure Worker Secrets:

- \`DEVICE_ENROLLMENT_SECRET\`: high-entropy bootstrap credential used only to create a JARVIS account. Never embed it in a public APK or desktop connector.
- \`DEVICE_TOKEN_SECRET\`: high-entropy HMAC signing secret for account and device tokens.

Existing deployments must have both KV bindings and both secrets configured. Account tokens are signed for one year; device tokens are signed for 30 days. Rotating \`DEVICE_TOKEN_SECRET\` invalidates all issued tokens. Account tokens must be treated as passwords and shared only with devices belonging to the same owner.

## Account and device enrollment

1. POST \`/v1/accounts/create\` with Authorization Bearer \`DEVICE_ENROLLMENT_SECRET\`. The response contains an \`accountId\` and one-year \`accountToken\`.
2. Store the account token securely. It is required to enroll additional devices and list devices belonging to the account.
3. POST \`/v1/devices/enroll\` with Authorization Bearer \`accountToken\` and device metadata. The Worker assigns account ownership from the signed token and ignores client-supplied ownership.
4. The response returns a device-scoped token. Android stores it encrypted with Android Keystore. Desktop connectors receive it through the setup process and require the operator to store it in a secure OS credential store or secret manager; the connector does not write it into the repository.
5. GET \`/v1/devices\` with the account token returns devices owned by that account. Account tokens cannot poll or acknowledge device command queues.

## Routes

- POST \`/v1/accounts/create\` — create an account using the bootstrap secret.
- POST \`/v1/devices/enroll\` — enroll a device using its account token.
- GET \`/v1/devices\` — list devices owned by the account token.
- GET \`/v1/devices/:deviceId\` — read a device record using that device's token.
- POST \`/v1/devices/:deviceId/heartbeat\` — update last-seen using that device's token.
- PUT \`/v1/devices/:deviceId/capabilities\` — update that device's declared capabilities.
- POST \`/v1/device/command\` — enqueue a validated command; account tokens may target only devices owned by the same account.
- GET \`/v1/device/commands?deviceId=...\` — device-scoped connector polls its own queue.
- POST \`/v1/device/commands/ack\` — device-scoped connector acknowledges its own command. Repeated acknowledgements are idempotent.

## Android command allowlist

- \`apps.list\`
- \`apps.launch\` (requires a syntactically valid Android package name)
- \`system.status\`
- \`notifications.list\`
- \`files.list\`
- \`files.search\` (query limited to 200 characters)
- \`files.read\` (SAF document ID limited to 1024 characters; text-only content capped at 64 KB)

Android files are accessed only through a folder selected in Android's system picker. The app verifies that a persisted read grant still exists before advertising file access.

## Desktop command allowlist

Windows, macOS and Linux currently support:

- \`system.status\`
- \`apps.list\`
- \`apps.launch\` using an app ID returned by \`apps.list\`
- \`files.list\`
- \`files.search\`
- \`files.read\` (text-only content capped at 64 KB)

Desktop file access is restricted to paths explicitly configured by the user in \`JARVIS_ALLOWED_DIRS\`. Path traversal and resolved-path escapes are rejected. Windows launches Start Menu shortcuts through a fixed PowerShell command with the target passed as an environment variable. macOS and Linux use fixed OS launcher commands with argument arrays. No arbitrary shell command or remote script execution is exposed.

## Safety and current limitations

The backend never executes shell commands, arbitrary code or direct filesystem operations. It checks declared capabilities and queues typed requests. A queued command is not proof of execution.

Android SAF folder selection and Notification Access remain explicit user-granted permissions. Desktop connectors require explicit folder roots. The current capability update endpoint accepts self-reported capabilities from a paired device; this is not hardware attestation and must not be treated as proof that a permission is genuine.

Sensitive operations remain blocked until a proper in-app confirmation flow is implemented. Account ownership and account-scoped listing/enrollment are implemented, but account-token revocation, device removal, multi-user roles and QR-based pairing are not implemented yet.

iOS/iPadOS, Wear OS, web and IoT are registry vocabulary only; no command connector is currently shipped for those platforms.
