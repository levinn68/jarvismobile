import test from "node:test";
import assert from "node:assert/strict";
import { normalizeDevice, validateCommand } from "../src/device/protocol.js";
import { authorizeCapability } from "../src/device/policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand, getDeviceCommand } from "../src/device/command-service.js";
import { handleDeviceRequest } from "../src/device/router.js";
import { issueAccountToken, verifyAccountToken, issueDeviceToken, verifyDeviceToken } from "../src/device/auth.js";

function memoryKv() {
  const store = new Map();
  return {
    async put(key, value) { store.set(key, value); },
    async get(key) { return store.get(key) ?? null; },
    async list({ prefix, limit }) {
      return { keys: [...store.keys()].filter(key => key.startsWith(prefix)).slice(0, limit).map(name => ({ name })) };
    }
  };
}

test("normalizes device identity and filters unsupported capabilities", () => {
  const device = normalizeDevice({
    deviceId: "android-12345678",
    accountId: "acct-123456789012",
    platform: "android",
    capabilities: ["apps.list", "fake.capability", "apps.list", "files.read"]
  });
  assert.deepEqual(device.capabilities, ["apps.list", "files.read"]);
  assert.equal(device.platform, "android");
});

test("rejects unsupported platform and malformed identity", () => {
  assert.throws(() => normalizeDevice({ deviceId: "bad", accountId: "acct-123456789012", platform: "android" }));
  assert.throws(() => normalizeDevice({ deviceId: "android-12345678", accountId: "acct-123456789012", platform: "unknown" }));
});

test("validates only typed Android actions and strict parameters", () => {
  const command = validateCommand({ action: "apps.launch", params: { packageName: "com.whatsapp" } });
  assert.equal(command.action, "apps.launch");
  assert.equal(command.capability, "apps.launch");
  assert.throws(() => validateCommand({ action: "shell.exec", params: { command: "id" } }));
  assert.throws(() => validateCommand({ action: "apps.launch", params: { packageName: "not a package" } }));
  assert.throws(() => validateCommand({ action: "files.search", params: { query: "" } }));
  assert.throws(() => validateCommand({ action: "system.status", params: { unexpected: true } }));
  assert.equal(validateCommand({ action: "screen.read" }).capability, "screen.read");
  assert.equal(validateCommand({ action: "contacts.list" }).capability, "contacts.read");
  assert.equal(validateCommand({ action: "calendar.list" }).capability, "calendar.read");
  assert.equal(validateCommand({ action: "apps.launch_label", params: { appName: "Spotify" } }).params.appName, "Spotify");
  assert.throws(() => validateCommand({ action: "apps.launch_label", params: { appName: "" } }));
});

test("capability policy blocks undeclared and sensitive actions without confirmation", () => {
  assert.equal(authorizeCapability({ capabilities: [] }, "apps.list").allowed, false);
  assert.equal(authorizeCapability({ capabilities: ["camera.capture"] }, "camera.capture").requiresConfirmation, true);
  assert.equal(authorizeCapability({ capabilities: ["camera.capture"] }, "camera.capture", { userConfirmed: true }).allowed, true);
});

test("queues commands only when device capability is declared and supports acknowledgement", async () => {
  const kv = memoryKv();
  const device = { deviceId: "android-12345678", capabilities: ["system.read"] };
  const denied = await enqueueDeviceCommand({ kv, device, input: { action: "apps.list" } });
  assert.equal(denied.ok, false);
  const queued = await enqueueDeviceCommand({ kv, device, input: { action: "system.status" } });
  assert.equal(queued.ok, true);
  assert.equal(queued.status, "queued");
  assert.equal((await listDeviceCommands({ kv, deviceId: device.deviceId })).length, 1);
  const ack = await acknowledgeDeviceCommand({
    kv, deviceId: device.deviceId, commandId: queued.commandId,
    result: { ok: true, batteryPercent: 82 }
  });
  assert.equal(ack.status, "completed");
  assert.equal((await listDeviceCommands({ kv, deviceId: device.deviceId })).length, 0);
  const saved = await getDeviceCommand({ kv, deviceId: device.deviceId, commandId: queued.commandId });
  assert.equal(saved.status, "completed");
  assert.equal(saved.result.batteryPercent, 82);
});


test("Google-linked account enrollment verifies Supabase user and reuses stable account", async () => {
  const kv = memoryKv();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.headers.apikey, "public-test-key");
    assert.equal(options.headers.Authorization, "Bearer valid-supabase-token");
    return new Response(JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174000" }), { status: 200 });
  };
  try {
    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "public-test-key",
      DEVICE_TOKEN_SECRET: "a-long-enough-test-signing-secret",
      DEVICE_REGISTRY: kv
    };
    const request = () => new Request("https://worker.test/v1/accounts/google", {
      method: "POST", headers: { Authorization: "Bearer valid-supabase-token" }
    });
    const first = await handleDeviceRequest(request(), env, new URL("https://worker.test/v1/accounts/google"));
    const firstBody = await first.json();
    const second = await handleDeviceRequest(request(), env, new URL("https://worker.test/v1/accounts/google"));
    const secondBody = await second.json();
    assert.equal(first.status, 200, JSON.stringify(firstBody));
    assert.equal(second.status, 200, JSON.stringify(secondBody));
    assert.equal(firstBody.account.accountId, "acct-123e4567-e89b-12d3-a456-426614174000");
    assert.equal(secondBody.account.accountId, firstBody.account.accountId);
    assert.notEqual(firstBody.accountToken, "");
    assert.equal(firstBody.expiresIn, 3600);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Google-linked account enrollment rejects invalid Supabase sessions", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ message: "invalid token" }), { status: 401 });
  try {
    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "public-test-key",
      DEVICE_TOKEN_SECRET: "a-long-enough-test-signing-secret",
      DEVICE_REGISTRY: memoryKv()
    };
    const request = new Request("https://worker.test/v1/accounts/google", {
      method: "POST", headers: { Authorization: "Bearer expired-token" }
    });
    const response = await handleDeviceRequest(request, env, new URL("https://worker.test/v1/accounts/google"));
    assert.equal(response.status, 401);
  } finally {
    globalThis.fetch = originalFetch;
  }
});


test("HMAC account and device tokens round-trip through base64url and scope checks", async () => {
  const secret = "a-long-enough-test-signing-secret";
  const accountToken = await issueAccountToken("acct-123456789012", secret);
  const accountRequest = new Request("https://worker.test", {
    headers: { Authorization: "Bearer " + accountToken }
  });
  const accountClaims = await verifyAccountToken(accountRequest, secret);
  assert.equal(accountClaims.sub, "acct-123456789012");
  assert.equal(accountClaims.scope, "account");

  const deviceToken = await issueDeviceToken("android-12345678", secret);
  const deviceRequest = new Request("https://worker.test", {
    headers: { Authorization: "Bearer " + deviceToken }
  });
  assert.equal((await verifyDeviceToken(deviceRequest, secret, "android-12345678")).scope, "device");
  assert.equal(await verifyDeviceToken(deviceRequest, secret, "android-otherdevice"), null);
});
