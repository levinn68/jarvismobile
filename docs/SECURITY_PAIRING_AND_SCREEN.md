# JARVIS security notes — pairing & screen control

Last reviewed: 2026-11-03 (feature/qr-pairing-v1)

## Pairing sessions

| Property | Behavior |
| --- | --- |
| One-time | Code status becomes `redeemed` after first successful enroll; subsequent redeem fails |
| Expiration | KV TTL 300 seconds; Android UI counts down the same window |
| Account binding | Session stores `accountId` from the authenticated account token; redeem cannot change ownership |
| Rate limit | Max 10 session creates per account per hour (`pair-rate:{accountId}`) |
| Brute force | Attempt counter; code locks after 8 failed redeem attempts (`status=locked`) |
| Replay | Redeemed codes cannot be reused; expired keys disappear from KV |
| Alphabet | Ambiguous characters removed (`0/O/1/I`) — 8-char from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` |
| Remote `userConfirmed` | Never trusted for screen control or other sensitive capabilities |

### Flow summary

1. Android (signed-in Google account) calls `POST /v1/pairing/sessions` with account token.
2. Worker stores session in `DEVICE_REGISTRY` KV under `pair:{CODE}` with 5-minute TTL.
3. Desktop agent calls `POST /v1/pairing/redeem` with the code + device metadata (no account token required at redeem — ownership comes from the session).
4. On success: device is enrolled under the **same** `accountId`, device HMAC token is issued, session marked `redeemed` with short residual TTL.
5. Android polls `GET /v1/pairing/sessions/status?code=...` (account-scoped) and shows linked desktop identity.

## Device tokens

- Issued as HMAC-signed bearer tokens scoped to one `deviceId`.
- Desktop agents store tokens under the user config directory:
  - Unix/macOS: `~/.config/jarvis/device-credentials.json` (mode `0600`, dir `0700`)
  - Windows: `%APPDATA%\\jarvis\\device-credentials.json` (best-effort restrictive mode)
- Override path: `JARVIS_CREDENTIALS_FILE`
- Auto-reconnect: agents call `loadCredentials()` on start; no env vars required after first successful pair.
- Revocation: account owner calls `DELETE /v1/devices/:id`; subsequent poll/ack returns 401/404 and connectors exit with code 2.

## Screen control approval

- `screen.tap` / `screen.type` may be queued when the device advertises `screen.control`.
- Android never auto-executes them. `ScreenActionApprovalActivity` requires an explicit on-device dialog choice.
- Rejected or dismissed actions are acknowledged as failed and are not performed.
- Accessibility gestures (`GestureDescription`, `ACTION_SET_TEXT`) run only after local approval marker is set by that activity.
- Command executor applies dedupe + TTL so replayed command IDs are ignored.

## What is intentionally not supported

- Silent screen interaction without a visible approval dialog
- Keylogging or background screen capture
- Pairing codes that enroll devices into a different account
- Committing device tokens or account tokens to git
- Trusting client-supplied `userConfirmed` flags from remote callers

## Residual risks / operator notes

- Pairing codes are short-lived secrets: share only while the Android pairing screen is open.
- Physical access to a paired desktop machine can use the stored device token until revoked.
- Accessibility is powerful; keep it limited to the JARVIS service and revoke when unused.
- Worker deploy and `main` merge still require explicit owner approval.