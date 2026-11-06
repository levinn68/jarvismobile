# Batch 8.7 Full-Stack Import

- All 212 source paths from the uploaded B8.7 snapshot are included in this integration branch.
- The newer mainline Android MainActivity is preserved. The B8.7 MainActivity plus its matching part manifest and preview are archived under `docs/archive/b8-7/`.
- Dedicated per-user credential encryption master key is now required via `AI_CREDENTIAL_ENCRYPTION_KEY`; no static or device-signing fallback is permitted.
- Browser CORS is exact-origin allowlist only. Origin-less Android calls remain subject to normal JWT/device authentication.
- Custom endpoints require HTTPS public DNS names and reject common private, local, link-local, metadata, credentials, query-string and fragment forms. Authenticated provider requests do not follow redirects.
- Staging/development no longer bind the production KV IDs. Dedicated namespaces must be provisioned before stateful remote tests.
- Worker deploy workflow is manual and protected by the `production` GitHub environment.
- KV pairing/quota read-modify-write operations remain non-atomic and need Durable Objects or transactional storage before strict single-use or billing enforcement.
- Connected Supabase migration history differs from this snapshot. Migrations must be reconciled against the live schema before any apply.
- Android Gradle build and physical ROG Phone 6 tests remain release gates.
