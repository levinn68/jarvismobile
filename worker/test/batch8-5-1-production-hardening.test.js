import test from "node:test";
import assert from "node:assert/strict";

import {
  PROVIDER_ERROR_CODES,
  classifyProviderError,
  invokeProviderCompletion,
  isFakeOrPlaceholderCredential,
  resolveAccountCredentialsChain,
  resolveExecutionEnvironment,
  saveUserAiAccount,
  testProviderConnection
} from "../src/ai/multi-account-manager.js";
import {
  AGENT_EVENT_TYPES,
  COLLABORATION_MODES,
  COLLABORATION_SESSION_STATUSES,
  CONSENSUS_STATUSES,
  KNOWLEDGE_EDGE_TYPES,
  KNOWLEDGE_NODE_TYPES,
  SYNTHESIS_PIPELINE_STAGES,
  buildKnowledgeGraphFromActualResponses,
  buildRealDebateAndConsensus,
  evaluateClaimConsensus,
  runCollaborationSession,
  runSynthesisPipeline,
  validateKnowledgeGraph
} from "../src/ai/collaboration-engine.js";

function createMockKv() {
  const map = new Map();
  return {
    store: map,
    async get(key) {
      return map.has(key) ? map.get(key) : null;
    },
    async put(key, value) {
      map.set(key, String(value));
    },
    async delete(key) {
      map.delete(key);
    },
    async list({ prefix = "" } = {}) {
      const keys = [...map.keys()]
        .filter((k) => k.startsWith(prefix))
        .map((name) => ({ name }));
      return { keys, list_complete: true };
    }
  };
}

// ============================================================================
// 1. ENVIRONMENT SAFETY GUARD & FAKE CREDENTIAL ELIMINATION TESTS
// ============================================================================
test("Batch 8.5.1 — Environment Safety Guard strictly isolates mock providers from production", async () => {
  // 1. Production mode with mock disabled (default)
  const prodDefault = resolveExecutionEnvironment({ JARVIS_ENV: "production" });
  assert.equal(prodDefault.jarvisEnv, "production");
  assert.equal(prodDefault.isProduction, true);
  assert.equal(prodDefault.allowMockProviders, false);

  // 2. Production mode with MOCK_PROVIDER_ENABLED=true MUST REJECT mock providers
  const prodWithMockTrue = resolveExecutionEnvironment({
    JARVIS_ENV: "production",
    MOCK_PROVIDER_ENABLED: "true"
  });
  assert.equal(prodWithMockTrue.isProduction, true);
  assert.equal(prodWithMockTrue.allowMockProviders, false, "Production must NEVER allow mock providers");
  assert.equal(prodWithMockTrue.rejectedMockInProduction, true);
  assert.equal(prodWithMockTrue.warning, "MOCK_PROVIDER_REJECTED_IN_PRODUCTION");

  // 3. Test mode with MOCK_PROVIDER_ENABLED=true allows mock fixtures
  const testWithMock = resolveExecutionEnvironment({
    JARVIS_ENV: "test",
    MOCK_PROVIDER_ENABLED: "true"
  });
  assert.equal(testWithMock.jarvisEnv, "test");
  assert.equal(testWithMock.isProduction, false);
  assert.equal(testWithMock.allowMockProviders, true);

  // 4. Development mode with mock disabled rejects mock providers
  const devMockDisabled = resolveExecutionEnvironment({
    JARVIS_ENV: "development",
    MOCK_PROVIDER_ENABLED: "false"
  });
  assert.equal(devMockDisabled.jarvisEnv, "development");
  assert.equal(devMockDisabled.allowMockProviders, false);

  // 5. Missing or invalid environment variable defaults strictly to production safety
  const missingEnv = resolveExecutionEnvironment({});
  assert.equal(missingEnv.jarvisEnv, "production");
  assert.equal(missingEnv.isProduction, true);
  assert.equal(missingEnv.allowMockProviders, false);

  const invalidEnv = resolveExecutionEnvironment({
    JARVIS_ENV: "unknown-staging-env",
    MOCK_PROVIDER_ENABLED: "true"
  });
  assert.equal(invalidEnv.jarvisEnv, "production");
  assert.equal(invalidEnv.isProduction, true);
  assert.equal(invalidEnv.allowMockProviders, false);
  assert.equal(invalidEnv.invalidEnvFallbackToProd, true);
});

