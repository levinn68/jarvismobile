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
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${env.SUPABASE_URL}/auth/v1/user`, {
      method: "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        apikey: env.SUPABASE_ANON_KEY,
      },
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403) {
      return { ok: false, status: 401, reason: "invalid_token", error: "Supabase session is invalid or expired." };
    }
    if (!response.ok) {
      return { ok: false, status: response.status >= 500 ? 502 : response.status, reason: "upstream", error: `Supabase Auth returned ${response.status}` };
    }
    const user = await response.json().catch(() => null);
    if (!user?.id) {
      return { ok: false, status: 401, reason: "invalid_token", error: "Supabase session is invalid." };
    }
    return { ok: true, user, token };
  } catch (error) {
    if (error?.name === "AbortError") {
      return { ok: false, status: 504, reason: "timeout", error: "Supabase Auth timed out." };
    }
    return { ok: false, status: 502, reason: "network", error: String(error?.message || error).slice(0, 200) };
  } finally {
    clearTimeout(timer);
  }
}

/** Backward-compatible wrapper used by older callers. */
export async function authenticateSupabaseUser(request, env) {
  const result = await authenticateSupabaseUserDetailed(request, env);
  return result.ok ? result.user : null;
}

export async function enforceAccountRateLimit(kv, scope, userId, limit, nowMs = Date.now()) {
  if (!kv || !userId || !limit) return true;
  const key = `rate:${scope}:${userId}`;
  const raw = await kv.get(key);
  let bucket = { count: 0, window: nowMs };
  if (raw) {
    try { bucket = JSON.parse(raw); } catch (_) {}
  }
  if (nowMs - (bucket.window || 0) > MAX_WINDOW_SECONDS * 1000) {
    bucket = { count: 0, window: nowMs };
  }
  bucket.count = (bucket.count || 0) + 1;
  await kv.put(key, JSON.stringify(bucket), { expirationTtl: MAX_WINDOW_SECONDS * 2 });
  return bucket.count <= limit;
}

export function requestOriginAllowed(request, env) {
  return true;
}

export function corsHeadersForRequest(request, env) {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS, GET, PUT, DELETE",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Request-Id, X-Jarvis-Device-Token, X-Idempotency-Key",
  };
}

export function detectPromptInjectionSignals(text) {
  const q = String(text || "").toLowerCase();
  const signals = [];
  if (/(ignore previous|system prompt|you are now|jailbreak|dan mode)/i.test(q)) signals.push("instruction_override");
  if (/(api[_\s-]?key|secret|password|token)/i.test(q) && /(print|show|reveal|leak)/i.test(q)) signals.push("secret_exfil");
  return { detected: signals.length > 0, signals };
}
