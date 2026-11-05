export const webCrypto = globalThis.crypto;
if (!webCrypto?.subtle) {
  throw new Error("Web Crypto API is required by the JARVIS Worker runtime");
}

export function boundedString(value, max = 500) {
  return String(value ?? "").trim().slice(0, max);
}

export function jsonResponse(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...headers }
  });
}

export function safeError(error, max = 300) {
  return String(error?.message || error || "Unknown error").slice(0, max);
}

export function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export const COMMAND_TTL_MS = 5 * 60 * 1000;
export const MAX_FUTURE_SKEW_MS = 60 * 1000;

export function parseIsoTimestampMs(value) {
  if (typeof value !== "string" || !value.trim()) return Number.NaN;
  const ms = Date.parse(value.trim());
  return Number.isFinite(ms) ? ms : Number.NaN;
}

export function isCommandTimestampFresh(createdAt, nowMs = Date.now(), ttlMs = COMMAND_TTL_MS, maxFutureSkewMs = MAX_FUTURE_SKEW_MS) {
  const createdMs = parseIsoTimestampMs(createdAt);
  if (!Number.isFinite(createdMs)) return false;
  const ageMs = nowMs - createdMs;
  return ageMs >= -maxFutureSkewMs && ageMs <= ttlMs;
}