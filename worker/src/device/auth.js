const encoder = new TextEncoder();

function base64UrlEncode(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function base64UrlDecode(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
    throw new Error("Invalid token encoding");
  }
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function sign(value, secret) {
  if (typeof secret !== "string" || secret.length < 32) {
    throw new Error("DEVICE_TOKEN_SECRET must contain at least 32 characters");
  }
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  return base64UrlEncode(new Uint8Array(
    await crypto.subtle.sign("HMAC", key, encoder.encode(value))
  ));
}

async function issueToken(claims, secret, ttlSeconds) {
  if (!Number.isInteger(ttlSeconds) || ttlSeconds <= 0) {
    throw new Error("Token lifetime must be a positive integer");
  }
  const now = Math.floor(Date.now() / 1000);
  const payload = base64UrlEncode(encoder.encode(JSON.stringify({
    ...claims,
    iat: now,
    exp: now + ttlSeconds,
  })));
  return payload + "." + await sign(payload, secret);
}

async function verifyToken(request, secret, scope, expectedSubject) {
  if (!secret || typeof secret !== "string") return null;

  const authorization = request.headers.get("Authorization") || "";
  if (authorization.length > 4096) return null;
  const match = authorization.trim().match(/^Bearer\s+(\S+)$/i);
  if (!match) return null;

  const [payload, signature, ...extra] = match[1].split(".");
  if (!payload || !signature || extra.length || !/^[A-Za-z0-9_-]+$/.test(signature)) {
    return null;
  }

  try {
    const expected = await sign(payload, secret);
    if (signature.length !== expected.length) return null;

    // Constant-time comparison for equal-length signatures.
    let difference = 0;
    for (let index = 0; index < signature.length; index += 1) {
      difference |= signature.charCodeAt(index) ^ expected.charCodeAt(index);
    }
    if (difference !== 0) return null;

    const claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(payload)));
    const now = Math.floor(Date.now() / 1000);
    if (
      !claims ||
      typeof claims !== "object" ||
      !Number.isInteger(claims.exp) ||
      !Number.isInteger(claims.iat) ||
      claims.exp <= now ||
      claims.iat > now + 60 ||
      claims.scope !== scope ||
      typeof claims.sub !== "string" ||
      (expectedSubject && claims.sub !== expectedSubject)
    ) {
      return null;
    }
    return claims;
  } catch (_) {
    return null;
  }
}

export const issueDeviceToken = (deviceId, secret, ttlSeconds = 2592000) =>
  issueToken({ sub: deviceId, scope: "device" }, secret, ttlSeconds);

export const verifyDeviceToken = (request, secret, expectedDeviceId) =>
  verifyToken(request, secret, "device", expectedDeviceId);

export const issueAccountToken = (accountId, secret, ttlSeconds = 31536000) =>
  issueToken({ sub: accountId, scope: "account" }, secret, ttlSeconds);

export const verifyAccountToken = (request, secret) =>
  verifyToken(request, secret, "account");
