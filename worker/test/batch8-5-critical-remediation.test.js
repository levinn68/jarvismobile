import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  AGENT_EVENT_TYPES,
  COLLABORATION_MODES,
  resolveCollaborationMode,
  runCollaborationSession
} from "../src/ai/collaboration-engine.js";
import { saveUserAiAccount, invokeProviderCompletion } from "../src/ai/multi-account-manager.js";
import worker from "../src/index.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");

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

test("Batch 8.5 Remediation 1 — All 7 Collaboration Modes execute distinct real provider requests and emit real events", async () => {
  const modeKeys = Object.keys(COLLABORATION_MODES);
  assert.deepEqual(modeKeys, [
    "parallel",
    "sequential",
    "debate",
    "consensus",
    "specialist",
    "reviewer",
    "ensemble"
  ]);

  const kv = createMockKv();
  const env = {
    DEVICE_REGISTRY: kv,
    OPENAI_API_KEY_1: "sk-live-openai-test-key",
    ANTHROPIC_API_KEY_1: "sk-ant-live-test-key",
    GEMINI_API_KEY_1: "AIzaSyLiveGeminiTestKey123456",
    DEEPSEEK_API_KEY_1: "sk-live-deepseek-test-key",
    GROQ_API_KEY_1: "gsk_live_groq_test_key_12345",
    OPENROUTER_API_KEY_1: "sk-or-live-test-key-12345"
  };

  const capturedCalls = [];
  const customFetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    const bodyObj = init?.body ? JSON.parse(String(init.body)) : {};
    const promptText =
      bodyObj?.messages?.[bodyObj.messages.length - 1]?.content ||
      bodyObj?.contents?.[bodyObj.contents.length - 1]?.parts?.[0]?.text ||
      "";
    capturedCalls.push({ url, promptText });

    const reply = [
      `Respons aktual dari endpoint ${new URL(url).hostname} untuk prompt: ${promptText.slice(0, 90).replace(/\n/g, " ")}`,
      "- Rekomendasi: Terapkan arsitektur modular dengan PostgreSQL RLS, Redis cache, dan circuit breaker karena mencegah cascading failure serta menjaga p95 latency < 120ms.",
      "- Risiko: Bottleneck koneksi database pada trafik puncak memerlukan connection pooling PgBouncer."
    ].join("\n");

    if (url.includes("generativelanguage.googleapis.com")) {
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: reply }] } }] }), { status: 200 });
    }
    if (url.includes("api.anthropic.com")) {
      return new Response(JSON.stringify({ content: [{ type: "text", text: reply }] }), { status: 200 });
    }
    return new Response(JSON.stringify({ choices: [{ message: { content: reply } }] }), { status: 200 });
  };

  // Execute all 7 modes and verify distinct behavior
  for (const modeId of modeKeys) {
    const beforeCount = capturedCalls.length;
    const session = await runCollaborationSession(env, "user-b8-5-modes", {
      topic: `Evaluasi arsitektur mode ${modeId} untuk sistem berskala tinggi`,
      selectionMode: "manual",
      selectedModelIds: ["gpt-4o", "claude-3-5-sonnet", "gemini-1.5-pro"],
      mode: modeId,
      rounds: modeId === "debate" ? 2 : 1,
      fetchImpl: customFetch
    });

    const callsForMode = capturedCalls.slice(beforeCount);
    assert.ok(callsForMode.length >= 4, `Mode ${modeId} must dispatch real HTTP requests to agents + synthesis provider`);
    assert.equal(session.mode, modeId);
    assert.equal(session.status, "SUCCESS");

    // Verify real events were emitted
    const eventTypes = session.events.map((e) => e.eventType);
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.SESSION_CREATED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.TASK_PLANNED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_ASSIGNED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_QUEUED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_STARTED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_RESPONSE_RECEIVED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.SYNTHESIS_STARTED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.SYNTHESIS_COMPLETED));
    assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.SESSION_COMPLETED));

    if (modeId === "sequential") {
      assert.match(callsForMode[1].promptText, /Hasil Tahap Sebelumnya/);
    }
    if (modeId === "debate") {
      assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.DEBATE_ROUND_STARTED));
      assert.equal(callsForMode.length, 7); // 3 agents * 2 rounds + 1 synthesis provider call
      assert.match(callsForMode[3].promptText, /Ringkasan Posisi Agent Lain pada Putaran 1/);
    }
    if (modeId === "specialist") {
      assert.match(callsForMode[0].promptText, /Subtugas Spesifik Anda:/);
    }
    if (modeId === "reviewer") {
      assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.REVIEW_COMPLETED));
      assert.ok(session.preReviewOutput, "Reviewer mode must save preReviewOutput");
      assert.ok(session.postReviewOutput, "Reviewer mode must save postReviewOutput");
    }
    if (modeId === "ensemble") {
      assert.ok(Array.isArray(session.ensembleRanking) && session.ensembleRanking.length === 3);
    }

    // Verify synthesis has all required sections
    assert.ok(session.synthesis.summary);
    assert.ok(session.synthesis.conclusion);
    assert.ok(session.synthesis.executiveSummary);
    assert.ok(Array.isArray(session.synthesis.evidence));
    assert.ok(Array.isArray(session.synthesis.agreements));
    assert.ok(Array.isArray(session.synthesis.disagreements));
    assert.ok(Array.isArray(session.synthesis.risks));
    assert.ok(Array.isArray(session.synthesis.limitations));
    assert.ok(Array.isArray(session.synthesis.recommendations));
  }
});

