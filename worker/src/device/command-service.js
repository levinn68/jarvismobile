import { validateCommand } from "./protocol.js";
import { authorizeCapability } from "./policy.js";

export async function enqueueDeviceCommand({ kv, device, input, context = {} }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  const command = validateCommand(input, device.platform);
  // Remote userConfirmed is never trusted. Pass through only for logging;
  // authorizeCapability ignores it for sensitive actions.
  const decision = authorizeCapability(device, command.capability, {
    userConfirmed: false
  });
  if (!decision.allowed) {
    return { ok: false, status: "blocked", ...decision };
  }
  const record = {
    ...command,
    deviceId: device.deviceId,
    status: decision.requiresConfirmation ? "queued_pending_approval" : "queued",
    requiresConfirmation: !!decision.requiresConfirmation,
    onDeviceApprovalRequired: !!decision.onDeviceApprovalRequired
  };
  await kv.put(
    "command:" + device.deviceId + ":" + command.commandId,
    JSON.stringify(record),
    { expirationTtl: 300 }
  );
  return {
    ok: true,
    commandId: command.commandId,
    status: record.status,
    deviceId: device.deviceId,
    requiresConfirmation: !!decision.requiresConfirmation
  };
}

export async function listDeviceCommands({ kv, deviceId, limit = 20 }) {
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
          return raw ? JSON.parse(raw) : null;
        } catch (_) {
          return null;
        }
      })
    );

    for (const record of records) {
      if (record && (record.status === "queued" || record.status === "queued_pending_approval")) {
        pending.push(record);
        if (pending.length >= requestedLimit) break;
      }
    }

    if (page.list_complete === true || !page.cursor) break;
    cursor = page.cursor;
  }

  return pending;
}

export async function acknowledgeDeviceCommand({ kv, deviceId, commandId, result }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(String(deviceId || ""))) {
    throw new Error("A valid deviceId is required");
  }
  if (!/^[a-f0-9-]{36}$/i.test(String(commandId || ""))) {
    throw new Error("A valid commandId is required");
  }
  if (result !== null && result !== undefined &&
      (typeof result !== "object" || Array.isArray(result))) {
    throw new Error("Command result must be a JSON object");
  }
  let serializedResult = "null";
  try {
    serializedResult = JSON.stringify(result ?? null);
  } catch (_) {
    throw new Error("Command result must be serializable JSON");
  }
  if (serializedResult.length > 65536) {
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
  // Idempotent ack: do not re-complete an already completed command with a different result.
  if (record.status === "completed") {
    return { ok: true, commandId, status: "completed", duplicate: true };
  }
  record.status = "completed";
  record.completedAt = new Date().toISOString();
  record.result = result ?? null;
  await kv.put(key, JSON.stringify(record), { expirationTtl: 86400 });
  return { ok: true, commandId, status: record.status };
}

export async function getDeviceCommand({ kv, deviceId, commandId }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(deviceId) || !/^[a-f0-9-]{36}$/i.test(commandId)) {
    throw new Error("A valid deviceId and commandId are required");
  }
  const raw = await kv.get("command:" + deviceId + ":" + commandId);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    return null;
  }
}
