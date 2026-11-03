import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

function oversizedJsonRequest(path, bytes) {
  const padding = "x".repeat(bytes);
  return new Request("https://jarvis.test" + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ payload: padding })
  });
}

test("chat endpoint rejects JSON bodies above 512 KiB", async () => {
  const request = oversizedJsonRequest("/v1/chat", 520 * 1024);
  const response = await worker.fetch(request, {});
  assert.equal(response.status, 413);
  assert.match((await response.json()).detail, /too large/);
});

test("search endpoint rejects JSON bodies above 64 KiB", async () => {
  const request = oversizedJsonRequest("/v1/search/images", 65 * 1024);
  const response = await worker.fetch(request, {});
  assert.equal(response.status, 413);
  assert.match((await response.json()).detail, /too large/);
});

test("TTS endpoint rejects JSON bodies above 64 KiB before calling the provider", async () => {
  const request = oversizedJsonRequest("/v1/tts", 65 * 1024);
  const response = await worker.fetch(request, { FISH_API_KEY: "test-key" });
  assert.equal(response.status, 413);
  assert.match((await response.json()).detail, /too large/);
});

test("public API rejects JSON null and array bodies with HTTP 400", async () => {
  const nullResponse = await worker.fetch(new Request("https://jarvis.test/v1/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "null"
  }), {});
  assert.equal(nullResponse.status, 400);

  const arrayResponse = await worker.fetch(new Request("https://jarvis.test/v1/search/images", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "[]"
  }), {});
  assert.equal(arrayResponse.status, 400);
});


test("public API rejects non-string chat, search, and TTS text fields", async () => {
  const chatResponse = await worker.fetch(new Request("https://jarvis.test/v1/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message: { prompt: "unexpected object" } })
  }), {});
  assert.equal(chatResponse.status, 400);
  assert.match((await chatResponse.json()).error, /message must be a string/);

  const searchResponse = await worker.fetch(new Request("https://jarvis.test/v1/search/images", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: ["unexpected", "array"] })
  }), {});
  assert.equal(searchResponse.status, 400);
  assert.match((await searchResponse.json()).error, /query must be a string/);

  const ttsResponse = await worker.fetch(new Request("https://jarvis.test/v1/tts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text: { unexpected: "object" } })
  }), { FISH_API_KEY: "test-key" });
  assert.equal(ttsResponse.status, 400);
  assert.match((await ttsResponse.json()).error, /text must be a string/);
});


test("CORS preflight permits the device capability PUT method", async () => {
  const response = await worker.fetch(new Request("https://jarvis.test/v1/devices/android-test-device/capabilities", {
    method: "OPTIONS",
    headers: {
      Origin: "https://jarvis-client.test",
      "Access-Control-Request-Method": "PUT",
      "Access-Control-Request-Headers": "authorization,content-type"
    }
  }), {});
  assert.equal(response.status, 200);
  assert.match(response.headers.get("Access-Control-Allow-Methods") || "", /\\bPUT\\b/);
});
