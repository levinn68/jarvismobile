import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import workerApp from "../src/index.js";
import {
  PROVIDER_CATALOG,
  PROVIDER_ERROR_CODES,
  decryptCredentialFromKv,
  encryptCredentialForKv,
  invokeProviderCompletion,
  listServerManagedCredentials,
  listUserAiAccounts,
  removeUserAiAccount,
  resolveAccountCredentialsChain,
  saveProviderConfiguration,
  saveUserAiAccount,
  summarizeProvidersStatus,
  testProviderConnection,
  validateProviderApiKeyFormat
} from "../src/ai/multi-account-manager.js";
import {
  getProviderModels,
  listModelCatalog
} from "../src/ai/model-registry.js";

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

test("Batch 8.5.2 — 1 & 2. Independent Provider Configuration: Adding OpenAI does not affect Gemini, and adding Gemini does not affect OpenAI", async () => {
  const kv = createMockKv();
  const userId = "user-indep-001";
  const env = { DEVICE_AUTH_SECRET: "kms-secret-key-for-b8-5-2-encryption" };

  // 1. Add OpenAI account -> must NOT affect Gemini or Claude
  const openaiAcc = await saveUserAiAccount(
    kv,
    userId,
    {
      provider: "openai",
      label: "OpenAI Personal",
      email: "fajar@openai.user",
      apiKey: "sk-proj-OpenAiPersonalSecretKey123456"
    },
    env
  );

  // Saving an API key alone must NOT mark the account or provider as Connected!
  assert.equal(openaiAcc.status, "configured_unverified");
  assert.equal(openaiAcc.connectionVerified, false);

  let statusList = await summarizeProvidersStatus(kv, userId, env);
  const openaiAfterStep1 = statusList.find((p) => p.id === "openai");
  const geminiAfterStep1 = statusList.find((p) => p.id === "gemini");
  const claudeAfterStep1 = statusList.find((p) => p.id === "anthropic");

  assert.equal(openaiAfterStep1.accountCount, 1);
  assert.equal(openaiAfterStep1.connected, false, "Must NOT claim Connected merely because API key was saved");
  assert.equal(openaiAfterStep1.connectionStatus, "configured_unverified");
  assert.equal(geminiAfterStep1.accountCount, 0, "Gemini must remain unaffected when OpenAI is added");
  assert.equal(geminiAfterStep1.connected, false);
  assert.equal(claudeAfterStep1.accountCount, 0, "Anthropic must remain unaffected when OpenAI is added");

  // 2. Add Gemini account -> must NOT affect OpenAI
  const geminiAcc = await saveUserAiAccount(
    kv,
    userId,
    {
      provider: "gemini",
      label: "Gemini Personal",
      email: "fajar@gemini.user",
      apiKey: "AIzaSyGeminiPersonalSecretKey987654321"
    },
    env
  );
  assert.equal(geminiAcc.status, "configured_unverified");
  assert.equal(geminiAcc.connectionVerified, false);

  statusList = await summarizeProvidersStatus(kv, userId, env);
  const openaiAfterStep2 = statusList.find((p) => p.id === "openai");
  const geminiAfterStep2 = statusList.find((p) => p.id === "gemini");

  assert.equal(openaiAfterStep2.accountCount, 1);
  assert.equal(openaiAfterStep2.accounts[0].label, "OpenAI Personal");
  assert.equal(geminiAfterStep2.accountCount, 1);
  assert.equal(geminiAfterStep2.accounts[0].label, "Gemini Personal");

  // 3. Verify cross-provider key rejection (OpenAI key cannot be used for Gemini or Claude)
  assert.equal(validateProviderApiKeyFormat("gemini", "sk-proj-OpenAiKey12345").valid, false);
  assert.equal(validateProviderApiKeyFormat("anthropic", "sk-proj-OpenAiKey12345").valid, false);
  assert.equal(validateProviderApiKeyFormat("openai", "AIzaSyGeminiKey123456").valid, false);
  assert.equal(validateProviderApiKeyFormat("openai", "sk-ant-api03-ClaudeKey123").valid, false);
  assert.equal(validateProviderApiKeyFormat("groq", "AIzaSyGeminiKey123456").valid, false);

  await assert.rejects(
    async () =>
      saveUserAiAccount(
        kv,
        userId,
        {
          provider: "gemini",
          label: "Invalid Cross-Provider Key",
          apiKey: "sk-proj-OpenAiKeyCannotWorkOnGemini"
        },
        env
      ),
    (err) => err.errorCode === PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED
  );
});