test("Batch 8.5.1 — Fake Credential Elimination rejects all dummy/fallback credential patterns", async () => {
  const fakeSamples = [
    "env-fallback-openai-key",
    "env-fallback-anthropic-key",
    "env-fallback-gemini-key",
    "env-fallback-deepseek-key",
    "env-fallback-groq-key",
    "env-fallback-openrouter-key",
    "fake-openai-key",
    "dummy-api-key",
    "mock-key-12345",
    "placeholder-secret",
    ""
  ];
  for (const sample of fakeSamples) {
    assert.equal(
      isFakeOrPlaceholderCredential(sample),
      true,
      `Expected fake credential '${sample}' to be rejected`
    );
  }
  assert.equal(isFakeOrPlaceholderCredential("sk-proj-liveValidKey987654321"), false);

  const kv = createMockKv();
  // Attempting to save a fake key into KV must throw PROVIDER_AUTH_FAILED
  await assert.rejects(
    async () => {
      await saveUserAiAccount(kv, "user-1", {
        provider: "openai",
        label: "Fake Key Attempt",
        apiKey: "env-fallback-openai-key"
      });
    },
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED
  );

  // Supplying fake keys in env must result in an empty credential chain
  const chainWithFakeEnv = await resolveAccountCredentialsChain(kv, "user-1", "openai", {
    OPENAI_API_KEY_1: "env-fallback-openai-key",
    OPENAI_API_KEY_2: "fake-openai-key"
  });
  assert.equal(chainWithFakeEnv.length, 0);

  // testProviderConnection must reject fake keys
  const connTest = await testProviderConnection({
    provider: "openai",
    apiKey: "env-fallback-openai-key"
  });
  assert.equal(connTest.ok, false);
  assert.equal(connTest.errorCode, PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED);
});

// ============================================================================
// 2. PROVIDER FAILURE STATUS MAPPING & NO FALSE SUCCESS TESTS
// ============================================================================
test("Batch 8.5.1 — Provider Failure Status Mapping covers all 8 required failure conditions", async () => {
  const kv = createMockKv();

  // 1. Missing API key -> PROVIDER_CREDENTIAL_MISSING
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: {}
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING
  );

  // 2. Disabled provider -> PROVIDER_DISABLED
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789", DISABLED_PROVIDERS: "openai" }
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_DISABLED
  );

  // 3. Network failure -> NETWORK_ERROR
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async () => {
          throw new TypeError("fetch failed: ECONNREFUSED");
        }
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.NETWORK_ERROR
  );

  // 4. Request timeout -> PROVIDER_TIMEOUT
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        timeoutMs: 60,
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async (_url, init) =>
          new Promise((resolve, reject) => {
            init?.signal?.addEventListener("abort", () => {
              const abortErr = new Error("Aborted");
              abortErr.name = "AbortError";
              reject(abortErr);
            });
          })
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_TIMEOUT
  );

  // 5. Rate limit (HTTP 429) -> PROVIDER_RATE_LIMITED
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async () => new Response(JSON.stringify({ error: "Rate limit exceeded" }), { status: 429 })
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_RATE_LIMITED
  );

  // 6. Invalid credential (HTTP 401 & HTTP 403) -> PROVIDER_AUTH_FAILED
  for (const status of [401, 403]) {
    await assert.rejects(
      async () =>
        invokeProviderCompletion({
          kv,
          userId: "user-err",
          provider: "openai",
          prompt: "Hello",
          env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
          fetchImpl: async () => new Response(JSON.stringify({ error: "Unauthorized" }), { status })
        }),
      (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED
    );
  }

  // 7. Model unavailable (HTTP 404) -> MODEL_UNAVAILABLE
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        apiModel: "gpt-nonexistent-99",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async () => new Response(JSON.stringify({ error: "model_not_found" }), { status: 404 })
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE
  );

  // 8. HTTP 500, invalid JSON, and empty completion -> PROVIDER_ERROR
  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async () => new Response("Internal Server Error", { status: 500 })
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_ERROR
  );

  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async () => new Response("<<<not-json>>>", { status: 200 })
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_ERROR
  );

  await assert.rejects(
    async () =>
      invokeProviderCompletion({
        kv,
        userId: "user-err",
        provider: "openai",
        prompt: "Hello",
        env: { OPENAI_API_KEY_1: "sk-real-key-123456789" },
        fetchImpl: async () => new Response(JSON.stringify({ choices: [{ message: { content: "   " } }] }), { status: 200 })
      }),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_ERROR
  );
});

