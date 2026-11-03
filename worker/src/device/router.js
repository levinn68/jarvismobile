import { issueAccountToken, issueDeviceToken, verifyAccountToken, verifyDeviceToken } from "./auth.js";
import { createAccount, getAccount, getDevice, listDevicesForAccount, saveDevice, touchDevice } from "./registry.js";
import { publicDevice } from "./policy.js";
import { enqueueDeviceCommand, listDeviceCommands, acknowledgeDeviceCommand } from "./command-service.js";

const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS"
  }
});

const MAX_DEVICE_REQUEST_BYTES = 128 * 1024;

async function readBody(request) {
  const declaredLength = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_DEVICE_REQUEST_BYTES) {
    const error = new Error("Request body is too large (maximum 131072 bytes)");
    error.status = 413;
    throw error;
  }
  if (!request.body) throw new Error("Request body must be valid JSON");

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_DEVICE_REQUEST_BYTES) {
        await reader.cancel();
        const error = new Error("Request body is too large (maximum 131072 bytes)");
        error.status = 413;
        throw error;
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error.status === 413) throw error;
    throw new Error("Request body could not be read");
  }

  try {
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    const parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("Request body must be a JSON object");
    }
    return parsed;
  } catch (error) {
    if (error.message === "Request body must be a JSON object") throw error;
    throw new Error("Request body must be valid JSON");
  }
}

