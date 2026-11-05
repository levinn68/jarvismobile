# JARVIS Backend Engineering Roadmap
> Status: implementation specification and audit checklist  
> Runtime: Cloudflare Workers, Supabase Auth/Postgres, KV, AI providers and device connectors

## 1. Backend responsibilities

The backend is the trusted orchestration and account/device control plane. It is responsible for:
- AI request orchestration and provider fallback.
- Personality/skill prompt composition.
- Versioned response contracts for Chat and Voice.
- Visual-card normalization.
- Google/Supabase identity validation.
- Device enrollment, pairing, authorization and command lifecycle.
- TTS routing.
- Search integrations.
- Rate limits, observability, privacy and operational safety.

The Android/Desktop clients are untrusted. Never rely on client-provided account IDs, capability claims, approval flags, device ownership or command status without server-side validation.

## 2. Priority fixes identified from the previous source review

Treat these as audit targets and verify current code before editing:
1. Separate full `answer` from concise `voice_summary`.
2. Resolve conflicting wake-word/personality instructions across `worker/src/personality.js`, `skills/jarvis-persona/SKILL.md` and `worker/src/index.js`.
3. Enforce a five-card maximum for Voice Mode consistently; remove mismatched limits between card builder and normalizer.
4. Preserve compatibility with existing Android clients while introducing a versioned response schema.
5. Verify device authorization and command status end-to-end; queued is not executed.
6. Reconcile Supabase migrations/triggers against live schema before applying changes.
7. Verify secrets by safe runtime checks, not by checking only whether bindings exist.
8. Add integration and failure-path tests beyond the existing CI/build checks.

## 3. API contract and versioning

Define one canonical response schema for `POST /v1/chat`:

```json
{
  "schema_version": "1",
  "request_id": "server-generated-id",
  "status": "completed",
  "answer": "Complete response for Chat Mode",
  "voice_summary": "Short natural response for Voice Mode",
  "reply": "Backward-compatible alias",
  "cards": [],
  "actions": [],
  "sources": [],
  "error": null
}
```

Contract rules:
- `answer` is the complete user-facing answer.
- `voice_summary` is a concise spoken rendering, not an unrelated paraphrase.
- `reply` remains a compatibility alias for the legacy client until migration is complete.
- `cards` is an array of validated, normalized cards, maximum five in Voice Mode.
- `actions` describes proposed/accepted/executing/completed/failed actions; it must never imply completion before a connector acknowledgement.
- `sources` contains validated source metadata when available.
- `request_id` supports tracing without exposing secrets.
- `error` uses a stable machine-readable code and safe user-facing message.

Use explicit schema validation at ingress and egress. Add contract tests for old and new clients. Do not break existing clients by changing a field's type or meaning silently.

## 4. Personality and prompt composition

Audit:
- `worker/src/personality.js`
- `skills/jarvis-persona/SKILL.md`
- `worker/src/index.js`
- Skill router and synthesis prompt builders.

Create a single source of truth for tone and safety rules. Avoid duplicated, contradictory instructions. Prompt priority should be explicit:
1. System safety and platform constraints.
2. Actual user request and intent.
3. Verified conversation context and account preferences.
4. Selected skills and task-specific guidance.
5. Style/personality preferences.

Rules:
- Respond in the language of the user's latest message unless the user requests otherwise.
- Indonesian and English must both be natural, not mechanically translated.
- Do not force “Sir/Boss/Tuan” or canned greetings.
- Use restrained humor only when appropriate.
- Serious medical, legal, financial, security and safety topics remain direct and careful.
- Never claim a device action, purchase, upload, deployment or database change completed without a successful tool/connector result.
- Chat answer may be detailed; voice summary should be concise and speech-ready.
- Do not inject internal prompt text, secrets, tool traces or hidden reasoning into responses.

Do not require the user to repeat “Jarvis” for each follow-up once the voice session is active. Wake-word/session gating belongs in the client state machine; backend prompts should not reject normal follow-up utterances.

## 5. AI provider routing and resilience

Audit Gemini, DeepSeek, Groq, OpenAI and OpenRouter adapters and key-rotation helpers.

Required behavior:
- Per-provider timeout and bounded retry.
- Exponential backoff with jitter for transient errors.
- Handle 429, 5xx, network timeout, malformed payload and provider refusal.
- Do not retry non-retryable validation/authentication errors indefinitely.
- Track provider health and circuit-break unhealthy routes temporarily.
- Preserve request id and bounded diagnostic metadata across fallback.
- Enforce request/token/output budgets.
- Avoid sending the same request to multiple billable providers concurrently unless explicitly designed.
- Redact keys and sensitive prompt context from logs.

