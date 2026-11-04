import test from "node:test";
import assert from "node:assert/strict";
import { CAPABILITIES, validateCommand } from "../src/device/protocol.js";
import { authorizeCapability } from "../src/device/policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand } from "../src/device/command-service.js";

function memoryKv() {
  const store = new Map();
  return {
    async put(key, value, _opts) { store.set(key, value); },
    async get(key) { return store.get(key) ?? null; },
    async list({ prefix, limit }) {
      return {
        keys: [...store.keys()]
          .filter((key) => key.startsWith(prefix))
          .slice(0, limit)
          .map((name) => ({ name }))
      };
    }
  };
}

test("screen control capability is recognized and requires on-device confirmation", () => {
  assert.equal(CAPABILITIES.has("screen.control"), true);
  const tap = validateCommand({ action: "screen.tap", params: { x: 0.5, y: 0.25 } }, "android");
  assert.equal(tap.capability, "screen.control");
  assert.equal(tap.requiresConfirmation, true);
  const type = validateCommand({ action: "screen.type", params: { text: "Hello JARVIS" } }, "android");
  assert.equal(type.requiresConfirmation, true);
  assert.throws(() => validateCommand({ action: "screen.tap", params: { x: 1.1, y: 0.5 } }, "android"));
  assert.throws(() => validateCommand({ action: "screen.tap", params: { x: 0.5 } }, "android"));
  assert.throws(() => validateCommand({ action: "screen.type", params: { text: " " } }, "android"));
  assert.throws(() => validateCommand({ action: "screen.type", params: { text: "x".repeat(501) } }, "android"));
});

test("screen-control policy allows queueing but never trusts remote userConfirmed", async () => {
  const kv = memoryKv();
  const device = { deviceId: "android-12345678", platform: "android", capabilities: ["screen.control"] };

  const decision = authorizeCapability(device, "screen.control", { userConfirmed: true });
  assert.equal(decision.allowed, true);
  assert.equal(decision.requiresConfirmation, true);
  assert.equal(decision.onDeviceApprovalRequired, true);

  // Even with a forged remote userConfirmed, enqueue still only marks on-device approval required.
  const queued = await enqueueDeviceCommand({
    kv,
    device,
    input: { action: "screen.tap", params: { x: 0.5, y: 0.5 } },
    context: { userConfirmed: true }
  });
  assert.equal(queued.ok, true);
  assert.equal(queued.requiresConfirmation, true);
  assert.equal(queued.status, "queued_pending_approval");

  const listed = await listDeviceCommands({ kv, deviceId: device.deviceId });
  assert.equal(listed.length, 1);
  assert.equal(listed[0].action, "screen.tap");
  assert.equal(listed[0].requiresConfirmation, true);
});

test("screen-control enqueue is blocked when capability is missing", async () => {
  const kv = memoryKv();
  const device = { deviceId: "android-87654321", platform: "android", capabilities: ["apps.list"] };
  const denied = await enqueueDeviceCommand({
    kv,
    device,
    input: { action: "screen.tap", params: { x: 0.2, y: 0.3 } },
    context: {}
  });
  assert.equal(denied.ok, false);
  assert.equal(denied.status, "blocked");
});

test("acknowledge is idempotent for completed screen commands", async () => {
  const kv = memoryKv();
  const device = { deviceId: "android-abcdef12", platform: "android", capabilities: ["screen.control"] };
  const queued = await enqueueDeviceCommand({
    kv,
    device,
    input: { action: "screen.type", params: { text: "ok" } }
  });
  const first = await acknowledgeDeviceCommand({
    kv,
    deviceId: device.deviceId,
    commandId: queued.commandId,
    result: { ok: true, approved: true, executed: true }
  });
  assert.equal(first.ok, true);
  assert.equal(first.status, "completed");
  const second = await acknowledgeDeviceCommand({
    kv,
    deviceId: device.deviceId,
    commandId: queued.commandId,
    result: { ok: false, error: "should not overwrite" }
  });
  assert.equal(second.ok, true);
  assert.equal(second.duplicate, true);
});
