import test from "node:test";
import assert from "node:assert/strict";

import {
  issueAccountToken,
  issueDeviceToken,
  verifyAccountToken,
  verifyDeviceToken
} from "../src/device/auth.js";
import { normalizeDevice, validateCommand } from "../src/device/protocol.js";
import { saveDevice, createAccount, listDevicesForAccount } from "../src/device/registry.js";
import { acknowledgeDeviceCommand, listDeviceCommands } from "../src/device/command-service.js";
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


test("device ID cannot be re-enrolled into a different account", async () => {
  class MemoryKV {
    constructor() { this.values = new Map(); }
    async get(key) { return this.values.get(key) ?? null; }
    async put(key, value) { this.values.set(key, value); }
  }
  const kv = new MemoryKV();
  await createAccount(kv, "acct-test-account-123");
  await createAccount(kv, "acct-other-account-456");

  const first = await saveDevice(kv, {
    deviceId: "android-test-device-01",
    accountId: "acct-test-account-123",
    platform: "android",
    capabilities: ["system.read"]
  });
  assert.equal(first.accountId, "acct-test-account-123");

  await assert.rejects(
    () => saveDevice(kv, {
      deviceId: "android-test-device-01",
      accountId: "acct-other-account-456",
      platform: "android",
      capabilities: ["system.read"]
    }),
    /already enrolled in another account/
  );
});


test("inactive accounts revoke existing device and account token access", async () => {
  class MemoryKV {
    constructor() { this.values = new Map(); }
    async get(key) { return this.values.get(key) ?? null; }
    async put(key, value) { this.values.set(key, value); }
  }

  const registry = new MemoryKV();
  const commands = new MemoryKV();
  const accountId = "acct-disabled-account-123";
  const deviceId = "android-disabled-device-01";
  await createAccount(registry, accountId);
  await saveDevice(registry, {
    deviceId,
    accountId,
    platform: "android",
    capabilities: ["system.read"]
  });

  const accountToken = await issueAccountToken(accountId, secret);
  const deviceToken = await issueDeviceToken(deviceId, secret);

  await registry.put("account:" + accountId, JSON.stringify({
    accountId,
    status: "inactive",
    createdAt: new Date().toISOString()
  }));

  const env = {
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: commands
  };

  const deviceRequest = new Request("https://jarvis.test/v1/device/commands?deviceId=" + deviceId, {
    headers: { Authorization: "Bearer " + deviceToken }
  });
  const deviceResponse = await handleDeviceRequest(deviceRequest, env, new URL(deviceRequest.url));
  assert.equal(deviceResponse.status, 403);

  const accountRequest = new Request("https://jarvis.test/v1/device/command", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + accountToken,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ deviceId, action: "system.status", params: {} })
  });
  const accountResponse = await handleDeviceRequest(accountRequest, env, new URL(accountRequest.url));
  assert.equal(accountResponse.status, 403);
});


test("inactive account revokes access for every existing device-token route", async () => {
  class MemoryKV {
    constructor() { this.values = new Map(); }
    async get(key) { return this.values.get(key) ?? null; }
    async put(key, value) { this.values.set(key, value); }
    async list({ prefix = "", limit = 100 }) {
      return {
        keys: [...this.values.keys()]
          .filter((key) => key.startsWith(prefix))
          .slice(0, limit)
          .map((name) => ({ name }))
      };
    }
  }

  const registry = new MemoryKV();
  const commands = new MemoryKV();
  const accountId = "acct-revocation-test-123";
  const deviceId = "android-revocation-01";
  await createAccount(registry, accountId);
  await saveDevice(registry, {
    accountId,
    deviceId,
    platform: "android",
    capabilities: ["system.read"]
  });
  const accountToken = await issueAccountToken(accountId, secret);
  const deviceToken = await issueDeviceToken(deviceId, secret);
  const env = {
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: commands
  };

  async function call(method, path, token, body) {
    const request = new Request("https://jarvis.test" + path, {
      method,
      headers: {
        Authorization: "Bearer " + token,
        ...(body ? { "Content-Type": "application/json" } : {})
      },
      ...(body ? { body: JSON.stringify(body) } : {})
    });
    return handleDeviceRequest(request, env, new URL(request.url));
  }

  // The device token is valid and works before account deactivation.
  assert.equal((await call("GET", "/v1/devices/" + deviceId, deviceToken)).status, 200);

  await registry.put("account:" + accountId, JSON.stringify({
    accountId,
    status: "inactive"
  }));

  const blocked = [
    await call("GET", "/v1/devices/" + deviceId, deviceToken),
    await call("POST", "/v1/devices/" + deviceId + "/heartbeat", deviceToken, {}),
    await call("PUT", "/v1/devices/" + deviceId + "/capabilities", deviceToken, {
      capabilities: ["system.read"]
    }),
    await call("GET", "/v1/device/commands?deviceId=" + deviceId, deviceToken),
    await call("POST", "/v1/device/commands/ack", deviceToken, {
      deviceId,
      commandId: "command-revocation-01",
      result: { ok: true }
    }),
    await call("POST", "/v1/device/command", deviceToken, {
      deviceId,
      action: "system.status",
      params: {}
    }),
    await call("GET", "/v1/devices", accountToken)
  ];

  assert.deepEqual(blocked.map((response) => response.status), [
    403, 403, 403, 403, 403, 403, 403
  ]);
});


