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

test("chat endpoint rejects JSON bodies above 128 KiB", async () => {
  const request = oversizedJsonRequest("/v1/chat", 130 * 1024);
  const response = await worker.fetch(request, {});
  assert.equal(response.status, 413);
  assert.match((await response.json()).detail, /too large/);
});

test("search endpoint rejects JSON bodies above 16 KiB", async () => {
  const request = oversizedJsonRequest("/v1/search/images", 17 * 1024);
  const response = await worker.fetch(request, {});
  assert.equal(response.status, 413);
  assert.match((await response.json()).detail, /too large/);
});

test("TTS endpoint rejects JSON bodies above 32 KiB before calling the provider", async () => {
  const request = oversizedJsonRequest("/v1/tts", 33 * 1024);
  const response = await worker.fetch(request, { FISH_API_KEY: "test-key" });
  assert.equal(response.status, 413);
  assert.match((await response.json()).detail, /too large/);
});
