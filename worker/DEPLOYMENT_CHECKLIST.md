# JARVIS Cloudflare deployment checklist

This guide prepares the Worker deployment. Do not put secrets in source files, Android resources, desktop connector files, or commit history.

## 1. Create the two KV namespaces

In Cloudflare Dashboard, open **Storage & databases → KV** and create two namespaces:

- `jarvis-device-registry`
- `jarvis-device-commands`

Open **Workers & Pages → jarvis-api → Settings → Bindings** and add KV namespace bindings with these exact variable names:

| Variable name | KV namespace |
| --- | --- |
| `DEVICE_REGISTRY` | `jarvis-device-registry` |
| `DEVICE_COMMANDS` | `jarvis-device-commands` |

The names are case-sensitive. The Worker device API returns HTTP 503 when these bindings are absent.

## 2. Configure GitHub Actions secrets

In the GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret** and configure:

| Secret | Purpose |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | Cloudflare API token with permission to deploy Workers and manage Worker secrets |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare account ID |
| `FISH_API_KEY` | Fish Audio TTS |
| `DEVICE_ENROLLMENT_SECRET` | Random bootstrap secret, minimum 32 characters |
| `DEVICE_TOKEN_SECRET` | Independent random HMAC signing secret, minimum 32 characters |
| `SERPAPI_API_KEY` | Optional product/image search |

Generate two different high-entropy values for the device secrets. Do not reuse the Cloudflare API token, Fish Audio key, or each other. Treat the enrollment secret and account tokens as credentials. The deployment workflow validates the required secrets and writes them to the Worker using Wrangler; values are never printed intentionally.

## 3. Deploy

After KV bindings exist and all required GitHub secrets are configured, open **Actions → Deploy JARVIS Cloudflare Worker → Run workflow**. The workflow deploys the Worker named `jarvis-api`.

The deployment workflow does not create KV namespaces or attach bindings automatically. Complete step 1 first. Do not trigger deployment until the bindings and secrets are ready.

## 4. Verify deployment

Check:

- `GET /health` returns `ok: true`.
- `POST /v1/accounts/create` without the bootstrap secret returns HTTP 401.
- The same endpoint with the bootstrap secret returns HTTP 201 and an account token. Keep that token private.
- `POST /v1/devices/enroll` with the account token enrolls a test device.
- `GET /v1/devices` with the account token lists only devices belonging to that account.
- A device token can poll only its own command queue.

Do not paste secrets or bearer tokens into public issues, chat screenshots, or source code. If a secret is exposed, rotate it. Rotating `DEVICE_TOKEN_SECRET` invalidates all existing account and device tokens.

## 5. Current external validation still required

A successful GitHub Actions build is not proof of a live deployment or physical-device integration. After deployment, install the APK on the target Android phone, grant only the required permissions, pair it with a test account, and verify heartbeat, command polling, command execution and acknowledgement. Desktop connectors must be tested on their respective host operating systems.
