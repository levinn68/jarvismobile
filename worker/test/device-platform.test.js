import test from "node:test";
import assert from "node:assert/strict";

import {
  issueAccountToken,
  issueDeviceToken,
  verifyAccountToken,
  verifyDeviceToken
} from "../src/device/auth.js";
import { normalizeDevice, validateCommand } from "../src/device/protocol.js";

const secret = "test-only-secret-with-at-least-32-characters-long";

function requestWithToken(token) {
  return new Request("https://jarvis.test", {
    headers: { Authorization: "Bearer " + token }
  });
}

function base64UrlEncode(value) {
  return btoa(value).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

test("account token round-trips with Bearer authorization", async () => {
  const token = await issueAccountToken("acct-test-account-123", secret);
  assert.match(token, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  const claims = await verifyAccountToken(requestWithToken(token), secret);
  assert.equal(claims?.sub, "acct-test-account-123");
  assert.equal(claims?.scope, "account");
});

test("device token is scoped to its device", async () => {
  const token = await issueDeviceToken("android-test-device-01", secret);
  const request = requestWithToken(token);
  assert.equal((await verifyDeviceToken(request, secret, "android-test-device-01"))?.scope, "device");
  assert.equal(await verifyDeviceToken(request, secret, "android-another-device"), null);
  assert.equal(await verifyAccountToken(request, secret), null);
});

test("invalid, tampered, expired, and missing Bearer tokens are rejected", async () => {
  const token = await issueAccountToken("acct-test-account-123", secret);
  const [payload, signature] = token.split(".");
  const tampered = payload + "." + (signature[0] === "A" ? "B" : "A") + signature.slice(1);
  assert.equal(await verifyAccountToken(requestWithToken(tampered), secret), null);
  assert.equal(await verifyAccountToken(requestWithToken("not-a-token"), secret), null);
  assert.equal(await verifyAccountToken(new Request("https://jarvis.test"), secret), null);

  const now = Math.floor(Date.now() / 1000);
  const expiredPayload = base64UrlEncode(JSON.stringify({
    sub: "acct-test-account-123",
    scope: "account",
    iat: now - 120,
    exp: now - 60
  }));
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signatureBytes = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(expiredPayload))
  );
  let binary = "";
  for (const byte of signatureBytes) binary += String.fromCharCode(byte);
  const expiredSignature = btoa(binary)
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
  assert.equal(
    await verifyAccountToken(requestWithToken(expiredPayload + "." + expiredSignature), secret),
    null
  );
});

test("secret length is enforced when issuing tokens", async () => {
  await assert.rejects(() => issueAccountToken("acct-test-account-123", "short"));
});

test("Android package names accept normal dotted identifiers", () => {
  const command = validateCommand({
    action: "apps.launch",
    params: { packageName: "com.example.my_app" }
  });
  assert.equal(command.params.packageName, "com.example.my_app");
});

test("Android commands reject malformed package names and unknown parameters", () => {
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { packageName: "com..example" }
  }));
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { packageName: "com.example", shell: "whoami" }
  }));
  assert.throws(() => validateCommand({ action: "shell.exec", params: { command: "id" } }));
});

test("Windows connector validates app and file commands", () => {
  const app = validateCommand({ action: "apps.launch", params: { appId: "QWERTY_123" } }, "windows");
  assert.equal(app.params.appId, "QWERTY_123");

  const list = validateCommand({
    action: "files.list",
    params: { rootIndex: 1, relativePath: "Reports/2026" }
  }, "windows");
  assert.equal(list.params.rootIndex, 1);
  assert.equal(list.params.relativePath, "Reports/2026");

  const read = validateCommand({
    action: "files.read",
    params: { rootIndex: 0, relativePath: "report.txt" }
  }, "windows");
  assert.equal(read.params.relativePath, "report.txt");
});

test("Windows connector rejects invalid app IDs, paths, and unknown commands", () => {
  assert.throws(() => validateCommand({
    action: "apps.launch",
    params: { appId: "../powershell" }
  }, "windows"));
  assert.throws(() => validateCommand({
    action: "files.read",
    params: { relativePath: "report.txt" }
  }, "windows"));
  assert.throws(() => validateCommand({
    action: "shell.exec",
    params: { command: "whoami" }
  }, "windows"));
});

test("macOS and Linux remain blocked until their native connectors exist", () => {
  for (const platform of ["macos", "linux"]) {
    assert.throws(
      () => validateCommand({ action: "apps.list", params: {} }, platform),
      /No command connector is registered/
    );
  }
});

test("device normalization accepts the supported desktop platforms", () => {
  for (const platform of ["android", "windows", "macos", "linux", "ios", "web"]) {
    const device = normalizeDevice({
      deviceId: "device-test-0001",
      accountId: "acct-test-account-123",
      platform
    });
    assert.equal(device.platform, platform);
  }
});
