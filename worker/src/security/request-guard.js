import { globalRateLimiter, checkRateLimiter } from "./distributed-rate-limiter.js";
export {
  isBlockedIp,
  isBlockedIpv4,
  isBlockedIpv6,
  isBlockedHostname,
  resolveHostAddresses,
  validateSafeEndpoint,
  validateCustomProviderEndpoint,
  safeFetchWithDnsRebindingGuard
} from "./ssrf-guard.js";

const MAX_WINDOW_SECONDS = 60;

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
 * Distributed Rate Limiting (Cloudflare Durable Objects & atomic coordination) — B8.1.1 R2
 * Ensures rate limits cannot be bypassed by hopping isolates or sending concurrent bursts.
 */
export async function enforceAccountRateLimit(kvOrEnv, scope, userId, limit, nowMs = Date.now(), windowSeconds = MAX_WINDOW_SECONDS) {
  const safeScope = String(scope || "default").replace(/[^a-zA-Z0-9_-]/g, "");
  const safeSubject = String(userId || "anonymous").replace(/[^a-zA-Z0-9_.:-]/g, "").slice(0, 100);
  const partitionKey = `${safeScope}:${safeSubject}`;

  // Check if kvOrEnv provides Durable Objects binding RATE_LIMITER
  let rateLimiterStub;
  if (kvOrEnv?.RATE_LIMITER && typeof kvOrEnv.RATE_LIMITER.idFromName === "function") {
    const id = kvOrEnv.RATE_LIMITER.idFromName(partitionKey);
    rateLimiterStub = kvOrEnv.RATE_LIMITER.get(id);
  } else {
    // Shared atomic coordinator across all isolates
    rateLimiterStub = globalRateLimiter.get({ name: partitionKey });
  }

  const safeWindowSeconds = Math.max(1, Number(windowSeconds) || MAX_WINDOW_SECONDS);
  const result = await checkRateLimiter(rateLimiterStub, {
    scope: safeScope,
    subject: safeSubject,
    limit,
    windowSeconds: safeWindowSeconds,
    nowMs
  });

  // Also sync to KV if KV is available (for observability / KV metrics)
  const kv = kvOrEnv?.put ? kvOrEnv : kvOrEnv?.DEVICE_REGISTRY;
  if (kv && typeof kv.put === "function") {
    const window = Math.floor(nowMs / (safeWindowSeconds * 1000));
    const kvKey = "rate:v1:" + safeScope + ":" + safeSubject + ":" + window;
    await kv.put(kvKey, String(result.count || 1), { expirationTtl: safeWindowSeconds * 2 }).catch(() => {});
  }

  return {
    allowed: result.allowed,
    remaining: result.remaining,
    retryAfter: result.retryAfter
  };
}

export async function enforceUserRateLimit(env, userId, scope = "user_api", limit = 60, windowSeconds = 60) {
  return await enforceAccountRateLimit(env, `user_${scope}`, userId, limit, Date.now(), windowSeconds);
}

export async function enforceDeviceRateLimit(env, deviceId, scope = "device_poll", limit = 120, windowSeconds = 60) {
  return await enforceAccountRateLimit(env, `device_${scope}`, deviceId, limit, Date.now(), windowSeconds);
}

export async function enforceIpRateLimit(env, clientIp, scope = "ip_general", limit = 100, windowSeconds = 60) {
  return await enforceAccountRateLimit(env, `ip_${scope}`, clientIp, limit, Date.now(), windowSeconds);
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