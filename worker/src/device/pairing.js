import { webCrypto } from "../utils.js";

/**
 * J.A.R.V.I.S. B8.1.1 Remediation R1 — Distributed-Safe Device Pairing & Atomic Redemption
 * Remediates JARVIS-004:
 * - Atomic single-use pairing code redemption across concurrent Worker isolates using:
 *   1. Optional Durable Object / transactional coordinator (`coordinator.redeemAtomic`) when bound, AND
 *   2. Distributed claim-key election (`pair-claim:<code>:<orderKey>`) across KV so concurrent isolates
 *      that do not share in-memory locks still elect exactly one winner before device creation, AND
 *   3. In-isolate fast-path lock (`activeRedemptionLocks`).
 * - Full transactional rollback if `saveDevice()` succeeds but `issueDeviceToken()` or final state persistence fails.
 * - Atomic enforcement of expiration and cancellation.
 */

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
const CODE_LENGTH = 8;
const SESSION_TTL_SECONDS = 300; // 5 minutes
const MAX_ATTEMPTS = 8;

const activeRedemptionLocks = new Set();
const pairingCodeLocks = new Map();
const pairingRateLocks = new Map();
let claimSequenceCounter = 0;

async function withMapLock(map, key, fn) {
  const safeKey = String(key || "default");
  const prev = map.get(safeKey) || Promise.resolve();
  let release;
  const next = new Promise((resolve) => {
    release = resolve;
  });
  const chained = prev.then(() => next, () => next);
  map.set(safeKey, chained);
  try {
    await prev.catch(() => {});
    return await fn();
  } finally {
    release();
    if (map.get(safeKey) === chained) {
      map.delete(safeKey);
    }
  }
}

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

function claimPrefix(code) {
  return "pair-claim:" + extractPairingCode(code) + ":";
}

function rateKey(accountId) {
  return "pair-rate:" + accountId;
}

export async function createPairingSession({ kv, accountId, deviceHint = {} }) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required for pairing sessions");
  if (!/^[a-zA-Z0-9_-]{12,80}$/.test(accountId)) {
    throw new Error("A valid accountId is required");
  }

  return withMapLock(pairingRateLocks, accountId, async () => {
    const rate = rateKey(accountId);
    const rateRaw = await kv.get(rate);
    const rateCount = rateRaw ? Number(rateRaw) || 0 : 0;
    if (rateCount >= 10) {
      throw new Error("Too many pairing sessions. Wait before creating another code.");
    }
    await kv.put(rate, String(rateCount + 1), { expirationTtl: 3600 });

    let code = randomCode();
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
      version: 1,
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
  });
}

/**
 * Rolls back a newly saved or updated device in KV if token issuance or pairing finalization fails.
 */
async function rollbackDeviceRegistration({ kv, device, accountId, previousDeviceState, rollbackDevice }) {
  try {
    if (typeof rollbackDevice === "function") {
      await rollbackDevice(device, previousDeviceState);
      return;
    }
    if (!device?.deviceId || !kv) return;
    if (previousDeviceState) {
      await kv.put(`device:${device.deviceId}`, JSON.stringify(previousDeviceState));
    } else {
      if (typeof kv.delete === "function") {
        await kv.delete(`device:${device.deviceId}`);
        if (accountId) {
          await kv.delete(`account-device:${accountId}:${device.deviceId}`);
        }
      }
    }
  } catch (_) {}
}

