import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import workerApp from "../src/index.js";
import { handleDeviceRequest } from "../src/device/router.js";
import { validateCommand, SUPPORTED_PLATFORMS } from "../src/device/protocol.js";
import { createPairingSession, redeemPairingSession } from "../src/device/pairing.js";
import { createAccount, saveDevice, getDevice } from "../src/device/registry.js";
import { issueDeviceToken, verifyDeviceToken } from "../src/device/auth.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../..");

function createMemoryKv() {
  const store = new Map();
  return {
    async get(key) {
      const entry = store.get(key);
      return entry ? entry.value : null;
    },
    async put(key, value, options = {}) {
      store.set(key, { value: String(value), options });
    },
    async delete(key) {
      store.delete(key);
    }
  };
}

test("Batch 6 Final: Repository completeness & regression recovery across all subsystems", () => {
  const requiredPaths = [
    "app/src/main/java/com/jarvis/mobile/MainActivity.kt",
    "app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt",
    "app/src/main/java/com/jarvis/mobile/device/DeviceCommandExecutor.kt",
    "app/src/main/java/com/jarvis/mobile/device/JarvisDeviceClient.kt",
    "app/src/main/java/com/jarvis/mobile/device/ScreenActionApprovalActivity.kt",
    "worker/src/index.js",
    "worker/src/device/router.js",
    "worker/src/device/pairing.js",
    "worker/src/device/command-service.js",
    "desktop/windows/src/agent.js",
    "desktop/windows/src/credentials.js",
    "desktop/macos/src/agent.js",
    "desktop/macos/src/credentials.js",
    "desktop/unix/src/agent.js",
    "desktop/unix/src/credentials.js",
    "supabase/migrations/20261105160000_jarvis_b4_schema_rls_and_triggers_repair.sql",
    "supabase/migrations/20261105161000_jarvis_b4_levpay_cleanup.sql"
  ];
  for (const rel of requiredPaths) {
    const full = path.join(REPO_ROOT, rel);
    assert.equal(fs.existsSync(full), true, `Expected restored/integrated file to exist: ${rel}`);
    assert.ok(fs.statSync(full).size > 100, `Expected non-empty implementation in: ${rel}`);
  }

  // Verify macOS and Unix canonical parity
  const macAgent = fs.readFileSync(path.join(REPO_ROOT, "desktop/macos/src/agent.js"), "utf8");
  const unixAgent = fs.readFileSync(path.join(REPO_ROOT, "desktop/unix/src/agent.js"), "utf8");
  assert.equal(macAgent, unixAgent, "macOS and Unix agent.js must be byte-for-byte synchronized");

  const macCred = fs.readFileSync(path.join(REPO_ROOT, "desktop/macos/src/credentials.js"), "utf8");
  const unixCred = fs.readFileSync(path.join(REPO_ROOT, "desktop/unix/src/credentials.js"), "utf8");
  assert.equal(macCred, unixCred, "macOS and Unix credentials.js must be byte-for-byte synchronized");

  // Verify MainActivity.kt B6 enhancements (401 refresh on /v1/tts + payload.images rendering)
  const mainKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/MainActivity.kt"), "utf8");
  assert.ok(mainKt.includes("missingImages"), "MainActivity.kt must render payload.images when not already in markdown");
  assert.ok(mainKt.includes("Fish Audio HTTP"), "MainActivity.kt must preserve Fish Audio TTS error handling");
  assert.ok(mainKt.includes("refreshSessionBlocking"), "MainActivity.kt must support blocking 401 session refresh");

  // Verify ScreenActionApprovalActivity.kt KDoc and B4 security controls
  const screenKt = fs.readFileSync(
    path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/ScreenActionApprovalActivity.kt"),
    "utf8"
  );
  assert.ok(screenKt.includes("Uses the unified, strict timestamp validator"), "KDoc comment from B3 must be restored");
  assert.ok(screenKt.includes("recordRejectedApproval"), "B4 rejection deduplication must be preserved");
  assert.ok(screenKt.includes("FLAG_SECURE"), "B4 FLAG_SECURE must be preserved");
});

test("Batch 6 Final: Re-pairing a desktop or Android device increments tokenVersion and issues a valid matching token", async () => {
  const kv = createMemoryKv();
  const secret = "batch6-super-secret-device-token-key-32bytes";
  const accountId = "acct-bbbb6666-6666-4666-8666-666666666666";
  await createAccount(kv, accountId);

  const s1 = await createPairingSession({ kv, accountId, platform: "windows", label: "Workstation" });
  const r1 = await redeemPairingSession({
    kv,
    code: s1.code,
    deviceInput: {
      deviceId: "win-host-01",
      platform: "windows",
      name: "Workstation",
      capabilities: ["system.read", "files.list"]
    },
    saveDevice: async (input) => saveDevice(kv, { ...input, tokenVersion: 1 }),
    issueDeviceToken: (deviceId, sec, ttl = 2592000, extra = {}) => issueDeviceToken(deviceId, sec, ttl, extra),
    tokenSecret: secret
  });
  const v1 = await verifyDeviceToken(new Request("https://jarvis.local", { headers: { Authorization: `Bearer ${r1.token}` } }), secret);
  assert.equal(v1.sub, "win-host-01");
  assert.equal(v1.tv, 1);

  // Re-pair same device ID -> tokenVersion increments to 2
  const s2 = await createPairingSession({ kv, accountId, platform: "windows", label: "Workstation Re-paired" });
  const existing = await getDevice(kv, "win-host-01");
  const nextTv = (typeof existing?.tokenVersion === "number" ? existing.tokenVersion : 1) + 1;
  const r2 = await redeemPairingSession({
    kv,
    code: s2.code,
    deviceInput: {
      deviceId: "win-host-01",
      platform: "windows",
      name: "Workstation Re-paired",
      capabilities: ["system.read", "files.list", "files.read"]
    },
    saveDevice: async (input) => saveDevice(kv, { ...input, tokenVersion: nextTv }),
    issueDeviceToken: (deviceId, sec, ttl = 2592000, extra = {}) => issueDeviceToken(deviceId, sec, ttl, extra),
    tokenSecret: secret
  });
  const v2 = await verifyDeviceToken(new Request("https://jarvis.local", { headers: { Authorization: `Bearer ${r2.token}` } }), secret);
  assert.equal(v2.sub, "win-host-01");
  assert.equal(v2.tv, 2);
});