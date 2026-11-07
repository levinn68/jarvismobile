import { listDevicesForAccount, getAccount, getDevice } from "./registry.js";
import { enqueueDeviceCommand, getDeviceCommand } from "./command-service.js";

const DEVICE_INTENTS = [
  {
    action: "system.status",
    capability: "system.read",
    pattern: /\b(?:cek|periksa|lihat|tampilkan)\s+(?:status|kondisi)\s+(?:perangkat|device|komputer|pc|laptop|hp|ponsel|android)|\b(?:device|system)\s+status\b/i,
    params: {}
  },
  {
    action: "apps.list",
    capability: "apps.list",
    pattern: /\b(?:daftar|list|tampilkan|lihat|cek)\s+(?:aplikasi|apps)(?:\s+yang\s+terpasang)?\b/i,
    params: {}
  },
  {
    action: "notifications.list",
    capability: "notifications.read",
    pattern: /\b(?:cek|periksa|lihat|tampilkan|baca|daftar)\s+notifikasi(?:\s+terbaru)?\b|\b(?:check|list|show|read)\s+notifications\b/i,
    params: {}
  },
  {
    action: "contacts.list",
    capability: "contacts.read",
    pattern: /\b(?:cek|periksa|lihat|tampilkan|daftar)\s+kontak(?:\s+saya)?\b|\b(?:list|show)\s+contacts\b/i,
    params: {}
  },
  {
    action: "calendar.list",
    capability: "calendar.read",
    pattern: /\b(?:cek|periksa|lihat|tampilkan|daftar)\s+(?:jadwal\s+)?(?:kalender|agenda)(?:\s+saya)?\b|\b(?:list|show|check)\s+(?:calendar|events)\b/i,
    params: {}
  },
  {
    action: "screen.read",
    capability: "screen.read",
    pattern: /\b(?:baca|lihat|periksa|ringkas)\s+(?:isi\s+|teks\s+)?layar\b|\bread\s+(?:the\s+)?screen\b/i,
    params: {}
  },
  {
    action: "files.list",
    capability: "files.read",
    pattern: /\b(?:daftar|list|lihat|tampilkan)\s+(?:semua\s+)?(?:file|berkas|dokumen)(?:\s+saya)?(?:\s+(?:di|pada|on)\s+(?:android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop))?\s*$/i,
    params: {}
  },
  {
    action: "files.search",
    capability: "files.read",
    pattern: /\b(?:cari|temukan|search)\s+(?:file|berkas|dokumen)\s+(.{1,160})$/i
  },
  {
    action: "apps.launch_label",
    capability: "apps.launch",
    pattern: /^(?:(?:hey|hai|halo|hello)\s+)?(?:jarvis[,\s]+)?(?:tolong\s+|please\s+|coba\s+)?(?:buka|bukakan|jalankan|launch|open)\s+(?:(?:aplikasi|app)\s+)?([\p{L}\p{N}][\p{L}\p{N} ._+-]{0,79}?)(?=\s+(?:di|pada|on|untuk)\s+(?:android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop|hp|ponsel)\b|$)/iu
  }
];

function parseTarget(prompt) {
  const text = String(prompt || "");
  const explicitMatch = text.match(/\b(?:di|pada|on|untuk|ke)\s+(android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop|hp|ponsel)\b/i);
  const implicitMatch = text.match(/\b(?:status|kondisi|layar|aplikasi|file|berkas|notifikasi)\s+(android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop|hp|ponsel)(?:\s+saya)?\b/i);
  const match = explicitMatch || implicitMatch;
  if (!match) return null;
  const value = match[1].toLowerCase();
  if (value === "mac") return "macos";
  if (["laptop", "pc", "komputer", "desktop"].includes(value)) return "desktop";
  if (["rog", "hp", "ponsel"].includes(value)) return "android";
  return value;
}

function stripTrailingTarget(raw) {
  return String(raw || "")
    .replace(/\s+(?:di|pada|on)\s+(?:android|windows|linux|macos|mac|laptop|pc|komputer|rog|desktop)\b.*$/i, "")
    .trim()
    .replace(/[.!?]+$/, "")
    .trim();
}

