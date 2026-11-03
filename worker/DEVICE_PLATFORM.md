# JARVIS Universal Device Platform

The Worker is the control plane. A native connector on each device performs local operations through platform APIs and permissions.

## Modules
- src/device/protocol.js: platform/capability vocabulary and strict command validation.
- src/device/auth.js: HMAC-signed expiring device bearer tokens.
- src/device/policy.js: per-device capability authorization and confirmation gates.
- src/device/registry.js: device registration and heartbeat persistence.
- src/device/router.js: enrollment, registry, capabilities, heartbeat and command queue.

## Cloudflare setup
Create KV namespaces and bind them:
- DEVICE_REGISTRY: device metadata.
- DEVICE_COMMANDS: command queue with five-minute TTL.

Configure Worker Secrets:
- DEVICE_ENROLLMENT_SECRET: high-entropy bootstrap credential; never embed in a public APK.
- DEVICE_TOKEN_SECRET: high-entropy HMAC signing secret.

Enrollment is currently an operator/bootstrap operation. A public production rollout must replace the shared enrollment credential with account-authenticated short-lived pairing codes and user/device ownership checks.

## Routes
- POST /v1/devices/enroll — bootstrap enrollment; Authorization Bearer DEVICE_ENROLLMENT_SECRET.
- GET /v1/devices — enrolled device list.
- GET /v1/devices/:deviceId — own device record.
- POST /v1/devices/:deviceId/heartbeat — update last-seen.
- PUT /v1/devices/:deviceId/capabilities — update declared capabilities.
- POST /v1/device/command — validate and enqueue a typed command.
- GET /v1/device/commands?deviceId=... — connector polls commands.

## Safety
The backend never executes shell commands, arbitrary code, or direct filesystem operations. It checks declared capabilities and queues typed requests. Each connector must independently verify local OS permissions and confirm sensitive operations. A queued command is not proof of execution.

Android/iOS sandboxing and consent remain in force. File, screen, camera, microphone, contacts, notification, location and app access require platform-specific permissions or supported APIs.

- POST /v1/device/commands/ack — acknowledge a completed command and attach its result.
