import { listDevicesForAccount, getAccount, getDevice } from "./registry.js";
import { enqueueDeviceCommand, getDeviceCommand } from "./command-service.js";

const DEVICE_INTENTS = [
  {
    action: "system.status",
    capability: "system.read",
    pattern: /\b(?:cek|periksa|lihat|tampilkan)\s+(?:status|kondisi)\s+(?:perangkat|device|komputer|pc|laptop)|\b(?:device|system)\s+status\b/i,
    params: {}
  },
  {
    action: "apps.list",
    capability: "apps.list",
    pattern: /\b(?:daftar|list|tampilkan|lihat|cek)\s+(?:aplikasi|apps)(?:\s+yang\s+terpasang)?\b/i,
    params: {}
  },
  {
    action: "apps.launch_label",
    capability: "apps.launch",
    pattern: /\b(?:buka|jalankan|launch|open)\s+(?:aplikasi\s+)?([\p{L}\p{N}][\p{L}\p{N} ._+-]{0,79}?)(?=\s+(?:di|pada|on)\s+(?:android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop)\b|$)/iu
  },
  {
    action: "files.search",
    capability: "files.read",
    pattern: /\b(?:cari|temukan|search)\s+file\s+(.{1,160})$/i
  }
];

function parseTarget(prompt) {
  const match = String(prompt).match(/\b(?:di|pada|on)\s+(android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop)\b/i);
  if (!match) return null;
  const value = match[1].toLowerCase();
  if (value === "mac") return "macos";
  if (["laptop", "pc", "komputer", "desktop"].includes(value)) return "desktop";
  if (value === "rog") return "android";
  return value;
}

export function parseDeviceIntent(prompt) {
  const text = String(prompt || "").trim();
  if (!text || text.length > 1000) return null;
  for (const item of DEVICE_INTENTS) {
    const match = text.match(item.pattern);
    if (!match) continue;
    const params = item.params ? { ...item.params } : {};
    if (item.action === "apps.launch_label") {
      const appName = String(match[1] || "").trim().replace(/[.!?]+$/, "");
      if (!appName || /^(di|pada|on|android|windows|linux|macos)$/i.test(appName)) continue;
      params.appName = appName;
    }
    if (item.action === "files.search") {
      const query = String(match[1] || "").trim().replace(/[.!?]+$/, "");
      if (!query || query.length > 160) continue;
      params.query = query;
    }
    return { action: item.action, capability: item.capability, params, targetPlatform: parseTarget(text) };
  }
  return null;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function executeDeviceIntent({ prompt, userId, env, waitMs = 22000 }) {
  const intent = parseDeviceIntent(prompt);
  if (!intent) return { handled: false };
  if (!userId || !env?.DEVICE_REGISTRY || !env?.DEVICE_COMMANDS) {
    return { handled: true, ok: false, status: "not_configured", message: "Akun atau layanan perangkat JARVIS belum dikonfigurasi." };
  }
  const accountId = "acct-" + userId;
  const account = await getAccount(env.DEVICE_REGISTRY, accountId);
  if (!account || account.status !== "active") {
    return { handled: true, ok: false, status: "account_inactive", message: "Akun JARVIS tidak aktif atau belum terhubung." };
  }
  const devices = await listDevicesForAccount(env.DEVICE_REGISTRY, accountId);
  let candidates = devices.filter((device) => device.capabilities?.includes(intent.capability));
  if (intent.targetPlatform) {
    candidates = candidates.filter((device) =>
      intent.targetPlatform === "desktop"
        ? ["windows", "linux", "macos"].includes(device.platform)
        : device.platform === intent.targetPlatform
    );
  }
  const namedMatches = candidates.filter((device) =>
    device.name && String(prompt).toLocaleLowerCase().includes(device.name.toLocaleLowerCase())
  );
  if (namedMatches.length === 1) candidates = namedMatches;
  const now = Date.now();
  const onlineCandidates = candidates.filter((device) => {
    const seen = Date.parse(device.lastSeen || "");
    return Number.isFinite(seen) && now - seen <= 120000 && seen <= now + 60000;
  });
  if (!onlineCandidates.length) {
    return {
      handled: true, ok: false, status: "device_offline",
      message: "Tidak ada perangkat yang cocok dan mengirim heartbeat dalam 120 detik terakhir.",
      devices: candidates.map(({ deviceId, name, platform, lastSeen }) => ({ deviceId, name, platform, lastSeen }))
    };
  }
  candidates = onlineCandidates;
  if (!candidates.length) {
    return {
      handled: true, ok: false, status: "no_capable_device",
      message: "Tidak ada perangkat milik akun ini yang memiliki kemampuan " + intent.capability + ".",
      requestedPlatform: intent.targetPlatform
    };
  }
  if (candidates.length > 1) {
    return {
      handled: true, ok: false, status: "device_selection_required",
      message: "Ada beberapa perangkat yang cocok. Pengguna harus memilih perangkat target.",
      devices: candidates.map(({ deviceId, name, platform, lastSeen }) => ({ deviceId, name, platform, lastSeen }))
    };
  }
  const device = await getDevice(env.DEVICE_REGISTRY, candidates[0].deviceId);
  if (!device || device.accountId !== accountId) {
    return { handled: true, ok: false, status: "device_unavailable", message: "Perangkat target tidak tersedia atau bukan milik akun ini." };
  }
  const lastSeen = Date.parse(device.lastSeen || "");
  if (!Number.isFinite(lastSeen) || Date.now() - lastSeen > 120000 || lastSeen > Date.now() + 60000) {
    return {
      handled: true, ok: false, status: "device_offline",
      message: "Perangkat " + device.name + " belum mengirim heartbeat terbaru.",
      device: { deviceId: device.deviceId, name: device.name, platform: device.platform }
    };
  }
  let queued;
  try {
    queued = await enqueueDeviceCommand({
      kv: env.DEVICE_COMMANDS,
      device,
      input: { action: intent.action, params: intent.params },
      context: { source: "authenticated_ai_tool" }
    });
  } catch (error) {
    return { handled: true, ok: false, status: "rejected", message: String(error?.message || error).slice(0, 180) };
  }
  if (!queued?.ok) {
    return { handled: true, ...queued, device: { deviceId: device.deviceId, name: device.name, platform: device.platform } };
  }
  const deadline = Date.now() + Math.max(0, Math.min(waitMs, 25000));
  while (Date.now() < deadline) {
    await sleep(1000);
    const record = await getDeviceCommand({ kv: env.DEVICE_COMMANDS, deviceId: device.deviceId, commandId: queued.commandId });
    if (record?.status === "completed") {
      return {
        handled: true, ok: true, status: "completed", action: intent.action,
        commandId: queued.commandId,
        device: { deviceId: device.deviceId, name: device.name, platform: device.platform },
        result: record.result ?? null
      };
    }
  }
  return {
    handled: true, ok: true, status: queued.status || "queued",
    requiresConfirmation: Boolean(queued.requiresConfirmation),
    commandId: queued.commandId,
    action: intent.action,
    device: { deviceId: device.deviceId, name: device.name, platform: device.platform },
    message: queued.requiresConfirmation
      ? "Perintah menunggu persetujuan langsung pada perangkat target."
      : "Perintah sudah masuk antrean; perangkat belum mengirim acknowledgement."
  };
}
