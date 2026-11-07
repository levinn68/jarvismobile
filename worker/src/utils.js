export const webCrypto = globalThis.crypto;
if (!webCrypto?.subtle) {
  throw new Error("Web Crypto API is required by the JARVIS Worker runtime");
}

const textEncoder = new TextEncoder();

export function boundedString(value, max = 500) {
  return String(value ?? "").trim().slice(0, max);
}

export function jsonResponse(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      "Cache-Control": "no-store",
      ...headers
    }
  });
}

export function redactSecrets(input) {
  const raw = String(input ?? "");
  return raw
    .replace(/([?&](?:key|api_key|token|access_token|refresh_token|secret)=)[^&\s"']+/gi, "$1[REDACTED]")
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, "$1[REDACTED]")
    .replace(/(X-Jarvis-Device-Token:\s*)[A-Za-z0-9._~+/-]+=*/gi, "$1[REDACTED]")
    .replace(/\b(sk-[A-Za-z0-9_-]{12,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{16,}|sb_publishable_[A-Za-z0-9_-]{10,}|sb_secret_[A-Za-z0-9_-]{10,})\b/g, "[REDACTED_KEY]");
}

export function safeError(error, max = 300) {
  return redactSecrets(error?.message || error || "Unknown error").slice(0, max);
}

export function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function canonicalJson(value) {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return "[" + value.map((item) => canonicalJson(item)).join(",") + "]";
  }
  const keys = Object.keys(value).sort();
  return "{" + keys.map((k) => JSON.stringify(k) + ":" + canonicalJson(value[k])).join(",") + "}";
}

export async function sha256Hex(input) {
  const digest = await webCrypto.subtle.digest("SHA-256", textEncoder.encode(String(input ?? "")));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function validateProviderJsonResponse(rawText, providerName = "provider") {
  if (typeof rawText !== "string" || !rawText.trim()) {
    const err = new Error(`${providerName} returned an empty response body`);
    err.status = 502;
    throw err;
  }
  let parsed;
  try {
    parsed = JSON.parse(rawText);
  } catch (_) {
    const err = new Error(`${providerName} returned malformed JSON`);
    err.status = 502;
    throw err;
  }
  if (!isPlainObject(parsed)) {
    const err = new Error(`${providerName} returned a non-object JSON payload`);
    err.status = 502;
    throw err;
  }
  return parsed;
}

export const DEFAULT_MAX_BODY_BYTES = 256 * 1024;

export async function readBoundedJson(request, maxBytes = DEFAULT_MAX_BODY_BYTES) {
  const contentLength = Number(request?.headers?.get?.("content-length") || 0);
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    const err = new Error(`Request payload exceeds ${maxBytes} bytes limit`);
    err.status = 413;
    throw err;
  }
  const raw = await request.text();
  const byteLength = textEncoder.encode(raw).byteLength;
  if (raw.length > maxBytes || byteLength > maxBytes) {
    const err = new Error(`Request payload exceeds ${maxBytes} bytes limit`);
    err.status = 413;
    throw err;
  }
  if (!raw.trim()) return {};
  try {
    const parsed = JSON.parse(raw);
    if (!isPlainObject(parsed)) {
      const err = new Error("Request body must be a JSON object");
      err.status = 400;
      throw err;
    }
    return parsed;
  } catch (error) {
    if (error.status) throw error;
    const err = new Error("Request body must be valid JSON");
    err.status = 400;
    throw err;
  }
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