function constantTimeEqual(leftValue, rightValue) {
  const left = String(leftValue || "");
  const right = String(rightValue || "");
  const length = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < length; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^ (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

const bearer = request => {
  const value = request.headers.get("Authorization") || "";
  if (value.length > 4096) return "";
  const match = value.trim().match(/^Bearer\\s+(\\S+)$/i);
  return match ? match[1] : "";
};

async function accountIsActive(kv, accountId) {
  const account = await getAccount(kv, accountId);
  return Boolean(account && account.status === "active");
}

async function activeDeviceForToken(kv, deviceId) {
  const device = await getDevice(kv, deviceId);
  if (!device) return { device: null, status: 404, error: "Device not registered" };
  if (!await accountIsActive(kv, device.accountId)) {
    return { device: null, status: 403, error: "Device account is inactive" };
  }
  return { device, status: 200, error: null };
}

export async function handleDeviceRequest(request, env, url) {
  const path = url.pathname;

  if (path === "/v1/accounts/create" && request.method === "POST") {
    if (!env.DEVICE_ENROLLMENT_SECRET || !env.DEVICE_TOKEN_SECRET || !env.DEVICE_REGISTRY) {
      return json({ ok: false, error: "Account enrollment is not configured." }, 503);
    }
    if (!constantTimeEqual(bearer(request), env.DEVICE_ENROLLMENT_SECRET)) {
      return json({ ok: false, error: "Unauthorized bootstrap secret" }, 401);
    }
    try {
      const accountId = "acct-" + crypto.randomUUID();
      const account = await createAccount(env.DEVICE_REGISTRY, accountId);
      const accountToken = await issueAccountToken(accountId, env.DEVICE_TOKEN_SECRET);
      return json({
        ok: true,
        account: { accountId, createdAt: account.createdAt },
        accountToken,
        tokenType: "Bearer",
        expiresIn: 31536000
      }, 201);
    } catch (error) {
      return json({ ok: false, error: error.message }, error.status || 400);
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
      const device = await saveDevice(env.DEVICE_REGISTRY, { ...input, accountId: accountClaims.sub });
      const token = await issueDeviceToken(device.deviceId, env.DEVICE_TOKEN_SECRET);
      return json({ ok: true, device: publicDevice(device), token, tokenType: "Bearer" }, 201);
    } catch (error) {
      return json({ ok: false, error: error.message }, error.status || 400);
    }
  }

  const match = path.match(/^\/v1\/devices\/([a-zA-Z0-9_-]{8,80})(?:\/(heartbeat|capabilities))?$/);
  if (match) {
    const [, id, action] = match;
    const claims = await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id);
    if (!claims) return json({ ok: false, error: "Unauthorized device token" }, 401);

    const state = await activeDeviceForToken(env.DEVICE_REGISTRY, id);
    if (!state.device) return json({ ok: false, error: state.error }, state.status);
    const device = state.device;

    if (action === "heartbeat" && request.method === "POST") {
      const updated = await touchDevice(env.DEVICE_REGISTRY, id);
      return json({ ok: true, device: publicDevice(updated) });
    }
    if (action === "capabilities" && request.method === "PUT") {
      try {
        const body = await readBody(request);
        const updated = await saveDevice(env.DEVICE_REGISTRY, { ...device, capabilities: body.capabilities });
        return json({ ok: true, device: publicDevice(updated) });
      } catch (error) {
        return json({ ok: false, error: error.message }, error.status || 400);
      }
    }
    return json({ ok: true, device: publicDevice(device) });
  }

  if (path === "/v1/devices" && request.method === "GET") {
    const claims = await verifyAccountToken(request, env.DEVICE_TOKEN_SECRET);
    if (!claims) return json({ ok: false, error: "Account token required" }, 401);
    if (!await accountIsActive(env.DEVICE_REGISTRY, claims.sub)) {
      return json({ ok: false, error: "Account is inactive" }, 403);
    }
    const devices = await listDevicesForAccount(env.DEVICE_REGISTRY, claims.sub);
    return json({ ok: true, account: { accountId: claims.sub }, devices: devices.map(publicDevice) });
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
      if (accountClaims && !await accountIsActive(env.DEVICE_REGISTRY, accountClaims.sub)) {
        return json({ ok: false, error: "Account is inactive" }, 403);
      }
      const state = await activeDeviceForToken(env.DEVICE_REGISTRY, id);
      if (!state.device) return json({ ok: false, error: state.error }, state.status);
      const device = state.device;
      if (accountClaims && device.accountId !== accountClaims.sub) {
        return json({ ok: false, error: "Device belongs to another account" }, 403);
      }
      const result = await enqueueDeviceCommand({
        kv: env.DEVICE_COMMANDS,
        device,
        input: body,
        context: { userConfirmed: false }
      });
      return json(result, result.ok ? 200 : 403);
    } catch (error) {
      return json({ ok: false, error: error.message }, error.status || 400);
    }
  }

  if (path === "/v1/device/commands" && request.method === "GET") {
    const id = url.searchParams.get("deviceId") || "";
    if (!await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id)) {
      return json({ ok: false, error: "Unauthorized" }, 401);
    }
    const state = await activeDeviceForToken(env.DEVICE_REGISTRY, id);
    if (!state.device) return json({ ok: false, error: state.error }, state.status);
    try {
      return json({ ok: true, commands: await listDeviceCommands({ kv: env.DEVICE_COMMANDS, deviceId: id }) });
    } catch (error) {
      return json({ ok: false, error: error.message }, 503);
    }
  }

  if (path === "/v1/device/commands/ack" && request.method === "POST") {
    try {
      const body = await readBody(request);
      const id = String(body.deviceId || "");
      if (!await verifyDeviceToken(request, env.DEVICE_TOKEN_SECRET, id)) {
        return json({ ok: false, error: "Unauthorized" }, 401);
      }
      const state = await activeDeviceForToken(env.DEVICE_REGISTRY, id);
      if (!state.device) return json({ ok: false, error: state.error }, state.status);
      const result = await acknowledgeDeviceCommand({
        kv: env.DEVICE_COMMANDS,
        deviceId: id,
        commandId: String(body.commandId || ""),
        result: body.result
      });
      return json(result, result.ok ? 200 : 404);
    } catch (error) {
      return json({ ok: false, error: error.message }, error.status || 400);
    }
  }

  return null;
}
