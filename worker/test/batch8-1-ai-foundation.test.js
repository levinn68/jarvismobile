import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import workerApp from "../src/index.js";
import {
  JARVIS_TIERS,
  BUILTIN_MODELS,
  listModelCatalog,
  registerCustomModel,
  resolveModelOrTier
} from "../src/ai/model-registry.js";
import {
  PROVIDER_CATALOG,
  maskSecretValue,
  maskEmailOrLabel,
  listUserAiAccounts,
  saveUserAiAccount,
  removeUserAiAccount,
  resolveAccountCredentialsChain,
  summarizeProvidersStatus,
  testProviderConnection
} from "../src/ai/multi-account-manager.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../..");

function memoryKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.get(key) ?? null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    }
  };
}

test("Batch 8.1 — Model Registry: CORE, PULSE, SPARK, APEX tiers, capability filtering, and custom model registration", () => {
  assert.deepEqual(Object.keys(JARVIS_TIERS), ["CORE", "PULSE", "SPARK", "APEX"]);
  assert.equal(BUILTIN_MODELS.length, 6);

  const visionModels = listModelCatalog({ category: "Vision" });
  assert.ok(visionModels.some((m) => m.id === "gpt-4o"));
  assert.ok(visionModels.some((m) => m.id === "gemini-1.5-pro"));
  assert.equal(visionModels.some((m) => m.id === "deepseek-v3"), false);

  const coreRoute = resolveModelOrTier("CORE", ["groq", "gemini"]);
  assert.equal(coreRoute.tier, "CORE");
  assert.equal(coreRoute.provider, "groq");

  const sparkRoute = resolveModelOrTier("SPARK", ["deepseek", "openai"]);
  assert.equal(sparkRoute.tier, "SPARK");
  assert.equal(sparkRoute.provider, "deepseek");

  const custom = registerCustomModel({
    id: "mistral-large-custom",
    name: "Mistral Large Custom",
    provider: "openrouter",
    apiModel: "mistralai/mistral-large",
    tier: "SPARK",
    capabilities: ["text", "code"]
  });
  assert.equal(custom.isCustom, true);
  assert.equal(resolveModelOrTier("mistral-large-custom", ["openrouter"]).modelId, "mistral-large-custom");
});

test("Batch 8.1 — Multi-Account Architecture: account storage, default switching, quota fallback, and credential masking", async () => {
  const kv = memoryKv();
  const userId = "123e4567-e89b-12d3-a456-426614174000";
  const testKeyEnv = { AI_CREDENTIAL_ENCRYPTION_KEY: "test-only-ai-credential-encryption-secret-32bytes!", OPENAI_API_KEY_1: "sk-server-fallback-3333" };

  assert.equal(maskSecretValue("sk-proj-1234567890abcdef"), "sk-***cdef");
  assert.equal(maskEmailOrLabel("alvino@domain.com"), "al***@domain.com");

  const acc1 = await saveUserAiAccount(kv, userId, {
    accountId: "acc-openai-primary",
    provider: "openai",
    label: "Akun Utama",
    email: "primary@domain.com",
    apiKey: "sk-primary-secret-key-1111",
    isDefault: true,
    quotaUsedTokens: 64000,
    quotaLimitTokens: 200000
  }, testKeyEnv);
  assert.equal(acc1.apiKey, undefined, "Public account object must never expose raw apiKey");
  assert.equal(acc1.isDefault, true);

  const acc2 = await saveUserAiAccount(kv, userId, {
    accountId: "acc-openai-work",
    provider: "openai",
    label: "Akun Work",
    email: "work@domain.com",
    apiKey: "sk-work-secret-key-2222",
    isDefault: false,
    quotaUsedTokens: 10000,
    quotaLimitTokens: 200000
  }, testKeyEnv);
  assert.equal(acc2.isDefault, false);

  // Switch default to Akun Work
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-openai-work",
    provider: "openai",
    label: "Akun Work",
    isDefault: true
  }, testKeyEnv);

  const chain1 = await resolveAccountCredentialsChain(kv, userId, "openai", testKeyEnv);
  assert.equal(chain1.length, 3);
  assert.equal(chain1[0].accountId, "acc-openai-work");
  assert.equal(chain1[1].accountId, "acc-openai-primary");
  assert.equal(chain1[2].source, "worker_secret");

  // Simulate quota exhaustion on Akun Work -> automatic fallback to Akun Utama
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-openai-work",
    provider: "openai",
    label: "Akun Work",
    isDefault: true,
    quotaUsedTokens: 200000,
    quotaLimitTokens: 200000
  }, testKeyEnv);
  const chainAfterQuota = await resolveAccountCredentialsChain(kv, userId, "openai", testKeyEnv);
  assert.equal(chainAfterQuota[0].accountId, "acc-openai-primary");

  const removed = await removeUserAiAccount(kv, userId, "acc-openai-work");
  assert.equal(removed, true);
});