test("Batch 8.5.2 — 3. Independent Multi-Account: Multiple accounts per provider, deleting one account, and disabling one account without affecting others", async () => {
  const kv = createMockKv();
  const userId = "user-multi-acc-002";
  const env = { DEVICE_AUTH_SECRET: "kms-secret-key-for-b8-5-2-multi" };

  // Add 3 OpenAI accounts: OpenAI Personal, OpenAI Work, OpenAI Research
  const oaPersonal = await saveUserAiAccount(kv, userId, {
    accountId: "acc-oa-personal",
    provider: "openai",
    label: "OpenAI Personal",
    email: "personal@openai.id",
    apiKey: "sk-proj-oaPersonalKey111111",
    isDefault: true
  }, env);
  const oaWork = await saveUserAiAccount(kv, userId, {
    accountId: "acc-oa-work",
    provider: "openai",
    label: "OpenAI Work",
    email: "work@openai.id",
    apiKey: "sk-proj-oaWorkKey222222",
    isDefault: false
  }, env);
  const oaResearch = await saveUserAiAccount(kv, userId, {
    accountId: "acc-oa-research",
    provider: "openai",
    label: "OpenAI Research",
    email: "research@openai.id",
    apiKey: "sk-proj-oaResearchKey333333",
    isDefault: false
  }, env);

  // Add 2 Gemini accounts: Gemini Personal, Gemini Workspace
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-gem-personal",
    provider: "gemini",
    label: "Gemini Personal",
    email: "personal@gemini.id",
    apiKey: "AIzaSyGeminiPersonalKey111111",
    isDefault: true
  }, env);
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-gem-workspace",
    provider: "gemini",
    label: "Gemini Workspace",
    email: "workspace@gemini.id",
    apiKey: "AIzaSyGeminiWorkspaceKey222222",
    isDefault: false
  }, env);

  // Add 2 Claude accounts: Claude Personal, Claude Work
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-claude-personal",
    provider: "anthropic",
    label: "Claude Personal",
    email: "personal@claude.id",
    apiKey: "sk-ant-api03-ClaudePersonal1111",
    isDefault: true
  }, env);
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-claude-work",
    provider: "anthropic",
    label: "Claude Work",
    email: "work@claude.id",
    apiKey: "sk-ant-api03-ClaudeWork2222",
    isDefault: false
  }, env);

  // Verify strict per-provider account isolation
  const oaAccounts = await listUserAiAccounts(kv, userId, env, { provider: "openai" });
  const gemAccounts = await listUserAiAccounts(kv, userId, env, { provider: "gemini" });
  const claudeAccounts = await listUserAiAccounts(kv, userId, env, { provider: "anthropic" });
  assert.equal(oaAccounts.length, 3);
  assert.equal(gemAccounts.length, 2);
  assert.equal(claudeAccounts.length, 2);

  // Disable one OpenAI account (OpenAI Work) -> OpenAI Personal and OpenAI Research remain active!
  await saveUserAiAccount(kv, userId, {
    accountId: "acc-oa-work",
    provider: "openai",
    label: "OpenAI Work",
    enabled: false
  }, env);

  const oaChainAfterDisable = await resolveAccountCredentialsChain(kv, userId, "openai", env, {
    userAccountsOnly: true
  });
  assert.equal(oaChainAfterDisable.length, 2);
  assert.deepEqual(
    oaChainAfterDisable.map((c) => c.accountId),
    ["acc-oa-personal", "acc-oa-research"]
  );

  // Delete OpenAI Personal -> only removes OpenAI Personal, promotes next active OpenAI account to default, and leaves Gemini/Claude untouched
  const deleted = await removeUserAiAccount(kv, userId, oaPersonal.accountId, { provider: "openai" });
  assert.equal(deleted, true);

  const oaAfterDelete = await listUserAiAccounts(kv, userId, env, { provider: "openai" });
  const gemAfterDelete = await listUserAiAccounts(kv, userId, env, { provider: "gemini" });
  const claudeAfterDelete = await listUserAiAccounts(kv, userId, env, { provider: "anthropic" });

  assert.equal(oaAfterDelete.length, 2);
  assert.equal(oaAfterDelete.find((a) => a.accountId === oaResearch.accountId)?.isDefault, true, "Remaining enabled OpenAI account must be promoted to default");
  assert.equal(gemAfterDelete.length, 2, "Deleting an OpenAI account must not affect Gemini");
  assert.equal(claudeAfterDelete.length, 2, "Deleting an OpenAI account must not affect Claude");
  assert.equal(gemAfterDelete.find((a) => a.accountId === "acc-gem-personal")?.isDefault, true);
});

