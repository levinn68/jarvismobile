import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import worker from "../src/index.js";
import { createTaskOrchestrator } from "../src/core/task-orchestrator.js";
import { validateCommand } from "../src/device/protocol.js";
import {
  enqueueDeviceCommand,
  acknowledgeDeviceCommand,
  getDeviceCommand
} from "../src/device/command-service.js";
import { listUserAiAccounts } from "../src/ai/multi-account-manager.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "../..");

function createMemoryKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.has(key) ? store.get(key) : null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    },
    async list({ prefix = "" } = {}) {
      const keys = [];
      for (const k of store.keys()) {
        if (k.startsWith(prefix)) keys.push({ name: k });
      }
      return { keys, list_complete: true };
    }
  };
}

test("Batch 8.7 — 1. /v1/chat routes simple & complex prompts using user-configured encrypted accounts (Anthropic, Gemini, Custom) and tracks token usage", async () => {
  const kv = createMemoryKv();
  const userId = "123e4567-e89b-12d3-a456-426614174887";
  // Notice: ZERO server-managed provider keys in env! Only user-configured accounts in KV.
  const env = {
    JARVIS_ENV: "production",
    DEVICE_REGISTRY: kv,
    DEVICE_TOKEN_SECRET: "b8-7-production-secret-key-minimum-32-bytes-long-998877",
    AI_CREDENTIAL_ENCRYPTION_KEY: "test-only-ai-credential-master-key-64-characters-minimum-000000000000000000",
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "sb_publishable_test_key"
  };

  const originalFetch = globalThis.fetch;
  const calledUrls = [];
  try {
    globalThis.fetch = async (url, init = {}) => {
      const u = String(url);
      calledUrls.push({ url: u, body: init?.body ? JSON.parse(init.body) : null });
      if (u.includes("/auth/v1/user")) {
        return new Response(JSON.stringify({ id: userId, email: "fajar@jarvis.id" }), { status: 200 });
      }
      if (u.includes("api.anthropic.com/v1/messages")) {
        return new Response(
          JSON.stringify({
            content: [
              {
                type: "text",
                text: "Halo bro, Claude 3.5 Sonnet siap membantu analisis sistem J.A.R.V.I.S. dengan aman."
              }
            ]
          }),
          { status: 200 }
        );
      }
      if (u.includes("generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent")) {
        return new Response(
          JSON.stringify({
            candidates: [
              {
                content: {
                  parts: [{ text: "Respons langsung dari model gemini-1.5-pro sesuai pilihan pengguna." }]
                }
              }
            ]
          }),
          { status: 200 }
        );
      }
      return new Response(JSON.stringify({ error: `Unexpected URL: ${u}` }), { status: 500 });
    };

    const headers = {
      Authorization: "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLWI4LTcifQ.sig",
      "Content-Type": "application/json"
    };

    // 1. Add user-managed Anthropic Claude account
    const addAnthropicRes = await worker.fetch(
      new Request("https://jarvis.test/v1/ai-accounts", {
        method: "POST",
        headers,
        body: JSON.stringify({
          accountId: "acc-claude-b87",
          provider: "anthropic",
          label: "Claude Production",
          email: "fajar@anthropic.com",
          apiKey: "sk-ant-api03-ValidAnthropicProductionKey987654321"
        })
      }),
      env
    );
    assert.equal(addAnthropicRes.status, 201);

    // 2. Send a SIMPLE prompt ("Halo") with model: "claude-3-5-sonnet" and zero Worker env keys
    const chatClaudeRes = await worker.fetch(
      new Request("https://jarvis.test/v1/chat", {
        method: "POST",
        headers,
        body: JSON.stringify({
          message: "Halo",
          model: "claude-3-5-sonnet",
          accountId: "acc-claude-b87"
        })
      }),
      env
    );
    assert.equal(chatClaudeRes.status, 200);
    const chatClaudeBody = await chatClaudeRes.json();
    assert.equal(chatClaudeBody.ok, true);
    assert.equal(chatClaudeBody.selectedProvider, "anthropic");
    assert.match(chatClaudeBody.answer, /Claude 3\.5 Sonnet/);

    // Verify token usage was recorded on the user's Anthropic account
    const accountsAfter = await listUserAiAccounts(kv, userId, env, { provider: "anthropic" });
    assert.equal(accountsAfter.length, 1);
    assert.ok(accountsAfter[0].quotaUsedTokens > 0, "Token usage must be incremented after /v1/chat");

    // 3. Add user-managed Gemini account and verify modelOverride ("gemini-1.5-pro") is honored in URL
    await worker.fetch(
      new Request("https://jarvis.test/v1/ai-accounts", {
        method: "POST",
        headers,
        body: JSON.stringify({
          accountId: "acc-gemini-b87",
          provider: "gemini",
          label: "Gemini Pro Account",
          email: "fajar@google.com",
          apiKey: "AIzaSyValidGeminiProductionKey987654321"
        })
      }),
      env
    );

    const chatGemRes = await worker.fetch(
      new Request("https://jarvis.test/v1/chat", {
        method: "POST",
        headers,
        body: JSON.stringify({
          message: "Halo Gemini",
          model: "gemini-1.5-pro",
          accountId: "acc-gemini-b87"
        })
      }),
      env
    );
    assert.equal(chatGemRes.status, 200);
    const chatGemBody = await chatGemRes.json();
    assert.equal(chatGemBody.ok, true);
    assert.match(chatGemBody.answer, /gemini-1\.5-pro/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Batch 8.7 — 2. Section 6.3 Device Control Commands (OPEN_APP, WAIT, TAP_COORDINATE, CLICK_TEXT, TYPE_TEXT, SCROLL, GLOBAL_ACTION) validation & local approval enforcement", async () => {
  // 1. Verify all 7 commands validate strictly when valid params are supplied
  const openApp = validateCommand({ action: "OPEN_APP", params: { packageName: "com.whatsapp" } }, "android");
  assert.equal(openApp.capability, "apps.launch");
  assert.equal(openApp.requiresConfirmation, false);

  const waitCmd = validateCommand({ action: "WAIT", params: { durationMs: 1200 } }, "android");
  assert.equal(waitCmd.capability, "system.read");
  assert.equal(waitCmd.requiresConfirmation, false);
  assert.equal(waitCmd.params.durationMs, 1200);

  const tapCmd = validateCommand({ action: "TAP_COORDINATE", params: { x: 0.25, y: 0.75 } }, "android");
  assert.equal(tapCmd.capability, "screen.control");
  assert.equal(tapCmd.requiresConfirmation, true);

  const clickTextCmd = validateCommand({ action: "CLICK_TEXT", params: { text: "Lanjutkan" } }, "android");
  assert.equal(clickTextCmd.capability, "screen.control");
  assert.equal(clickTextCmd.requiresConfirmation, true);

  const typeTextCmd = validateCommand({ action: "TYPE_TEXT", params: { text: "Halo JARVIS" } }, "android");
  assert.equal(typeTextCmd.capability, "screen.control");
  assert.equal(typeTextCmd.requiresConfirmation, true);

  const scrollCmd = validateCommand({ action: "SCROLL", params: { direction: "down" } }, "android");
  assert.equal(scrollCmd.capability, "screen.control");
  assert.equal(scrollCmd.requiresConfirmation, true);

  const globalCmd = validateCommand({ action: "GLOBAL_ACTION", params: { globalAction: "BACK" } }, "android");
  assert.equal(globalCmd.capability, "screen.control");
  assert.equal(globalCmd.requiresConfirmation, true);

  // 2. Verify sensitive commands (CLICK_TEXT, SCROLL, GLOBAL_ACTION) reject unapproved ACKs on the Worker
  const kv = createMemoryKv();
  const device = {
    deviceId: "rog-phone-6-b87",
    accountId: "acct-b87",
    platform: "android",
    capabilities: ["screen.read", "screen.control", "apps.launch", "system.read"]
  };

  for (const input of [
    { action: "CLICK_TEXT", params: { text: "Simpan" } },
    { action: "SCROLL", params: { direction: "up" } },
    { action: "GLOBAL_ACTION", params: { globalAction: "HOME" } }
  ]) {
    const queued = await enqueueDeviceCommand({
      kv,
      device,
      input,
      context: { userConfirmed: true } // Remote userConfirmed must be ignored!
    });
    assert.equal(queued.ok, true);
    assert.equal(queued.status, "queued_pending_approval");
    assert.equal(queued.requiresConfirmation, true);

    // Attempt to ACK without approved: true must fail
    const badAck = await acknowledgeDeviceCommand({
      kv,
      deviceId: device.deviceId,
      commandId: queued.commandId,
      result: { ok: true, executed: true }
    });
    assert.equal(badAck.ok, false);
    assert.equal(badAck.outcome, "failed");
    const rec = await getDeviceCommand({ kv, deviceId: device.deviceId, commandId: queued.commandId });
    assert.match(rec.result.error, /explicit on-device user approval/);
  }
});

test("Batch 8.7 — 3. Android Vault Thread-Safety, Bounded HTTP Reads, Coroutine Cancellation Guards & Production Config Verification", () => {
  const devVault = fs.readFileSync(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/device/DeviceCredentialVault.kt"),
    "utf8"
  );
  assert.match(devVault, /@Synchronized\s+fun save\(/, "DeviceCredentialVault.save must be @Synchronized");
  assert.match(devVault, /@Synchronized\s+fun saveAccountToken\(/, "DeviceCredentialVault.saveAccountToken must be @Synchronized");
  assert.match(devVault, /recreateIfBroken: Boolean/, "DeviceCredentialVault must recover from broken Keystore keys");

  const accessService = fs.readFileSync(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/device/JarvisAccessibilityService.kt"),
    "utf8"
  );
  assert.match(accessService, /fun clickText\(/, "JarvisAccessibilityService must implement clickText");
  assert.match(accessService, /fun scroll\(/, "JarvisAccessibilityService must implement scroll");
  assert.match(accessService, /fun globalAction\(/, "JarvisAccessibilityService must implement globalAction");
  assert.match(accessService, /gestureCallbackThread\?\.quitSafely\(\)/, "JarvisAccessibilityService must clean up HandlerThread in onDestroy");

  const supaClient = fs.readFileSync(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt"),
    "utf8"
  );
  assert.match(supaClient, /readBoundedBytes\(/, "SupabaseJarvisClient must use bounded stream reads");
  assert.match(supaClient, /if \(error is kotlinx\.coroutines\.CancellationException\) throw error/, "SupabaseJarvisClient must rethrow CancellationException");

  const devClient = fs.readFileSync(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/device/JarvisDeviceClient.kt"),
    "utf8"
  );
  assert.match(devClient, /readBoundedBytes\(/, "JarvisDeviceClient must use bounded stream reads");

  const wranglerToml = fs.readFileSync(path.join(repoRoot, "worker/wrangler.toml"), "utf8");
  assert.match(wranglerToml, /JARVIS_ENV = "production"/, "wrangler.toml default vars must set JARVIS_ENV = production");
  assert.match(wranglerToml, /\[env\.staging\]/, "wrangler.toml must define [env.staging]");
  assert.match(wranglerToml, /\[env\.development\]/, "wrangler.toml must define [env.development]");
});


test("task orchestration fails closed when no authorizer is configured", async () => {
  let invoked = false;
  const orchestrator = createTaskOrchestrator({ handlers: { sensitive: async () => { invoked = true; return "ran"; } } });
  const result = await orchestrator.run({ type: "sensitive", payload: {} });
  assert.equal(result.status, "blocked");
  assert.equal(result.ok, false);
  assert.equal(invoked, false);
});