export async function redeemPairingSession({
  kv,
  code,
  deviceInput,
  saveDevice,
  rollbackDevice = null,
  issueDeviceToken,
  tokenSecret,
  coordinator = null,
  bypassIsolateLock = false,
  nowMs = Date.now()
}) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required for pairing sessions");
  const normalized = extractPairingCode(code);
  if (!/^[A-Z0-9]{8}$/.test(normalized)) {
    throw new Error("Invalid pairing code format");
  }

  // Fast-path in-isolate rejection unless simulating separate Worker isolates (`bypassIsolateLock: true`)
  if (!bypassIsolateLock) {
    if (activeRedemptionLocks.has(normalized)) {
      throw new Error("Pairing code has already been used");
    }
    activeRedemptionLocks.add(normalized);
  }

  const seq = (++claimSequenceCounter).toString(36).padStart(6, "0");
  const claimId = `${nowMs.toString(36)}_${seq}_${webCrypto.randomUUID().slice(0, 8)}`;
  const myClaimKey = `${claimPrefix(normalized)}${claimId}`;
  let wonClaim = false;

  try {
    const key = sessionKey(normalized);
    const raw = await kv.get(key);
    if (!raw) throw new Error("Pairing code is invalid or expired");

    let session;
    try {
      session = JSON.parse(raw);
    } catch (_) {
      throw new Error("Pairing session is corrupt");
    }

    if (session.expiresAt) {
      const expiresMs = Date.parse(session.expiresAt);
      if (!Number.isFinite(expiresMs) || expiresMs <= nowMs) {
        session.status = "expired";
        await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
        throw new Error("Pairing code is invalid or expired");
      }
    }

    if (session.status === "cancelled") {
      throw new Error("Pairing code has been cancelled or already been used");
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

    // Persist attempt increment before validating platform/deviceInput so brute-force attempts are counted
    await kv.put(key, JSON.stringify(session), { expirationTtl: SESSION_TTL_SECONDS });

    const platform = String(deviceInput?.platform || "").toLowerCase();
    if (!["windows", "macos", "linux", "android"].includes(platform)) {
      throw new Error("Unsupported pairing platform");
    }

    // Distributed atomic claim election across isolates (JARVIS-004):
    // 1. If a dedicated coordinator (Durable Object or Postgres RPC) is bound, acquire atomic claim there.
    if (coordinator && typeof coordinator.claimPairingCode === "function") {
      const coordRes = await coordinator.claimPairingCode(normalized, claimId, nowMs);
      if (!coordRes || !coordRes.claimed) {
        throw new Error("Pairing code has already been used");
      }
    }

    // 2. Write distinct per-attempt claim key to KV and verify election across concurrent isolates.
    await kv.put(
      myClaimKey,
      JSON.stringify({ claimId, code: normalized, claimedAt: nowMs }),
      { expirationTtl: 120 }
    );

    if (typeof kv.list === "function") {
      const listed = await kv.list({ prefix: claimPrefix(normalized) });
      const sortedClaimKeys = (listed?.keys || [])
        .map((k) => k.name)
        .filter(Boolean)
        .sort();
      if (sortedClaimKeys.length > 0 && sortedClaimKeys[0] !== myClaimKey) {
        if (typeof kv.delete === "function") {
          await kv.delete(myClaimKey).catch(() => {});
        }
        throw new Error("Pairing code has already been used");
      }
    }

    // Re-read session after claim barrier to ensure it was not cancelled or redeemed concurrently
    const recheckRaw = await kv.get(key);
    const recheckSession = recheckRaw ? JSON.parse(recheckRaw) : null;
    if (
      !recheckSession ||
      recheckSession.status !== "pending" ||
      (recheckSession.claimedBy && recheckSession.claimedBy !== claimId)
    ) {
      if (typeof kv.delete === "function") {
        await kv.delete(myClaimKey).catch(() => {});
      }
      throw new Error("Pairing code has already been used");
    }

    wonClaim = true;
    session.status = "redeeming";
    session.claimedBy = claimId;
    await kv.put(key, JSON.stringify(session), { expirationTtl: SESSION_TTL_SECONDS });

    const deviceId =
      String(deviceInput?.deviceId || "").trim() ||
      platform + "-" + webCrypto.randomUUID();

    // Capture previous device record if any so we can roll back cleanly if token issuance fails
    let previousDeviceState = null;
    try {
      const existingDevRaw = await kv.get(`device:${deviceId}`);
      if (existingDevRaw) previousDeviceState = JSON.parse(existingDevRaw);
    } catch (_) {}

    let device = null;
    let token = null;
    try {
      device = await saveDevice({
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

      token = await issueDeviceToken(device.deviceId, tokenSecret, 2592000, {
        ...(typeof device?.tokenVersion === "number" ? { tv: device.tokenVersion } : {})
      });
      if (!token || typeof token !== "string") {
        throw new Error("Failed to issue valid device token");
      }

      session.status = "redeemed";
      session.redeemedAt = new Date(nowMs).toISOString();
      session.redeemedDeviceId = device.deviceId;
      session.version = (session.version || 1) + 1;
      await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
    } catch (enrollOrTokenError) {
      // Rollback device creation & restore pending session if token issuance or finalization failed!
      if (device) {
        await rollbackDeviceRegistration({
          kv,
          device,
          accountId: session.accountId,
          previousDeviceState,
          rollbackDevice
        });
      }
      if (wonClaim) {
        session.status = "pending";
        delete session.claimedBy;
        await kv.put(key, JSON.stringify(session), { expirationTtl: SESSION_TTL_SECONDS }).catch(() => {});
        if (typeof kv.delete === "function") {
          await kv.delete(myClaimKey).catch(() => {});
        }
      }
      throw enrollOrTokenError;
    }

    return {
      ok: true,
      device,
      token,
      tokenType: "Bearer",
      accountId: session.accountId
    };
  } finally {
    if (!bypassIsolateLock) {
      activeRedemptionLocks.delete(normalized);
    }
  }
}

export async function getPairingSession({ kv, code, accountId, nowMs = Date.now() }) {
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
  const expired = session.expiresAt && Date.parse(session.expiresAt) <= nowMs;
  return {
    code: session.code,
    status: expired && session.status === "pending" ? "expired" : session.status,
    expiresAt: session.expiresAt,
    redeemedDeviceId: session.redeemedDeviceId || null
  };
}

export async function cancelPairingSession({ kv, code, accountId, nowMs = Date.now() }) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  const normalized = extractPairingCode(code);
  if (!/^[A-Z0-9]{8}$/.test(normalized)) {
    throw new Error("Invalid pairing code format");
  }

  return withMapLock(pairingCodeLocks, normalized, async () => {
    const key = sessionKey(normalized);
    const raw = await kv.get(key);
    if (!raw) return null;
    let session;
    try {
      session = JSON.parse(raw);
    } catch (_) {
      return null;
    }
    if (accountId && session.accountId !== accountId) {
      const err = new Error("Pairing session belongs to another account");
      err.status = 403;
      throw err;
    }
    if (session.status === "redeemed" || session.status === "redeeming") {
      const err = new Error("Pairing session has already been redeemed and cannot be cancelled");
      err.status = 409;
      throw err;
    }
    if (session.status === "pending") {
      // Also write a terminal claim blocker so any concurrent redemption across isolates is blocked
      const cancelBlockKey = `${claimPrefix(normalized)}000000_cancelled`;
      await kv.put(cancelBlockKey, JSON.stringify({ cancelled: true, cancelledAt: nowMs }), {
        expirationTtl: 120
      });
      session.status = "cancelled";
      session.cancelledAt = new Date(nowMs).toISOString();
      await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
    }
    return {
      code: session.code,
      status: session.status,
      cancelledAt: session.cancelledAt || null
    };
  });
}
