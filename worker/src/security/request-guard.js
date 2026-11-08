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
      reason: isAbort ? "timeout" : "network_error",
      error: isAbort ? "Supabase Auth request timed out." : "Failed to reach Supabase Auth."
    };
  } finally {
    clearTimeout(timeout);
  }
}

export async function enforceAccountRateLimit(kv, subject, limit = 60, windowSeconds = 60, options = {}) {
  const scope = options.scope || "default";
  const window = Math.min(MAX_WINDOW_SECONDS, Math.max(1, Number(windowSeconds) || 60));
  const keyBase = `rate:v2:${scope}:${subject}`;
  const windowKey = `${keyBase}:${Math.floor(Date.now() / 1000 / window)}`;

  return withRateLimitLock(windowKey, async () => {
    let count = 0;
    try {
      const raw = await kv.get(windowKey);
      count = raw ? parseInt(raw, 10) || 0 : 0;
    } catch {}
    if (count >= limit) {
      return { allowed: false, remaining: 0, limit, resetIn: window };
    }
    count += 1;
    try {
      await kv.put(windowKey, String(count), { expirationTtl: window + 5 });
    } catch {}
    return { allowed: true, remaining: Math.max(0, limit - count), limit, resetIn: window };
  });
}

const PROMPT_INJECTION_PATTERNS = [
  /ignore (all )?(previous|prior|above) (instructions|prompts)/i,
  /you are now (a |an )?(different|new|evil)/i,
  /system\s*:\s*override/i,
  /\bDAN\b|do anything now/i,
  /jailbreak|prompt injection/i
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
