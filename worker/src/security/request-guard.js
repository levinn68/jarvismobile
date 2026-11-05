const MAX_WINDOW_SECONDS = 60;

export function bearerToken(request) {
  const header = request?.headers?.get?.("Authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

export async function authenticateSupabaseUser(request, env) {
  const token = bearerToken(request);
  if (!token || !env?.SUPABASE_URL || !env?.SUPABASE_ANON_KEY) return null;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
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
    if (!response.ok) return null;
    const user = await response.json();
    if (!/^[a-f0-9-]{36}$/i.test(String(user?.id || ""))) return null;
    return { id: String(user.id), email: typeof user.email === "string" ? user.email : null };
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

export async function enforceAccountRateLimit(kv, scope, userId, limit, nowMs = Date.now()) {
  if (!kv) return { allowed: false, reason: "rate_limit_storage_unavailable" };
  const window = Math.floor(nowMs / (MAX_WINDOW_SECONDS * 1000));
  const key = "rate:v1:" + scope + ":" + userId + ":" + window;
  const current = Number(await kv.get(key) || 0);
  if (!Number.isFinite(current) || current >= limit) {
    return { allowed: false, retryAfter: MAX_WINDOW_SECONDS - Math.floor((nowMs / 1000) % MAX_WINDOW_SECONDS) };
  }
  await kv.put(key, String(current + 1), { expirationTtl: MAX_WINDOW_SECONDS * 2 });
  return { allowed: true, remaining: Math.max(0, limit - current - 1) };
}

export function requestOriginAllowed(request, env) {
  const origin = request?.headers?.get?.("Origin");
  if (!origin) return true;
  const allowed = String(env?.JARVIS_ALLOWED_ORIGINS || "")
    .split(",").map((item) => item.trim()).filter(Boolean);
  return allowed.length === 0 || allowed.includes(origin);
}