test("Batch 8.5.1 — No False Success: Production session with missing keys or failing providers returns COLLABORATION_FAILED", async () => {
  const kv = createMockKv();
  // Production environment with NO API keys configured and MOCK_PROVIDER_ENABLED=true (which must be rejected in prod!)
  const prodEnvNoKeys = {
    DEVICE_REGISTRY: kv,
    JARVIS_ENV: "production",
    MOCK_PROVIDER_ENABLED: "true"
  };

  const failedSession = await runCollaborationSession(prodEnvNoKeys, "user-prod-fail", {
    topic: "Evaluasi keamanan produksi tanpa kunci API",
    selectionMode: "manual",
    selectedModelIds: ["gpt-4o", "claude-3-5-sonnet", "gemini-1.5-pro"],
    mode: "parallel"
  });

  assert.equal(failedSession.status, COLLABORATION_SESSION_STATUSES.COLLABORATION_FAILED);
  assert.equal(failedSession.collaborationStatus, COLLABORATION_SESSION_STATUSES.COLLABORATION_FAILED);
  assert.equal(failedSession.environmentSafety.allowMockProviders, false);
  assert.equal(failedSession.environmentSafety.rejectedMockInProduction, true);
  assert.equal(failedSession.synthesis, null, "Must NOT save simulated synthesis when all agents fail");
  // Every message must be marked failed with PROVIDER_CREDENTIAL_MISSING and empty content (no fake AI text)
  for (const msg of failedSession.messages) {
    assert.equal(msg.status, "failed");
    assert.equal(msg.content, "");
    assert.equal(msg.errorCode, PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING);
  }

  // Must NEVER emit AGENT_RESPONSE_RECEIVED when agents fail
  const eventTypes = failedSession.events.map((e) => e.eventType);
  assert.equal(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_RESPONSE_RECEIVED), false);
  assert.equal(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_FAILED), true);
  assert.equal(eventTypes.includes(AGENT_EVENT_TYPES.SYNTHESIS_FAILED), true);
});

