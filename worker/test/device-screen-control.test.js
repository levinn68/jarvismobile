import test from "node:test";
import assert from "node:assert/strict";
import { CAPABILITIES, validateCommand } from "../src/device/protocol.js";
import { authorizeCapability } from "../src/device/policy.js";
import { enqueueDeviceCommand } from "../src/device/command-service.js";

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

test("screen control capability is recognized but requires explicit confirmation", () => {
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

test("screen-control policy blocks remote enqueue without a confirmed device approval", async () => {
  const kv = memoryKv();
  const device = { deviceId: "android-12345678", capabilities: ["screen.control"] };
  const denied = await enqueueDeviceCommand({
    kv,
    device,
    input: { action: "screen.tap", params: { x: 0.5, y: 0.5 } },
    context: { userConfirmed: false }
  });
  assert.equal(denied.ok, false);
  assert.equal(denied.requiresConfirmation, true);
  assert.equal(authorizeCapability(device, "screen.control").allowed, false);
  assert.equal(authorizeCapability(device, "screen.control", { userConfirmed: true }).allowed, true);
});
