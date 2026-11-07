import { normalizeDevice } from "./protocol.js";

const key = (id) => "device:" + id;
const accountKey = (id) => "account:" + id;
const accountDeviceKey = (accountId, deviceId) => "account-device:" + accountId + ":" + deviceId;
const DEVICE_TTL_SECONDS = 7776000;

export async function createAccount(kv, accountId) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  if (!/^[a-zA-Z0-9_-]{12,80}$/.test(String(accountId || ""))) {
    throw new Error("A valid accountId is required");
  }
  const existing = await getAccount(kv, accountId);
  if (existing) return existing;
  const record = { accountId, createdAt: new Date().toISOString(), status: "active" };
  await kv.put(accountKey(accountId), JSON.stringify(record));
  return record;
}

export async function getAccount(kv, id) {
  if (!kv || !id) return null;
  const raw = await kv.get(accountKey(id));
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    return null;
  }
}

export async function saveDevice(kv, input) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  const device = normalizeDevice(input);
  if (!device.accountId) throw new Error("accountId is required for device enrollment");
  const account = await getAccount(kv, device.accountId);
  if (!account || account.status !== "active") {
    throw new Error("JARVIS account not found or inactive");
  }

  // Prevent Cross-Account Device Hijacking (IDOR):
  // A deviceId already registered to one account cannot be claimed or overwritten by another account.
  const existing = await getDevice(kv, device.deviceId);
  if (existing && existing.accountId && existing.accountId !== device.accountId) {
    const err = new Error("Device ID belongs to another account and cannot be claimed");
    err.status = 403;
    throw err;
  }

  const record = {
    ...device,
    enrolledAt: existing?.enrolledAt || device.lastSeen,
    tokenVersion:
      typeof input?.tokenVersion === "number" && Number.isInteger(input.tokenVersion)
        ? input.tokenVersion
        : typeof existing?.tokenVersion === "number"
          ? existing.tokenVersion
          : 1
  };

  await kv.put(key(record.deviceId), JSON.stringify(record), { expirationTtl: DEVICE_TTL_SECONDS });
  await kv.put(accountDeviceKey(record.accountId, record.deviceId), "1", { expirationTtl: DEVICE_TTL_SECONDS });
  return record;
}

export async function getDevice(kv, id) {
  if (!kv || !id) return null;
  const raw = await kv.get(key(id));
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (_) {
    return null;
  }
}

async function listAllKeys(kv, prefix) {
  const keys = [];
  let cursor;
  let pages = 0;
  do {
    const page = await kv.list({ prefix, limit: 1000, ...(cursor ? { cursor } : {}) });
    keys.push(...(page.keys || []));
    cursor = page.list_complete === false ? page.cursor : undefined;
    pages += 1;
  } while (cursor && pages < 50);
  return keys;
}

export async function listDevicesForAccount(kv, accountId) {
  if (!kv || !accountId) return [];
  const prefix = "account-device:" + accountId + ":";
  const keys = await listAllKeys(kv, prefix);
  const values = await Promise.all(
    keys.map(async (item) => getDevice(kv, item.name.slice(prefix.length)))
  );
  return values.filter((device) => device && device.accountId === accountId);
}

export async function listDevices(kv) {
  if (!kv) return [];
  const prefix = "device:";
  const keys = await listAllKeys(kv, prefix);
  const values = await Promise.all(
    keys.map(async (item) => getDevice(kv, item.name.slice(prefix.length)))
  );
  return values.filter(Boolean);
}

export async function touchDevice(kv, id) {
  const device = await getDevice(kv, id);
  if (!device) return null;
  device.lastSeen = new Date().toISOString();
  await kv.put(key(id), JSON.stringify(device), { expirationTtl: DEVICE_TTL_SECONDS });
  // Keep the account index alive for as long as the device heartbeat keeps it alive.
  await kv.put(accountDeviceKey(device.accountId, id), "1", { expirationTtl: DEVICE_TTL_SECONDS });
  return device;
}

export async function removeDevice(kv, id, accountId) {
  if (!kv || typeof kv.delete !== "function") {
    throw new Error("DEVICE_REGISTRY KV delete support is required");
  }
  const device = await getDevice(kv, id);
  if (!device) return false;
  if (device.accountId !== accountId) {
    const err = new Error("Device does not belong to this account");
    err.status = 403;
    throw err;
  }
  await kv.delete(key(id));
  await kv.delete(accountDeviceKey(accountId, id));
  return true;
}