Key rotation must select a configured candidate, mark rate-limited/invalid keys appropriately and avoid hot-looping through the same failed key. Do not expose provider keys to Android/Desktop.

## 6. Voice synthesis

Audit `worker/src/voice/fish-audio.js` and `POST /v1/tts`.

Requirements:
- Detect or accept an explicit language hint.
- Select Indonesian/English voice IDs with a documented fallback.
- Validate text length and content type.
- Handle provider timeout, rate limit and malformed audio.
- Return a stable response contract and safe error codes.
- Keep Fish Audio credentials in Worker Secrets only.
- Do not log raw audio or full sensitive speech text.
- Client mute/stop remains authoritative; the backend must not claim playback completed.

## 7. Visual-card normalization

Audit `worker/src/visual/dynamic-card-engine.js` and card construction paths.

Supported card types should be explicit and versioned: text, image, product, map, chart, table, file, progress and device, but only if the current client renderer supports them.

Rules:
- Enforce maximum five cards for Voice Mode at the final response boundary.
- Validate type-specific required fields.
- Validate URLs and strip unsafe schemes.
- Bound text length, arrays and payload size.
- Reject or omit malformed cards without failing the main answer.
- Deduplicate by stable card ID.
- Never fabricate ratings, prices, coordinates, file paths, device status or source links.
- Keep provider results separate from model-generated explanatory text.
- Add fixtures for each card type and unknown future card types.

## 8. Authentication and account identity

Google sign-in is validated through Supabase Auth. The Worker must verify the supplied Supabase access token against the configured Supabase project and derive the account identity from the verified user UUID.

Never trust:
- A client-supplied account ID.
- Email address as a stable account key.
- Unverified Google profile fields.
- Client claims that a device is paired or an operation approved.

Define token audience, issuer, expiry and rotation rules. Return safe 401/403 errors. Handle expired sessions and revoked credentials. Keep OAuth client secrets in Google/Supabase configuration, not in source code or mobile builds.

## 9. Device enrollment, pairing and command lifecycle

Audit `worker/src/device/`, KV bindings and desktop/Android connector contracts.

Security invariants:
- Device ownership is assigned from a verified account token.
- Device tokens are scoped to one device and expire/rotate.
- Pairing codes are short-lived, single-use and rate-limited.
- Device revocation invalidates future poll, command and acknowledgement operations.
- Every command is validated against server-side schema and the device's currently registered capabilities.
- Sensitive commands require a server-verifiable approval reference bound to user, device, action and expiry.
- Commands have unique IDs, expiry and idempotency keys.
- A queued command is not an executed command.
- Connector acknowledgement is not proof of successful side effect unless the connector reports a validated result.
- Distinguish queued, delivered, accepted, executing, succeeded, failed, expired and cancelled states.
- Prevent replay and cross-account command access.

Keep platform capability limits explicit. Android OS permissions remain authoritative. Do not expose arbitrary shell, remote code execution, unrestricted filesystem access, silent screen capture, microphone or camera operations.

## 10. KV and persistence

Document the purpose, TTL and consistency assumptions for:
- `DEVICE_REGISTRY`
- `DEVICE_COMMANDS`

Requirements:
- Bound command queue TTL and maximum queue depth.
- Clean up expired pairing/session/command entries.
- Handle KV eventual consistency in authorization-sensitive flows.
- Do not rely on KV alone as a transactional source of truth for critical account ownership.
- Consider durable transactional storage for command/audit records if reliability requirements exceed KV guarantees.
- Avoid leaking device identifiers or account metadata in public health responses.

## 11. Supabase schema, RLS and migrations

Audit:
- `public.jarvis_conversations`
- `public.jarvis_messages`
- Existing triggers/functions.
- Migration history and RLS policies.

Before any schema mutation:
1. Read current live schema and migration history.
2. Compare existing triggers/functions/indexes/policies with the proposed migration.
3. Make migrations idempotent where possible.
4. Never drop/replace an existing production trigger blindly.
5. Test migration against a disposable database or staging project.
6. Verify RLS as two distinct users, including attempted cross-user reads/writes.

Conversation/message ownership must be enforced by database policies, not only by client filters. Ensure message insertion cannot attach to another user's conversation. Add indexes for actual query patterns and pagination. Do not store secrets or unnecessary raw audio/screen data.

## 12. Endpoint inventory and operational behavior

