# J.A.R.V.I.S. Modular Ecosystem Architecture

**Status:** Architecture proposal / implementation baseline  
**Target repository:** `levinn68/jarvismobile`  
**Branch:** `architecture/modular-jarvis-ecosystem`  
**Date:** 2026-11-06

> This document defines the intended architecture. It does not claim that every module described below is implemented or production-verified. Existing code is preserved and extended incrementally.

## 1. Goals

Evolve the existing Android + Cloudflare Worker + Supabase + desktop-agent project into a modular assistant ecosystem with:
- independent subsystems and explicit contracts;
- a central orchestrator that routes work without owning every implementation;
- JVS series as the AI intelligence layer;
- autonomous research and learning with provenance, validation, and user controls;
- device and desktop operations behind per-platform permission boundaries;
- shared infrastructure without mixing users' private data;
- observable, testable, reversible releases.

Non-goals:
- rewriting the project from scratch;
- silently changing the current Android API contract;
- moving built-in provider keys into APKs;
- requiring every user to configure built-in provider keys;
- allowing autonomous production deployments, arbitrary shell execution, or unapproved sensitive device actions.

## 2. Existing implementation observed on 2026-11-06

The repository already contains modular foundations under `worker/src/`:
- `ai/`: provider routing, fallback, key health/rotation, provider orchestration and reasoning;
- `core/`: event bus, task orchestrator and core facade;
- `device/`: pairing, auth, registry, policy, commands and routing;
- `connectors/`: platform capability metadata;
- `skills/`: skill registry and device skills;
- `visual/`: dynamic cards, hologram and voice visual state;
- `voice/`: communication and Fish Audio helpers;
- Android app and desktop connectors under `app/` and `desktop/`.

The root README states that the Android build workflow extracts `JarvisMobile-source.zip`; the Kotlin source tree is not represented as a normal complete Gradle project at repository root. Preserve this source-bundle build contract unless a separately reviewed migration is approved.

The current `worker/src/core/task-orchestrator.js` has a useful handler/authorization seam, but its default authorization callback allows tasks when no authorizer is supplied. Production entrypoints must pass explicit authorization policies. The current core facade is intentionally small and should become a composition root, not an unrestricted god object.

The current skill registry contains a catalog of skill specifications and placeholder default `run()` implementations. Do not represent catalog entries as fully implemented agents until real handlers and tests exist.

The current chat route uses built-in provider orchestration but does not yet expose a complete JVS series selector or a durable learning pipeline. The `/v1/skills` route currently returns the skill catalog without authentication. Review whether it is public by design; otherwise protect it.

## 3. Target subsystem map

### A. Central Orchestrator
Responsibilities:
- accept authenticated user intents;
- create traceable task IDs and task plans;
- select the subsystem or agent;
- enforce policy and approval requirements before execution;
- manage deadlines, cancellation, idempotency and bounded retries;
- collect results and return the existing response contract.

It must not contain provider-specific HTTP code, database schema logic, platform-specific device actions, or unbounded autonomous loops.

### B. JVS Intelligence Layer
JVS is the internal AI series abstraction, separate from external providers.

Initial series:
- JVS CORE 1.0
- JVS PULSE 1.0
- JVS SPARK 1.0
- JVS APEX 1.0

Each series has a versioned profile:
- purpose and capability policy;
- prompt/behavior configuration;
- provider/model routing policy;
- reasoning budget and tool permissions;
- memory scope;
- evaluation suite and release history.

The five built-in providers remain configured centrally as Cloudflare Worker Secrets. The API keys are shared backend credentials available to connected APK devices through authenticated Worker requests; they are never shipped inside the APK. Users may separately add custom providers/endpoints per their account/device ownership model. Custom credentials must be isolated, encrypted at rest, and never written to logs or returned to clients.

A JVS series does not imply a new base model or fine-tuned weights. Initially, series are orchestration/configuration profiles over the available providers. Fine-tuning is a separate future capability requiring model/provider support, datasets, evaluation and cost approval.

### C. Agent System
Responsibilities:
- create short-lived task agents with a narrow role;
- delegate sub-tasks and track dependencies;
- isolate context and tools per agent;
- impose max depth, max agents, token/time/cost budgets;
- require explicit approval for external side effects;
- store task results and provenance.

Agent instances are not copies of model weights. They are isolated task contexts using configured models and tools.

### D. Memory System
Separate scopes:
- conversation context (short-lived);
- user-approved long-term preferences;
- episodic task history;
- semantic knowledge references;
- procedural/skill metadata;
- shared public/product knowledge.

Never place one user's private memory into another user's retrieval context. Every retrieval must be ownership-filtered on the server before content is passed to a model.

### E. Autonomous Learning System
Lifecycle:
1. schedule or receive a user-authorized research goal;
2. collect information from allowed sources;
3. store source URL, publisher, retrieval time, content hash and collection method;
4. normalize and chunk content;
5. detect duplicates and conflicting claims;
6. evaluate source trust and freshness;
7. create embeddings and retrieval indexes;
8. stage candidate knowledge;
9. run quality, safety and privacy checks;
10. publish accepted knowledge to the appropriate scope;
11. evaluate retrieval quality and roll back bad knowledge versions.