test("Batch 8.1 — Provider Connection Testing & HTTP Endpoints (/v1/models, /v1/providers, /v1/providers/test, /v1/ai-accounts)", async () => {
  // 1. Test live connection success vs failure vs unconfigured
  const unconfiguredRes = await testProviderConnection({ provider: "anthropic", apiKey: "", env: {} });
  assert.equal(unconfiguredRes.ok, false);
  assert.equal(unconfiguredRes.integrationState, "ui_configured_only");

  const failRes = await testProviderConnection({
    provider: "openai",
    apiKey: "sk-invalid-key-value",
    fetchImpl: async () => new Response(JSON.stringify({ error: { message: "Invalid key" } }), { status: 401 })
  });
  assert.equal(failRes.ok, false);
  assert.equal(failRes.integrationState, "connection_failed");
  assert.equal(failRes.error.includes("sk-invalid-key-value"), false);

  const okRes = await testProviderConnection({
    provider: "gemini",
    apiKey: "AIzaSyValidKey123456789012345",
    fetchImpl: async () => new Response(JSON.stringify({ models: [{ name: "models/gemini-2.5-flash" }] }), { status: 200 })
  });
  assert.equal(okRes.ok, true);
  assert.equal(okRes.integrationState, "working_live");

  // 2. Test Worker HTTP endpoints
  const kv = memoryKv();
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      if (String(url).includes("/auth/v1/user")) {
        return new Response(JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174000", email: "user@example.com" }), { status: 200 });
      }
      return new Response(JSON.stringify({ data: [] }), { status: 200 });
    };

    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon-key",
      AI_CREDENTIAL_ENCRYPTION_KEY: "test-only-ai-credential-encryption-secret-32bytes!",
      GEMINI_API_KEY_1: "gem-key-1",
      OPENAI_API_KEY_1: "open-key-1",
      DEVICE_REGISTRY: kv
    };

    const modelsHttp = await workerApp.fetch(new Request("https://worker.test/v1/models?category=Kode"), env);
    assert.equal(modelsHttp.status, 200);
    const modelsBody = await modelsHttp.json();
    assert.equal(modelsBody.ok, true);
    assert.ok(modelsBody.tiers.APEX);

    const providersHttp = await workerApp.fetch(
      new Request("https://worker.test/v1/providers", { headers: { Authorization: "Bearer valid-jwt" } }),
      env
    );
    assert.equal(providersHttp.status, 200);
    const providersBody = await providersHttp.json();
    assert.equal(providersBody.ok, true);
    assert.ok(providersBody.providers.some((p) => p.id === "gemini" && p.connected === true));

    const createAccHttp = await workerApp.fetch(
      new Request("https://worker.test/v1/ai-accounts", {
        method: "POST",
        headers: { Authorization: "Bearer valid-jwt", "Content-Type": "application/json" },
        body: JSON.stringify({
          provider: "deepseek",
          label: "Akun Riset DeepSeek",
          email: "riset@domain.com",
          apiKey: "sk-deepseek-secret-key-9999"
        })
      }),
      env
    );
    assert.equal(createAccHttp.status, 201);
    const createAccBody = await createAccHttp.json();
    assert.equal(createAccBody.ok, true);
    assert.equal(createAccBody.account.apiKey, undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Batch 8.1 — Android UI Foundation & Supabase Migration static verification", () => {
  const dsKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/ui/JarvisDesignSystem.kt"), "utf8");
  const foundKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/ui/JarvisFoundationScreens.kt"), "utf8");
  const aiKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/ai/JarvisAiFoundationScreens.kt"), "utf8");
  const migSql = fs.readFileSync(path.join(REPO_ROOT, "supabase/migrations/20261106081000_jarvis_b8_1_ai_foundation.sql"), "utf8");

  assert.ok(dsKt.includes("enum class JarvisB8NavTab"));
  assert.ok(dsKt.includes("enum class JarvisIntelligenceTier"));
  assert.ok(foundKt.includes("fun JarvisB8OnboardingFlow("));
  assert.ok(foundKt.includes("fun JarvisDashboardScreen("));
  assert.ok(foundKt.includes("fun JarvisB8BottomNavigationBar("));
  assert.ok(aiKt.includes("fun JarvisModelSelectorScreen("));
  assert.ok(aiKt.includes("fun JarvisProvidersScreen("));
  assert.ok(aiKt.includes("fun JarvisMultiAccountsScreen("));
  assert.match(migSql, /create table if not exists public\.jarvis_ai_providers/i);
  assert.match(migSql, /create table if not exists public\.jarvis_ai_accounts/i);
  assert.match(migSql, /create table if not exists public\.jarvis_model_preferences/i);
  assert.match(migSql, /force row level security/i);
});