test("Batch 8.5.2 — 4 & 5. AES-256-GCM Credential Encryption in KV, User Isolation, and Worker Secret Separation", async () => {
  const kv = createMockKv();
  const userA = "user-alpha-111";
  const userB = "user-beta-222";
  const secretPlain = "sk-proj-SuperSecretPlaintextApiKey987654321";
  const env = {
    DEVICE_AUTH_SECRET: "kms-master-secret-key-2026",
    OPENAI_API_KEY_1: "sk-server-worker-secret-openai-999",
    GEMINI_API_KEY_1: "AIzaSyServerWorkerSecretGemini888"
  };

  await saveUserAiAccount(
    kv,
    userA,
    {
      accountId: "acc-alpha-openai",
      provider: "openai",
      label: "Alpha OpenAI",
      email: "alpha@jarvis.id",
      apiKey: secretPlain
    },
    env
  );

  // 1. Inspect raw KV JSON record -> must NEVER contain plaintext apiKey!
  const rawKvJson = await kv.get(`ai-accounts:v1:${userA}`);
  assert.ok(rawKvJson, "KV record must exist");
  assert.equal(
    rawKvJson.includes(secretPlain),
    false,
    "Raw KV storage must NEVER contain the plaintext API key"
  );
  const parsedKvList = JSON.parse(rawKvJson);
  assert.equal(parsedKvList[0].apiKey, undefined, "record.apiKey must be undefined in KV");
  assert.equal(parsedKvList[0].encryptedApiKey.alg, "AES-256-GCM");
  assert.equal(parsedKvList[0].encryptedApiKey.kdf, "HKDF-SHA256");
  assert.ok(parsedKvList[0].encryptedApiKey.iv);
  assert.ok(parsedKvList[0].encryptedApiKey.ciphertext);

  // 2. Decrypt with matching (userA, openai) succeeds; decrypting under userB or gemini MUST fail!
  const decryptedOk = await decryptCredentialFromKv(parsedKvList[0], {
    env,
    userId: userA,
    provider: "openai"
  });
  assert.equal(decryptedOk, secretPlain);

  await assert.rejects(
    async () =>
      decryptCredentialFromKv(parsedKvList[0], {
        env,
        userId: userB, // Wrong user!
        provider: "openai"
      }),
    "Cross-user decryption must fail authenticated AES-GCM check"
  );

  await assert.rejects(
    async () =>
      decryptCredentialFromKv(parsedKvList[0], {
        env,
        userId: userA,
        provider: "gemini" // Wrong provider!
      }),
    "Cross-provider decryption must fail authenticated AES-GCM check"
  );

  // 3. User isolation: User B must see 0 accounts from User A
  const userBAccounts = await listUserAiAccounts(kv, userB, env);
  assert.equal(userBAccounts.length, 0);

  // 4. Worker Secret Isolation: listUserAiAccounts must NEVER include server Worker Secrets as personal user accounts!
  const userAAccounts = await listUserAiAccounts(kv, userA, env);
  assert.equal(userAAccounts.length, 1);
  assert.equal(userAAccounts[0].accountId, "acc-alpha-openai");
  assert.equal(userAAccounts[0].source, "user_account");
  assert.equal(userAAccounts[0].apiKey, undefined);
  assert.equal(userAAccounts[0].encryptedApiKey, undefined);
  assert.equal(
    userAAccounts.some((a) => a.source === "worker_secret"),
    false,
    "Worker Secrets must never appear in listUserAiAccounts"
  );

  const serverCreds = listServerManagedCredentials(env);
  assert.equal(serverCreds.length, 2);
  assert.ok(serverCreds.some((s) => s.provider === "openai" && s.source === "worker_secret"));
  assert.ok(serverCreds.some((s) => s.provider === "gemini" && s.source === "worker_secret"));
});