test("Batch 8.5.1 — Synthesis Failure & Retry preserves completed agent results without copying last agent output", async () => {
  const kv = createMockKv();
  const env = {
    DEVICE_REGISTRY: kv,
    JARVIS_ENV: "production",
    OPENAI_API_KEY_1: "sk-real-openai-key",
    ANTHROPIC_API_KEY_1: "sk-real-anthropic-key"
  };

  let failSynthesisCall = true;
  const customFetch = async (_input, init) => {
    const body = JSON.parse(String(init?.body || "{}"));
    const promptText =
      body?.messages?.[body.messages.length - 1]?.content || "";

    if (promptText.includes("[J.A.R.V.I.S. MULTI-AGENT SYNTHESIS ORCHESTRATOR]")) {
      if (failSynthesisCall) {
        return new Response("Synthesis model overloaded", { status: 503 });
      }
      return new Response(
        JSON.stringify({
          choices: [
            {
              message: {
                content:
                  "Sintesis Eksekutif Terverifikasi: Menggabungkan arsitektur modular PostgreSQL RLS dari GPT-4o dan strategi Redis cache dari Claude 3.5 Sonnet."
              }
            }
          ]
        }),
        { status: 200 }
      );
    }

    if (promptText.includes("Analis")) {
      return new Response(
        JSON.stringify({
          choices: [
            {
              message: {
                content:
                  "Analisis GPT-4o: Terapkan arsitektur modular dengan PostgreSQL RLS karena menjaga isolasi data tenant dan menurunkan risiko kebocoran hingga 99%."
              }
            }
          ]
        }),
        { status: 200 }
      );
    }
    return new Response(
      JSON.stringify({
        content: [
          {
            type: "text",
            text: "Analisis Claude: Gunakan arsitektur modular, PostgreSQL RLS, serta Redis cache karena memangkas latensi baca hingga 45ms."
          }
        ]
      }),
      { status: 200 }
    );
  };

  const sessionWithFailedSynth = await runCollaborationSession(env, "user-synth-fail", {
    topic: "Arsitektur multi-tenant dengan ketahanan tinggi",
    selectionMode: "manual",
    selectedModelIds: ["gpt-4o", "claude-3-5-sonnet"],
    mode: "parallel",
    fetchImpl: customFetch
  });

  // Agents succeeded, but synthesis failed -> SYNTHESIS_FAILED
  assert.equal(sessionWithFailedSynth.status, COLLABORATION_SESSION_STATUSES.SYNTHESIS_FAILED);
  assert.equal(sessionWithFailedSynth.synthesisStatus, "SYNTHESIS_FAILED");
  assert.equal(sessionWithFailedSynth.synthesis.status, "SYNTHESIS_FAILED");
  assert.equal(sessionWithFailedSynth.synthesis.executiveSummary, "UNAVAILABLE");
  // Completed agent results are preserved!
  assert.equal(sessionWithFailedSynth.messages.filter((m) => m.status === "completed").length, 2);

  // Now retry synthesis on the preserved session
  failSynthesisCall = false;
  const retriedSession = await runCollaborationSession(env, "user-synth-fail", {
    action: "retry_synthesis",
    sessionId: sessionWithFailedSynth.sessionId,
    fetchImpl: customFetch
  });
  assert.equal(retriedSession.status, COLLABORATION_SESSION_STATUSES.SUCCESS);
  assert.equal(retriedSession.synthesis.status, "SYNTHESIS_COMPLETED");
  assert.match(retriedSession.synthesis.executiveSummary, /Sintesis Eksekutif Terverifikasi/);
  // Verify executiveSummary is NOT a verbatim copy of the last agent's raw message
  const lastAgentMsg = retriedSession.messages[retriedSession.messages.length - 1].content;
  assert.notEqual(retriedSession.synthesis.executiveSummary, lastAgentMsg);
});

