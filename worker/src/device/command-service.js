import { validateCommand } from "./protocol.js";
import { authorizeCapability } from "./policy.js";
import { isCommandTimestampFresh, canonicalJson, sha256Hex } from "../utils.js";

const IDEMPOTENCY_TTL_SECONDS = 300;
const DEDUPE_WINDOW_MS = 10000;
const recentCommandIndex = new Map();

function sanitizeIdempotencyKey(raw) {
  const clean = String(raw || "").trim();
  if (!clean) return "";
  if (!/^[a-zA-Z0-9_.:-]{4,120}$/.test(clean)) {
    throw new Error("Invalid idempotencyKey format");
  }
  return clean;
}

function pruneRecentCommandIndex(nowMs = Date.now()) {
  for (const [k, entry] of recentCommandIndex.entries()) {
    if (!entry || nowMs - entry.savedAt > IDEMPOTENCY_TTL_SECONDS * 1000) {
      recentCommandIndex.delete(k);
    }
  }
}

export async function enqueueDeviceCommand({ kv, device, input, context = {} }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  if (!device?.deviceId || !/^[a-zA-Z0-9_-]{8,80}$/.test(String(device.deviceId))) {
    throw new Error("A valid target device is required");
  }

  const command = validateCommand(input, device.platform);
  // Remote userConfirmed is never trusted. Pass through only for logging;
  // authorizeCapability ignores it for sensitive actions.
  const decision = authorizeCapability(device, command.capability, {
    userConfirmed: false
  });
  if (!decision.allowed) {
    return { ok: false, status: "blocked", ...decision };
  }

  const nowMs = Date.now();
  pruneRecentCommandIndex(nowMs);

  const explicitIdemKey = sanitizeIdempotencyKey(
    input?.idempotencyKey || input?.clientCommandId || context?.idempotencyKey || ""
  );
  const fingerprint = await sha256Hex(
    `${device.deviceId}:${command.action}:${canonicalJson(command.params || {})}`
  );
  const lookupKey = explicitIdemKey
    ? `idemp:${device.deviceId}:${explicitIdemKey}`
    : input?.preventDuplicate === true || context?.preventDuplicate === true
      ? `dedupe:${device.deviceId}:${fingerprint.slice(0, 32)}`
      : "";

  const kvDedupeKey = explicitIdemKey
    ? `cmd-idemp:${device.deviceId}:${explicitIdemKey}`
    : lookupKey
      ? `cmd-${lookupKey}`
      : "";

  if (lookupKey) {
    const indexed = recentCommandIndex.get(lookupKey);
    const candidateId =
      indexed?.commandId ||
      (kvDedupeKey && typeof kv.get === "function" ? await kv.get(kvDedupeKey) : null);

    if (candidateId && /^[a-f0-9-]{36}$/i.test(candidateId)) {
      const existingRaw = await kv.get("command:" + device.deviceId + ":" + candidateId);
      if (existingRaw) {
        try {
          const existingRecord = JSON.parse(existingRaw);
          const createdMs = Date.parse(existingRecord.createdAt || "");
          const withinWindow =
            Boolean(explicitIdemKey) ||
            (Number.isFinite(createdMs) &&
              nowMs - createdMs >= 0 &&
              nowMs - createdMs <= DEDUPE_WINDOW_MS &&
              existingRecord.status !== "completed" &&
              existingRecord.status !== "expired");
          if (withinWindow && isCommandTimestampFresh(existingRecord.createdAt, nowMs)) {
            return {
              ok: true,
              duplicate: true,
              commandId: existingRecord.commandId,
              status: existingRecord.status,
              deviceId: device.deviceId,
              requiresConfirmation: !!existingRecord.requiresConfirmation
            };
          }
        } catch (_) {
          // Ignore corrupt entry and proceed with fresh command creation.
        }
      }
    }
  }

  const record = {
    ...command,
    deviceId: device.deviceId,
    accountId: device.accountId || undefined,
    status: decision.requiresConfirmation ? "queued_pending_approval" : "queued",
    requiresConfirmation: !!decision.requiresConfirmation,
    onDeviceApprovalRequired: !!decision.onDeviceApprovalRequired,
    deliveryCount: 0,
    ...(explicitIdemKey ? { idempotencyKey: explicitIdemKey } : {})
  };

  await kv.put(
    "command:" + device.deviceId + ":" + command.commandId,
    JSON.stringify(record),
    { expirationTtl: 300 }
  );

  if (lookupKey) {
    recentCommandIndex.set(lookupKey, { commandId: command.commandId, savedAt: nowMs });
  }
  if (kvDedupeKey && typeof kv.put === "function") {
    await kv.put(kvDedupeKey, command.commandId, {
      expirationTtl: explicitIdemKey ? IDEMPOTENCY_TTL_SECONDS : Math.max(60, Math.ceil(DEDUPE_WINDOW_MS / 1000))
    });
  }

  return {
    ok: true,
    commandId: command.commandId,
    status: record.status,
    deviceId: device.deviceId,    requiresConfirmation: !!decision.requiresConfirmation
  };
}

