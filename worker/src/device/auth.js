import { webCrypto } from "../utils.js";

const encoder = new TextEncoder();

function b64(bytes) {
  let value = "";
  for (const byte of bytes) value += String.fromCharCode(byte);
  return btoa(value).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function unb64(value) {
  const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

export function constantTimeEqual(a, b) {
  const strA = String(a ?? "");
  const strB = String(b ?? "");
  const bytesA = encoder.encode(strA);
  const bytesB = encoder.encode(strB);
  const maxLen = Math.max(bytesA.length, bytesB.length);
  let diff = bytesA.length ^ bytesB.length;
  for (let i = 0; i < maxLen; i++) {
    diff |= (bytesA[i] || 0) ^ (bytesB[i] || 0);
  }
  return diff === 0 && bytesA.length > 0;
}

async function sign(value, secret) {
  const key = await webCrypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return b64(new Uint8Array(await webCrypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

async function issueToken(claims, secret, ttlSeconds) {
  if (!secret) throw new Error("DEVICE_TOKEN_SECRET is not configured");
  const now = Math.floor(Date.now() / 1000);
  const payload = b64(
    encoder.encode(
      JSON.stringify({
        ...claims,
        jti: claims.jti || webCrypto.randomUUID(),
        iat: now,
        exp: now + ttlSeconds
      })
    )
  );
  return payload + "." + (await sign(payload, secret));
}

export function extractDeviceOrBearerToken(request) {
  const authHeader = (request?.headers?.get?.("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  if (authHeader) return authHeader;
  return (request?.headers?.get?.("X-Jarvis-Device-Token") || "").trim();
}

async function verifyToken(request, secret, scope, expectedSubject) {
  if (!secret) return null;
  const token =
    scope === "device"
      ? extractDeviceOrBearerToken(request)
      : (request?.headers?.get?.("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
  const [payload, signature, ...extra] = token.split(".");
  if (!payload || !signature || extra.length) return null;
  const expected = await sign(payload, secret);
  if (!constantTimeEqual(signature, expected)) return null;
  try {
    const claims = JSON.parse(new TextDecoder().decode(unb64(payload)));
    const nowSec = Date.now() / 1000;
    if (
      typeof claims.exp !== "number" ||
      claims.exp < nowSec ||
      claims.scope !== scope ||
      (expectedSubject && claims.sub !== expectedSubject)
    ) {
      return null;
    }
    return claims;
  } catch (_) {
    return null;
  }
}

export const issueDeviceToken = (deviceId, secret, ttlSeconds = 2592000, extraClaims = {}) =>
  issueToken({ sub: deviceId, scope: "device", ...extraClaims }, secret, ttlSeconds);

export const verifyDeviceToken = (request, secret, expectedDeviceId) =>
  verifyToken(request, secret, "device", expectedDeviceId);

export const issueAccountToken = (accountId, secret, ttlSeconds = 31536000, extraClaims = {}) =>
  issueToken({ sub: accountId, scope: "account", ...extraClaims }, secret, ttlSeconds);

export const verifyAccountToken = (request, secret) =>
  verifyToken(request, secret, "account");
