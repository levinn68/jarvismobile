# JARVIS All Access: Secure AI-to-Device Rollout

## Scope

This change introduces the first authenticated AI-to-device execution path for paired Android, Windows, Linux, and macOS agents. It reuses the existing account registry, device capability policy, command queue, and device acknowledgements.

## AI endpoint authentication

The following endpoints now require a valid Supabase access token:

- `POST /v1/chat`
- `POST /v1/tts`
- `POST /v1/search/products`
- `POST /v1/search/images`

Clients send:

```http
Authorization: Bearer <supabase_access_token>
```

The Worker validates the token against the Supabase Auth `/auth/v1/user` endpoint. It does not trust a user ID supplied in the request body. The user ID maps to the existing JARVIS account ID `acct-<supabase-user-id>`.

Provider API keys are read only from Worker secrets. Client-provided `geminiApiKey` request fields and `X-Jarvis-Gemini-Key` headers are no longer used.

## Per-account request quotas

The initial limits are fixed-window quotas backed by the existing `DEVICE_REGISTRY` KV namespace:

| Endpoint family | Limit |
|---|---:|
| Chat | 30 requests/minute/account |
| TTS | 20 requests/minute/account |
| Image/product search | 15 requests/minute/account |

KV reads and writes are not an atomic counter. This is a best-effort abuse guard and can be exceeded under concurrent requests. Before broad production exposure, use a Cloudflare Rate Limiting binding or a Durable Object counter with atomic coordination.

## Browser origins

Native Android requests normally do not send an `Origin` header and remain supported.

If a browser frontend sends an `Origin` header, set the comma-separated Worker variable `JARVIS_ALLOWED_ORIGINS` to the exact approved origins, for example:

```text
JARVIS_ALLOWED_ORIGINS=https://app.example.com,https://www.example.com
```

When the variable is empty, origin checking is permissive for compatibility; authentication remains mandatory. Configure the real frontend origins before enabling browser use in production.

## AI-to-device routing

Explicit commands are routed to existing paired devices:

- `Buka WhatsApp di Windows` → `apps.launch_label`
- `Daftar aplikasi di Android` → `apps.list`
- `Cek status perangkat di Linux` → `system.status`
- `Cari file laporan di macOS` → `files.search`

The Worker:
1. Validates the Supabase session.
2. Resolves the account from the verified Supabase user ID.
3. Lists only devices indexed under that account.
4. Filters devices by declared capability and requested platform/name.
5. Requires a recent device heartbeat.
6. Validates the command against the platform-specific protocol.
7. Enqueues the command through the existing capability policy.
8. Waits up to 22 seconds for acknowledgement.
9. Supplies only the authoritative command result to the AI response context.

If more than one online device matches, JARVIS asks the user to specify a device. It does not broadcast the command to all devices.

Sensitive actions remain gated by local device approval. A remote request or model-generated claim cannot mark an action as user-approved.

## Android client

The Android chat transport sends the current Supabase access token in the Authorization header. It no longer sends a user-supplied Gemini provider key to the Worker.

Users must sign in with Google and have a valid Supabase session before using authenticated AI endpoints. Device enrollment/pairing continues to use the existing account-token flow.

## Rollout and verification

- The changes are isolated on `feature/all-access-secure-tool-execution-20261105`.
- Pull request: [#15](https://github.com/levinn68/jarvismobile/pull/15).
- Worker deployment workflow is skipped on feature branches; no live Worker deployment is performed by this change.
- Do not apply Supabase schema migrations for this feature; it uses the existing device KV registry and command queue.
- CI must pass before merging.
- Physical Android, Windows, Linux, and macOS device tests remain required before declaring end-to-end completion.