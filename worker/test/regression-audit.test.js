import test from "node:test";
import assert from "node:assert/strict";
import workerApp, { normalizeConversationHistory } from "../src/index.js";
import { detectSpeechLanguage, fishVoiceConfig } from "../src/voice/fish-audio.js";
import { isCommandTimestampFresh } from "../src/utils.js";
import { listDeviceCommands } from "../src/device/command-service.js";
import { extractPairingCode } from "../src/device/pairing.js";
import { listSkills, getSkill, runSkill } from "../src/skills/skill-registry.js";
import { connectorFor, PLATFORM_CONNECTORS } from "../src/connectors/platform-adapters.js";
import { JARVIS_CONFIG, readRuntimeConfig } from "../src/core/config.js";

test("detectSpeechLanguage accurately distinguishes English and Indonesian text (regression for double-escaped regex)", () => {
  assert.equal(detectSpeechLanguage("What is the weather today and how can you help me explain this?"), "en");
  assert.equal(detectSpeechLanguage("Tolong jelaskan bagaimana cara membuat jadwal belajar untuk saya"), "id");

  const enCfg = fishVoiceConfig({ FISH_API_KEY: "k", FISH_VOICE_ID_EN: "voice-en", FISH_VOICE_ID_ID: "voice-id" }, "Please explain how the system works and what you can build");
  assert.equal(enCfg.language, "en");
  assert.equal(enCfg.voiceId, "voice-en");

  const idCfg = fishVoiceConfig({ FISH_API_KEY: "k", FISH_VOICE_ID_EN: "voice-en", FISH_VOICE_ID_ID: "voice-id" }, "Saya ingin kamu menjelaskan bagaimana cara kerja sistem ini");
  assert.equal(idCfg.language, "id");
  assert.equal(idCfg.voiceId, "voice-id");
});

test("isCommandTimestampFresh rejects future timestamps, expired timestamps, and malformed values", () => {
  const now = Date.parse("2026-11-05T12:00:00.000Z");
  assert.equal(isCommandTimestampFresh("2026-11-05T11:58:00.000Z", now), true);
  // Slight clock skew within 60s tolerance is accepted:
  assert.equal(isCommandTimestampFresh("2026-11-05T12:00:30.000Z", now), true);
  // Future timestamp beyond 60s skew is rejected:
  assert.equal(isCommandTimestampFresh("2026-11-05T12:05:00.000Z", now), false);
  // Expired timestamp (>5m old) is rejected:
  assert.equal(isCommandTimestampFresh("2026-11-05T11:54:00.000Z", now), false);
  assert.equal(isCommandTimestampFresh("", now), false);
  assert.equal(isCommandTimestampFresh("not-a-date", now), false);
});

test("listDeviceCommands filters out future-dated and expired queued commands", async () => {
  const now = Date.parse("2026-11-05T12:00:00.000Z");
  const store = new Map([
    ["command:android-12345678:1", JSON.stringify({
      commandId: "11111111-1111-1111-1111-111111111111",
      status: "queued",
      createdAt: "2026-11-05T11:59:00.000Z"
    })],
    ["command:android-12345678:2", JSON.stringify({
      commandId: "22222222-2222-2222-2222-222222222222",
      status: "queued",
      createdAt: "2026-11-05T12:15:00.000Z" // Future timestamp
    })],
    ["command:android-12345678:3", JSON.stringify({
      commandId: "33333333-3333-3333-3333-333333333333",
      status: "queued",
      createdAt: "2026-11-05T11:40:00.000Z" // Expired timestamp
    })],
    ["command:android-12345678:4", JSON.stringify({
      commandId: "44444444-4444-4444-4444-444444444444",
      status: "queued" // Missing timestamp must also be rejected.
    })]
  ]);
  const kv = {
    async get(key) { return store.get(key) ?? null; },
    async list() { return { keys: [...store.keys()].map((name) => ({ name })), list_complete: true }; }
  };
  const pending = await listDeviceCommands({ kv, deviceId: "android-12345678", nowMs: now });
  assert.equal(pending.length, 1);
  assert.equal(pending[0].commandId, "11111111-1111-1111-1111-111111111111");
});

test("extractPairingCode parses both raw 8-char codes and jarvis-pair:// QR URIs", () => {
  assert.equal(extractPairingCode("jarvis-pair://v1?code=abcd2345"), "ABCD2345");
  assert.equal(extractPairingCode("abcd 2345"), "ABCD2345");
});

test("builtin skill registry, platform connectors, and runtime config cover all platforms and 29 skills", async () => {
  const skills = listSkills();
  assert.equal(skills.length, 29);
  assert.ok(getSkill("jarvis-persona"));
  const executed = await runSkill("jarvis-persona", { prompt: "Halo Jarvis" });
  assert.equal(executed.id, "jarvis-persona");

  for (const platform of ["android", "windows", "macos", "linux", "ios", "ipados", "wearos", "web", "iot"]) {
    assert.ok(JARVIS_CONFIG.supportedPlatforms.includes(platform), "Missing in JARVIS_CONFIG: " + platform);
    assert.ok(PLATFORM_CONNECTORS[platform], "Missing in PLATFORM_CONNECTORS: " + platform);
    assert.ok(connectorFor(platform), "connectorFor returned null for: " + platform);
  }

  const runtime = readRuntimeConfig({ DEVICE_TOKEN_SECRET: "secret", SUPABASE_ANON_KEY: "anon" });
  assert.equal(runtime.deviceAuthConfigured, true);
});

