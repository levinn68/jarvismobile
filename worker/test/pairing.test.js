import test from "node:test";
import assert from "node:assert/strict";
import { createPairingSession, redeemPairingSession } from "../src/device/pairing.js";

function memoryKv() {
  const store = new Map();
  return {
    async put(key, value, _opts) { store.set(key, value); },
    async get(key) { return store.get(key) ?? null; },
    async list() { return { keys: [...store.keys()].map((name) => ({ name })) }; }
  };
}

test("pairing session issues an 8-character code bound to the account", async () => {
  const kv = memoryKv();
  const session = await createPairingSession({
    kv,
    accountId: "acct-11111111-1111-1111-1111-111111111111",
    deviceHint: { name: "Desktop" }
  });
  assert.match(session.code, /^[A-Z0-9]{8}$/);
  assert.equal(session.expiresIn, 300);
  assert.ok(session.qrPayload.includes(session.code));
});

test("redeem enrolls device under the session account and burns the code", async () => {
  const kv = memoryKv();
  const accountId = "acct-22222222-2222-2222-2222-222222222222";
  const session = await createPairingSession({ kv, accountId });

  const saved = [];
  const result = await redeemPairingSession({
    kv,
    code: session.code,
    deviceInput: {
      platform: "linux",
      name: "dev-box",
      osVersion: "6.8",
      capabilities: ["apps.list", "system.read"]
    },
    saveDevice: async (input) => {
      saved.push(input);
      return { ...input, lastSeen: new Date().toISOString(), protocolVersion: "1.0.0" };
    },
    issueDeviceToken: async (deviceId) => "token-for-" + deviceId,
    tokenSecret: "test-secret"
  });

  assert.equal(result.ok, true);
  assert.equal(result.accountId, accountId);
  assert.equal(saved[0].accountId, accountId);
  assert.equal(saved[0].platform, "linux");
  assert.ok(result.token.startsWith("token-for-"));

  await assert.rejects(
    () => redeemPairingSession({
      kv,
      code: session.code,
      deviceInput: { platform: "linux", name: "again" },
      saveDevice: async (input) => input,
      issueDeviceToken: async () => "x",
      tokenSecret: "test-secret"
    }),
    /already been used|invalid or expired/i
  );
});

test("invalid pairing codes are rejected", async () => {
  const kv = memoryKv();
  await assert.rejects(
    () => redeemPairingSession({
      kv,
      code: "BAD",
      deviceInput: { platform: "windows" },
      saveDevice: async (input) => input,
      issueDeviceToken: async () => "x",
      tokenSecret: "test-secret"
    }),
    /Invalid pairing code/
  );
});
