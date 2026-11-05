import test from "node:test";
import assert from "node:assert/strict";
import { parseDeviceIntent, executeDeviceIntent } from "../src/device/ai-tool-service.js";
import { authenticateSupabaseUser, enforceAccountRateLimit, requestOriginAllowed } from "../src/security/request-guard.js";
import { createAccount, saveDevice } from "../src/device/registry.js";

function memoryKv() {
  const store = new Map();
  return {
    async get(key) { return store.get(key) ?? null; },
    async put(key, value) { store.set(key, value); },
    async list({ prefix }) {
      return { keys: [...store.keys()].filter((key) => key.startsWith(prefix)).map((name) => ({ name })), list_complete: true };
    }
  };
}

test("explicit app launch requests are parsed into a safe desktop-capable tool intent", () => {
  assert.deepEqual(parseDeviceIntent("Buka WhatsApp di Windows"), {
    action: "apps.launch_label",
    capability: "apps.launch",
    params: { appName: "WhatsApp" },
    targetPlatform: "windows"
  });
});

test("device status and file search requests are parsed with bounded parameters", () => {
  assert.equal(parseDeviceIntent("cek status perangkat di Android").action, "system.status");
  assert.deepEqual(parseDeviceIntent("cari file laporan keuangan"), {
    action: "files.search",
    capability: "files.read",
    params: { query: "laporan keuangan" },
    targetPlatform: null
  });
  assert.equal(parseDeviceIntent("cari file " + "x".repeat(200)), null);
});

test("ordinary conversation is not interpreted as a device command", () => {
  assert.equal(parseDeviceIntent("Jelaskan cara kerja WhatsApp"), null);
  assert.equal(parseDeviceIntent("Apa kabar JARVIS?"), null);
});

test("device execution refuses inactive or missing account registry", async () => {
  const result = await executeDeviceIntent({
    prompt: "Buka WhatsApp di Windows",
    userId: "123e4567-e89b-12d3-a456-426614174000",
    env: { DEVICE_REGISTRY: memoryKv(), DEVICE_COMMANDS: memoryKv() },
    waitMs: 0
  });
  assert.equal(result.handled, true);
  assert.equal(result.status, "account_inactive");
});

test("Supabase AI endpoints reject missing bearer tokens before network access", async () => {
  const request = new Request("https://jarvis.example/v1/chat", { method: "POST" });
  assert.equal(await authenticateSupabaseUser(request, {
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "public-key"
  }), null);
});

test("per-account rate limit allows the configured quota and then blocks", async () => {
  const kv = memoryKv();
  const now = 1_800_000_000_000;
  assert.equal((await enforceAccountRateLimit(kv, "chat", "user-1", 2, now)).allowed, true);
  assert.equal((await enforceAccountRateLimit(kv, "chat", "user-1", 2, now + 1)).allowed, true);
  const blocked = await enforceAccountRateLimit(kv, "chat", "user-1", 2, now + 2);
  assert.equal(blocked.allowed, false);
  assert.ok(blocked.retryAfter > 0);
});

test("configured browser origins are checked exactly and native requests remain supported", () => {
  assert.equal(requestOriginAllowed(new Request("https://api.example")), true);
  assert.equal(requestOriginAllowed(new Request("https://api.example", { headers: { Origin: "https://good.example" } }), {
    JARVIS_ALLOWED_ORIGINS: "https://good.example,https://app.example"
  }), true);
  assert.equal(requestOriginAllowed(new Request("https://api.example", { headers: { Origin: "https://evil.example" } }), {
    JARVIS_ALLOWED_ORIGINS: "https://good.example"
  }), false);
});

test("authenticated device intent queues a command only for the caller account device", async () => {
  const registry = memoryKv();
  const commands = memoryKv();
  const userId = "123e4567-e89b-12d3-a456-426614174000";
  const accountId = "acct-" + userId;
  await createAccount(registry, accountId);
  await saveDevice(registry, {
    deviceId: "windows-device-123",
    accountId,
    platform: "windows",
    name: "Windows PC",
    capabilities: ["apps.launch", "apps.list", "system.read"]
  });
  const result = await executeDeviceIntent({
    prompt: "Buka WhatsApp di Windows",
    userId,
    env: { DEVICE_REGISTRY: registry, DEVICE_COMMANDS: commands },
    waitMs: 0
  });
  assert.equal(result.handled, true);
  assert.equal(result.ok, true);
  assert.equal(result.status, "queued");
  assert.equal(result.action, "apps.launch_label");
  assert.equal(result.device.platform, "windows");
  const stored = await commands.get("command:windows-device-123:" + result.commandId);
  assert.ok(stored);
  assert.equal(JSON.parse(stored).params.appName, "WhatsApp");
});

test("ambiguous multi-device requests require explicit device selection", async () => {
  const registry = memoryKv();
  const userId = "123e4567-e89b-12d3-a456-426614174001";
  const accountId = "acct-" + userId;
  await createAccount(registry, accountId);
  for (const [deviceId, platform] of [["windows-device-001", "windows"], ["linux-device-001", "linux"]]) {
    await saveDevice(registry, {
      deviceId, accountId, platform, name: platform,
      capabilities: ["apps.launch"]
    });
  }
  const result = await executeDeviceIntent({
    prompt: "Buka Chrome",
    userId,
    env: { DEVICE_REGISTRY: registry, DEVICE_COMMANDS: memoryKv() },
    waitMs: 0
  });
  assert.equal(result.status, "device_selection_required");
  assert.equal(result.devices.length, 2);
});
