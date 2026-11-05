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

export function detectPromptInjectionSignals(text) {
  const q = String(text || "").toLowerCase();
  const signals = [];
  if (/(ignore previous|system prompt|you are now|jailbreak|dan mode)/i.test(q)) signals.push("instruction_override");
  if (/(api[_\s-]?key|secret|password|token)/i.test(q) && /(print|show|reveal|leak)/i.test(q)) signals.push("secret_exfil");
  return { detected: signals.length > 0, signals };
}