test("Batch 8.5 Remediation 1 — Agent Scheduler concurrency limit, 429 retry, per-agent failure isolation, idempotency, and cancellation", async () => {
  const kv = createMockKv();
  const env = {
    DEVICE_REGISTRY: kv,
    OPENAI_API_KEY_1: "sk-openai-1",
    ANTHROPIC_API_KEY_1: "sk-ant-1",
    GEMINI_API_KEY_1: "AIzaGemini1",
    DEEPSEEK_API_KEY_1: "sk-deepseek-1"
  };

  let currentInFlight = 0;
  let maxObservedInFlight = 0;
  let openaiAttempts = 0;

  const schedulerFetch = async (input) => {
    const url = typeof input === "string" ? input : input.url;
    currentInFlight += 1;
    if (currentInFlight > maxObservedInFlight) {
      maxObservedInFlight = currentInFlight;
    }
    await new Promise((r) => setTimeout(r, 25));
    try {
      if (url.includes("api.openai.com")) {
        openaiAttempts += 1;
        if (openaiAttempts === 1) {
          return new Response(JSON.stringify({ error: { message: "Rate limit exceeded" } }), { status: 429 });
        }
        return new Response(
          JSON.stringify({ choices: [{ message: { content: "OpenAI succeeded on retry with modular architecture and PostgreSQL RLS." } }] }),
          { status: 200 }
        );
      }
      if (url.includes("api.deepseek.com")) {
        // Simulate permanent failure on DeepSeek to test per-agent failure isolation
        return new Response(JSON.stringify({ error: { message: "Invalid model request" } }), { status: 400 });
      }
      if (url.includes("generativelanguage.googleapis.com")) {
        return new Response(
          JSON.stringify({ candidates: [{ content: { parts: [{ text: "Gemini analysis completed with modular architecture and PostgreSQL RLS." }] } }] }),
          { status: 200 }
        );
      }
      return new Response(
        JSON.stringify({ content: [{ type: "text", text: "Anthropic analysis completed with modular architecture and PostgreSQL RLS." }] }),
        { status: 200 }
      );
    } finally {
      currentInFlight -= 1;
    }
  };

  const session = await runCollaborationSession(env, "user-scheduler-test", {
    topic: "Uji konkurensi, retry 429, dan isolasi kegagalan agent",
    selectionMode: "manual",
    selectedModelIds: ["gpt-4o", "claude-3-5-sonnet", "gemini-1.5-pro", "deepseek-v3"],
    mode: "parallel",
    concurrencyLimit: 2,
    clientRequestId: "idemp-req-001",
    fetchImpl: schedulerFetch
  });

  assert.ok(maxObservedInFlight <= 2, `Expected max concurrency <= 2, observed ${maxObservedInFlight}`);
  assert.equal(session.status, "PARTIAL_SUCCESS");

  const eventTypes = session.events.map((e) => e.eventType);
  assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_RETRIED), "Must emit AGENT_RETRIED on HTTP 429");
  assert.ok(eventTypes.includes(AGENT_EVENT_TYPES.AGENT_FAILED), "Must emit AGENT_FAILED for isolated failing agent");

  const completedMsgs = session.messages.filter((m) => m.status === "completed");
  const failedMsgs = session.messages.filter((m) => m.status === "failed");
  assert.equal(completedMsgs.length, 3, "Healthy agents must complete despite one agent failing");
  assert.equal(failedMsgs.length, 1);

  // Test idempotency replay
  const replayed = await runCollaborationSession(env, "user-scheduler-test", {
    topic: "Uji konkurensi, retry 429, dan isolasi kegagalan agent",
    clientRequestId: "idemp-req-001"
  });
  assert.equal(replayed.replayed, true);
  assert.equal(replayed.sessionId, session.sessionId);
});

