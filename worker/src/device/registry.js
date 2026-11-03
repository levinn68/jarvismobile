import { normalizeDevice } from "./protocol.js";
const key = id => "device:" + id;
const accountKey = id => "account:" + id;
const accountDevicePrefix = id => "account-device:" + id + ":";

export async function createAccount(kv, accountId) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  const record = { accountId, createdAt: new Date().toISOString(), status: "active" };
  await kv.put(accountKey(accountId), JSON.stringify(record));
  return record;
}

export async function getAccount(kv, id) {
  if (!kv) return null;
  const raw = await kv.get(accountKey(id));
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

export async function saveDevice(kv, input) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  const device = normalizeDevice(input);
  if (!device.accountId) throw new Error("accountId is required for device enrollment");
  const account = await getAccount(kv, device.accountId);
  if (!account || account.status !== "active") {
    throw new Error("JARVIS account not found or inactive");
  }

  // Prevent sequential cross-account device ID takeover.
  // Cloudflare KV is eventually consistent; this check is not an atomic reservation.
  const existing = await getDevice(kv, device.deviceId);
  if (existing && existing.accountId !== device.accountId) {
    throw new Error("Device ID is already enrolled in another account");
  }

  await kv.put(key(device.deviceId), JSON.stringify(device), { expirationTtl: 7776000 });
  await kv.put(accountDevicePrefix(device.accountId) + device.deviceId, "1", { expirationTtl: 7776000 });
  return device;
}

export async function getDevice(kv, id) {
  if (!kv) return null;
  const raw = await kv.get(key(id));
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

async function listAllKeys(kv, prefix) {
  const keys = [];
  let cursor;
  const seenCursors = new Set();

  for (let pageNumber = 0; pageNumber < 100; pageNumber += 1) {
    const options = { prefix, limit: 100 };
    if (cursor) options.cursor = cursor;
    const page = await kv.list(options);
    keys.push(...(page.keys || []));

    if (page.list_complete !== false || !page.cursor || seenCursors.has(page.cursor)) {
      break;
    }
    seenCursors.add(page.cursor);
    cursor = page.cursor;
  }
  return keys;
}

export async function listDevicesForAccount(kv, accountId) {
  if (!kv) return [];
  const prefix = accountDevicePrefix(accountId);
  const keys = await listAllKeys(kv, prefix);
  const values = await Promise.all(keys.map(item =>
    getDevice(kv, item.name.slice(prefix.length))
  ));
  return values.filter(value => value && value.accountId === accountId);
}

export async function listDevices(kv) {
  if (!kv) return [];
  const prefix = "device:";
  const keys = await listAllKeys(kv, prefix);
  const values = await Promise.all(keys.map(item =>
    getDevice(kv, item.name.slice(prefix.length))
  ));
  return values.filter(Boolean);
}

export async function touchDevice(kv, id) {
  const device = await getDevice(kv, id);
  if (!device) return null;
  device.lastSeen = new Date().toISOString();
  await kv.put(key(id), JSON.stringify(device), { expirationTtl: 7776000 });
  return device;
}