Learning levels:
- knowledge-base updates and RAG (first);
- preference/behavior adaptation through approved feedback (first);
- skill updates in sandbox (later);
- model fine-tuning (optional future phase).

Retrieved content is untrusted data, not executable instructions. Prompt-injection defense must run before any retrieved content is supplied to agents. Knowledge changes must be versioned and reversible. A single user correction must not silently rewrite global behavior.

### F. Device Intelligence
Keep platform-native operations in dedicated Android/desktop connectors. Worker routes must verify account, device token, capability, freshness, and approval. Sensitive actions require approval on the target device. Never infer consent from a remote client flag.

### G. Computer Intelligence
Desktop agents remain separate Node.js processes with:
- explicit approved directories;
- command allowlists;
- no arbitrary shell by default;
- per-action capability declarations;
- local credential storage;
- audit logs and revocation.

### H. Multimodal Intelligence
Separate adapters for:
- images and screenshots;
- audio input/transcription;
- speech output;
- video/frame extraction;
- document ingestion.

Each adapter declares input size limits, retention, permissions, model/provider compatibility and fallback behavior.

### I. Security & Policy
Central policy service with:
- authenticated user and device identity;
- resource ownership checks;
- per-capability permission checks;
- explicit confirmation for sensitive actions;
- rate/concurrency/cost limits;
- audit events without secret or private payload leakage;
- prompt-injection and SSRF defenses.

### J. Automation & Workflow
Use durable background workflows for multi-step tasks that must retry, wait, resume or be scheduled. Each workflow must be idempotent and expose cancel/status operations. Use queue-based buffering for bursts; do not implement durable jobs as untracked promises in a request handler.

### K. Knowledge & Search
Use Supabase Postgres as the primary relational system and pgvector for semantic retrieval where appropriate. Keep source documents and chunk metadata separate. Retrieval access must respect ownership/RLS; server-side privileged retrieval must explicitly filter by owner and scope.

### L. MCP & Plugin Ecosystem
Each tool/plugin declares:
- stable tool ID and version;
- input/output schema;
- required capabilities;
- trust level;
- timeout and resource budgets;
- side-effect classification;
- confirmation requirement;
- audit event contract.

Tool discovery does not imply permission to execute a tool.

### M. Observability & Health
Collect structured traces across Android -> Worker -> provider/tool -> Supabase. Redact credentials and sensitive user data. Expose health checks for dependencies, queue/workflow state, provider availability, latency, error rate and budget utilization.

## 4. Infrastructure responsibilities

### GitHub
- source of truth for code, migrations and architecture decisions;
- PR-based change review;
- CI for Worker, desktop, SQL and Android bundle build;
- protected production deployment approval;
- release manifests and rollback instructions.

### Cloudflare
- Workers: authenticated API and orchestration entrypoint;
- Worker Secrets: built-in provider keys, device signing secrets and dedicated encryption secrets;
- KV: cache/ephemeral lookup only where eventual consistency is acceptable;
- Durable Objects: atomic per-entity coordination (pairing redemption, quotas/locks, task coordination) when required;
- Queues: asynchronous buffering;
- Workflows: durable multi-step jobs;
- AI Gateway: optional provider request observability/routing if it fits existing provider integration;
- R2: optional large document/audio/image object storage.

Do not create or bind new production resources until names, environments, permissions, limits and cost implications are reviewed.

### Supabase
- Auth: user identity;
- Postgres: conversations, user preferences, task metadata, knowledge records, provenance and audit metadata;
- pgvector: embeddings and semantic retrieval;
- RLS: owner isolation for exposed tables;
- Storage: optional private uploaded documents/media.

Never expose `service_role` or secret keys to Android. Keep authorization attributes in trusted server-controlled claims/tables, not user-editable metadata.

## 5. Critical findings to address before broad feature work

These observations are based on source/configuration inspection and require focused remediation:
1. `requestOriginAllowed()` treats an empty origin allowlist as allow-all, while the main Worker also declares wildcard CORS headers. Separate browser CORS from native client authentication and fail closed for browser origins in production.
2. Pairing redemption reads and updates a pending session in KV using multiple read/put operations. KV does not provide compare-and-set semantics; concurrent redemptions need atomic coordination (prefer a Durable Object or transactional database mechanism).
3. Account rate limiting performs KV read-modify-write and can race. Do not use it as strict quota enforcement.
4. Device command acknowledgement also uses KV read-modify-write. Define idempotent/atomic completion semantics where concurrent acknowledgements matter.
5. Built-in provider orchestration currently has fixed provider model defaults and provider order by task complexity. Add a versioned JVS profile/config layer without breaking the existing provider fallback behavior.
6. `/v1/skills` currently lacks an authentication check. Decide whether catalog visibility is intentionally public; protect execution and private skill data regardless.
7. `task-orchestrator.js` defaults to allow-all authorization when no authorizer is provided. Production task handlers must inject explicit authorization and capability checks.
8. Skill registry default handlers are placeholders. Separate metadata catalog from executable handlers and report unavailable handlers honestly.
9. Supabase advisors reported two GraphQL schema exposure warnings for chat tables, eight RLS initplan performance warnings, and one unindexed foreign key. Review intended GraphQL exposure, optimize policy expressions using scalar subqueries where appropriate, and add only justified indexes.
10. Worker deployment workflow deploys on pushes to `main`. Add protected environment/manual approval or an equivalent production gate before future production releases.
11. README describes required secrets but code/configuration must be reconciled with actual deployed bindings. In particular, do not conflate `DEVICE_TOKEN_SECRET` with a dedicated AI credential encryption key.

