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
    async delete(key) { store.delete(key); },
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


test("validates Windows, macOS and Linux commands against platform-specific schemas", () => {
  for (const platform of ["windows", "macos", "linux"]) {
    const app = validateCommand({ action: "apps.launch", params: { appId: "APP_123" } }, platform);
    assert.equal(app.params.appId, "APP_123");
    const list = validateCommand({
      action: "files.list",
      params: { rootIndex: 1, relativePath: "Documents/Reports" }
    }, platform);
    assert.equal(list.params.rootIndex, 1);
    assert.equal(list.params.relativePath, "Documents/Reports");
    const read = validateCommand({
      action: "files.read",
      params: { rootIndex: 0, relativePath: "report.txt" }
    }, platform);
    assert.equal(read.params.relativePath, "report.txt");
    assert.throws(() => validateCommand({ action: "shell.exec", params: { command: "id" } }, platform));
  }
});

test("desktop command validation rejects traversal-shaped and malformed parameters", () => {
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { appId: "../powershell" }
  }, "windows"));
  assert.throws(() => validateCommand({
    action: "files.read",
    params: { relativePath: "report.txt" }
  }, "macos"));
  assert.throws(() => validateCommand({
    action: "files.search",
    params: { query: "", rootIndex: 0 }
  }, "linux"));
  for (const platform of ["ios", "ipados", "wearos", "web", "iot"]) {
    assert.throws(() => validateCommand({ action: "apps.list" }, platform), /No command connector is registered/);
  }
});


test("account owner can revoke a device and its device token loses command access", async () => {
  const accountId = "acct-owner-123456789";
  const deviceId = "android-revoke-123456";
  const registry = memoryKv();
  const commands = memoryKv();
  await registry.put("account:" + accountId, JSON.stringify({ accountId, status: "active" }));
  await registry.put("device:" + deviceId, JSON.stringify({
    deviceId, accountId, platform: "android", capabilities: ["system.read"]
  }));
  await registry.put("account-device:" + accountId + ":" + deviceId, "1");
  const accountToken = await issueAccountToken(accountId, "test-secret-with-more-than-32-characters");
  const deviceToken = await issueDeviceToken(deviceId, "test-secret-with-more-than-32-characters");

  const revoke = new Request("https://jarvis.test/v1/devices/" + deviceId, {
    method: "DELETE",
    headers: { Authorization: "Bearer " + accountToken }
  });
  const revoked = await handleDeviceRequest(revoke, {
    DEVICE_TOKEN_SECRET: "test-secret-with-more-than-32-characters",
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: commands
  }, new URL(revoke.url));
  assert.equal(revoked.status, 200);
  assert.equal((await revoked.json()).revoked, true);

  const poll = new Request("https://jarvis.test/v1/device/commands?deviceId=" + deviceId, {
    headers: { Authorization: "Bearer " + deviceToken }
  });
  const denied = await handleDeviceRequest(poll, {
    DEVICE_TOKEN_SECRET: "test-secret-with-more-than-32-characters",
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: commands
  }, new URL(poll.url));
  assert.equal(denied.status, 404);
  assert.equal(await registry.get("device:" + deviceId), null);
});

test("account cannot revoke another user's device", async () => {
  const owner = "acct-owner-123456789";
  const other = "acct-other-12345678";
  const deviceId = "android-owned-device";
  const registry = memoryKv();
  await registry.put("device:" + deviceId, JSON.stringify({
    deviceId, accountId: owner, platform: "android", capabilities: []
  }));
  const token = await issueAccountToken(other, "test-secret-with-more-than-32-characters");
  const request = new Request("https://jarvis.test/v1/devices/" + deviceId, {
    method: "DELETE", headers: { Authorization: "Bearer " + token }
  });
  const response = await handleDeviceRequest(request, {
    DEVICE_TOKEN_SECRET: "test-secret-with-more-than-32-characters",
    DEVICE_REGISTRY: registry
  }, new URL(request.url));
  assert.equal(response.status, 403);
});


test("desktop app launch by visible name is validated and remains typed", () => {
  for (const platform of ["windows", "macos", "linux"]) {
    const command = validateCommand({
      action: "apps.launch_label",
      params: { appName: "Visual Studio Code" }
    }, platform);
    assert.equal(command.capability, "apps.launch");
    assert.equal(command.params.appName, "Visual Studio Code");
    assert.throws(() => validateCommand({
      action: "apps.launch_label",
      params: { appName: "" }
    }, platform));
  }
});


test("inactive accounts cannot enqueue commands or poll device commands", async () => {
  const accountId = "acct-inactive-123456";
  const deviceId = "android-inactive-1234";
  const registry = memoryKv();
  const commands = memoryKv();
  await registry.put("account:" + accountId, JSON.stringify({ accountId, status: "suspended" }));
  await registry.put("device:" + deviceId, JSON.stringify({
    deviceId, accountId, platform: "android", capabilities: ["system.read"]
  }));
  const secret = "test-secret-with-more-than-32-characters";
  const accountToken = await issueAccountToken(accountId, secret);
  const deviceToken = await issueDeviceToken(deviceId, secret);
  const env = { DEVICE_TOKEN_SECRET: secret, DEVICE_REGISTRY: registry, DEVICE_COMMANDS: commands };

  const enqueue = new Request("https://jarvis.test/v1/device/command", {
    method: "POST",
    headers: { Authorization: "Bearer " + accountToken, "Content-Type": "application/json" },
    body: JSON.stringify({ deviceId, action: "system.status" })
  });
  const enqueueResponse = await handleDeviceRequest(enqueue, env, new URL(enqueue.url));
  assert.equal(enqueueResponse.status, 403);
  assert.equal((await enqueueResponse.json()).error, "Device account is inactive");

  const poll = new Request("https://jarvis.test/v1/device/commands?deviceId=" + deviceId, {
    headers: { Authorization: "Bearer " + deviceToken }
  });
  const pollResponse = await handleDeviceRequest(poll, env, new URL(poll.url));
  assert.equal(pollResponse.status, 403);
  assert.equal((await pollResponse.json()).error, "Device account is inactive");
});
