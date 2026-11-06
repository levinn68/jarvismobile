import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { handleDeviceRequest } from "../src/device/router.js";
import { createAccount } from "../src/device/registry.js";
import { issueAccountToken } from "../src/device/auth.js";

import * as winAgent from "../../desktop/windows/src/agent.js";
import * as unixAgent from "../../desktop/unix/src/agent.js";
import { loadCredentials as loadWinCreds } from "../../desktop/windows/src/credentials.js";
import { loadCredentials as loadUnixCreds } from "../../desktop/unix/src/credentials.js";

function memoryKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.get(key) ?? null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    },
    async list({ prefix = "", limit = 1000, cursor } = {}) {
      const all = [...store.keys()].filter((k) => k.startsWith(prefix)).sort();
      const offset = cursor ? Number(cursor) : 0;
      const keys = all.slice(offset, offset + limit).map((name) => ({ name }));
      const next = offset + keys.length;
      return {
        keys,
        list_complete: next >= all.length,
        ...(next < all.length ? { cursor: String(next) } : {})
      };
    }
  };
}

test("B5 E2E Integration: Windows, macOS, and Linux Desktop Agents pair, poll, execute, ACK, and handle revocation against B4 Cloudflare Worker", async () => {
  const registry = memoryKv();
  const commands = memoryKv();
  const secret = "b5-integration-secret-with-more-than-32-chars";
  const accountId = "acct-55555555-5555-4555-8555-555555555555";
  await createAccount(registry, accountId);
  const accountToken = await issueAccountToken(accountId, secret);

  const env = {
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: commands
  };

  // In-memory fetch bridge directly to B4 Cloudflare Worker handleDeviceRequest
  const workerFetch = async (url, init = {}) => {
    const req = new Request(String(url), init);
    const res = await handleDeviceRequest(req, env, new URL(String(url)));
    return res || new Response(JSON.stringify({ ok: false, error: "Not found" }), { status: 404 });
  };

  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-b5-e2e-"));
  const winCredFile = path.join(tmpDir, "win-creds.json");
  const macCredFile = path.join(tmpDir, "mac-creds.json");
  const linuxCredFile = path.join(tmpDir, "linux-creds.json");
  const allowedDir = path.join(tmpDir, "approved");
  await fs.mkdir(allowedDir, { recursive: true });
  await fs.writeFile(path.join(allowedDir, "architecture.md"), "# JARVIS B5 Architecture");

  const noOsKeychain = { async isAvailable() { return false; } };

  try {
    // 1. Android creates QR pairing session for Windows Desktop Agent
    const createSessRes = await workerFetch("https://worker.test/v1/pairing/sessions", {
      method: "POST",
      headers: { Authorization: "Bearer " + accountToken, "Content-Type": "application/json" },
      body: JSON.stringify({ deviceHint: { name: "Windows Workstation", platform: "windows" } })
    });
    assert.equal(createSessRes.status, 201);
    const sessData = await createSessRes.json();

    // 2. Windows Agent redeems QR pairing URI
    const winPaired = await winAgent.pair(sessData.qrPayload, {
      baseUrl: "https://worker.test",
      fetchImpl: workerFetch,
      credentialsFile: winCredFile,
      secretStoreAdapter: noOsKeychain,
      silent: true
    });
    assert.equal(winPaired.ok, true);

    // Re-using the same one-time pairing code MUST fail
    await assert.rejects(
      () =>
        winAgent.pair(sessData.code, {
          baseUrl: "https://worker.test",
          fetchImpl: workerFetch,
          credentialsFile: winCredFile,
          secretStoreAdapter: noOsKeychain,
          silent: true,
          maxRetries: 1
        }),
      /already been used/i
    );

    // 3. macOS and Linux Agents enroll using accountToken
    const macPaired = await unixAgent.pair("", {
      platformOverride: "macos",
      accountToken,
      baseUrl: "https://worker.test",
      fetchImpl: workerFetch,
      credentialsFile: macCredFile,
      secretStoreAdapter: noOsKeychain,
      silent: true
    });
    assert.equal(macPaired.ok, true);

    const linuxPaired = await unixAgent.pair("", {
      platformOverride: "linux",
      accountToken,
      baseUrl: "https://worker.test",
      fetchImpl: workerFetch,
      credentialsFile: linuxCredFile,
      secretStoreAdapter: noOsKeychain,
      silent: true
    });
    assert.equal(linuxPaired.ok, true);

    // 4. Enqueue files.read command for Windows Agent and pollOnce
    const enqueueWin = await (
      await workerFetch("https://worker.test/v1/device/command", {
        method: "POST",
        headers: { Authorization: "Bearer " + accountToken, "Content-Type": "application/json" },
        body: JSON.stringify({
          deviceId: winPaired.deviceId,
          action: "files.read",
          params: { rootIndex: 0, relativePath: "architecture.md" }
        })
      })
    ).json();
    assert.equal(enqueueWin.ok, true);

    const winStored = await loadWinCreds({ credentialsFile: winCredFile, secretStoreAdapter: noOsKeychain });
    winAgent.setRuntimeIdentity(winStored);
    const winPoll = await winAgent.pollOnce({
      baseUrl: "https://worker.test",
      fetchImpl: workerFetch,
      credentialsFile: winCredFile,
      secretStoreAdapter: noOsKeychain,
      allowedDirs: allowedDir,
      silent: true
    });
    assert.equal(winPoll.ok, true);
    assert.equal(winPoll.processed, 1);

    // Verify command status in Worker is now completed with file contents
    const cmdStatusRes = await (
      await workerFetch(
        `https://worker.test/v1/device/command/status?deviceId=${encodeURIComponent(winPaired.deviceId)}&commandId=${encodeURIComponent(enqueueWin.commandId)}`,
        { headers: { Authorization: "Bearer " + accountToken } }
      )
    ).json();
    assert.equal(cmdStatusRes.ok, true);
    assert.equal(cmdStatusRes.command.status, "completed");
    assert.equal(cmdStatusRes.command.result.content, "# JARVIS B5 Architecture");

    // 5. Revoke Linux device on Worker and verify Linux agent detects HTTP 404 and triggers revocation cleanup
    const revokeLinuxRes = await workerFetch(
      `https://worker.test/v1/devices/${encodeURIComponent(linuxPaired.deviceId)}`,
      {
        method: "DELETE",
        headers: { Authorization: "Bearer " + accountToken }
      }
    );
    assert.equal(revokeLinuxRes.status, 200);

    const linuxStored = await loadUnixCreds({ credentialsFile: linuxCredFile, secretStoreAdapter: noOsKeychain });
    unixAgent.setRuntimeIdentity(linuxStored);
    await assert.rejects(
      () =>
        unixAgent.pollOnce({
          baseUrl: "https://worker.test",
          fetchImpl: workerFetch,
          credentialsFile: linuxCredFile,
          secretStoreAdapter: noOsKeychain,
          silent: true
        }),
      (err) => err.status === 404
    );

    const prevExit = process.exitCode;
    const revOutcome = await unixAgent.handleRevocation("HTTP 404: Device has been revoked", {
      credentialsFile: linuxCredFile,
      secretStoreAdapter: noOsKeychain
    });
    assert.equal(revOutcome.exitCode, 2);
    assert.equal(await loadUnixCreds({ credentialsFile: linuxCredFile, secretStoreAdapter: noOsKeychain }), null);
    process.exitCode = prevExit;
  } finally {
    winAgent.stopAgent();
    unixAgent.stopAgent();
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});