Encryption architecture:
- built-in provider keys stay in Cloudflare Worker Secrets;
- a dedicated `AI_CREDENTIAL_ENCRYPTION_KEY` may encrypt user-managed custom provider credentials;
- device token signing remains a separate key purpose;
- no hardcoded/default master encryption key;
- missing encryption key must fail closed;
- rotation must support key IDs and migration/decryption of existing ciphertext before retiring old keys.

## 6. Supabase data model proposal (not yet applied)

Add tables incrementally through versioned migrations after inspecting current schema and testing on an isolated development database:
- `jarvis_tasks`: task lifecycle, owner, series, trace ID, status, timing and budget;
- `jarvis_task_events`: append-only state transitions and safe diagnostics;
- `jarvis_knowledge_sources`: source provenance, owner/scope, hashes, freshness and trust metadata;
- `jarvis_knowledge_chunks`: chunk text, source reference, embedding and metadata;
- `jarvis_learning_runs`: research/training job status, policy decisions and evaluation summary;
- `jarvis_jvs_profiles`: versioned JVS series profiles and activation state;
- `jarvis_skill_versions`: skill metadata, version, checksum and approval state.

All user-owned rows must have owner IDs and explicit RLS policies. Shared/global knowledge must use a distinct scope and administrative publishing path. Do not let users update system-owned JVS profiles or global knowledge directly.

The existing chat tables remain intact. Do not perform destructive schema changes or alter existing user data in this architecture phase.

## 7. Recommended implementation phases

### Phase 0 — Stabilize existing foundation
- verify current Worker and Android bundle build;
- add missing explicit authorizers;
- tighten CORS and route exposure;
- harden pairing/command atomicity;
- reconcile provider secret and encryption configuration;
- protect production deployment.

### Phase 1 — Modular contracts
- define subsystem interfaces and typed request/result contracts;
- make central orchestrator a composition root;
- split skill catalog from executable handlers;
- introduce trace/task IDs, cancellation and idempotency;
- preserve current Android API response shape.

### Phase 2 — JVS profile engine
- add CORE/PULSE/SPARK/APEX versioned profiles;
- route each profile to the existing five built-in providers and supported models;
- keep custom provider configuration per owner;
- implement profile evaluation benchmarks and version history.

### Phase 3 — Memory and knowledge
- create isolated user memory scopes;
- add source provenance, chunking and semantic retrieval;
- implement owner-filtered RAG and retention/deletion controls.

### Phase 4 — Autonomous learning
- research scheduler and job queue/workflow;
- source trust and conflict checks;
- staged knowledge publication;
- evaluation, rollback and audit trail.

### Phase 5 — Agents, MCP and automation
- isolated bounded agents;
- MCP tool registry with schemas and permissions;
- durable workflows and approval gates;
- task monitoring and cancellation.

### Phase 6 — Device, desktop and multimodal integration
- unify connector capability contract;
- add cross-platform conformance tests;
- integrate approved multimodal pipelines;
- validate real hardware and desktop systems.

### Phase 7 — Observability and release engineering
- end-to-end tracing and safe logs;
- cost/latency/error dashboards;
- staging validation;
- protected production rollout and rollback.

## 8. Acceptance criteria

A module is not marked complete merely because a directory, catalog entry, schema or mock test exists.

Each module must provide:
- documented contract and ownership boundary;
- actual implementation and dependency wiring;
- authentication/authorization and data isolation;
- unit tests and integration tests;
- failure, retry, timeout and cancellation behavior;
- structured redacted observability;
- migration/rollback plan where data is involved;
- truthful status: `PASS`, `FAIL`, `BLOCKED`, `MOCKED`, or `LIVE VERIFIED`.

Release readiness requires an actual Worker build/deploy verification, Android Gradle build from the documented source bundle, live provider integration tests, Supabase RLS tests, and physical device tests on the target ASUS ROG Phone 6. A static check or mocked upstream call must never be reported as live verification.

## 9. Change control

- Work on feature branches and open PRs.
- Do not merge or deploy production without explicit user approval.
- Do not apply Supabase production migrations during architecture planning.
- Do not create costly Cloudflare/Supabase resources without checking costs and receiving approval where required.
- Preserve current provider behavior, API compatibility, user data and device security boundaries.
