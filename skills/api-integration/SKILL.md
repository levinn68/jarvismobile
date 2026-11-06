---
name: api-integration
description: Integrate third-party APIs with robust authentication, schemas, retries, quotas, timeouts, and observability.
---

# API Integration Engineering

## Contract first
- Read the provider's current official API documentation.
- Confirm endpoint, method, auth scheme, required fields, response schema, quotas, and version lifecycle.
- Validate request and response data at the boundary.
- Keep provider-specific code behind a clear adapter.

## Reliability
- Set explicit connection and total timeouts.
- Retry only transient failures (e.g. selected 429/5xx) with bounded exponential backoff and jitter.
- Respect Retry-After and provider quota limits.
- Never blindly retry non-idempotent writes.
- Distinguish authentication, permission, validation, quota, timeout, and server errors.
- Use circuit breaking or provider fallback only when semantics remain correct.
- Avoid infinite retries and uncontrolled API-key rotation.

## Security and observability
- Store credentials server-side.
- Redact secrets from logs and telemetry.
- Log request correlation IDs, provider, latency, status, and sanitized error categories.
- Never log full sensitive payloads by default.