export function parseDeviceIntent(prompt) {
  const text = String(prompt || "").trim();
  if (!text || text.length > 1000) return null;

  // Check if user is asking to list their paired devices
  if (/\b(?:daftar|list|lihat|cek|tampilkan)\s+perangkat(?:\s+(?:terhubung|terdaftar|saya|paired))?\b|\b(?:list|show|check)\s+(?:paired\s+|connected\s+)?devices\b/i.test(text)) {
    return { action: "devices.list", capability: "device.pair", params: {}, targetPlatform: null };
  }

  for (const item of DEVICE_INTENTS) {
    const match = text.match(item.pattern);
    if (!match) continue;
    const params = item.params ? { ...item.params } : {};
    if (item.action === "apps.launch_label") {
      const appName = stripTrailingTarget(match[1]);
      if (
        !appName ||
        /^(?:di|pada|on|android|windows|linux|macos|file|berkas|dokumen|layar|notifikasi|kontak|kalender|status|perangkat|halaman|situs|website|url|link|tautan|folder|direktori|diskusi|percakapan)(?:\b|$)/i.test(appName)
      ) {
        continue;
      }
      params.appName = appName;
    }
    if (item.action === "files.search") {
      const query = stripTrailingTarget(match[1]);
      if (!query || query.length > 160) continue;
      params.query = query;
    }
    return { action: item.action, capability: item.capability, params, targetPlatform: parseTarget(text) };
  }
  return null;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function executeDeviceIntent({ prompt, userId, env, waitMs = 22000, clientRequestId = "" }) {
  const intent = parseDeviceIntent(prompt);
  if (!intent) return { handled: false };
  const trace = [{ step: "intent_detected", action: intent.action, capability: intent.capability, targetPlatform: intent.targetPlatform }];

  if (!userId || !env?.DEVICE_REGISTRY || !env?.DEVICE_COMMANDS) {
    return { handled: true, ok: false, status: "not_configured", message: "Akun atau layanan perangkat JARVIS belum dikonfigurasi.", trace };
  }
  const accountId = "acct-" + userId;
  const account = await getAccount(env.DEVICE_REGISTRY, accountId);
  if (!account || account.status !== "active") {
    return { handled: true, ok: false, status: "account_inactive", message: "Akun JARVIS tidak aktif atau belum terhubung.", trace };
  }
  const devices = await listDevicesForAccount(env.DEVICE_REGISTRY, accountId);
  const now = Date.now();

  if (intent.action === "devices.list") {
    const list = devices.map(({ deviceId, name, platform, capabilities, lastSeen }) => {
      const seen = Date.parse(lastSeen || "");
      const online = Number.isFinite(seen) && now - seen <= 120000 && seen <= now + 60000;
      return { deviceId, name, platform, online, lastSeen, capabilities };
    });
    trace.push({ step: "devices_listed", count: list.length });
    return {
      handled: true,
      ok: true,
      status: "completed",
      action: "devices.list",
      devices: list,
      trace
    };
  }

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

  if (!candidates.length) {
    trace.push({ step: "no_capable_device" });
    return {
      handled: true,
      ok: false,
      status: "no_capable_device",
      message: "Tidak ada perangkat milik akun ini yang memiliki kemampuan " + intent.capability + ".",
      requestedPlatform: intent.targetPlatform,
      trace
    };
  }

  const onlineCandidates = candidates.filter((device) => {
    const seen = Date.parse(device.lastSeen || "");
    return Number.isFinite(seen) && now - seen <= 120000 && seen <= now + 60000;
  });
  if (!onlineCandidates.length) {
    trace.push({ step: "device_offline", candidateCount: candidates.length });
    return {
      handled: true,
      ok: false,
      status: "device_offline",
      message: "Tidak ada perangkat yang cocok dan mengirim heartbeat dalam 120 detik terakhir.",
      devices: candidates.map(({ deviceId, name, platform, lastSeen }) => ({ deviceId, name, platform, lastSeen })),
      trace
    };
  }
  candidates = onlineCandidates;
  if (candidates.length > 1) {
    trace.push({ step: "device_selection_required", candidateCount: candidates.length });
    return {
      handled: true,
      ok: false,
      status: "device_selection_required",
      message: "Ada beberapa perangkat yang cocok. Pengguna harus memilih perangkat target.",
      devices: candidates.map(({ deviceId, name, platform, lastSeen }) => ({ deviceId, name, platform, lastSeen })),
      trace
    };
  }
  const device = await getDevice(env.DEVICE_REGISTRY, candidates[0].deviceId);
  if (!device || device.accountId !== accountId) {
    return { handled: true, ok: false, status: "device_unavailable", message: "Perangkat target tidak tersedia atau bukan milik akun ini.", trace };
  }
  const lastSeen = Date.parse(device.lastSeen || "");
  if (!Number.isFinite(lastSeen) || Date.now() - lastSeen > 120000 || lastSeen > Date.now() + 60000) {
    return {
      handled: true,
      ok: false,
      status: "device_offline",
      message: "Perangkat " + device.name + " belum mengirim heartbeat terbaru.",
      device: { deviceId: device.deviceId, name: device.name, platform: device.platform },
      trace
    };
  }
  const cleanReqId = String(clientRequestId || "").replace(/[^a-zA-Z0-9_.:-]/g, "").slice(0, 64);
  const idempotencyKey = cleanReqId ? `chat:${cleanReqId}` : undefined;
  let queued;
  try {
    queued = await enqueueDeviceCommand({
      kv: env.DEVICE_COMMANDS,
      device,
      input: { action: intent.action, params: intent.params, ...(idempotencyKey ? { idempotencyKey } : {}) },
      context: { source: "authenticated_ai_tool", idempotencyKey }
    });
  } catch (error) {
    return { handled: true, ok: false, status: "rejected", message: String(error?.message || error).slice(0, 180), trace };
  }
  if (!queued?.ok) {
    return { handled: true, ...queued, device: { deviceId: device.deviceId, name: device.name, platform: device.platform }, trace };
  }
  trace.push({ step: "command_enqueued", commandId: queued.commandId, status: queued.status, duplicate: Boolean(queued.duplicate) });
  const deadline = Date.now() + Math.max(0, Math.min(waitMs, 25000));
  let pollStep = 0;
  while (Date.now() < deadline) {
    const stepDelay = pollStep === 0 ? 120 : pollStep === 1 ? 250 : pollStep < 5 ? 500 : 1000;
    pollStep += 1;
    await sleep(Math.min(stepDelay, Math.max(10, deadline - Date.now())));
    const record = await getDeviceCommand({ kv: env.DEVICE_COMMANDS, deviceId: device.deviceId, commandId: queued.commandId });
    if (record?.status === "completed" || record?.status === "expired") {
      const outcomeOk = record.status === "completed" && record.outcome !== "failed" && record.result?.ok !== false;
      trace.push({
        step: "command_acknowledged",
        commandId: queued.commandId,
        outcome: record.outcome || (outcomeOk ? "succeeded" : "failed")
      });
      return {
        handled: true,
        ok: outcomeOk,
        status: record.status,
        outcome: record.outcome || (outcomeOk ? "succeeded" : "failed"),
        action: intent.action,
        commandId: queued.commandId,
        device: { deviceId: device.deviceId, name: device.name, platform: device.platform },
        result: record.result ?? null,
        trace
      };
    }
  }
  trace.push({ step: "wait_timeout", commandId: queued.commandId, status: queued.status || "queued" });
  return {
    handled: true,
    ok: true,
    status: queued.status || "queued",
    requiresConfirmation: Boolean(queued.requiresConfirmation),
    commandId: queued.commandId,
    action: intent.action,
    device: { deviceId: device.deviceId, name: device.name, platform: device.platform },
    message: queued.requiresConfirmation
      ? "Perintah menunggu persetujuan langsung pada perangkat target."
      : "Perintah sudah masuk antrean; perangkat belum mengirim acknowledgement.",
    trace
  };
}
