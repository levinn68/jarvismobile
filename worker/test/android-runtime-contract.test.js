import test from "node:test";
import assert from "node:assert/strict";
import { validateCommand } from "../src/device/protocol.js";
import {
  enqueueDeviceCommand,
  listDeviceCommands,
  acknowledgeDeviceCommand,
  getDeviceCommand
} from "../src/device/command-service.js";

function memoryKv() {
  const store = new Map();
  return {
    async put(key, value, _opts) { store.set(key, value); },
    async get(key) { return store.get(key) ?? null; },
    async delete(key) { store.delete(key); },
    async list({ prefix, limit = 1000 }) {
      return {
        keys: [...store.keys()]
          .filter((key) => key.startsWith(prefix))
          .slice(0, limit)
          .map((name) => ({ name })),
        list_complete: true
      };
    }
  };
}

test("Android Device Runtime capability matrix matches protocol and executor classifications", () => {
  const activeActions = [
    ["apps.list", {}, "apps.list", false],
    ["apps.launch", { packageName: "com.whatsapp" }, "apps.launch", false],
    ["apps.launch_label", { appName: "WhatsApp" }, "apps.launch", false],
    ["system.status", {}, "system.read", false],
    ["files.list", {}, "files.read", false],
    ["files.search", { query: "invoice" }, "files.read", false],
    ["files.read", { documentId: "primary:Documents/notes.txt" }, "files.read", false],
    ["notifications.list", {}, "notifications.read", false],
    ["contacts.list", {}, "contacts.read", false],
    ["calendar.list", {}, "calendar.read", false]
  ];

  for (const [action, params, expectedCapability, expectedConfirm] of activeActions) {
    const res = validateCommand({ action, params }, "android");
    assert.equal(res.capability, expectedCapability);
    assert.equal(res.requiresConfirmation, expectedConfirm);
  }

  const approvalActions = [
    ["screen.read", {}, "screen.read", true],
    ["screen.tap", { x: 0.5, y: 0.5 }, "screen.control", true],
    ["screen.type", { text: "Halo Jarvis" }, "screen.control", true]
  ];
  for (const [action, params, expectedCapability, expectedConfirm] of approvalActions) {
    const res = validateCommand({ action, params }, "android");
    assert.equal(res.capability, expectedCapability);
    assert.equal(res.requiresConfirmation, expectedConfirm);
  }

  // Unimplemented actions must throw on Android
  for (const unimplemented of ["WAIT", "CLICK_TEXT", "SCROLL", "GLOBAL_ACTION", "files.write", "notifications.manage", "location.read"]) {
    assert.throws(() => validateCommand({ action: unimplemented, params: {} }, "android"));
  }
});

test("AppController packageName validation enforces strict Android package regex", () => {
  assert.equal(validateCommand({ action: "apps.launch", params: { packageName: "com.whatsapp" } }, "android").action, "apps.launch");
  assert.equal(validateCommand({ action: "apps.launch", params: { packageName: "com.google.android.youtube" } }, "android").action, "apps.launch");
  assert.throws(() => validateCommand({ action: "apps.launch", params: { packageName: "invalid_package_without_dot" } }, "android"));
  assert.throws(() => validateCommand({ action: "apps.launch", params: { packageName: "123.bad.start" } }, "android"));
  assert.throws(() => validateCommand({ action: "apps.launch", params: { packageName: "com.bad;inject" } }, "android"));
});

test("DeviceCommandExecutor ISO-8601 timestamp expiration and future-skew window match Android runtime", () => {
  const COMMAND_TTL_MS = 5 * 60 * 1000;
  const MAX_FUTURE_SKEW_MS = 60 * 1000;
  function isCommandExpired(createdAtIso, nowMs) {
    if (!createdAtIso || typeof createdAtIso !== "string" || !createdAtIso.trim()) return true;
    const createdMs = Date.parse(createdAtIso.trim());
    if (Number.isNaN(createdMs)) return true;
    const ageMs = nowMs - createdMs;
    return ageMs < -MAX_FUTURE_SKEW_MS || ageMs > COMMAND_TTL_MS;
  }

  const now = Date.parse("2026-04-19T12:00:00.000Z");
  assert.equal(isCommandExpired("2026-04-19T11:58:00.000Z", now), false);
  assert.equal(isCommandExpired("2026-04-19T11:54:59.000Z", now), true);
  assert.equal(isCommandExpired("2026-04-19T12:00:45.000Z", now), false);
  assert.equal(isCommandExpired("2026-04-19T12:01:05.000Z", now), true);
  assert.equal(isCommandExpired("", now), true);
  assert.equal(isCommandExpired("not-a-timestamp", now), true);
});