test("normalizeConversationHistory sanitizes roles and bounds message count", () => {
  const history = normalizeConversationHistory([
    { role: "user", content: "Halo Jarvis" },
    { role: "jarvis", text: "Siap, Tuan." },
    { role: "unknown", message: "Pertanyaan lanjutan" }
  ]);
  assert.deepEqual(history, [
    { role: "user", content: "Halo Jarvis" },
    { role: "assistant", content: "Siap, Tuan." },
    { role: "user", content: "Pertanyaan lanjutan" }
  ]);
});

test("Worker /health and /v1/skills endpoints return integrated module telemetry", async () => {
  const healthRes = await workerApp.fetch(new Request("https://worker.test/health"), {
    GEMINI_API_KEY_1: "test-key",
    DEVICE_TOKEN_SECRET: "secret-1234567890",
    SUPABASE_ANON_KEY: "anon-key"
  });
  assert.equal(healthRes.status, 200);
  const healthBody = await healthRes.json();
  assert.equal(healthBody.ok, true);
  assert.equal(healthBody.registeredSkillCount, 29);
  assert.equal(healthBody.runtime.deviceAuthConfigured, true);
  assert.equal(healthBody.features.conversationMemory, true);

  const skillsRes = await workerApp.fetch(new Request("https://worker.test/v1/skills"), {});
  assert.equal(skillsRes.status, 200);
  const skillsBody = await skillsRes.json();
  assert.equal(skillsBody.ok, true);
  assert.equal(skillsBody.count, 29);
});

test("Worker /v1/chat forwards conversation history and returns normalized visual cards & hologram state", async () => {
  const originalFetch = globalThis.fetch;
  let capturedMessages = null;
  globalThis.fetch = async (url, options = {}) => {
    const u = String(url);
    if (u.includes("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174000", email: "test@example.com" }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    if (u.includes("raw.githubusercontent.com")) {
      return new Response("# Skill Mock\n Instructions", { status: 200 });
    }
    if (u.includes("api.groq.com")) {
      const payload = JSON.parse(options.body);
      capturedMessages = payload.messages;
      return new Response(JSON.stringify({
        choices: [{ message: { content: "Halo kembali, Tuan. Tentu saya ingat percakapan kita." } }]
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    return new Response("Not found", { status: 404 });
  };
  try {
    const req = new Request("https://worker.test/v1/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": "Bearer supabase-test-access-token" },
      body: JSON.stringify({
        message: "Halo",
        clientRequestId: "req-12345",
        history: [
          { role: "user", content: "Nama saya Budi" },
          { role: "assistant", content: "Baik Pak Budi, akan saya ingat." }
        ]
      })
    });
    const res = await workerApp.fetch(req, {
      GROQ_API_KEY_1: "groq-test-key",
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "test-public-key",
      DEVICE_REGISTRY: { async get() { return null; }, async put() {} }
    });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.clientRequestId, "req-12345");
    assert.ok(Array.isArray(body.visuals.cards));
    assert.equal(body.visuals.hologram.state, "idle");
    assert.ok(Array.isArray(capturedMessages));
    assert.equal(capturedMessages.length, 4); // system + 2 history + 1 user
    assert.equal(capturedMessages[1].content, "Nama saya Budi");
    assert.equal(capturedMessages[2].content, "Baik Pak Budi, akan saya ingat.");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("authenticated /v1/tts proxies Fish Audio with one stable Jarvis voice profile", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), init });
    if (String(url).endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "user-jarvis-test" }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    if (String(url) === "https://api.fish.audio/v1/tts") {
      assert.equal(init.headers.Authorization, "Bearer fish-test-key");
      assert.equal(init.headers.model, "s2.1-pro-free");
      const body = JSON.parse(init.body);
      assert.equal(body.reference_id, "612b878b113047d9a770c069c8b4fdfe");
      assert.equal(body.format, "mp3");
      return new Response(new Uint8Array([73, 68, 51, 4]), { status: 200, headers: { "Content-Type": "audio/mpeg" } });
    }
    throw new Error("Unexpected upstream URL: " + url);
  };
  try {
    const response = await workerApp.fetch(new Request("https://worker.test/v1/tts", {
      method: "POST",
      headers: { Authorization: "Bearer supabase-test-token", "Content-Type": "application/json" },
      body: JSON.stringify({ text: "Good evening, Sir." })
    }), {
      SUPABASE_URL: "https://supabase.test",
      SUPABASE_ANON_KEY: "anon-test",
      FISH_API_KEY: "fish-test-key"
    });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Content-Type"), "audio/mpeg");
    assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [73, 68, 51, 4]);
    assert.equal(calls.length, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("unauthenticated /v1/tts requests are rejected before contacting Fish Audio", async () => {
  const originalFetch = globalThis.fetch;
  let fishCalled = false;
  globalThis.fetch = async (url) => {
    if (String(url).endsWith("/auth/v1/user")) return new Response("{}", { status: 401 });
    fishCalled = true;
    return new Response("unexpected", { status: 500 });
  };
  try {
    const response = await workerApp.fetch(new Request("https://worker.test/v1/tts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: "Hello" })
    }), { SUPABASE_URL: "https://supabase.test", SUPABASE_ANON_KEY: "anon", FISH_API_KEY: "fish" });
    assert.equal(response.status, 401);
    assert.equal(fishCalled, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