// ============================================================================
// 3. ALL 8 REQUIRED CONSENSUS SCENARIOS (SECTION 5.4)
// ============================================================================
test("Batch 8.5.1 — Consensus Scoring: Validates all 8 required claim-based consensus scenarios", async () => {
  const baseTeam = [
    { agentId: "a1", modelId: "gpt-4o", modelName: "GPT-4o", provider: "openai", role: "Analis" },
    { agentId: "a2", modelId: "claude-3-5-sonnet", modelName: "Claude 3.5", provider: "anthropic", role: "Kreatif" },
    { agentId: "a3", modelId: "gemini-1.5-pro", modelName: "Gemini 1.5", provider: "gemini", role: "Evaluator" },
    { agentId: "a4", modelId: "deepseek-v3", modelName: "DeepSeek V3", provider: "deepseek", role: "Teknis" }
  ];

  // Scenario 1: All agents agree
  const s1 = evaluateClaimConsensus(
    "Arsitektur SaaS",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Rekomendasi: Gunakan arsitektur modular microservices dan PostgreSQL RLS karena menjaga p95 latency 45ms dan isolasi tenant." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "completed", confidence: 0.9, content: "Setuju, terapkan arsitektur modular microservices serta PostgreSQL RLS karena mencegah kebocoran data lintas tenant hingga 99.9%." },
      { id: "m3", agentId: "a3", modelName: "Gemini 1.5", role: "Evaluator", status: "completed", confidence: 0.9, content: "Mendukung penggunaan microservices modular dan PostgreSQL RLS karena bukti benchmark menunjukkan throughput 5000 rps." },
      { id: "m4", agentId: "a4", modelName: "DeepSeek V3", role: "Teknis", status: "completed", confidence: 0.9, content: "Wajib gunakan arsitektur modular microservices dan PostgreSQL RLS karena isolasi layanan mencegah cascading failure." }
    ],
    baseTeam
  );
  assert.equal(s1.consensusStatus, CONSENSUS_STATUSES.AGREED);
  assert.equal(s1.consensusReached, true);
  assert.ok(s1.consensusScore >= 80, `Expected high consensusScore >= 80, got ${s1.consensusScore}`);

  // Scenario 2: All agents disagree (opposing stances on same concepts)
  const s2 = evaluateClaimConsensus(
    "Microservices vs Monolith",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Rekomendasi: Gunakan arsitektur modular microservices karena isolasi layanan mendukung 10.000 rps. Jangan gunakan monolith karena sulit diskalakan." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "completed", confidence: 0.9, content: "Tidak setuju. Jangan gunakan microservices karena overhead jaringan menambah latency 250ms. Gunakan aplikasi tunggal monolith karena jauh lebih efisien." }
    ],
    baseTeam.slice(0, 2)
  );
  assert.equal(s2.consensusStatus, CONSENSUS_STATUSES.DISAGREED);
  assert.equal(s2.consensusReached, false);
  assert.ok(s2.consensusScore <= 45, `Expected low consensusScore <= 45 on direct disagreement, got ${s2.consensusScore}`);

  // Scenario 3: Majority agrees (3 PRO), 1 agent rejects with strong reason (1 CONTRA)
  const s3 = evaluateClaimConsensus(
    "Strategi Caching Redis",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Gunakan Redis cache karena menurunkan beban baca database hingga 70% dengan latency 5ms." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "completed", confidence: 0.9, content: "Terapkan Redis cache karena meningkatkan throughput API hingga 4000 rps." },
      { id: "m3", agentId: "a3", modelName: "Gemini 1.5", role: "Evaluator", status: "completed", confidence: 0.9, content: "Rekomendasi gunakan Redis cache karena mempercepat respons kueri berulang di bawah 10ms." },
      { id: "m4", agentId: "a4", modelName: "DeepSeek V3", role: "Teknis", status: "completed", confidence: 0.9, content: "Tidak setuju, jangan gunakan Redis cache karena risiko stale data pada transaksi finansial menyebabkan inkonsistensi saldo." }
    ],
    baseTeam
  );
  assert.equal(s3.consensusStatus, CONSENSUS_STATUSES.MOSTLY_AGREED);
  assert.ok(s3.conflictingEvidence.length >= 1, "Must preserve dissenting agent counter-evidence");

  // Scenario 4: Two agents have same position but different evidence
  const s4 = evaluateClaimConsensus(
    "Keamanan Multi-Tenant",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Gunakan PostgreSQL RLS karena kebijakan akses baris dieksekusi langsung di engine database sehingga kebal terhadap bug filter aplikasi." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "completed", confidence: 0.9, content: "Gunakan PostgreSQL RLS karena audit kepatuhan ISO-27001 menjadi terpusat dengan overhead latensi hanya 1.2ms per transaksi." }
    ],
    baseTeam.slice(0, 2)
  );
  assert.equal(s4.consensusStatus, CONSENSUS_STATUSES.AGREED);
  assert.ok(s4.supportingEvidence.length >= 2, "Must preserve both distinct supporting evidence items");

  // Scenario 5: Two agents use completely different words but reach the same conclusion
  const s5 = evaluateClaimConsensus(
    "Desain Sistem Skala Tinggi",
    [
      {
        id: "m1",
        agentId: "a1",
        modelName: "GPT-4o",
        role: "Analis",
        status: "completed",
        confidence: 0.9,
        content:
          "Terapkan komponen terpisah dengan bounded context, antrean asinkron untuk pemrosesan latar belakang, serta penyimpanan memori cepat karena menjaga latensi 20ms."
      },
      {
        id: "m2",
        agentId: "a2",
        modelName: "Claude 3.5",
        role: "Kreatif",
        status: "completed",
        confidence: 0.9,
        content:
          "Recommend adopting microservices with message broker event queue and in-memory cache layer because it prevents overload at 8000 rps."
      }
    ],
    baseTeam.slice(0, 2)
  );
  assert.equal(s5.consensusStatus, CONSENSUS_STATUSES.AGREED);
  assert.equal(s5.agreementScore, 100, "Must recognize semantic agreement despite different surface vocabulary");
  assert.ok(s5.similarityScore < 30, "Lexical similarityScore must remain low when words are different");

  // Scenario 6: Agents provide contradicting evidence
  const s6 = evaluateClaimConsensus(
    "Evaluasi Database",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Gunakan PostgreSQL relational database karena transaksi ACID menjamin konsistensi 100% pada p95 latency 15ms." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "completed", confidence: 0.9, content: "Jangan gunakan PostgreSQL relational database karena bukti benchmark tulis masif mengalami bottleneck fatal 650ms; gunakan NoSQL DynamoDB." }
    ],
    baseTeam.slice(0, 2)
  );
  assert.equal(s6.consensusReached, false);
  assert.ok(
    s6.consensusStatus === CONSENSUS_STATUSES.DISAGREED || s6.consensusStatus === CONSENSUS_STATUSES.UNRESOLVED
  );
  assert.ok(s6.claims.some((c) => c.contradictionStatus === "DIRECT_CONTRADICTION"));

  // Scenario 7: Some agents fail to respond -> failed agents excluded from having an opinion
  const s7 = evaluateClaimConsensus(
    "Ketahanan Sistem",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Gunakan circuit breaker dan connection pool PgBouncer karena mencegah kehabisan koneksi pada 5000 pengguna." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "completed", confidence: 0.9, content: "Terapkan connection pool dan circuit breaker karena mengisolasi lonjakan trafik dalam 30ms." },
      { id: "m3", agentId: "a3", modelName: "Gemini 1.5", role: "Evaluator", status: "failed", errorCode: "PROVIDER_TIMEOUT", error: "Timed out", content: "" },
      { id: "m4", agentId: "a4", modelName: "DeepSeek V3", role: "Teknis", status: "failed", errorCode: "PROVIDER_RATE_LIMITED", error: "HTTP 429", content: "" }
    ],
    baseTeam
  );
  assert.equal(s7.activeAgentsCount, 2);
  assert.equal(s7.excludedFailedAgents.length, 2);
  assert.equal(s7.consensusStatus, CONSENSUS_STATUSES.AGREED);

  // Scenario 8: Only one agent succeeds -> CONSENSUS_NOT_ESTABLISHED
  const s8 = evaluateClaimConsensus(
    "Kegagalan Mayoritas Agent",
    [
      { id: "m1", agentId: "a1", modelName: "GPT-4o", role: "Analis", status: "completed", confidence: 0.9, content: "Gunakan arsitektur modular microservices dan Redis cache karena cepat." },
      { id: "m2", agentId: "a2", modelName: "Claude 3.5", role: "Kreatif", status: "failed", errorCode: "NETWORK_ERROR", error: "Network error", content: "" },
      { id: "m3", agentId: "a3", modelName: "Gemini 1.5", role: "Evaluator", status: "failed", errorCode: "PROVIDER_AUTH_FAILED", error: "HTTP 401", content: "" }
    ],
    baseTeam.slice(0, 3)
  );
  assert.equal(s8.consensusStatus, CONSENSUS_STATUSES.CONSENSUS_NOT_ESTABLISHED);
  assert.equal(s8.consensusReached, false);
  assert.equal(s8.consensusScore, 0);
});

