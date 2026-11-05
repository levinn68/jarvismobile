import test from "node:test";
import assert from "node:assert/strict";
import { runJarvisProviders, listConfiguredJarvisProviders } from "../src/ai/provider-orchestrator.js";

test("lists all configured JARVIS providers without exposing credentials", () => {
  const env = {
    GEMINI_API_KEY_1: "gemini-secret",
    DEEPSEEK_API_KEY_1: "deepseek-secret",
    GROQ_API_KEY_1: "groq-secret",
    OPENAI_API_KEY_1: "openai-secret",
    OPENROUTER_API_KEY_1: "openrouter-secret",
  };
  assert.deepEqual(listConfiguredJarvisProviders(env), ["gemini", "deepseek", "groq", "openai", "openrouter"]);
});

test("uses configured provider and returns normalized assistant text", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({
    choices: [{ message: { content: "Certainly, Sir. The systems are ready." } }],
  }), { status: 200, headers: { "Content-Type": "application/json" } });
  try {
    const result = await runJarvisProviders({
      env: { GROQ_API_KEY_1: "test-key" },
      messages: [{ role: "system", content: "You are JARVIS." }, { role: "user", content: "Status?" }],
      level: "simple",
    });
    assert.equal(result.ok, true);
    assert.equal(result.provider, "groq");
    assert.match(result.result, /systems are ready/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("falls back to the next provider after a retryable upstream failure", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).includes("api.openai.com")) {
      return new Response(JSON.stringify({ error: { message: "temporary outage" } }), { status: 503 });
    }
    return new Response(JSON.stringify({
      choices: [{ message: { content: "Fallback response from Groq." } }],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const result = await runJarvisProviders({
      env: { OPENAI_API_KEY_1: "openai-test", GROQ_API_KEY_1: "groq-test" },
      messages: [{ role: "user", content: "Design an architecture." }],
      level: "complex",
      onAttempt: () => {},
    });
    assert.equal(result.ok, true);
    assert.equal(result.provider, "groq");
    assert.equal(calls.length, 3);
    assert.ok(calls[0].includes("api.openai.com"));
    assert.ok(calls[2].includes("api.groq.com"));
  } finally {
    globalThis.fetch = originalFetch;
  }
});
