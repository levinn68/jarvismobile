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

test("PeaProxy is opt-in and requires HTTPS, local gateway key, and exact model id", () => {
  assert.deepEqual(listConfiguredJarvisProviders({
    PEAPROXY_BASE_URL: "http://127.0.0.1:8317/v1",
    PEAPROXY_MODEL: "some-model",
  }), []);
  assert.deepEqual(listConfiguredJarvisProviders({
    PEAPROXY_BASE_URL: "https://proxy.example/v1",
    PEAPROXY_API_KEY: "local-key",
  }), []);
  assert.deepEqual(listConfiguredJarvisProviders({
    PEAPROXY_BASE_URL: "https://proxy.example/v1",
    PEAPROXY_MODEL: "exact-live-model-id",
    PEAPROXY_ACCESS_CLIENT_ID: "cf-id",
    PEAPROXY_ACCESS_CLIENT_SECRET: "cf-secret",
  }), ["peaproxy"]);
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

test("PeaProxy provider sends OpenAI-compatible chat request and reads assistant response", async () => {
  const originalFetch = globalThis.fetch;
  let calledUrl = "";
  let calledHeaders;
  let calledBody;
  globalThis.fetch = async (url, init) => {
    calledUrl = String(url);
    calledHeaders = init.headers;
    calledBody = JSON.parse(init.body);
    return new Response(JSON.stringify({
      choices: [{ message: { role: "assistant", content: "Hello from PeaProxy Gemini." } }],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const result = await runJarvisProviders({
      env: {
        PEAPROXY_BASE_URL: "https://proxy.example/v1/",
            PEAPROXY_MODEL: "gemini-model-from-catalog",
        PEAPROXY_ACCESS_CLIENT_ID: "cf-client-id",
        PEAPROXY_ACCESS_CLIENT_SECRET: "cf-client-secret",
        PEAPROXY_ACCESS_CLIENT_ID: "cf-client-id",
        PEAPROXY_ACCESS_CLIENT_SECRET: "cf-client-secret",
      },
      messages: [{ role: "system", content: "You are JARVIS." }, { role: "user", content: "Status?" }],
      level: "simple",
    });
    assert.equal(result.ok, true);
    assert.equal(result.provider, "peaproxy");
    assert.equal(result.result, "Hello from PeaProxy Gemini.");
    assert.equal(calledUrl, "https://proxy.example/v1/chat/completions");
    assert.equal(calledHeaders["CF-Access-Client-Id"], "cf-client-id");
    assert.equal(calledHeaders["CF-Access-Client-Secret"], "cf-client-secret");
    assert.equal(calledBody.model, "gemini-model-from-catalog");
    assert.equal(calledBody.stream, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("falls back to PeaProxy after the preferred provider fails", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).includes("api.groq.com")) {
      return new Response(JSON.stringify({ error: { message: "temporary outage" } }), { status: 503 });
    }
    return new Response(JSON.stringify({
      choices: [{ message: { content: "Fallback through PeaProxy." } }],
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  };
  try {
    const result = await runJarvisProviders({
      env: {
        GROQ_API_KEY_1: "groq-test",
        PEAPROXY_BASE_URL: "https://proxy.example/v1",
        PEAPROXY_MODEL: "gemini-model-from-catalog",
        PEAPROXY_ACCESS_CLIENT_ID: "cf-client-id",
        PEAPROXY_ACCESS_CLIENT_SECRET: "cf-client-secret",
      },
      messages: [{ role: "user", content: "Hello" }],
      level: "simple",
    });
    assert.equal(result.ok, true);
    assert.equal(result.provider, "peaproxy");
    assert.match(result.result, /Fallback through PeaProxy/);
    assert.equal(calls.length, 3);
    assert.ok(calls[0].includes("api.groq.com"));
    assert.ok(calls[2].includes("proxy.example"));
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
