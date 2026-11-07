import { webCrypto } from "../utils.js";
import {
  PairingCoordinator,
  globalPairingCoordinator
} from "./pairing-coordinator.js";
import { globalRateLimiter, checkRateLimiter } from "../security/distributed-rate-limiter.js";

/**
 * Short-lived, single-use pairing sessions for QR / manual code flow.
 * Codes are bound to the account that created them, enforce strict atomic
 * single-redemption against concurrent requests across all Worker isolates,
 * and cannot enroll devices into another account.
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

function createPairingCoordinatorClient(stub) {
  const invoke = async (path, payload = {}) => {
    const response = await stub.fetch(new Request("https://pairing-coordinator" + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    }));
    return await response.json();
  };
  return {
    acquireLock: (payload) => invoke("/acquire", payload),
    commit: (payload) => invoke("/commit", payload),
    rollback: (payload) => invoke("/rollback", payload),
    cancel: (payload) => invoke("/cancel", payload),
    getState: () => invoke("/state", {})
  };
}

function resolvePairingCoordinator(env, pairingCoordinator, code) {
  const normalized = extractPairingCode(code);
  if (env?.PAIRING_COORDINATOR && typeof env.PAIRING_COORDINATOR.idFromName === "function") {
    const id = env.PAIRING_COORDINATOR.idFromName(normalized);
    const stub = env.PAIRING_COORDINATOR.get(id);
    return typeof stub.acquireLock === "function" ? stub : createPairingCoordinatorClient(stub);
  }
  if (pairingCoordinator && typeof pairingCoordinator.get === "function") {
    const id = pairingCoordinator.idFromName ? pairingCoordinator.idFromName(normalized) : { name: normalized };
    return pairingCoordinator.get(id);
  }
  return globalPairingCoordinator.get({ name: normalized });
}

export async function createPairingSession({ kv, accountId, deviceHint = {}, env = {} }) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required for pairing sessions");
  if (!/^[a-zA-Z0-9_-]{12,80}$/.test(accountId)) {
    throw new Error("A valid accountId is required");
  }

  // Atomic Distributed Rate Limiting via Durable Objects / Coordinator
  const rateLimiter = env?.RATE_LIMITER && typeof env.RATE_LIMITER.idFromName === "function"
    ? env.RATE_LIMITER.get(env.RATE_LIMITER.idFromName(`pair-create:${accountId}`))
    : globalRateLimiter.get({ name: `pair-create:${accountId}` });

  const rateCheck = await checkRateLimiter(rateLimiter, {
    scope: "pairing_create",
    subject: accountId,
    limit: 10,
    windowSeconds: 3600
  });

  if (!rateCheck.allowed) {
    throw new Error("Too many pairing sessions. Wait before creating another code.");
  }

  // Soft fallback backup in KV
  const rate = rateKey(accountId);
  const rateRaw = await kv.get(rate);
  const rateCount = rateRaw ? Number(rateRaw) || 0 : 0;
  await kv.put(rate, String(rateCount + 1), { expirationTtl: 3600 });

  let code = randomCode();
  // Collision avoidance
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
  tokenSecret,
  rollbackDevice = null,
  nowMs = Date.now(),
  env = {},
  pairingCoordinator = null
}) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required for pairing sessions");
  const normalized = extractPairingCode(code);
  if (!/^[A-Z0-9]{8}$/.test(normalized)) {
    throw new Error("Invalid pairing code format");
  }

  const coordinator = resolvePairingCoordinator(env, pairingCoordinator, normalized);

  // 1. Acquire Distributed Atomic Lock via Durable Object across isolates
  const lockResult = await coordinator.acquireLock({
    code: normalized,
    deviceId: deviceInput?.deviceId || null,
    nowMs,
    ttlSeconds: 300
  });

  if (!lockResult.ok) {
    if (lockResult.status === "locked") {
      throw new Error("Pairing code locked after too many attempts");
    }
    // Concurrency conflict or already redeemed
    throw new Error("Pairing code has already been used");
  }

  const leasePayload = {
    fencingToken: lockResult.fencingToken,
    deviceId: deviceInput?.deviceId || null
  };
  const rollbackCoordinator = (payload = {}) =>
    coordinator.rollback({ ...payload, ...leasePayload });

  let deviceRegistered = false;
  let registeredDeviceId = null;
  let coordinatorCommitted = false;
  let sessionStateValid = false;
  let session;

  try {
    const key = sessionKey(normalized);
    const raw = await kv.get(key);
    if (!raw) {
      await rollbackCoordinator({ code: normalized, isAttemptFailed: false });
      throw new Error("Pairing code is invalid or expired");
    }

    try {
      session = JSON.parse(raw);
    } catch (_) {
      await rollbackCoordinator({ code: normalized, isAttemptFailed: false });
      throw new Error("Pairing session is corrupt");
    }

    if (session.expiresAt) {
      const expiresMs = Date.parse(session.expiresAt);
      if (!Number.isFinite(expiresMs) || expiresMs <= nowMs) {
        session.status = "expired";
        await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
        await rollbackCoordinator({ code: normalized, isAttemptFailed: false });
        throw new Error("Pairing code is invalid or expired");
      }
    }

    if (session.status !== "pending") {
      await rollbackCoordinator({ code: normalized, isAttemptFailed: false });
      throw new Error("Pairing code has already been used");
    }

    session.attempts = (session.attempts || 0) + 1;
    if (session.attempts > MAX_ATTEMPTS) {
      session.status = "locked";
      await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
      await rollbackCoordinator({ code: normalized, isAttemptFailed: true, maxAttempts: MAX_ATTEMPTS });
      throw new Error("Pairing code locked after too many attempts");
    }

    // Persist attempt count before validating device input so brute force burns attempts.
    await kv.put(key, JSON.stringify(session), { expirationTtl: SESSION_TTL_SECONDS });
    sessionStateValid = true;

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

    deviceRegistered = true;
    registeredDeviceId = device.deviceId;

    const token = await issueDeviceToken(device.deviceId, tokenSecret, 2592000, {
      ...(typeof device?.tokenVersion === "number" ? { tv: device.tokenVersion } : {})
    });

    // Commit the fenced lease before marking the KV session redeemed.
    const commitResult = await coordinator.commit({
      code: normalized,
      deviceId: device.deviceId,
      fencingToken: lockResult.fencingToken,
      nowMs
    });
    if (!commitResult.ok) {
      if (typeof rollbackDevice === "function") {
        await rollbackDevice(device.deviceId, session.accountId).catch(() => {});
        deviceRegistered = false;
      }
      throw new Error(commitResult.error || "Pairing lease expired before commit");
    }
    coordinatorCommitted = true;

    // Durable Object state is authoritative for single-use redemption.
    session.status = "redeemed";
    session.redeemedAt = new Date(nowMs).toISOString();
    session.redeemedDeviceId = device.deviceId;
    await kv.put(key, JSON.stringify(session), { expirationTtl: 60 }).catch(() => {});

    return {
      ok: true,
      device,
      token,
      tokenType: "Bearer",
      accountId: session.accountId
    };
  } catch (error) {
    // If device registration did not complete, roll back distributed coordinator
    // so recovery or legitimate retry is possible without leaving session in corrupt state
    if (!coordinatorCommitted) {
      if (deviceRegistered && registeredDeviceId && typeof rollbackDevice === "function") {
        await rollbackDevice(registeredDeviceId, session?.accountId).catch(() => {});
      }
      await rollbackCoordinator({
        code: normalized,
        isAttemptFailed: sessionStateValid,
        maxAttempts: MAX_ATTEMPTS
      }).catch(() => {});
    }
    throw error;
  }
}

export async function getPairingSession({ kv, code, accountId, nowMs = Date.now(), env = {} }) {
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

export async function cancelPairingSession({ kv, code, accountId, nowMs = Date.now(), env = {} }) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV binding is required");
  const normalized = extractPairingCode(code);
  if (!/^[A-Z0-9]{8}$/.test(normalized)) {
    throw new Error("Invalid pairing code format");
  }
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

  // Also notify Distributed Coordinator
  const coordinator = resolvePairingCoordinator(env, null, normalized);
  await coordinator.cancel({ code: normalized }).catch(() => {});

  if (session.status === "pending") {
    session.status = "cancelled";
    session.cancelledAt = new Date(nowMs).toISOString();
    await kv.put(key, JSON.stringify(session), { expirationTtl: 60 });
  }
  return {
    code: session.code,
    status: session.status,
    cancelledAt: session.cancelledAt || null
  };
}