export async function listDeviceCommands({ kv, deviceId, limit = 20, nowMs = Date.now() }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(String(deviceId || ""))) {
    throw new Error("A valid deviceId is required");
  }

  const requestedLimit = Number.isFinite(Number(limit))
    ? Math.min(1000, Math.max(1, Math.floor(Number(limit))))
    : 20;
  const prefix = "command:" + deviceId + ":";
  const pending = [];
  let cursor;
  let pages = 0;

  // Cloudflare KV returns at most 1,000 keys per page. Continue through
  // cursors so older queued commands are not hidden behind completed ones.
  while (pending.length < requestedLimit && pages < 10) {
    const options = { prefix, limit: 1000 };
    if (cursor) options.cursor = cursor;
    const page = await kv.list(options);
    pages += 1;

    const records = await Promise.all(
      (page.keys || []).map(async (item) => {
        const raw = await kv.get(item.name);
        try {
          return raw ? { key: item.name, record: JSON.parse(raw) } : null;
        } catch (_) {
          return null;
        }
      })
    );

    for (const entry of records) {
      const record = entry?.record;
      if (
        !record ||
        (record.status !== "queued" &&
          record.status !== "queued_pending_approval" &&
          record.status !== "awaiting_user_approval")
      ) {
        continue;
      }
      // Missing or invalid timestamps are never trusted, including legacy or corrupted KV records.
      if (!isCommandTimestampFresh(record.createdAt, nowMs)) {
        if (typeof kv.put === "function") {
          record.status = "expired";
          record.outcome = "expired";
          record.expiredAt = new Date(nowMs).toISOString();
          await kv.put(entry.key, JSON.stringify(record), { expirationTtl: 3600 }).catch(() => {});
        }
        continue;
      }
      pending.push(record);
      if (pending.length >= requestedLimit) break;
    }

    if (page.list_complete === true || !page.cursor) break;
    cursor = page.cursor;
  }

  // Deterministic FIFO ordering by createdAt ascending (with commandId tie-breaker).
  pending.sort((a, b) => {
    const tA = Date.parse(a.createdAt || "") || 0;
    const tB = Date.parse(b.createdAt || "") || 0;
    if (tA !== tB) return tA - tB;
    return String(a.commandId || "").localeCompare(String(b.commandId || ""));
  });

  return pending;
}

