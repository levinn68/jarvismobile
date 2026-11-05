import { webCrypto } from "../utils.js";

/**
 * Short-lived, single-use pairing sessions for QR / manual code flow.
 * Codes are bound to the account that created them and cannot enroll
 * devices into another account.
 */

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
const CODE_LENGTH = 8;
const SESSION_TTL_SECONDS = 300; // 5 minutes
const MAX_ATTEMPTS = 8;

function randomCode() {
  const bytes = webCrypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  let out = "";
  for (const b of bytes) out += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return out;
}

export function extractPairingCode(rawInput) {
  const raw = String(rawInput || "").trim();
  if (!raw) return "";
  if (raw.toLowerCase().startsWith("jarvis-pair://")) {
    try {
      const parsed = new URL(raw);
      return String(parsed.searchParams.get("code") || "").trim().toUpperCase();
    } catch (_) {
      const match = raw.match(/[?&]code=([A-Za-z0-9]+)/);
      return match ? match[1].toUpperCase() : "";
    }
  }
  return raw.replace(/\s+/g, "").toUpperCase();
}

function sessionKey(code) {
  return "pair:" + extractPairingCode(code);
}

function rateKey(accountId) {
  return "pair-rate:" + accountId;
}

export async function createPairingSession({ kv, accountId, deviceHint = {} }) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required for pairing sessions");
  if (!/^[a-zA-Z0-9_-]{12,80}$/.test(accountId)) {
    throw new Error("A valid accountId is required");
  }

  // Soft rate limit: at most 10 active creations per account per hour.
  const rate = rateKey(accountId);
  const rateRaw = await kv.get(rate);
  const rateCount = rateRaw ? Number(rateRaw) || 0 : 0;
  if (rateCount >= 10) {
    throw new Error("Too many pairing sessions. Wait before creating another code.");
  }
  await kv.put(rate, String(rateCount + 1), { expirationTtl: 3600 });

  let code = randomCode();
  // Collision avoidance (extremely unlikely)
  for (let i = 0; i < 5; i++) {
    const existing = await kv.get(sessionKey(code));
    if (!existing) break;
    code = randomCode();
  }

  const createdAt = new Date().toISOString();
  const expiresAt = new Date(Date.now() + SESSION_TTL_SECONDS * 1000).toISOString();
  const session = {
    code,
    accountId,
    status: "pending",
    attempts: 0,
    createdAt,
    expiresAt,
    deviceHint: {
      name: String(deviceHint.name || "").slice(0, 80),
      platform: String(deviceHint.platform || "").slice(0, 20)
    }
  };

  await kv.put(sessionKey(code), JSON.stringify(session), {
    expirationTtl: SESSION_TTL_SECONDS
  });

  return {
    code,
    expiresIn: SESSION_TTL_SECONDS,
    expiresAt,
    qrPayload: "jarvis-pair://v1?code=" + encodeURIComponent(code)
  };
}

export async function redeemPairingSession({
  kv,
  code,
  deviceInput,
  saveDevice,
  issueDeviceToken,
  tokenSecret
}) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required for pairing sessions");
  const normalized = extractPairingCode(code);
  if (!/^[A-Z0-9]{8}$/.test(normalized)) {
    throw new Error("Invalid pairing code format");
  }

  const key = sessionKey(normalized);
  const raw = await kv.get(key);
  if (!raw) throw new Error("Pairing code is invalid or expired");

  let session;
  try {
    session = JSON.parse(raw);
  } catch (_) {
    throw new Error("Pairing session is corrupt");
  }

  if (session.status !== "pending") {
    throw new Error("Pairing code has already been used");
  }

  session.attempts = (session.attempts || 0) + 1;
  if (session.attempts > MAX_ATTEMPTS) {
    session.status = "locked";
    await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
    throw new Error("Pairing code locked after too many attempts");
  }

  // Persist attempt count before validating device input so brute force burns attempts.
  await kv.put(key, JSON.stringify(session), { expirationTtl: SESSION_TTL_SECONDS });

  const platform = String(deviceInput?.platform || "").toLowerCase();
  if (!["windows", "macos", "linux", "android"].includes(platform)) {
    throw new Error("Unsupported pairing platform");
  }

  const deviceId =
    String(deviceInput?.deviceId || "").trim() ||
    platform + "-" + webCrypto.randomUUID();

  const device = await saveDevice({
    deviceId,
    accountId: session.accountId,
    platform,
    name: String(deviceInput?.name || session.deviceHint?.name || "JARVIS device").slice(0, 80),
    osVersion: String(deviceInput?.osVersion || "").slice(0, 40),
    appVersion: String(deviceInput?.appVersion || "1.0.0").slice(0, 40),
    capabilities: Array.isArray(deviceInput?.capabilities)
      ? deviceInput.capabilities
      : ["apps.list", "apps.launch", "system.read", "files.read"]
  });

  const token = await issueDeviceToken(device.deviceId, tokenSecret);

  // One-time use: mark redeemed and shrink TTL.
  session.status = "redeemed";
  session.redeemedAt = new Date().toISOString();
  session.redeemedDeviceId = device.deviceId;
  await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });

  return {
    ok: true,
    device,
    token,
    tokenType: "Bearer",
    accountId: session.accountId
  };
}

export async function getPairingSession({ kv, code, accountId }) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  const normalized = extractPairingCode(code);
  const raw = await kv.get(sessionKey(normalized));
  if (!raw) return null;
  let session;
  try {
    session = JSON.parse(raw);
  } catch (_) {
    return null;
  }
  if (accountId && session.accountId !== accountId) return null;
  return {
    code: session.code,
    status: session.status,
    expiresAt: session.expiresAt,
    redeemedDeviceId: session.redeemedDeviceId || null
  };
}