test("Batch 8.5.2 — 6, 7 & 8. Connection Test, Model Discovery, Invalid Key, Timeout, Rate Limit & Persistence", async () => {
  const kv = createMockKv();
  const userId = "user-conn-discovery-003";
  const env = { DEVICE_AUTH_SECRET: "kms-secret-key-conn-test" };

  // Save an OpenAI account -> initially configured_unverified (connected = false)
  const saved = await saveUserAiAccount(
    kv,
    userId,
    {
      accountId: "acc-oa-live",
      provider: "openai",
      label: "OpenAI Production",
      email: "prod@openai.id",
      apiKey: "sk-proj-ValidOpenAiKeyForDiscovery123456"
    },
    env
  );
  assert.equal(saved.connectionVerified, false);

  // 1. Test Invalid API Key (HTTP 401) -> connection_failed + PROVIDER_AUTH_FAILED
  const invalidRes = await testProviderConnection({
    kv,
    userId,
    provider: "openai",
    accountId: "acc-oa-live",
    env,
    fetchImpl: async () => new Response(JSON.stringify({ error: "Invalid API Key" }), { status: 401 })
  });
  assert.equal(invalidRes.ok, false);
  assert.equal(invalidRes.errorCode, PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED);
  assert.equal(invalidRes.connectionStatus, "connection_failed");

  let providersStatus = await summarizeProvidersStatus(kv, userId, env);
  assert.equal(providersStatus.find((p) => p.id === "openai").connected, false);
  assert.equal(providersStatus.find((p) => p.id === "openai").connectionStatus, "connection_failed");

  // 2. Test Rate Limit (HTTP 429) -> PROVIDER_RATE_LIMITED
  const rateLimitRes = await testProviderConnection({
    kv,
    userId,
    provider: "openai",
    accountId: "acc-oa-live",
    env,
    fetchImpl: async () => new Response(JSON.stringify({ error: "Too many requests" }), { status: 429 })
  });
  assert.equal(rateLimitRes.ok, false);
  assert.equal(rateLimitRes.errorCode, PROVIDER_ERROR_CODES.PROVIDER_RATE_LIMITED);

  // 3. Test Timeout (AbortError) -> PROVIDER_TIMEOUT
  const timeoutRes = await testProviderConnection({
    kv,
    userId,
    provider: "openai",
    accountId: "acc-oa-live",
    env,
    fetchImpl: async () => {
      const err = new Error("Request timed out");
      err.name = "AbortError";
      throw err;
    }
  });
  assert.equal(timeoutRes.ok, false);
  assert.equal(timeoutRes.errorCode, PROVIDER_ERROR_CODES.PROVIDER_TIMEOUT);

  // 4. Test Successful Connection + Live Model Discovery -> transitions to connected = true & discovers models!
  const successRes = await testProviderConnection({
    kv,
    userId,
    provider: "openai",
    accountId: "acc-oa-live",
    env,
    fetchImpl: async () =>
      new Response(
        JSON.stringify({
          data: [
            { id: "gpt-4o" },
            { id: "gpt-4.1-mini" },
            { id: "o3-mini" },
            { id: "gpt-4.5-preview-discovered" }
          ]
        }),
        {
          status: 200,
          headers: {
            "Content-Type": "application/json",
            "x-ratelimit-limit-tokens": "500000",
            "x-ratelimit-remaining-tokens": "420000"
          }
        }
      )
  });
  assert.equal(successRes.ok, true);
  assert.equal(successRes.connectionStatus, "connected");
  assert.equal(successRes.integrationState, "working_live");
  assert.ok(successRes.models.includes("gpt-4.5-preview-discovered"));

  // Verify persistence in KV after connection test
  providersStatus = await summarizeProvidersStatus(kv, userId, env);
  const oaProvider = providersStatus.find((p) => p.id === "openai");
  const gemProvider = providersStatus.find((p) => p.id === "gemini");

  assert.equal(oaProvider.connected, true);
  assert.equal(oaProvider.userConnected, true);
  assert.equal(oaProvider.connectionStatus, "connected");
  assert.ok(oaProvider.supportedModels.includes("gpt-4.5-preview-discovered"));
  // Verify OpenAI models list ONLY contains OpenAI models (never Gemini or Claude models!)
  assert.equal(oaProvider.models.every((m) => m.provider === "openai"), true);
  assert.equal(gemProvider.models.every((m) => m.provider === "gemini"), true);

  // Verify quota headers were persisted on the tested account
  const persistedAccs = await listUserAiAccounts(kv, userId, env, { provider: "openai" });
  assert.equal(persistedAccs[0].connectionVerified, true);
  assert.equal(persistedAccs[0].quotaLimitTokens, 500000);
  assert.equal(persistedAccs[0].quotaUsedTokens, 80000);
  assert.equal(persistedAccs[0].quotaRemainingTokens, 420000);
});

