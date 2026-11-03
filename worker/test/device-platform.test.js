import test from "node:test";
import assert from "node:assert/strict";

import {
  issueAccountToken,
  issueDeviceToken,
  verifyAccountToken,
  verifyDeviceToken
} from "../src/device/auth.js";
import { normalizeDevice, validateCommand } from "../src/device/protocol.js";
import { acknowledgeDeviceCommand } from "../src/device/command-service.js";
import { handleDeviceRequest } from "../src/device/router.js";

const secret = "test-only-secret-with-at-least-32-characters-long";

function requestWithToken(token) {
  return new Request("https://jarvis.test", {
    headers: { Authorization: "Bearer " + token }
  });
}

function base64UrlEncode(value) {
  return btoa(value).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

test("account token round-trips with Bearer authorization", async () => {
  const token = await issueAccountToken("acct-test-account-123", secret);
  assert.match(token, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  const claims = await verifyAccountToken(requestWithToken(token), secret);
  assert.equal(claims?.sub, "acct-test-account-123");
  assert.equal(claims?.scope, "account");
});

test("device token is scoped to its device", async () => {
  const token = await issueDeviceToken("android-test-device-01", secret);
  const request = requestWithToken(token);
  assert.equal((await verifyDeviceToken(request, secret, "android-test-device-01"))?.scope, "device");
  assert.equal(await verifyDeviceToken(request, secret, "android-another-device"), null);
  assert.equal(await verifyAccountToken(request, secret), null);
});

test("invalid, tampered, expired, and missing Bearer tokens are rejected", async () => {
  const token = await issueAccountToken("acct-test-account-123", secret);
  const [payload, signature] = token.split(".");
  const tampered = payload + "." + (signature[0] === "A" ? "B" : "A") + signature.slice(1);
  assert.equal(await verifyAccountToken(requestWithToken(tampered), secret), null);
  assert.equal(await verifyAccountToken(requestWithToken("not-a-token"), secret), null);
  assert.equal(await verifyAccountToken(new Request("https://jarvis.test"), secret), null);

  const now = Math.floor(Date.now() / 1000);
  const expiredPayload = base64UrlEncode(JSON.stringify({
    sub: "acct-test-account-123",
    scope: "account",
    iat: now - 120,
    exp: now - 60
  }));
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signatureBytes = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(expiredPayload))
  );
  let binary = "";
  for (const byte of signatureBytes) binary += String.fromCharCode(byte);
  const expiredSignature = btoa(binary)
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
  assert.equal(
    await verifyAccountToken(requestWithToken(expiredPayload + "." + expiredSignature), secret),
    null
  );
});

test("secret length is enforced when issuing tokens", async () => {
  await assert.rejects(() => issueAccountToken("acct-test-account-123", "short"));
});

test("Android package names accept normal dotted identifiers", () => {
  const command = validateCommand({
    action: "apps.launch",
    params: { packageName: "com.example.my_app" }
  });
  assert.equal(command.params.packageName, "com.example.my_app");
});

test("Android commands reject malformed package names and unknown parameters", () => {
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { packageName: "com..example" }
  }));
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { packageName: "com.example", shell: "whoami" }
  }));
  assert.throws(() => validateCommand({ action: "shell.exec", params: { command: "id" } }));
});

test("Windows connector validates app and file commands", () => {
  const app = validateCommand({ action: "apps.launch", params: { appId: "QWERTY_123" } }, "windows");
  assert.equal(app.params.appId, "QWERTY_123");

  const list = validateCommand({
    action: "files.list",
    params: { rootIndex: 1, relativePath: "Reports/2026" }
  }, "windows");
  assert.equal(list.params.rootIndex, 1);
  assert.equal(list.params.relativePath, "Reports/2026");

  const read = validateCommand({
    action: "files.read",
    params: { rootIndex: 0, relativePath: "report.txt" }
  }, "windows");
  assert.equal(read.params.relativePath, "report.txt");
});

test("Windows connector rejects invalid app IDs, paths, and unknown commands", () => {
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { appId: "../powershell" }
  }, "windows"));
  assert.throws(() => validateCommand({
    action: "files.read",
    params: { relativePath: "report.txt" }
  }, "windows"));
  assert.throws(() => validateCommand({
    action: "shell.exec",
    params: { command: "whoami" }
  }, "windows"));
});

test("macOS and Linux use the validated desktop command schema", () => {
  for (const platform of ["macos", "linux"]) {
    const app = validateCommand({ action: "apps.launch", params: { appId: "APP_123" } }, platform);
    assert.equal(app.params.appId, "APP_123");
    const search = validateCommand({ action: "files.search", params: { query: "invoice", rootIndex: 0 } }, platform);
    assert.equal(search.params.query, "invoice");
  }
});

