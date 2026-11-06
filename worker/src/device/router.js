import { issueAccountToken, issueDeviceToken, verifyAccountToken, verifyDeviceToken, constantTimeEqual } from "./auth.js";
import { createAccount, getAccount, getDevice, listDevicesForAccount, saveDevice, touchDevice, removeDevice } from "./registry.js";
import { publicDevice, authorizeCapability } from "./policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand, getDeviceCommand, purgeDeviceCommands } from "./command-service.js";
import { createPairingSession, redeemPairingSession, getPairingSession, cancelPairingSession } from "./pairing.js";
import { createDeviceSession } from "./session.js";
import { createDeviceSkills } from "../skills/device-skills.js";
import { webCrypto, jsonResponse, readBoundedJson, safeError } from "../utils.js";
import { corsHeadersForRequest, authenticateSupabaseUserDetailed, enforceAccountRateLimit, clientIpFromRequest, requestOriginAllowed } from "../security/request-guard.js";
import { logger } from "../core/logger.js";

export const deviceSessions = createDeviceSession({ ttlMs: 90000 });

const bearer = (request) => (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();

async function readBody(request, maxBytes = 131072) {
  return readBoundedJson(request, maxBytes);
}

async function isDeviceAccountActive(env, device) {
  if (!device?.accountId) return false;
  const account = await getAccount(env.DEVICE_REGISTRY, device.accountId);
  return account?.status === "active";
}

function isTokenVersionValid(device, claims) {
  if (!device || !claims) return false;
  if (typeof claims.tv === "number" && typeof device.tokenVersion === "number") {
    return claims.tv === device.tokenVersion;
  }
  return true;
}

export function createWorkerDeviceSkills(env) {
  return createDeviceSkills({
    authorize: async ({ deviceId, action }) => {
      const device = await getDevice(env.DEVICE_REGISTRY, deviceId);
      if (!device) return { allowed: false, reason: "Device not registered" };
      const capability = action.startsWith("files.") ? "files.read"
        : action.startsWith("apps.launch") ? "apps.launch"
        : action === "apps.list" ? "apps.list"
        : action === "system.status" ? "system.read"
        : action === "notifications.list" ? "notifications.read"
        : action === "contacts.list" ? "contacts.read"
        : action === "calendar.list" ? "calendar.read"
        : action === "screen.read" ? "screen.read"
        : "screen.control";
      return authorizeCapability(device, capability, { userConfirmed: false });
    },
    requestDeviceCommand: async ({ deviceId, action, params }) => {
      const device = await getDevice(env.DEVICE_REGISTRY, deviceId);
      if (!device) return { ok: false, error: "Device not registered" };
      return enqueueDeviceCommand({
        kv: env.DEVICE_COMMANDS,
        device,        input: { action, params },
        context: { userConfirmed: false }
      });
    }
  });
}

export async function handleDeviceRequest(request, env, url) {
  const path = url.pathname;
  const json = (data, status = 200) =>
    jsonResponse(data, status, corsHeadersForRequest(request, env));

  if (path.startsWith("/v1/") && !requestOriginAllowed(request, env)) {
    return json({ ok: false, error: "Origin is not allowed." }, 403);
  }

  if (path === "/v1/accounts/google" && request.method === "POST") {
    if (!env.SUPABASE_URL || !env.SUPABASE_ANON_KEY || !env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Google-linked device enrollment is not configured on the Worker." }, 503);
    }
    const accessToken = bearer(request);
    if (!accessToken) return json({ ok: false, error: "A Supabase Google session is required" }, 401);

    const authResult = await authenticateSupabaseUserDetailed(request, env, 8000);
    if (!authResult.ok) {
      return json({ ok: false, error: authResult.error, reason: authResult.reason }, authResult.status);
    }

    try {
      const userId = authResult.user.id;
      const rate = await enforceAccountRateLimit(env.DEVICE_REGISTRY, "accounts_google", userId, 30);
      if (!rate.allowed) {
        return json({ ok: false, error: "Rate limit exceeded.", retryAfter: rate.retryAfter || 60 }, 429);
      }
      const accountId = "acct-" + userId;
      let account = await getAccount(env.DEVICE_REGISTRY, accountId);
      if (!account) account = await createAccount(env.DEVICE_REGISTRY, accountId);
      if (account.status !== "active") return json({ ok: false, error: "JARVIS account is inactive" }, 403);
      const accountToken = await issueAccountToken(accountId, env.DEVICE_TOKEN_SECRET, 3600);
      return json(
        {
          ok: true,
          account: { accountId, createdAt: account.createdAt },
          accountToken,
          tokenType: "Bearer",
          expiresIn: 3600,
          provider: "supabase-google"
        },
        200
      );
    } catch (e) {
      logger.error("account_google_enrollment_failed", { error: safeError(e, 240) });
      return json({ ok: false, error: safeError(e, 200) }, 502);
    }
  }

  if (path === "/v1/accounts/create" && request.method === "POST") {
    if (String(env.DISABLE_LEGACY_ACCOUNT_CREATE || "").toLowerCase() === "true") {
      return json({ ok: false, error: "Legacy bootstrap account creation is disabled. Use /v1/accounts/google." }, 403);
    }
    if (!env.DEVICE_ENROLLMENT_SECRET || !env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Account enrollment is not configured." }, 503);
    }
    if (!constantTimeEqual(bearer(request), env.DEVICE_ENROLLMENT_SECRET)) {
      return json({ ok: false, error: "Unauthorized bootstrap secret" }, 401);
    }
    let enrollmentStage = "initializing";
    try {
      enrollmentStage = "generate_account_id";
      const accountId = "acct-" + webCrypto.randomUUID();
      enrollmentStage = "write_account_to_kv";
      const account = await createAccount(env.DEVICE_REGISTRY, accountId);
      enrollmentStage = "issue_account_token";
      const accountToken = await issueAccountToken(accountId, env.DEVICE_TOKEN_SECRET);
      return json(
        {
          ok: true,
          account: { accountId, createdAt: account.createdAt },
          accountToken,
          tokenType: "Bearer",
          expiresIn: 31536000
        },
        201
      );
    } catch (e) {
      const errorName = String(e?.name || "Error").slice(0, 80);
      const errorMessage = safeError(e, 240);
      logger.error("account_bootstrap_enrollment_failed", {
        stage: enrollmentStage,
        name: errorName,
        message: errorMessage
      });
      return json({ ok: false, error: errorMessage, stage: enrollmentStage }, 400);
    }
  }

  if (path === "/v1/devices/enroll" && request.method === "POST") {
    if (!env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Device enrollment is not configured." }, 503);
    }
    const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    if (!accountClaims) return json({ ok: false, error: "A valid JARVIS account token is required" }, 401);
    try {
      const input = await readBody(request);
      const existing = input?.deviceId ? await getDevice(env.DEVICE_REGISTRY, String(input.deviceId).trim()) : null;
      if (existing && existing.accountId && existing.accountId !== accountClaims.sub) {
        return json({ ok: false, error: "Device ID belongs to another account and cannot be claimed" }, 403);
      }
      const nextTokenVersion = (existing?.tokenVersion || 0) + 1;
      const device = await saveDevice(env.DEVICE_REGISTRY, {
        ...input,
        accountId: accountClaims.sub,
        tokenVersion: nextTokenVersion
      });
      deviceSessions.touch(device.deviceId, { platform: device.platform, accountId: device.accountId });
      const token = await issueDeviceToken(device.deviceId, env.DEVICE_TOKEN_SECRET, 2592000, {
        tv: device.tokenVersion
      });
      return json({ ok: true, device: publicDevice(device), token, tokenType: "Bearer" }, 201);
    } catch (e) {
      const status = e?.status === 413 ? 413 : e?.status === 403 ? 403 : 400;
      return json({ ok: false, error: safeError(e, 240) }, status);
    }
  }

  // --- QR / manual pairing sessions ---
  if (path === "/v1/pairing/sessions" && request.method === "POST") {
    if (!env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Pairing is not configured." }, 503);
    }
    const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    if (!accountClaims) return json({ ok: false, error: "A valid JARVIS account token is required" }, 401);
    const account = await getAccount(env.DEVICE_REGISTRY, accountClaims.sub);
    if (!account || account.status !== "active") return json({ ok: false, error: "Account is inactive" }, 403);
    try {
      const body = await readBody(request).catch(() => ({}));
      const session = await createPairingSession({
        kv: env.DEVICE_REGISTRY,
        accountId: accountClaims.sub,
        deviceHint: body?.deviceHint || {}
      });
      return json({ ok: true, ...session }, 201);
    } catch (e) {
      return json({ ok: false, error: safeError(e, 240) }, e?.status === 413 ? 413 : 400);
    }
  }

  if (path === "/v1/pairing/redeem" && request.method === "POST") {
    if (!env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Pairing is not configured." }, 503);
    }
    const clientIp = clientIpFromRequest(request);
    const rateSubject = clientIp !== "anonymous" ? clientIp : "anon-fallback";
    const rateLimit = clientIp !== "anonymous" ? 20 : 30;
    const ipRate = await enforceAccountRateLimit(env.DEVICE_REGISTRY, "pair_redeem_ip", rateSubject, rateLimit);
    if (!ipRate.allowed) {
      return json({ ok: false, error: "Too many pairing redeem attempts. Try again shortly.", retryAfter: ipRate.retryAfter || 60 }, 429);
    }
    try {
      const body = await readBody(request);
      const result = await redeemPairingSession({
        kv: env.DEVICE_REGISTRY,
        code: body.code,
        deviceInput: body,
        saveDevice: async (input) => {
          const existing = input?.deviceId ? await getDevice(env.DEVICE_REGISTRY, String(input.deviceId).trim()) : null;
          const nextTokenVersion = (existing?.tokenVersion || 0) + 1;
          return saveDevice(env.DEVICE_REGISTRY, { ...input, tokenVersion: nextTokenVersion });
        },
        issueDeviceToken: (deviceId, secret, ttl = 2592000, extraClaims = {}) =>
          issueDeviceToken(deviceId, secret, ttl, extraClaims),
        tokenSecret: env.DEVICE_TOKEN_SECRET
      });
      deviceSessions.touch(result.device.deviceId, {
        platform: result.device.platform,
        accountId: result.accountId
      });
      return json(
        {
          ok: true,
          device: publicDevice(result.device),
          token: result.token,
          tokenType: result.tokenType,
          accountId: result.accountId
        },
        201
      );
    } catch (e) {
      const status = e?.status === 413 ? 413 : e?.status === 403 ? 403 : 400;
      return json({ ok: false, error: safeError(e, 240) }, status);
    }
  }

  if (path === "/v1/pairing/sessions/status" && request.method === "GET") {
    if (!env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Pairing is not configured." }, 503);
    }
    const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    if (!accountClaims) return json({ ok: false, error: "A valid JARVIS account token is required" }, 401);
    const code = url.searchParams.get("code") || "";
    const session = await getPairingSession({ kv: env.DEVICE_REGISTRY, code, accountId: accountClaims.sub });
    if (!session) return json({ ok: false, error: "Pairing session not found" }, 404);
    return json({ ok: true, session });
  }

  if (path === "/v1/pairing/sessions/cancel" && request.method === "POST") {
    if (!env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Pairing is not configured." }, 503);
    }
    const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    if (!accountClaims) return json({ ok: false, error: "A valid JARVIS account token is required" }, 401);
    try {
      const body = await readBody(request);
      const cancelled = await cancelPairingSession({
        kv: env.DEVICE_REGISTRY,
        code: body.code,
        accountId: accountClaims.sub
      });
      if (!cancelled) return json({ ok: false, error: "Pairing session not found" }, 404);
      return json({ ok: true, session: cancelled });
    } catch (e) {
      return json({ ok: false, error: safeError(e, 240) }, e?.status || 400);
    }
  }

  const match = path.match(/^\/v1\/devices\/([a-zA-Z0-9_-]{8,80})(?:\/(heartbeat|capabilities))?$/);
  if (match) {
    const [, id, action] = match;
    if (!action && request.method === "DELETE") {
      const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
      if (!accountClaims) return json({ ok: false, error: "Account token required to revoke a device" }, 401);
      const existing = await getDevice(env.DEVICE_REGISTRY, id);
      if (!existing) return json({ ok: false, error: "Device not registered" }, 404);
      if (existing.accountId !== accountClaims.sub) {
        return json({ ok: false, error: "Device belongs to another account" }, 403);
      }
      try {
        await removeDevice(env.DEVICE_REGISTRY, id, accountClaims.sub);
        if (env.DEVICE_COMMANDS) {
          await purgeDeviceCommands({ kv: env.DEVICE_COMMANDS, deviceId: id }).catch(() => {});
        }
        deviceSessions.remove(id);
        return json({ ok: true, deviceId: id, revoked: true });
      } catch (e) {
        return json({ ok: false, error: safeError(e, 240) }, 500);
      }
    }
    const claims = await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id);
    if (!claims) return json({ ok: false, error: "Unauthorized device token" }, 401);
    const device = await getDevice(env.DEVICE_REGISTRY, id);
    if (!device) return json({ ok: false, error: "Device not registered" }, 404);
    if (!isTokenVersionValid(device, claims)) {
      return json({ ok: false, error: "Device token has been superseded or revoked" }, 401);
    }
    if (!(await isDeviceAccountActive(env, device))) {
      return json({ ok: false, error: "Device account is inactive" }, 403);
    }
    if (action === "heartbeat" && request.method === "POST") {
      const updated = await touchDevice(env.DEVICE_REGISTRY, id);
      deviceSessions.touch(id, { platform: updated.platform, accountId: updated.accountId });
      return json({ ok: true, device: publicDevice(updated), online: true });
    }
    if (action === "capabilities" && request.method === "PUT") {
      try {
        const body = await readBody(request);
        const updated = await saveDevice(env.DEVICE_REGISTRY, { ...device, capabilities: body.capabilities });
        deviceSessions.touch(id, { platform: updated.platform, accountId: updated.accountId });
        return json({ ok: true, device: publicDevice(updated) });
      } catch (e) {
        return json({ ok: false, error: safeError(e, 240) }, e?.status === 413 ? 413 : 400);
      }
    }
    return json({ ok: true, device: publicDevice(device), online: deviceSessions.online(id) });
  }

  if (path === "/v1/devices" && request.method === "GET") {
    const claims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    if (!claims) return json({ ok: false, error: "Account token required" }, 401);
    const account = await getAccount(env.DEVICE_REGISTRY, claims.sub);
    if (!account || account.status !== "active") return json({ ok: false, error: "Account is inactive" }, 403);
    const devices = await listDevicesForAccount(env.DEVICE_REGISTRY, claims.sub);
    return json({
      ok: true,
      account: { accountId: claims.sub },
      devices: devices.map((d) => ({ ...publicDevice(d), online: deviceSessions.online(d.deviceId) }))
    });
  }

  if (path === "/v1/device/command" && request.method === "POST") {
    try {
      const body = await readBody(request);
      const id = String(body.deviceId || "");
      const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
      const deviceClaims = accountClaims ? null : await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id);
      if (!accountClaims && !deviceClaims) {
        return json({ ok: false, error: "Account or matching device token required" }, 401);
      }
      const device = await getDevice(env.DEVICE_REGISTRY, id);
      if (!device) return json({ ok: false, error: "Device not registered" }, 404);
      if (accountClaims && device.accountId !== accountClaims.sub) {
        return json({ ok: false, error: "Device belongs to another account" }, 403);
      }
      if (deviceClaims && !isTokenVersionValid(device, deviceClaims)) {
        return json({ ok: false, error: "Device token has been superseded or revoked" }, 401);
      }
      if (!(await isDeviceAccountActive(env, device))) {
        return json({ ok: false, error: "Device account is inactive" }, 403);
      }
      const headerIdemKey = (request.headers.get("X-Idempotency-Key") || "").trim();
      const result = await enqueueDeviceCommand({
        kv: env.DEVICE_COMMANDS,
        device,
        input: body,
        context: { userConfirmed: false, idempotencyKey: headerIdemKey || undefined }
      });
      return json(result, result.ok ? 200 : 403);
    } catch (e) {
      return json({ ok: false, error: safeError(e, 240) }, e?.status === 413 ? 413 : 400);
    }
  }

  if (path === "/v1/device/command/status" && request.method === "GET") {
    const id = url.searchParams.get("deviceId") || "";
    const commandId = url.searchParams.get("commandId") || "";
    const accountClaims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    const deviceClaims = accountClaims ? null : await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id);
    if (!accountClaims && !deviceClaims) {
      return json({ ok: false, error: "Account or matching device token required" }, 401);
    }
    const device = await getDevice(env.DEVICE_REGISTRY, id);
    if (!device) return json({ ok: false, error: "Device not registered" }, 404);
    if (accountClaims && device.accountId !== accountClaims.sub) {
      return json({ ok: false, error: "Device belongs to another account" }, 403);
    }
    if (deviceClaims && !isTokenVersionValid(device, deviceClaims)) {
      return json({ ok: false, error: "Device token has been superseded or revoked" }, 401);
    }
    if (!(await isDeviceAccountActive(env, device))) {
      return json({ ok: false, error: "Device account is inactive" }, 403);
    }
    try {
      const command = await getDeviceCommand({ kv: env.DEVICE_COMMANDS, deviceId: id, commandId });
      return command ? json({ ok: true, command }) : json({ ok: false, error: "Command not found or expired" }, 404);
    } catch (e) {
      return json({ ok: false, error: safeError(e, 240) }, 503);
    }
  }

  if (path === "/v1/device/commands" && request.method === "GET") {
    const id = url.searchParams.get("deviceId") || "";
    const deviceClaims = await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id);
    if (!deviceClaims) {
      return json({ ok: false, error: "Unauthorized" }, 401);
    }
    const device = await getDevice(env.DEVICE_REGISTRY, id);
    if (!device) return json({ ok: false, error: "Device has been revoked" }, 404);
    if (!isTokenVersionValid(device, deviceClaims)) {
      return json({ ok: false, error: "Device token has been superseded or revoked" }, 401);
    }
    if (!(await isDeviceAccountActive(env, device))) {
      return json({ ok: false, error: "Device account is inactive" }, 403);
    }
    deviceSessions.touch(id, { platform: device.platform, accountId: device.accountId });
    try {
      return json({ ok: true, commands: await listDeviceCommands({ kv: env.DEVICE_COMMANDS, deviceId: id }) });
    } catch (e) {
      return json({ ok: false, error: safeError(e, 240) }, 503);
    }
  }

  if (path === "/v1/device/commands/ack" && request.method === "POST") {
    try {
      const body = await readBody(request);
      const id = String(body.deviceId || "");
      const deviceClaims = await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id);
      if (!deviceClaims) {
        return json({ ok: false, error: "Unauthorized" }, 401);
      }
      const device = await getDevice(env.DEVICE_REGISTRY, id);
      if (!device) return json({ ok: false, error: "Device has been revoked" }, 404);
      if (!isTokenVersionValid(device, deviceClaims)) {
        return json({ ok: false, error: "Device token has been superseded or revoked" }, 401);
      }
      if (!(await isDeviceAccountActive(env, device))) {
        return json({ ok: false, error: "Device account is inactive" }, 403);
      }
      deviceSessions.touch(id, { platform: device.platform, accountId: device.accountId });
      const result = await acknowledgeDeviceCommand({
        kv: env.DEVICE_COMMANDS,
        deviceId: id,
        commandId: String(body.commandId || ""),
        result: body.result
      });
      const status = result.ok ? 200 : result.outcome === "failed" ? 403 : 404;
      return json(result, status);
    } catch (e) {
      return json({ ok: false, error: safeError(e, 240) }, e?.status === 413 ? 413 : 400);
    }
  }

  return null;
}