test("Batch 8.5.2 — 9. Worker HTTP Endpoints (/v1/providers, /v1/providers/config, /v1/providers/test, /v1/ai-accounts, /v1/models) & Android UI Bindings", async () => {
  const kv = createMockKv();
  const userId = "123e4567-e89b-12d3-a456-426614174000";
  const env = {
    DEVICE_REGISTRY: kv,
    DEVICE_AUTH_SECRET: "b8-5-2-http-secret-key",
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "anon-key"
  };

  const origFetch = globalThis.fetch;
  globalThis.fetch = async (input) => {
    const url = typeof input === "string" ? input : input.url;
    if (url.includes("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: userId, email: "fajar@jarvis.id" }), { status: 200 });
    }
    if (url.includes("generativelanguage.googleapis.com/v1beta/models")) {
      return new Response(
        JSON.stringify({
          models: [{ name: "models/gemini-2.5-flash" }, { name: "models/gemini-1.5-pro" }]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }
    return new Response(JSON.stringify({ data: [{ id: "custom-llm-v1" }] }), { status: 200 });
  };

  try {
    const authHeaders = {
      Authorization: "Bearer valid-jwt",
      "Content-Type": "application/json"
    };

    // 1. Configure Custom OpenAI-Compatible Provider independently via POST /v1/providers/config
    const cfgRes = await workerApp.fetch(
      new Request("https://worker.test/v1/providers/config", {
        method: "POST",
        headers: authHeaders,
        body: JSON.stringify({
          provider: "custom",
          name: "Together AI Custom",
          endpoint: "https://api.together.xyz/v1/chat/completions",
          modelsEndpoint: "https://api.together.xyz/v1/models",
          defaultModel: "meta-llama/Llama-3.3-70B-Instruct-Turbo",
          accountLabel: "Together Production",
          apiKey: "sk-together-custom-secret-key-987654"
        })
      }),
      env
    );
    assert.equal(cfgRes.status, 200);
    const cfgBody = await cfgRes.json();
    assert.equal(cfgBody.ok, true);
    assert.equal(cfgBody.provider.connected, false, "Must be configured_unverified before test");
    assert.equal(cfgBody.provider.connectionStatus, "configured_unverified");
    assert.equal(JSON.stringify(cfgBody).includes("sk-together-custom-secret-key-987654"), false, "Response must never leak raw API key");

    // 2. Add Gemini account via POST /v1/ai-accounts
    const addGemRes = await workerApp.fetch(
      new Request("https://worker.test/v1/ai-accounts", {
        method: "POST",
        headers: authHeaders,
        body: JSON.stringify({
          accountId: "acc-gemini-1",
          provider: "gemini",
          label: "Gemini Studio",
          email: "fajar@google.dev",
          apiKey: "AIzaSyLiveGoogleStudioKey987654321"
        })
      }),
      env
    );
    assert.equal(addGemRes.status, 201);
    const addGemBody = await addGemRes.json();
    assert.equal(addGemBody.account.status, "configured_unverified");
    assert.equal(addGemBody.account.hasEncryptedKey, true);
    assert.equal(addGemBody.account.encryptionAlgorithm, "AES-256-GCM");

    // 3. Run POST /v1/providers/test for Gemini using stored encrypted accountId (without resending raw apiKey!)
    const testGemRes = await workerApp.fetch(
      new Request("https://worker.test/v1/providers/test", {
        method: "POST",
        headers: authHeaders,
        body: JSON.stringify({
          provider: "gemini",
          accountId: "acc-gemini-1"
        })
      }),
      env
    );
    assert.equal(testGemRes.status, 200);
    const testGemBody = await testGemRes.json();
    assert.equal(testGemBody.ok, true);
    assert.equal(testGemBody.connectionStatus, "connected");
    assert.ok(testGemBody.models.includes("gemini-2.5-flash"));

    // 4. Verify GET /v1/models?provider=gemini only returns Gemini models
    const gemModelsRes = await workerApp.fetch(
      new Request("https://worker.test/v1/models?provider=gemini"),
      env
    );
    const gemModelsBody = await gemModelsRes.json();
    assert.equal(gemModelsBody.ok, true);
    assert.equal(gemModelsBody.models.every((m) => m.provider === "gemini"), true);

    // 5. Verify Android UI source files pass API keys to backend and do not discard via `_`
    const shellKt = fs.readFileSync(
      path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/ui/JarvisB8MasterShell.kt"),
      "utf8"
    );
    const aiScreensKt = fs.readFileSync(
      path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/ai/JarvisAiFoundationScreens.kt"),
      "utf8"
    );
    assert.equal(shellKt.includes("onAddProvider = { provId, _ ->"), false, "Must NOT discard apiKey with _ in onAddProvider");
    assert.equal(shellKt.includes("onAddAccount = { prov, label, email, _ ->"), false, "Must NOT discard apiKey with _ in onAddAccount");
    assert.match(shellKt, /\/v1\/providers\/test/);
    assert.match(shellKt, /\/v1\/providers\/config/);
    assert.match(shellKt, /\/v1\/ai-accounts/);
    assert.match(aiScreensKt, /fun validateProviderKeyClientSide/);
    assert.match(aiScreensKt, /Test Connection/);
  } finally {
    globalThis.fetch = origFetch;
  }
});
