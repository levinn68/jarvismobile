---
name: cloudflare-workers
description: Build and maintain Cloudflare Workers integrations with correct bindings, secrets, routing, runtime constraints, and deployment verification.
---

# Cloudflare Workers Specialist

## Inspect first
- Read wrangler configuration, entrypoint, bindings, compatibility date, routes, and existing endpoints.
- Identify environment bindings: vars, secrets, KV, D1, R2, Durable Objects, queues, and services.
- Confirm the current Cloudflare runtime APIs and compatibility settings.

## Implementation
- Keep secrets in Worker Secrets, never in source or client code.
- Validate request method, path, content type, payload size, and schema.
- Return consistent status codes and response shapes.
- Handle CORS deliberately; do not use permissive wildcard origins with credentials.
- Use bounded provider timeouts and retries.
- Avoid unsupported Node.js APIs unless compatibility flags and runtime support are confirmed.
- Preserve existing endpoint contracts.

## Verification
- Run available local checks and tests.
- Verify health endpoint and representative success/failure paths.
- Distinguish local test from deployed Worker.
- Deploy only with explicit authorization.
