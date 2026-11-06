const MAX_WINDOW_SECONDS = 60;

export function bearerToken(request) {
  const header = request?.headers?.get?.("Authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

export function clientIpFromRequest(request) {
  const cfIp = request?.headers?.get?.("CF-Connecting-IP") || "";
  if (cfIp.trim()) return cfIp.trim().slice(0, 64);
  const forwarded = request?.headers?.get?.("X-Forwarded-For") || "";  const first = forwarded.split(",")[0]?.trim() || "";
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

export async function enforceAccountRateLimit(kv, scope, userId, limit, nowMs = Date.now()) {
  if (!kv) return { allowed: false, reason: "rate_limit_storage_unavailable" };
  const safeScope = String(scope || "default").replace(/[^a-zA-Z0-9_-]/g, "");
  const safeSubject = String(userId || "anonymous").replace(/[^a-zA-Z0-9_.:-]/g, "").slice(0, 80);
  const window = Math.floor(nowMs / (MAX_WINDOW_SECONDS * 1000));
  const key = "rate:v1:" + safeScope + ":" + safeSubject + ":" + window;
  const current = Number((await kv.get(key)) || 0);
  if (!Number.isFinite(current) || current >= limit) {
    return {
      allowed: false,
      retryAfter: Math.max(1, MAX_WINDOW_SECONDS - Math.floor((nowMs / 1000) % MAX_WINDOW_SECONDS))
    };
  }
  await kv.put(key, String(current + 1), { expirationTtl: MAX_WINDOW_SECONDS * 2 });
  return { allowed: true, remaining: Math.max(0, limit - current - 1) };
}

function allowedBrowserOrigins(env = {}) {
  return String(env?.JARVIS_ALLOWED_ORIGINS || "")
    .split(",").map((item) => item.trim()).filter(Boolean);
}

export function requestOriginAllowed(request, env = {}) {
  const origin = request?.headers?.get?.("Origin");
  if (!origin) return true; // Native clients still require JWT/device authentication.
  return allowedBrowserOrigins(env).includes(origin);
}

export function corsHeadersForRequest(request, env = {}) {
  const origin = request?.headers?.get?.("Origin");
  const allowed = allowedBrowserOrigins(env);
  const headers = {
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Request-Id, X-Jarvis-Device-Token, X-Idempotency-Key",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  };
  if (origin && allowed.includes(origin)) headers["Access-Control-Allow-Origin"] = origin;
  return headers;
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