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
  const page = await kv.list({
    prefix: "command:" + deviceId + ":",
    limit: Math.min(50, Math.max(1, limit))
  });
  const records = await Promise.all(
    page.keys.map(async (item) => {
      const raw = await kv.get(item.name);
      try {
        return raw ? JSON.parse(raw) : null;
      } catch (_) {
        return null;
      }
    })
  );
  return records.filter(
    (x) => x && (x.status === "queued" || x.status === "queued_pending_approval")
  );
}

export async function acknowledgeDeviceCommand({ kv, deviceId, commandId, result }) {
  if (!kv) throw new Error("DEVICE_COMMANDS KV binding is required");
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
