# PeaProxy integration for J.A.R.V.I.S.

This integration adds PeaProxy as an **optional OpenAI-compatible upstream**. Existing Gemini API, DeepSeek, Groq, OpenAI, and OpenRouter providers remain unchanged.

## Important provider notice

PeaProxy documents Gemini/Antigravity subscription OAuth as an unofficial route and warns that it may violate provider terms and may lead to account restrictions. This repository does not automate Google OAuth, extract Google session cookies, or store Google OAuth refresh tokens. Use official Google AI Studio API credentials for supported production usage. Subscription OAuth use is entirely at the user's risk and may be blocked upstream.

## Architecture

For a Cloudflare Worker deployment, PeaProxy cannot remain bound only to `127.0.0.1` on a laptop. A Worker cannot connect to a loopback address on the user's device. The user must operate an HTTPS-reachable PeaProxy host, for example through a Cloudflare Tunnel protected by Cloudflare Access service authentication.

Recommended network path:

`J.A.R.V.I.S. Android -> J.A.R.V.I.S. Worker -> HTTPS Cloudflare Tunnel + Access -> PeaProxy -> configured upstream account`

Keep PeaProxy bound to loopback behind the tunnel. Do not expose port 8317 directly to the public internet.

## Worker configuration

Set these values as Cloudflare Worker Secrets (not in source control):

- `PEAPROXY_BASE_URL`: HTTPS base URL ending in `/v1`, e.g. `https://your-protected-host.example/v1`.
- `PEAPROXY_MODEL`: exact model ID returned by PeaProxy's live `GET /v1/models` catalog.
- `PEAPROXY_ACCESS_CLIENT_ID`: Cloudflare Access service-token client ID.
- `PEAPROXY_ACCESS_CLIENT_SECRET`: Cloudflare Access service-token client secret.

The Access application must require service authentication and allow this service token. The Worker sends both Access headers with its non-streaming OpenAI Chat Completions request to `/v1/chat/completions`. PeaProxy is not expected to validate a separate J.A.R.V.I.S. Bearer token; Cloudflare Access protects the public tunnel before the request reaches PeaProxy.

The provider is considered configured only when the HTTPS base URL, exact model ID, and both Access credentials are present. The Worker does not guess model IDs.

## Local verification

With PeaProxy running locally, verify its model catalog:

```powershell
Invoke-RestMethod http://127.0.0.1:8317/v1/models
```

The local URL is only for a local test tool. It must not be configured as the Cloudflare Worker's production upstream.

To verify chat locally, use PeaProxy's documented endpoint. Do not paste Access service-token values or OAuth credentials into GitHub issues, chat messages, or source files.

## Tests

Run:

```powershell
npm test --prefix worker
```

The tests verify provider opt-in, HTTPS validation, OpenAI-compatible request shape, Access service-token headers, and fallback behavior.
