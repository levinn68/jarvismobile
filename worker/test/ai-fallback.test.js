import test from "node:test";
import assert from "node:assert/strict";
import { runWithFallback, isRetryableProviderError, withTimeout } from "../src/ai/fallback-engine.js";
import { createProviderManager } from "../src/ai/provider-manager.js";
import { createKeyRotator } from "../src/ai/key-rotation.js";

test("isRetryableProviderError classifies 429 and 5xx as retryable", () => {
  assert.equal(isRetryableProviderError(new Error("HTTP 429: rate")), true);
  assert.equal(isRetryableProviderError(Object.assign(new Error("fail"), { status: 503 })), true);
  assert.equal(isRetryableProviderError(new Error("HTTP 401: unauthorized")), false);
});

test("withTimeout rejects after deadline", async () => {
  await assert.rejects(
    () => withTimeout(new Promise(() => {}), 50, "slow"),
    /timed out/
  );
});

test("runWithFallback switches to the next provider after failure", async () => {
  const attempts = [];
  const result = await runWithFallback({
    providers: ["broken", "healthy"],
    timeoutMs: 2000,
    maxAttemptsPerProvider: 1,
    onAttempt: (e) => attempts.push(e),
    invoke: async (provider) => {
      if (provider === "broken") {
        const err = new Error("HTTP 503: down");
        err.status = 503;
        throw err;
      }
      return { text: "ok from " + provider };
    }
  });
  assert.equal(result.ok, true);
  assert.equal(result.provider, "healthy");
  assert.equal(result.result.text, "ok from healthy");
  assert.ok(result.errors.some((e) => e.provider === "broken"));
});

test("runWithFallback stops when error is not retryable and no healthy provider left", async () => {
  const result = await runWithFallback({
    providers: ["auth-fail"],
    maxAttemptsPerProvider: 2,
    invoke: async () => {
      const err = new Error("HTTP 401: bad key");
      err.status = 401;
      throw err;
    }
  });
  assert.equal(result.ok, false);
  assert.equal(result.errors.length, 1);
});

test("provider-manager rotates through keys without exposing secrets", async () => {
  const rotator = createKeyRotator({ cooldownMs: 10, maxFailures: 1 });
  const env = { KEY_A: "secret-a", KEY_B: "secret-b" };
  let used = [];
  const manager = createProviderManager({
    timeoutMs: 2000,
    rotator,
    providers: {
      demo: {
        keys: ["KEY_A", "KEY_B"],
        invoke: async ({ key }) => {
          used.push(key);
          if (key === "KEY_A") {
            const err = new Error("HTTP 429: limited");
            err.status = 429;
            throw err;
          }
          return "answer";
        }
      }
    }
  });
  const out = await manager.invoke("demo", env, {});
  assert.equal(out.result, "answer");
  assert.deepEqual(used, ["KEY_A", "KEY_B"]);
  assert.ok(!JSON.stringify(out).includes("secret"));
});