test("platforms without a connector remain blocked", () => {
  for (const platform of ["ios", "ipados", "wearos", "web", "iot"]) {
    assert.throws(
      () => validateCommand({ action: "apps.list", params: {} }, platform),
      /No command connector is registered/
    );
  }
});

test("device normalization accepts the supported desktop platforms", () => {
  for (const platform of ["android", "windows", "macos", "linux", "ios", "web"]) {
    const device = normalizeDevice({
      deviceId: "device-test-0001",
      accountId: "acct-test-account-123",
      platform
    });
    assert.equal(device.platform, platform);
  }
});


test("command acknowledgement is idempotent", async () => {
  const values = new Map();
  const kv = {
    async get(key) { return values.get(key) ?? null; },
    async put(key, value) { values.set(key, value); }
  };
  const key = "command:android-test-device-01:command-123";
  values.set(key, JSON.stringify({
    commandId: "command-123",
    deviceId: "android-test-device-01",
    status: "queued"
  }));

  const first = await acknowledgeDeviceCommand({
    kv,
    deviceId: "android-test-device-01",
    commandId: "command-123",
    result: { ok: true }
  });
  const second = await acknowledgeDeviceCommand({
    kv,
    deviceId: "android-test-device-01",
    commandId: "command-123",
    result: { ok: true }
  });

  assert.equal(first.ok, true);
  assert.equal(second.ok, true);
  assert.equal(second.alreadyAcknowledged, true);
});


test("account enrollment, Windows command queue, polling and acknowledgement work end to end", async () => {
  class MemoryKV {
    constructor() { this.values = new Map(); }
    async get(key) { return this.values.get(key) ?? null; }
    async put(key, value) { this.values.set(key, value); }
    async list({ prefix = "", limit = 100 }) {
      return {
        keys: [...this.values.keys()].filter((key) => key.startsWith(prefix)).slice(0, limit).map((name) => ({ name }))
      };
    }
  }

  const env = {
    DEVICE_ENROLLMENT_SECRET: "bootstrap-secret-for-integration-tests",
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: new MemoryKV(),
    DEVICE_COMMANDS: new MemoryKV()
  };

  async function call(method, path, token, body) {
    const request = new Request("https://jarvis.test" + path, {
      method,
      headers: { Authorization: "Bearer " + token, ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    return handleDeviceRequest(request, env, new URL(request.url));
  }

  const created = await call("POST", "/v1/accounts/create", env.DEVICE_ENROLLMENT_SECRET, {});
  assert.equal(created.status, 201);
  const accountPayload = await created.json();
  const accountToken = accountPayload.accountToken;
  assert.ok(accountToken);

  const enrolled = await call("POST", "/v1/devices/enroll", accountToken, {
    deviceId: "windows-test-device-01",
    platform: "windows",
    name: "Test Windows",
    capabilities: ["apps.list", "system.read", "files.read"]
  });
  assert.equal(enrolled.status, 201);
  const devicePayload = await enrolled.json();
  const deviceToken = devicePayload.token;
  assert.ok(deviceToken);

  const blockedCommand = await call("POST", "/v1/device/command", accountToken, {
    deviceId: "windows-test-device-01",
    action: "apps.launch",
    params: { appId: "APP_123" }
  });
  assert.equal(blockedCommand.status, 403);

  const otherAccountResponse = await call("POST", "/v1/accounts/create", env.DEVICE_ENROLLMENT_SECRET, {});
  const otherAccount = await otherAccountResponse.json();
  const crossAccountCommand = await call("POST", "/v1/device/command", otherAccount.accountToken, {
    deviceId: "windows-test-device-01",
    action: "system.status",
    params: {}
  });
  assert.equal(crossAccountCommand.status, 403);

  const queued = await call("POST", "/v1/device/command", accountToken, {
    deviceId: "windows-test-device-01",
    action: "system.status",
    params: {}
  });
  assert.equal(queued.status, 200);
  const queuePayload = await queued.json();
  assert.equal(queuePayload.status, "queued");

  const accountPoll = await call("GET", "/v1/device/commands?deviceId=windows-test-device-01", accountToken);
  assert.equal(accountPoll.status, 401);

  const devicePoll = await call("GET", "/v1/device/commands?deviceId=windows-test-device-01", deviceToken);
  assert.equal(devicePoll.status, 200);
  const commands = (await devicePoll.json()).commands;
  assert.equal(commands.length, 1);
  assert.equal(commands[0].action, "system.status");

  const ack = await call("POST", "/v1/device/commands/ack", deviceToken, {
    deviceId: "windows-test-device-01",
    commandId: commands[0].commandId,
    result: { ok: true }
  });
  assert.equal(ack.status, 200);

  const afterAck = await call("GET", "/v1/device/commands?deviceId=windows-test-device-01", deviceToken);
  assert.equal((await afterAck.json()).commands.length, 0);
});
