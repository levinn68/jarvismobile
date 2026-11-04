import test from "node:test";
import assert from "node:assert/strict";
import {
  createAccount,
  saveDevice,
  listDevicesForAccount,
  touchDevice,
  removeDevice
} from "../src/device/registry.js";

function memoryKv() {
  const values = new Map();
  const expirations = new Map();
  return {
    values,
    expirations,
    async put(key, value, options = {}) {
      values.set(key, value);
      if (options.expirationTtl) expirations.set(key, options.expirationTtl);
    },
    async get(key) { return values.get(key) ?? null; },
    async delete(key) { values.delete(key); expirations.delete(key); },
    async list({ prefix = "", limit = 1000, cursor } = {}) {
      const all = [...values.keys()].filter((key) => key.startsWith(prefix)).sort();
      const offset = cursor ? Number(cursor) : 0;
      const keys = all.slice(offset, offset + limit).map((name) => ({ name }));
      const next = offset + keys.length;
      return {
        keys,
        list_complete: next >= all.length,
        ...(next < all.length ? { cursor: String(next) } : {})
      };
    }
  };
}

test("account device listing paginates beyond the first KV page", async () => {
  const kv = memoryKv();
  const accountId = "acct-12345678-1234-1234-1234-123456789012";
  await createAccount(kv, accountId);

  for (let i = 0; i < 1005; i++) {
    const suffix = String(i).padStart(8, "0");
    await saveDevice(kv, {
      deviceId: "android-" + suffix,
      accountId,
      platform: "android",
      name: "Device " + i,
      capabilities: ["apps.list"]
    });
  }

  const devices = await listDevicesForAccount(kv, accountId);
  assert.equal(devices.length, 1005);
  assert.equal(devices[0].accountId, accountId);
});

test("device heartbeat refreshes both device and account-index TTL", async () => {
  const kv = memoryKv();
  const accountId = "acct-12345678-1234-1234-1234-123456789012";
  await createAccount(kv, accountId);
  const deviceId = "android-12345678";
  await saveDevice(kv, {
    deviceId,
    accountId,
    platform: "android",
    capabilities: ["apps.list"]
  });

  const updated = await touchDevice(kv, deviceId);
  assert.ok(updated.lastSeen);
  assert.equal(kv.expirations.get("device:" + deviceId), 7776000);
  assert.equal(kv.expirations.get("account-device:" + accountId + ":" + deviceId), 7776000);
});

test("device revocation removes only the owning account index", async () => {
  const kv = memoryKv();
  const accountId = "acct-12345678-1234-1234-1234-123456789012";
  await createAccount(kv, accountId);
  const deviceId = "android-12345678";
  await saveDevice(kv, { deviceId, accountId, platform: "android" });

  await assert.rejects(() => removeDevice(kv, deviceId, "acct-87654321-4321-4321-4321-210987654321"), /does not belong/);
  assert.equal(await removeDevice(kv, deviceId, accountId), true);
  assert.equal(await kv.get("device:" + deviceId), null);
  assert.equal(await kv.get("account-device:" + accountId + ":" + deviceId), null);
});