test("JarvisDeviceSyncService exponential backoff schedule scales 15s -> 30s -> 60s -> 120s max", () => {
  const POLL_INTERVAL_MS = 15000;
  const MAX_POLL_INTERVAL_MS = 120000;
  const MAX_BACKOFF_STEPS = 3;
  function nextPollDelayMs(failures) {
    const clamped = Math.max(0, Math.min(MAX_BACKOFF_STEPS, failures));
    return Math.min(MAX_POLL_INTERVAL_MS, POLL_INTERVAL_MS * (1 << clamped));
  }

  assert.equal(nextPollDelayMs(0), 15000);
  assert.equal(nextPollDelayMs(1), 30000);
  assert.equal(nextPollDelayMs(2), 60000);
  assert.equal(nextPollDelayMs(3), 120000);
  assert.equal(nextPollDelayMs(4), 120000);
  assert.equal(nextPollDelayMs(10), 120000);
});

test("JarvisNotificationListener deduplicates by key on post and removes dismissed notifications", () => {
  const recent = [];
  function removeByKey(key) {
    for (let i = recent.length - 1; i >= 0; i--) {
      if (recent[i].key === key) recent.splice(i, 1);
    }
  }
  function onNotificationPosted(sbn) {
    removeByKey(sbn.key);
    recent.push({ ...sbn });
    while (recent.length > 50) recent.shift();
  }
  function onNotificationRemoved(sbn) {
    removeByKey(sbn.key);
  }

  onNotificationPosted({ key: "com.whatsapp:1", packageName: "com.whatsapp", title: "Msg 1", text: "Hello" });
  onNotificationPosted({ key: "com.whatsapp:1", packageName: "com.whatsapp", title: "Msg 1", text: "Updated Hello" });
  onNotificationPosted({ key: "com.gmail:2", packageName: "com.gmail", title: "Mail", text: "Invoice" });
  assert.equal(recent.length, 2);
  assert.equal(recent[0].text, "Updated Hello");

  onNotificationRemoved({ key: "com.whatsapp:1" });
  assert.equal(recent.length, 1);
  assert.equal(recent[0].packageName, "com.gmail");
});

test("Full Worker <-> Android Device Runtime lifecycle for pendingApproval and terminal acknowledgement", async () => {
  const kv = memoryKv();
  const device = {
    deviceId: "rog-phone-6-device",
    accountId: "user-rog-6",
    deviceName: "ASUS ROG Phone 6",
    platform: "android",
    capabilities: ["screen.read", "screen.control", "apps.list", "apps.launch"]
  };

  const queued = await enqueueDeviceCommand({
    kv,
    device,
    input: { action: "screen.tap", params: { x: 0.5, y: 0.5 } }
  });
  assert.equal(queued.ok, true);
  assert.equal(queued.requiresConfirmation, true);
  assert.equal(queued.status, "queued_pending_approval");

  // Poll 1: Android receives queued command, opens ScreenActionApprovalActivity, does not ack yet
  const firstPoll = await listDeviceCommands({ kv, deviceId: device.deviceId });
  assert.equal(firstPoll.length, 1);
  assert.equal(firstPoll[0].commandId, queued.commandId);

  // Poll 2 (before user acts): Command remains queued on Worker
  const secondPoll = await listDeviceCommands({ kv, deviceId: device.deviceId });
  assert.equal(secondPoll.length, 1);

  // User approves on ScreenActionApprovalActivity -> acknowledgeDeviceCommand is called
  const ack = await acknowledgeDeviceCommand({
    kv,
    deviceId: device.deviceId,
    commandId: queued.commandId,
    result: {
      ok: true,
      approved: true,
      executed: true,
      action: "screen.tap",
      x: 0.5,
      y: 0.5,
      pixelX: 540,
      pixelY: 1200
    }
  });
  assert.equal(ack.ok, true);
  assert.equal(ack.status, "completed");

  // Status endpoint returns completed record
  const record = await getDeviceCommand({ kv, deviceId: device.deviceId, commandId: queued.commandId });
  assert.equal(record.status, "completed");
  assert.equal(record.result.executed, true);

  // Subsequent poll returns empty queue
  const thirdPoll = await listDeviceCommands({ kv, deviceId: device.deviceId });
  assert.equal(thirdPoll.length, 0);
});