test("account device listing follows KV pagination cursors", async () => {
  const accountId = "acct-pagination-test-123";
  const firstId = "android-page-device-01";
  const secondId = "android-page-device-02";
  const values = new Map([
    ["device:" + firstId, JSON.stringify({ deviceId: firstId, accountId })],
    ["device:" + secondId, JSON.stringify({ deviceId: secondId, accountId })]
  ]);
  const calls = [];
  const kv = {
    async get(key) { return values.get(key) ?? null; },
    async list(options) {
      calls.push(options);
      if (!options.cursor) {
        return {
          keys: [{ name: "account-device:" + accountId + ":" + firstId }],
          list_complete: false,
          cursor: "page-two"
        };
      }
      return {
        keys: [{ name: "account-device:" + accountId + ":" + secondId }],
        list_complete: true
      };
    }
  };

  const devices = await listDevicesForAccount(kv, accountId);
  assert.deepEqual(devices.map(device => device.deviceId).sort(), [firstId, secondId]);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].cursor, "page-two");
});


test("device registry pagination fails closed when continuation cursor is missing", async () => {
  const kv = {
    async list() {
      return { keys: [{ name: "account-device:acct-test:device-01" }], list_complete: false };
    }
  };
  await assert.rejects(
    () => listDevicesForAccount(kv, "acct-test"),
    /no continuation cursor was returned/
  );
});


test("device registry pagination fails closed when a cursor repeats", async () => {
  let calls = 0;
  const kv = {
    async list() {
      calls += 1;
      return {
        keys: [{ name: "account-device:acct-test:device-" + calls }],
        list_complete: false,
        cursor: "same-cursor"
      };
    }
  };
  await assert.rejects(
    () => listDevicesForAccount(kv, "acct-test"),
    /repeated continuation cursor/
  );
  assert.equal(calls, 2);
});


test("device registry pagination fails closed after the 100-page safety limit", async () => {
  let calls = 0;
  const kv = {
    async list() {
      calls += 1;
      return {
        keys: [],
        list_complete: false,
        cursor: "cursor-" + calls
      };
    }
  };
  await assert.rejects(
    () => listDevicesForAccount(kv, "acct-test"),
    /exceeded the 100-page safety limit/
  );
  assert.equal(calls, 100);
});


test("command polling paginates past completed records and returns queued commands in creation order", async () => {
  const deviceId = "android-command-page-01";
  const values = new Map();
  const firstPage = Array.from({ length: 100 }, (_, index) => {
    const name = "command:" + deviceId + ":completed-" + String(index).padStart(3, "0");
    values.set(name, JSON.stringify({
      deviceId,
      commandId: "completed-" + index,
      status: "completed",
      createdAt: "2026-01-01T00:00:00.000Z"
    }));
    return { name };
  });
  const queuedNew = "command:" + deviceId + ":queued-new";
  const queuedOld = "command:" + deviceId + ":queued-old";
  values.set(queuedNew, JSON.stringify({
    deviceId, commandId: "queued-new", status: "queued",
    createdAt: "2026-01-02T00:00:00.000Z"
  }));
  values.set(queuedOld, JSON.stringify({
    deviceId, commandId: "queued-old", status: "queued",
    createdAt: "2026-01-01T12:00:00.000Z"
  }));

  const calls = [];
  const kv = {
    async list(options) {
      calls.push(options);
      if (!options.cursor) {
        return { keys: firstPage, list_complete: false, cursor: "next-page" };
      }
      return {
        keys: [{ name: queuedNew }, { name: queuedOld }],
        list_complete: true
      };
    },
    async get(name) { return values.get(name) ?? null; }
  };

  const commands = await listDeviceCommands({ kv, deviceId, limit: 10 });
  assert.deepEqual(commands.map(command => command.commandId), ["queued-old", "queued-new"]);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].cursor, "next-page");
});


test("command acknowledgement rejects a mismatched stored command identity", async () => {
  const deviceId = "android-command-ack-01";
  const commandId = "command-expected-01";
  const key = "command:" + deviceId + ":" + commandId;
  const kv = {
    async get() {
      return JSON.stringify({
        deviceId,
        commandId: "command-other-01",
        status: "queued"
      });
    },
    async put() { throw new Error("Should not update a mismatched command"); }
  };

  const result = await acknowledgeDeviceCommand({ kv, deviceId, commandId, result: { ok: true } });
  assert.deepEqual(result, { ok: false, error: "Command identity does not match" });
});
