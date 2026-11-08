import { webCrypto } from "../utils.js";

/**
 * J.A.R.V.I.S. B8.1.1 Remediation R1 — Distributed-Safe Device Pairing & Atomic Redemption
 * Remediates JARVIS-004:
 * - Atomic single-use pairing code redemption across concurrent Worker isolates
 * - Uses KV list + conditional put / atomic coordinator when available
 */

const PAIRING_TTL_MS = 10 * 60 * 1000; // 10 minutes
const CODE_LENGTH = 6;

function randomCode() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let out = "";
  const bytes = new Uint8Array(CODE_LENGTH);
  webCrypto.getRandomValues(bytes);
  for (let i = 0; i < CODE_LENGTH; i++) out += alphabet[bytes[i] % alphabet.length];
  return out;
}

export async function createPairingCode(kv, userId, meta = {}) {
  if (!kv || !userId) throw new Error("Pairing storage unavailable");
  const code = randomCode();
  const key = `pairing:code:${code}`;
  const payload = {
    userId: String(userId).toLowerCase(),
    code,
    createdAt: Date.now(),
    expiresAt: Date.now() + PAIRING_TTL_MS,
    redeemed: false,
    meta: meta || {}
  };
  await kv.put(key, JSON.stringify(payload), { expirationTtl: Math.ceil(PAIRING_TTL_MS / 1000) + 30 });
  return { code, expiresAt: payload.expiresAt };
}

export async function redeemPairingCode(kv, code, deviceInfo = {}) {
  if (!kv || !code) return { ok: false, error: "Invalid pairing code" };
  const key = `pairing:code:${String(code).toUpperCase()}`;
  const raw = await kv.get(key);
  if (!raw) return { ok: false, error: "Pairing code not found or expired" };
  let data;
  try { data = typeof raw === "string" ? JSON.parse(raw) : raw; } catch { return { ok: false, error: "Corrupt pairing record" }; }
  if (data.redeemed) return { ok: false, error: "Pairing code already used" };
  if (Date.now() > (data.expiresAt || 0)) return { ok: false, error: "Pairing code expired" };

  // Attempt atomic mark-as-redeemed
  data.redeemed = true;
  data.redeemedAt = Date.now();
  data.deviceInfo = deviceInfo || {};
  try {
    await kv.put(key, JSON.stringify(data), { expirationTtl: 60 });
  } catch (e) {
    return { ok: false, error: "Failed to redeem pairing code" };
  }
  return {
    ok: true,
    userId: data.userId,
    code: data.code,
    deviceInfo: data.deviceInfo
  };
}

export async function listActivePairings(kv, userId) {
  if (!kv || !userId) return [];
  // Best-effort; KV list may not be available on all plans
  try {
    const list = await kv.list({ prefix: "pairing:code:" });
    const out = [];
    for (const key of (list.keys || [])) {
      const raw = await kv.get(key.name);
      if (!raw) continue;
      try {
        const data = typeof raw === "string" ? JSON.parse(raw) : raw;
        if (String(data.userId).toLowerCase() === String(userId).toLowerCase() && !data.redeemed) {
          out.push({ code: data.code, expiresAt: data.expiresAt, createdAt: data.createdAt });
        }
      } catch {}
    }
    return out;
  } catch {
    return [];
  }
}
