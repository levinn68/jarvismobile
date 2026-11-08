import { webCrypto } from "../utils.js";

/**
 * J.A.R.V.I.S. B8.1.1 Remediation R1 — Distributed-Safe Request Guard, Supabase Auth & Rate Limiter
 * Remediates JARVIS-005:
 * - Prevents concurrent rate-limit bypass across multiple Worker isolates using:
 *   1. Dedicated atomic coordinator / Durable Object / Postgres RPC when provided (`options.coordinator` or `kv.atomicIncrement`), AND
 *   2. Distributed per-request slot keys (`rate-slot:v2:<scope>:<subject>:<window>:<orderKey>`) when `kv.list` is available,
 *      so concurrent isolates writing to KV never overwrite each other's counter increments, AND
 *   3. In-isolate mutex queue (`withRateLimitLock`) for single-isolate serialization and minimal KV mocks.
 */

const MAX_WINDOW_SECONDS = 60;
const rateLimitLocks = new Map();
let slotSequenceCounter = 0;

async function withRateLimitLock(key, fn) {
  const prev = rateLimitLocks.get(key) || Promise.resolve();
  let release;
  const next = new Promise((resolve) => {
    release = resolve;
  });
  const chained = prev.then(() => next, () => next);
  rateLimitLocks.set(key, chained);
  try {
    await prev.catch(() => {});
    return await fn();
  } finally {
    release();
    if (rateLimitLocks.get(key) === chained) {
      rateLimitLocks.delete(key);
    }
  }
}

