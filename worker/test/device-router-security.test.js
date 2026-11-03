import test from "node:test";
import assert from "node:assert/strict";
import { handleDeviceRequest } from "../src/device/router.js";

const secret = "test-only-secret-with-at-least-32-characters-long";

function request(method, path, token, body) {
  return new Request("https://jarvis.test" + path, {
    method,
    headers: {
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(body !== undefined ? { "Content-Type": "application/json" } : {})
    },
    ...(body !== undefined ? { body: typeof body === "string" ? body : JSON.stringify(body) } : {})
  });
}

async function route(req, env = {}, url = new URL(req.url)) {
  return handleDeviceRequest(req, env, url);
}

test("account creation fails closed when required bindings or secrets are missing", async () => {
  const req = request("POST", "/v1/accounts/create", "bootstrap", {});
  const response = await route(req, {
    DEVICE_ENROLLMENT_SECRET: "bootstrap",
    DEVICE_TOKEN_SECRET: secret
  });
  assert.equal(response.status, 503);
  assert.match((await response.json()).error, /not configured/i);
});

test("account creation rejects a wrong bootstrap secret", async () => {
  const req = request("POST", "/v1/accounts/create", "wrong-secret", {});
  const response = await route(req, {
    DEVICE_ENROLLMENT_SECRET: "correct-bootstrap-secret",
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: { async put() {} }
  });
  assert.equal(response.status, 401);
});

test("device enrollment rejects missing and invalid account tokens", async () => {
  const env = { DEVICE_TOKEN_SECRET: secret, DEVICE_REGISTRY: {} };
  const missing = await route(request("POST", "/v1/devices/enroll", null, {}), env);
  const invalid = await route(request("POST", "/v1/devices/enroll", "not-a-token", {}), env);
  assert.equal(missing.status, 401);
  assert.equal(invalid.status, 401);
});

test("device command route rejects malformed JSON without throwing", async () => {
  const req = request("POST", "/v1/device/command", "invalid-token", "{");
  const response = await route(req, { DEVICE_TOKEN_SECRET: secret, DEVICE_REGISTRY: {} });
  assert.equal(response.status, 400);
  assert.match((await response.json()).error, /valid JSON/i);
});

test("device queue endpoints fail closed for missing device identity or token", async () => {
  const env = { DEVICE_TOKEN_SECRET: secret, DEVICE_COMMANDS: {} };
  const poll = await route(request("GET", "/v1/device/commands?deviceId=device-test-0001", null), env);
  const ack = await route(request("POST", "/v1/device/commands/ack", null, {
    deviceId: "device-test-0001",
    commandId: "command-123"
  }), env);
  assert.equal(poll.status, 401);
  assert.equal(ack.status, 401);
});

test("unknown device API paths return null for the main router fallback", async () => {
  const response = await route(request("GET", "/v1/devices/unknown/path", "token"), {
    DEVICE_TOKEN_SECRET: secret
  });
  assert.equal(response, null);
});
