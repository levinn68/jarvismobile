# JARVIS security notes — pairing & screen control

## Pairing sessions

| Property | Behavior |
| --- | --- |
| One-time | Code status becomes `redeemed` after first successful enroll; subsequent redeem fails |
| Expiration | KV TTL 300 seconds; Android UI counts down the same window |
| Account binding | Session stores `accountId` from the authenticated account token; redeem cannot change ownership |
| Rate limit | Max 10 session creates per account per hour |
| Brute force | Attempt counter; code locks after 8 failed redeem attempts |
| Replay | Redeemed codes cannot be reused; expired keys disappear from KV |
| Remote `userConfirmed` | Never trusted for screen control or other sensitive capabilities |

## Device tokens

- Issued as HMAC-signed bearer tokens scoped to one `deviceId`.
- Desktop agents store tokens under the user config directory (`~/.config/jarvis/` or `%APPDATA%\\jarvis`) with restrictive file modes when the OS supports them.
- Revocation: account owner calls `DELETE /v1/devices/:id`; subsequent poll/ack returns 404 and connectors stop.

## Screen control approval

- `screen.tap` / `screen.type` may be queued when the device advertises `screen.control`.
- Android never auto-executes them. `ScreenActionApprovalActivity` requires an explicit on-device dialog choice.
- Rejected or dismissed actions are acknowledged as failed and are not performed.
- Accessibility gestures run only after local approval marker is set by that activity.

## What is intentionally not supported

- Silent screen interaction without a visible approval dialog
- Keylogging or background screen capture
- Pairing codes that enroll devices into a different account
- Committing device tokens or account tokens to git
