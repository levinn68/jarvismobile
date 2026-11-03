import test from "node:test";
import assert from "node:assert/strict";
import { normalizeDevice, validateCommand } from "../src/device/protocol.js";
import { authorizeCapability } from "../src/device/policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand, getDeviceCommand } from "../src/device/command-service.js";

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