// ============================================================================
// 4. KNOWLEDGE GRAPH SEMANTIC INTEGRITY, PROVENANCE & VALIDATION TESTS
// ============================================================================
test("Batch 8.5.1 — Knowledge Graph enforces semantic node/edge types, full provenance, and validation", async () => {
  const team = [
    { agentId: "a1", modelId: "gpt-4o", modelName: "GPT-4o", provider: "openai", role: "Analis" },
    { agentId: "a2", modelId: "claude-3-5-sonnet", modelName: "Claude 3.5", provider: "anthropic", role: "Evaluator" }
  ];
  const nowIso = new Date().toISOString();
  const messages = [
    {
      id: "msg_1",
      round: 1,
      agentId: "a1",
      modelId: "gpt-4o",
      modelName: "GPT-4o",
      provider: "openai",
      role: "Analis",
      status: "completed",
      confidence: 0.92,
      createdAt: nowIso,
      content: [
        "Rekomendasi: Gunakan PostgreSQL RLS dan Redis cache untuk sistem multi-tenant.",
        "Bukti: Benchmark internal sesuai standar OWASP menunjukkan latensi p95 turun menjadi 18ms karena kueri di-cache.",
        "Risiko: Bottleneck koneksi database saat failover.",
        "Apakah kebijakan retensi audit memerlukan partisi tabel bulanan?"
      ].join("\n")
    },
    {
      id: "msg_2",
      round: 1,
      agentId: "a2",
      modelId: "claude-3-5-sonnet",
      modelName: "Claude 3.5",
      provider: "anthropic",
      role: "Evaluator",
      status: "completed",
      confidence: 0.9,
      createdAt: nowIso,
      content: [
        "Setuju untuk menerapkan PostgreSQL RLS dan Redis cache.",
        "Bukti: Isolasi tenant pada tingkat baris mencegah kebocoran lintas akun hingga 99.9% menurut dokumentasi PostgreSQL.",
        "Risiko: Kegagalan sinkronisasi cache saat invalidasi serentak."
      ].join("\n")
    }
  ];

  const kg = buildKnowledgeGraphFromActualResponses("Arsitektur Multi-Tenant", messages, team, true, {
    sessionId: "collab_kg_test"
  });

  assert.equal(kg.enabled, true);
  assert.equal(kg.validation.valid, true, `Expected valid graph, got issues: ${kg.validation.issues.join("; ")}`);

  const presentNodeTypes = new Set(kg.nodes.map((n) => n.nodeType));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.CLAIM));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.AGENT_POSITION));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.EVIDENCE));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.RISK));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.RECOMMENDATION));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.QUESTION));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.SOURCE));
  assert.ok(presentNodeTypes.has(KNOWLEDGE_NODE_TYPES.DECISION));

  // Verify every node & edge has full provenance
  for (const node of kg.nodes) {
    assert.ok(node.provenance.sessionId === "collab_kg_test");
    assert.ok(node.provenance.agentId);
    assert.ok(node.provenance.modelId);
    assert.ok(node.provenance.messageId);
    assert.ok(node.provenance.timestamp);
    assert.ok(node.provenance.sourceReference);
  }
  for (const edge of kg.edges) {
    assert.ok(edge.provenance.sessionId === "collab_kg_test");
    assert.ok(edge.provenance.agentId);
    assert.ok(edge.provenance.modelId);
    assert.ok(edge.provenance.messageId);
    assert.ok(edge.provenance.timestamp);
    assert.ok(edge.provenance.sourceReference);
    assert.ok(!edge.id.startsWith("edge_chain_"), "Must never chain edges merely by sequential message array order");
  }

  // Verify validateKnowledgeGraph detects corrupted graphs (duplicate nodes, orphan nodes, contradictory edges, invalid agentId, missing timestamp)
  const brokenGraph = {
    nodes: [
      { id: "n1", nodeType: "CLAIM", provenance: { agentId: "a1", timestamp: nowIso, sourceReference: "ref1" } },
      { id: "n1", nodeType: "CLAIM", provenance: { agentId: "unknown_agent", timestamp: "", sourceReference: "" } },
      { id: "n_orphan", nodeType: "RISK", provenance: { agentId: "a1", timestamp: nowIso, sourceReference: "ref2" } }
    ],
    edges: [
      { id: "e1", source: "n1", target: "n_missing", edgeType: "SUPPORTS", provenance: { agentId: "a1", timestamp: nowIso, sourceReference: "ref" } },
      { id: "e2", source: "n1", target: "n_orphan", edgeType: "SUPPORTS", provenance: { agentId: "a1", timestamp: nowIso, sourceReference: "ref" } },
      { id: "e3", source: "n1", target: "n_orphan", edgeType: "CONTRADICTS", provenance: { agentId: "a1", timestamp: nowIso, sourceReference: "ref" } }
    ]
  };
  const brokenValidation = validateKnowledgeGraph(brokenGraph, new Set(["a1", "a2"]));
  assert.equal(brokenValidation.valid, false);
  assert.ok(brokenValidation.duplicateNodeCount >= 1);
  assert.ok(brokenValidation.invalidEdgeCount >= 1);
  assert.ok(brokenValidation.missingSourceRefCount >= 1);
  assert.ok(brokenValidation.contradictoryEdgeCount >= 1);
  assert.ok(brokenValidation.invalidAgentRefCount >= 1);
  assert.ok(brokenValidation.missingTimestampCount >= 1);
});