export async function acknowledgeDeviceCommand({ kv, deviceId, commandId, result, nowMs = Date.now() }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(String(deviceId || ""))) {
    throw new Error("A valid deviceId is required");
  }
  if (!/^[a-f0-9-]{36}$/i.test(String(commandId || ""))) {
    throw new Error("A valid commandId is required");
  }
  if (
    result !== null &&
    result !== undefined &&
    (typeof result !== "object" || Array.isArray(result))
  ) {
    throw new Error("Command result must be a JSON object");
  }
  let serializedResult = "null";
  try {
    serializedResult = JSON.stringify(result ?? null);
  } catch (_) {
    throw new Error("Command result must be serializable JSON");
  }
  if (typeof result?.content === "string" && result.content.length > 65536) {
    throw new Error("Command result exceeds the 64 KB limit");
  }
  if (serializedResult.length > 73728) {
    throw new Error("Command result exceeds the 64 KB limit");
  }
  const key = "command:" + deviceId + ":" + commandId;
  const raw = await kv.get(key);
  if (!raw) return { ok: false, error: "Command not found or expired" };
  let record;
  try {
    record = JSON.parse(raw);
  } catch (_) {
    return { ok: false, error: "Command record is invalid" };
  }

  // Idempotent ack: do not overwrite an already completed or expired command.
  if (record.status === "completed" || record.status === "expired") {
    return {
      ok: true,
      commandId,
      status: record.status,
      outcome: record.outcome || "succeeded",
      duplicate: true
    };
  }

  // Intermediate state: device reports that the local approval dialog is currently displayed.
  if (result && result.pendingApproval === true && result.executed === false) {
    record.status = "queued_pending_approval";
    record.approvalPromptedAt = new Date(nowMs).toISOString();
    if (typeof kv.put === "function") {
      await kv.put(key, JSON.stringify(record), { expirationTtl: 300 });
    }
    return {
      ok: true,
      commandId,
      status: record.status,
      pendingApproval: true
    };
  }

  // Enforce command TTL expiration on acknowledgment.
  if (result?.expired === true || !isCommandTimestampFresh(record.createdAt, nowMs)) {
    record.status = "completed";
    record.outcome = "failed";
    record.expired = true;
    record.completedAt = new Date(nowMs).toISOString();
    record.result = {
      ...(result || {}),
      ok: false,
      expired: true,
      error: result?.error || "Command expired before completion"
    };
    if (typeof kv.put === "function") {
      await kv.put(key, JSON.stringify(record), { expirationTtl: 86400 });
    }
    return { ok: true, commandId, status: record.status, outcome: record.outcome, expired: true };
  }

  // Enforce local user approval for sensitive commands (e.g. screen.read, screen.tap, screen.type).
  const requiresApproval = Boolean(record.onDeviceApprovalRequired || record.requiresConfirmation);
  if (requiresApproval && (!result || result.ok !== false) && result?.approved !== true) {
    record.status = "completed";
    record.outcome = "failed";
    record.completedAt = new Date(nowMs).toISOString();
    record.result = {
      ok: false,
      approved: false,
      executed: false,
      error: "Sensitive command requires explicit on-device user approval"
    };
    if (typeof kv.put === "function") {
      await kv.put(key, JSON.stringify(record), { expirationTtl: 86400 });
    }
    return {
      ok: false,
      commandId,
      status: record.status,
      outcome: record.outcome,
      error: record.result.error
    };
  }

  record.status = "completed";
  record.outcome = result && result.ok === false ? "failed" : "succeeded";
  record.completedAt = new Date(nowMs).toISOString();
  record.result = result ?? null;
  if (typeof kv.put === "function") {
    await kv.put(key, JSON.stringify(record), { expirationTtl: 86400 });
  }
  return { ok: true, commandId, status: record.status, outcome: record.outcome };
}

export async function getDeviceCommand({ kv, deviceId, commandId, nowMs = Date.now() }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(deviceId) || !/^[a-f0-9-]{36}$/i.test(commandId)) {
    throw new Error("A valid deviceId and commandId are required");
  }
  const key = "command:" + deviceId + ":" + commandId;
  const raw = await kv.get(key);
  if (!raw) return null;
  try {
    const record = JSON.parse(raw);
    if (
      (record.status === "queued" ||
        record.status === "queued_pending_approval" ||
        record.status === "awaiting_user_approval") &&
      !isCommandTimestampFresh(record.createdAt, nowMs)
    ) {
      record.status = "expired";
      record.outcome = "expired";
      record.expiredAt = new Date(nowMs).toISOString();
      if (typeof kv.put === "function") {
        await kv.put(key, JSON.stringify(record), { expirationTtl: 3600 }).catch(() => {});
      }
    }
    return record;
  } catch (_) {
    return null;
  }
}

export async function purgeDeviceCommands({ kv, deviceId }) {
  if (!kv || typeof kv.delete !== "function" || typeof kv.list !== "function") return 0;
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(String(deviceId || ""))) return 0;
  const prefix = "command:" + deviceId + ":";
  let deleted = 0;
  let cursor;
  let pages = 0;
  do {
    const page = await kv.list({ prefix, limit: 1000, ...(cursor ? { cursor } : {}) });
    for (const item of page.keys || []) {
      await kv.delete(item.name);
      deleted += 1;
    }
    cursor = page.list_complete === false ? page.cursor : undefined;
    pages += 1;
  } while (cursor && pages < 10);
  return deleted;
}