export function bearerToken(request) {
  const header = request?.headers?.get?.("Authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

export function clientIpFromRequest(request) {
  const cfIp = request?.headers?.get?.("CF-Connecting-IP") || "";
  if (cfIp.trim()) return cfIp.trim().slice(0, 64);
  const forwarded = request?.headers?.get?.("X-Forwarded-For") || "";
  const first = forwarded.split(",")[0]?.trim() || "";
  return first ? first.slice(0, 64) : "anonymous";
}

/**
 * Detailed Supabase user verification that distinguishes between missing/invalid
 * tokens (401), unconfigured environment (503), and upstream Supabase Auth
 * timeouts/outages (504/502).
 */
export async function authenticateSupabaseUserDetailed(request, env, timeoutMs = 8000) {
  const token = bearerToken(request);
  if (!token) {
    return { ok: false, status: 401, reason: "missing_token", error: "A valid Supabase session is required." };
  }
  if (!env?.SUPABASE_URL || !env?.SUPABASE_ANON_KEY) {
    return { ok: false, status: 503, reason: "unconfigured", error: "Supabase authentication is not configured on the Worker." };
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Math.max(1000, Number(timeoutMs) || 8000));
  try {
    const response = await fetch(env.SUPABASE_URL.replace(/\/$/, "") + "/auth/v1/user", {
      method: "GET",
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: "Bearer " + token,
        Accept: "application/json"
      },
      signal: controller.signal
    });
    if (response.status === 401 || response.status === 403) {
      return { ok: false, status: 401, reason: "invalid_token", error: "Supabase session is invalid or expired." };
    }
    if (!response.ok) {
      return {
        ok: false,
        status: response.status >= 500 ? 502 : 401,
        reason: response.status >= 500 ? "upstream_error" : "invalid_token",
        error: response.status >= 500 ? `Supabase Auth upstream HTTP ${response.status}` : "A valid Supabase session is required."
      };
    }
    const user = await response.json().catch(() => null);
    if (!user || !/^[a-f0-9-]{36}$/i.test(String(user?.id || ""))) {
      return { ok: false, status: 401, reason: "invalid_identity", error: "Supabase user identity is invalid." };
    }
    return {
      ok: true,
      status: 200,
      user: {
        id: String(user.id).toLowerCase(),
        email: typeof user.email === "string" ? user.email : null
      }
    };
  } catch (error) {
    const isAbort = error?.name === "AbortError" || /abort|timed out|timeout/i.test(String(error?.message || error));
    return {
      ok: false,
      status: isAbort ? 504 : 502,
      reason: isAbort ? "supabase_timeout" : "supabase_unreachable",
      error: isAbort ? "Supabase authentication timed out." : "Unable to reach Supabase authentication service."
    };
  } finally {
    clearTimeout(timeout);
  }
}

export async function authenticateSupabaseUser(request, env) {
  const result = await authenticateSupabaseUserDetailed(request, env);
  return result.ok ? result.user : null;
}

/**
 * JARVIS-005 — Distributed-safe rate limiting across Worker isolates.
 */
export async function enforceAccountRateLimit(
  kv,
  scope,
  userId,
  limit,
  nowMs = Date.now(),
  options = {}
) {
  if (!kv) return { allowed: false, reason: "rate_limit_storage_unavailable" };
  const numericLimit = Math.max(0, Math.floor(Number(limit) || 0));
  const retryAfter = Math.max(1, MAX_WINDOW_SECONDS - Math.floor((nowMs / 1000) % MAX_WINDOW_SECONDS));
  const window = Math.floor(nowMs / (MAX_WINDOW_SECONDS * 1000));
  const windowResetAt = (window + 1) * MAX_WINDOW_SECONDS * 1000;

  if (numericLimit <= 0) {
    return { allowed: false, remaining: 0, retryAfter, resetAt: windowResetAt };
  }

  const safeScope = String(scope || "default").replace(/[^a-zA-Z0-9_-]/g, "");
  const safeSubject = String(userId || "anonymous").replace(/[^a-zA-Z0-9_.:-]/g, "").slice(0, 80);
  const key = "rate:v1:" + safeScope + ":" + safeSubject + ":" + window;
  const slotPrefix = "rate-slot:v2:" + safeScope + ":" + safeSubject + ":" + window + ":";

  const executeLimitCheck = async () => {
    // 1. If a dedicated atomic coordinator or atomicIncrement method is provided, use it directly
    const coordinator = options?.coordinator || kv?.rateLimitCoordinator;
    if (coordinator && typeof coordinator.incrementAndCheck === "function") {
      return coordinator.incrementAndCheck({ key, scope: safeScope, subject: safeSubject, window, limit: numericLimit, retryAfter, resetAt: windowResetAt });
    }
    if (typeof kv.atomicIncrement === "function") {
      const newCount = await kv.atomicIncrement(key, 1, { expirationTtl: MAX_WINDOW_SECONDS * 2 });
      if (newCount > numericLimit) {
        return { allowed: false, remaining: 0, retryAfter, resetAt: windowResetAt };
      }
      return { allowed: true, remaining: Math.max(0, numericLimit - newCount), resetAt: windowResetAt };
    }

    // 2. Check existing counter value first
    const rawCurrent = await kv.get(key);
    const currentCount = rawCurrent ? Number(rawCurrent) || 0 : 0;
    if (currentCount >= numericLimit) {
      return {
        allowed: false,
        remaining: 0,
        retryAfter,
        resetAt: windowResetAt
      };
    }

    // 3. When kv.list is available, write a distinct per-request slot key so concurrent isolates
    //    never lose increments to read-modify-write overwrites on the shared summary key.
    if (typeof kv.list === "function") {
      const seq = (++slotSequenceCounter).toString(36).padStart(6, "0");
      const slotId = `${nowMs.toString(36)}_${seq}_${webCrypto.randomUUID().slice(0, 8)}`;
      const mySlotKey = `${slotPrefix}${slotId}`;
      await kv.put(mySlotKey, "1", { expirationTtl: MAX_WINDOW_SECONDS * 2 });

      const listed = await kv.list({ prefix: slotPrefix });
      const sortedSlots = (listed?.keys || [])
        .map((k) => k.name)
        .filter(Boolean)
        .sort();
      const myRank = sortedSlots.indexOf(mySlotKey);
      const effectiveUsed = Math.max(currentCount + 1, myRank >= 0 ? myRank + 1 : sortedSlots.length);

      if ((myRank >= 0 && myRank >= numericLimit) || effectiveUsed > numericLimit) {
        await kv.put(key, String(Math.max(numericLimit, sortedSlots.length)), {
          expirationTtl: MAX_WINDOW_SECONDS * 2
        });
        return {
          allowed: false,
          remaining: 0,
          retryAfter,
          resetAt: windowResetAt
        };
      }

      await kv.put(key, String(Math.min(numericLimit, Math.max(currentCount + 1, sortedSlots.length))), {
        expirationTtl: MAX_WINDOW_SECONDS * 2
      });
      return {
        allowed: true,
        remaining: Math.max(0, numericLimit - effectiveUsed),
        resetAt: windowResetAt
      };
    }

    // 4. Fallback for minimal KV mocks without .list()
    await kv.put(key, String(currentCount + 1), { expirationTtl: MAX_WINDOW_SECONDS * 2 });
    return {
      allowed: true,
      remaining: Math.max(0, numericLimit - currentCount - 1),
      resetAt: windowResetAt
    };
  };

  if (options?.bypassIsolateLock === true) {
    return executeLimitCheck();
  }
  return withRateLimitLock(key, executeLimitCheck);
}

export function requestOriginAllowed(request, env) {
  const origin = request?.headers?.get?.("Origin");
  if (!origin) return true;
  const allowed = String(env?.JARVIS_ALLOWED_ORIGINS || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  return allowed.length === 0 || allowed.includes(origin);
}

export function corsHeadersForRequest(request, env) {
  const origin = request?.headers?.get?.("Origin");
  const allowed = String(env?.JARVIS_ALLOWED_ORIGINS || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  let allowOrigin = "*";
  if (allowed.length > 0) {
    allowOrigin = origin && allowed.includes(origin) ? origin : "null";
  }
  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Request-Id, X-Jarvis-Device-Token, X-Idempotency-Key",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  };
}

const PROMPT_INJECTION_PATTERNS = [
  /ignore\s+(?:all\s+|any\s+)?(?:previous|prior|above|system|developer)\s+instructions/i,
  /abaikan\s+(?:semua\s+|seluruh\s+)?(?:instruksi|aturan)\s+(?:sebelumnya|sistem|di\s+atas)/i,
  /(?:reveal|print|show|dump|leak|tampilkan|bocorkan)\s+(?:your\s+|the\s+)?(?:system\s+prompt|developer\s+prompt|api\s*key|secret|token|device_token_secret)/i,
  /system\s*override|developer\s*mode\s*enabled|jailbreak/i
];

export function detectPromptInjectionSignals(text) {
  const raw = String(text || "");
  const matched = [];
  for (const pattern of PROMPT_INJECTION_PATTERNS) {
    if (pattern.test(raw)) matched.push(pattern.source);
  }
  return {
    suspicious: matched.length > 0,
    matchCount: matched.length
  };
}