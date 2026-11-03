import { validateCommand } from "./protocol.js";
import { authorizeCapability } from "./policy.js";

export async function enqueueDeviceCommand({ kv, device, input, context = {} }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  const command = validateCommand(input, device.platform);
  const decision = authorizeCapability(device, command.capability, {
    userConfirmed: context.userConfirmed
  });
  if (!decision.allowed) return { ok: false, status: "blocked", ...decision };

  const record = { ...command, deviceId: device.deviceId, status: "queued" };
  await kv.put(
    "command:" + device.deviceId + ":" + command.commandId,
    JSON.stringify(record),
    { expirationTtl: 300 }
  );
  return {
    ok: true,
    commandId: command.commandId,
    status: "queued",
    deviceId: device.deviceId
  };
}

export async function listDeviceCommands({ kv, deviceId, limit = 20 }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  const safeLimit = Math.min(50, Math.max(1, Number.isInteger(limit) ? limit : 20));
  const prefix = "command:" + deviceId + ":";
  const keys = [];
  const seenCursors = new Set();
  let cursor;

  for (let pageNumber = 0; pageNumber < 100; pageNumber += 1) {
    const options = { prefix, limit: 100 };
    if (cursor) options.cursor = cursor;
    const page = await kv.list(options);
    keys.push(...(page.keys || []));

    if (page.list_complete !== false) break;
    if (!page.cursor) {
      throw new Error("Command queue pagination is incomplete but no continuation cursor was returned");
    }
    if (seenCursors.has(page.cursor)) {
      throw new Error("Command queue pagination returned a repeated continuation cursor");
    }
    seenCursors.add(page.cursor);
    cursor = page.cursor;

    if (pageNumber === 99) {
      throw new Error("Command queue pagination exceeded the 100-page safety limit");
    }
  }

  const records = await Promise.all(keys.map(async item => {
    const raw = await kv.get(item.name);
    try { return raw ? JSON.parse(raw) : null; }
    catch (_) { return null; }
  }));

  return records
    .filter(record => record && record.deviceId === deviceId && record.status === "queued")
    .sort((a, b) => String(a.createdAt || "").localeCompare(String(b.createdAt || "")))
    .slice(0, safeLimit);
}

export async function acknowledgeDeviceCommand({ kv, deviceId, commandId, result }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
  const key = "command:" + deviceId + ":" + commandId;
  const raw = await kv.get(key);
  if (!raw) return { ok: false, error: "Command not found or expired" };

  let record;
  try { record = JSON.parse(raw); }
  catch (_) { return { ok: false, error: "Command record is invalid" }; }

  if (record.deviceId !== deviceId || record.commandId !== commandId) {
    return { ok: false, error: "Command identity does not match" };
  }
  if (record.status === "completed") {
    return { ok: true, commandId, status: "completed", alreadyAcknowledged: true };
  }

  record.status = "completed";
  record.completedAt = new Date().toISOString();
  record.result = result ?? null;
  await kv.put(key, JSON.stringify(record), { expirationTtl: 86400 });
  return { ok: true, commandId, status: record.status };
}