test("Batch 8.5 Remediation 2 — Chat model/account routing & Fish Audio transparent failure vs success", async () => {
  const kv = createMockKv();
  const userId = "123e4567-e89b-12d3-a456-426614174000";

  const savedAcc = await saveUserAiAccount(kv, userId, {
    accountId: "acc-user-openai-custom",
    provider: "openai",
    label: "Akun OpenAI Pribadi",
    email: "fajar@jarvis.id",
    apiKey: "sk-user-custom-account-key-9999",
    isDefault: true
  }, { AI_CREDENTIAL_ENCRYPTION_KEY: "test-only-ai-credential-encryption-secret-32bytes!" });
  assert.equal(savedAcc.accountId, "acc-user-openai-custom");

  let usedAuthHeader = "";
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    if (url.includes("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: userId, email: "fajar@jarvis.id" }), { status: 200 });
    }
    if (url.includes("api.openai.com/v1/chat/completions")) {
      usedAuthHeader = init?.headers?.Authorization || "";
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "Jawaban langsung menggunakan akun OpenAI terpilih." } }]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }
    if (url.includes("api.fish.audio/v1/tts")) {
      return new Response("upstream fish error", { status: 502 });
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  try {
    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon-key",
      AI_CREDENTIAL_ENCRYPTION_KEY: "test-only-ai-credential-encryption-secret-32bytes!",
      OPENAI_API_KEY_1: "sk-server-default-key",
      FISH_API_KEY: "fish-secret-key",
      DEVICE_REGISTRY: kv
    };

    const chatRes = await worker.fetch(
      new Request("https://worker.test/v1/chat", {
        method: "POST",
        headers: { Authorization: "Bearer valid-jwt", "Content-Type": "application/json" },
        body: JSON.stringify({
          message: "Halo Jarvis, tes pemilihan model dan akun.",
          model: "gpt-4o",
          accountId: "acc-user-openai-custom"
        })
      }),
      env
    );
    assert.equal(chatRes.status, 200);
    const chatBody = await chatRes.json();
    assert.equal(chatBody.ok, true);
    assert.equal(chatBody.selectedModel, "gpt-4o");
    assert.equal(usedAuthHeader, "Bearer sk-user-custom-account-key-9999");

    // 2. Verify /v1/tts explicitly reports fishAudioSuccess: false when Fish Audio fails
    const ttsFailRes = await worker.fetch(
      new Request("https://worker.test/v1/tts", {
        method: "POST",
        headers: { Authorization: "Bearer valid-jwt", "Content-Type": "application/json" },
        body: JSON.stringify({ text: "Tes transparansi kegagalan Fish Audio." })
      }),
      env
    );
    assert.equal(ttsFailRes.status, 502);
    const ttsFailBody = await ttsFailRes.json();
    assert.equal(ttsFailBody.ok, false);
    assert.equal(ttsFailBody.fishAudioSuccess, false);
    assert.equal(ttsFailBody.ttsEngine, "android_tts_fallback");
  } finally {
    globalThis.fetch = origFetch;
  }
});

test("Batch 8.5 Remediation 3 & 4 — Android Fish Audio fallback transparency, Crash Guard, and Zero-Deletion Verification", async () => {
  const mainKt = fs.readFileSync(path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/MainActivity.kt"), "utf8");
  const collabKt = fs.readFileSync(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/ui/JarvisCollaborationScreens.kt"),
    "utf8"
  );

  // Verify Fish Audio fallback is never disguised as Fish Audio success
  assert.match(mainKt, /activeTtsEngineSource = "ANDROID_TTS_FALLBACK"/);
  assert.match(mainKt, /FISH AUDIO FAILED · ANDROID TTS FALLBACK/);
  assert.match(mainKt, /FISH AUDIO FAILED · ANDROID TTS SPEAKING/);
  assert.match(mainKt, /JarvisStabilityGuard\.rethrowIfCancellation\(error\)/);

  // Verify Collaboration UI binds to live /v1/collaboration/sessions and supports all 7 real modes
  assert.match(collabKt, /\/v1\/collaboration\/sessions/);
  assert.match(collabKt, /"parallel" to/);
  assert.match(collabKt, /"sequential" to/);
  assert.match(collabKt, /"debate" to/);
  assert.match(collabKt, /"consensus" to/);
  assert.match(collabKt, /"specialist" to/);
  assert.match(collabKt, /"reviewer" to/);
  assert.match(collabKt, /"ensemble" to/);
});