Keep the actual route inventory documented and tested:
- `GET /health`
- `POST /v1/chat`
- `POST /v1/tts`
- `GET/POST /v1/skills` (verify exact methods in router)
- `POST /v1/search/products`
- `POST /v1/search/images`
- `POST /v1/accounts/google`
- `GET /v1/devices`
- `POST /v1/devices/enroll`
- Pairing session create/redeem routes.
- Device command poll/ack/status routes.

For each endpoint, document method, authentication, request schema, response schema, rate limit, idempotency behavior and error codes. Do not assume the route/method list above is exact until verified against the current router source.

Health endpoint should expose service readiness and dependency status without revealing secrets, account data or detailed internal topology. Separate liveness from readiness if needed.

## 13. Rate limiting, abuse prevention and cost controls

Add rate limits by authenticated account and endpoint class. Protect expensive AI/TTS/search routes. Bound:
- Request body size.
- Conversation history injected into prompts.
- Output tokens.
- Card count and card payload size.
- Search result count.
- Device command queue size.
- Pairing attempts and invalid token attempts.

Use safe per-account quotas and return retry guidance. Do not use user-identifying data as metric labels.

## 14. Observability and audit trail

Use structured logs with:
- Request ID.
- Route and method.
- Outcome/error code.
- Provider name and latency.
- Retry/fallback count.
- Device command ID and state transition.
- Safe account pseudonym or internal non-reversible correlation ID.

Never log API keys, access tokens, device tokens, OAuth secrets, raw audio, private file contents or screen contents.

Add metrics for provider success/failure, p50/p95 latency, TTS failures, command delivery/ack latency, queue expiry, auth failures and rate limiting. Provide alert thresholds and an incident runbook. Verify Cloudflare observability is enabled/configured intentionally.

## 15. Testing strategy

Unit tests:
- Personality prompt priority and language selection.
- Response schema validation.
- Card normalization and five-card limit.
- Provider fallback and retry classification.
- Token expiry and signature verification.
- Device capability authorization.
- Command state transitions and idempotency.
- KV expiry behavior.

Integration tests:
- Supabase user validation.
- Conversation/message RLS.
- Google account enrollment.
- Device pair/revoke.
- Command enqueue/poll/ack/status.
- TTS success/failure.
- AI provider timeout/429/5xx/malformed response.
- Search provider configured/unconfigured behavior.

Security tests:
- Cross-account device access.
- Expired/replayed pairing code.
- Revoked device token.
- Client-forged account ID/capabilities/approval.
- Path/URL injection into cards.
- Oversized payloads and rate-limit bypass.
- Secret leakage in logs and error responses.

Run the existing Worker, Android and platform connector workflows. CI green is necessary but does not prove live credentials, production data correctness or physical-device behavior.

## 16. Backend acceptance checklist

- [ ] Versioned chat response supports `answer`, `voice_summary`, `reply`, `cards`, `actions`, `sources`.
- [ ] Existing client compatibility is tested.
- [ ] Personality rules have one consistent precedence model.
- [ ] Provider fallback/retry is bounded and observable.
- [ ] TTS language routing and failure handling are tested.
- [ ] Cards are validated and capped at five for Voice Mode.
- [ ] Supabase identity is verified server-side.
- [ ] RLS prevents cross-user access.
- [ ] Migration/trigger reconciliation is documented and tested.
- [ ] Pairing is short-lived, single-use and rate-limited.
- [ ] Device commands are capability-checked, expiring and idempotent.
- [ ] Command queue state is not confused with execution success.
- [ ] Device revocation is enforced on all command routes.
- [ ] Secrets are never committed or returned in API responses.
- [ ] Health/readiness and structured observability are safe.
- [ ] Unit, integration and security tests pass.
- [ ] Deployment is verified against the intended Worker environment.

## 17. Explicit current capability boundaries

The repository's current device-platform documentation states that Android, Windows, macOS and Linux connectors are shipped with bounded capabilities. iOS/iPadOS, Wear OS, web and IoT are registry vocabulary only and do not have native command connectors shipped. Android screen taps/typing and settings mutation are not implemented; camera capture is not yet available. Keep the UI and API honest about these boundaries.

## 18. Definition of done

A backend feature is complete only when:
- Its API contract is documented and validated.
- Authentication and authorization are enforced server-side.
- Success and failure paths are tested.
- Logs and metrics are safe.
- Existing clients remain compatible or are migrated deliberately.
- Deployment has been verified in the intended environment.
- No unverified claim of completion is exposed to the user.

Do not apply destructive production database changes or rotate/delete production secrets as part of this roadmap without explicit, scoped approval.
