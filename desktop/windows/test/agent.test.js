import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { EventEmitter } from "node:events";

import {
  isInside,
  safeTarget,
  normalizedForComparison,
  buildResultCachePayload,
  isResultCacheFresh,
  isCommandExpired,
  extractPairingCode,
  isBinaryBuffer,
  sanitizeChildEnv,
  listFiles,
  searchFiles,
  readFile,
  startMenuShortcuts,
  launchApp,
  launchAppByName,
  systemStatus,
  execute,
  apiRequest,
  pair,
  pollOnce,
  handleRevocation,
  loadResultCache,
  persistResultCache,
  setRuntimeIdentity,
  getLifecycleState,
  stopAgent,
  revokedStatePath
} from "../src/agent.js";

import {
  saveCredentials,
  loadCredentials,
  rotateCredentials,
  clearCredentials,
  redactToken
} from "../src/credentials.js";

// --- PRESERVED B4 BASELINE TESTS ---

test("approved-root containment rejects parent traversal", () => {
  const root = "C:\\JARVIS\\Allowed";
  assert.equal(isInside(root, "C:\\JARVIS\\Allowed\\notes\\file.txt", true), true);
  assert.equal(isInside(root, "C:\\JARVIS\\secret.txt", true), false);
});

test("safeTarget accepts a relative file path within the selected root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await fs.writeFile(path.join(root, "note.txt"), "approved");
    const canonicalRoot = await fs.realpath(root);
    const real = await fs.realpath(path.join(root, "note.txt"));
    assert.equal(isInside(canonicalRoot, real), true, JSON.stringify({
      canonicalRoot,
      real,
      normalizedRoot: normalizedForComparison(canonicalRoot),
      normalizedReal: normalizedForComparison(real)
    }));
    assert.equal(path.basename(await safeTarget(root, "note.txt")), "note.txt");
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("safeTarget rejects traversal and absolute paths", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await assert.rejects(
      () => safeTarget(root, "..\\outside.txt"),
      (err) => /escapes|ENOENT|no such file/i.test(String(err.message || err))
    );
    await assert.rejects(() => safeTarget(root, path.resolve(root, "..", "outside.txt")), /relative path/);
    await assert.rejects(() => safeTarget(root, "../outside.txt"), /escapes/);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("result cache preserves original timestamps instead of extending TTL on every write", async () => {
  const idFresh = "11111111-1111-4111-8111-111111111111";
  const idExpired = "22222222-2222-4222-8222-222222222222";
  const now = 1_000_000;
  const results = new Map([
    [idFresh, { ok: true }],
    [idExpired, { ok: true }]
  ]);
  const savedAt = new Map([
    [idFresh, now - 60_000],
    [idExpired, now - 16 * 60_000]
  ]);
  const payload = buildResultCachePayload(results, savedAt, now);
  assert.deepEqual(Object.keys(payload), [idFresh]);
  assert.equal(payload[idFresh].savedAt, now - 60_000);
  assert.deepEqual(payload[idFresh].result, { ok: true });
  assert.equal(isResultCacheFresh(now - 60_000, now), true);
  assert.equal(isResultCacheFresh(now - 16 * 60_000, now), false);
  assert.equal(isResultCacheFresh(now + 1, now), false);
});

test("isCommandExpired rejects missing, malformed, expired, and future-dated command timestamps", async () => {
  const now = Date.parse("2026-11-05T10:00:00.000Z");
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T09:58:00.000Z" }, now), false);
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T09:54:00.000Z" }, now), true);
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T10:05:00.000Z" }, now), true);
  assert.equal(isCommandExpired({ createdAt: "not-a-timestamp" }, now), true);
  assert.equal(isCommandExpired({}, now), true);
  assert.equal(extractPairingCode("jarvis-pair://v1?code=abcd2345"), "ABCD2345");
  assert.equal(extractPairingCode(" abcd 2345 "), "ABCD2345");
});

// --- BATCH 5 WINDOWS COMPREHENSIVE TESTS ---

test("B5 File Security: Windows drive letters, UNC paths, case-insensitivity, ADS, reserved names, symlinks/junctions, TOCTOU, binary & oversized files", async () => {
  // 1. Windows drive letter, case-insensitivity, extended \\?\ and UNC path containment
  assert.equal(isInside("C:\\Users\\Boss\\Documents", "c:\\users\\boss\\documents\\Report.txt", true), true);
  assert.equal(isInside("\\\\?\\C:\\Users\\Boss\\Documents", "C:\\Users\\Boss\\Documents\\sub\\a.txt", true), true);
  assert.equal(isInside("C:\\Users\\Boss\\Documents", "D:\\Users\\Boss\\Documents\\a.txt", true), false);
  assert.equal(isInside("\\\\Server01\\Share\\Approved", "\\\\server01\\share\\approved\\notes.txt", true), true);
  assert.equal(isInside("\\\\Server01\\Share\\Approved", "\\\\Server01\\Share\\Other\\notes.txt", true), false);

  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-sec-"));
  const outside = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-out-"));
  try {
    await fs.writeFile(path.join(root, "valid.txt"), "Hello JARVIS Windows");
    await fs.writeFile(path.join(outside, "secret.txt"), "TOP SECRET OUTSIDE");

    // 2. Reject UNC path, drive-relative path, NTFS Alternate Data Stream (:), reserved device names, and trailing dots
    await assert.rejects(() => safeTarget(root, "\\\\evil\\share\\pwn.txt"), /relative path/);
    await assert.rejects(() => safeTarget(root, "C:valid.txt"), /relative path/);
    await assert.rejects(() => safeTarget(root, "valid.txt:$DATA"), /relative path/);
    await assert.rejects(() => safeTarget(root, "CON.txt"), /reserved device/);
    await assert.rejects(() => safeTarget(root, "NUL"), /reserved device/);
    await assert.rejects(() => safeTarget(root, "valid.txt..."), /trailing/);

    // 3. Reject symlink / simulated junction escaping approved directory
    await fs.symlink(path.join(outside, "secret.txt"), path.join(root, "escape-file.txt"));
    await fs.symlink(outside, path.join(root, "escape-junction"), "dir");
    await assert.rejects(() => safeTarget(root, "escape-file.txt"), /escapes/);
    await assert.rejects(() => safeTarget(root, "escape-junction/secret.txt"), /escapes/);

    // 4. listFiles and searchFiles ignore escaping symlinks/junctions and read valid files
    const listed = await listFiles({ rootIndex: 0 }, { allowedDirs: root, silent: true });
    assert.equal(listed.ok, true);
    assert.deepEqual(listed.items.map((i) => i.name), ["valid.txt"]);

    const searched = await searchFiles({ query: "valid" }, { allowedDirs: root, silent: true });
    assert.equal(searched.ok, true);
    assert.equal(searched.count, 1);

    // 5. Binary file rejection (even with .txt extension)
    const binPath = path.join(root, "disguised.txt");
    await fs.writeFile(binPath, Buffer.from([0x4d, 0x5a, 0x90, 0x00, 0x03, 0x00, 0x00, 0x00]));
    const binRead = await readFile({ rootIndex: 0, relativePath: "disguised.txt" }, { allowedDirs: root, silent: true });    assert.equal(binRead.ok, false);
    assert.match(binRead.error, /Binary files are not permitted/i);

    // 6. Oversized file rejection (> 64 KB)
    const bigPath = path.join(root, "huge.txt");
    await fs.writeFile(bigPath, "A".repeat(70 * 1024));
    const bigRead = await readFile({ rootIndex: 0, relativePath: "huge.txt" }, { allowedDirs: root, silent: true });
    assert.equal(bigRead.ok, false);
    assert.match(bigRead.error, /64 KB/i);

    // 7. TOCTOU file replacement race protection: swap regular file with symlink right before open
    const racePath = path.join(root, "race.txt");
    await fs.writeFile(racePath, "initial safe content");
    const raceRead = await readFile(
      { rootIndex: 0, relativePath: "race.txt" },
      {
        allowedDirs: root,
        silent: true,
        beforeOpenHook: async (targetPath) => {
          await fs.unlink(targetPath);
          await fs.symlink(path.join(outside, "secret.txt"), targetPath);
        }
      }
    );
    assert.equal(raceRead.ok, false);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
    await fs.rm(outside, { recursive: true, force: true });
  }
});

test("B5 Applications: Windows Start Menu discovery, launch by appId/name, ambiguity, unknown apps, and shell injection prevention", async () => {
  const menuDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-menu-"));
  const outsideDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-menu-out-"));
  try {
    await fs.mkdir(path.join(menuDir, "SubA"), { recursive: true });
    await fs.mkdir(path.join(menuDir, "SubB"), { recursive: true });
    await fs.writeFile(path.join(menuDir, "Calculator.lnk"), "shortcut-calc");
    await fs.writeFile(path.join(menuDir, "SubA", "DuplicateApp.lnk"), "shortcut-dup-1");
    await fs.writeFile(path.join(menuDir, "SubB", "DuplicateApp.lnk"), "shortcut-dup-2");

    // Escaping symlink inside Start Menu must be ignored
    await fs.writeFile(path.join(outsideDir, "Evil.lnk"), "evil");
    await fs.symlink(path.join(outsideDir, "Evil.lnk"), path.join(menuDir, "EvilLink.lnk"));

    const apps = await startMenuShortcuts({ startMenuDirs: [menuDir] });
    assert.equal(apps.some((a) => a.name === "EvilLink"), false);
    const calc = apps.find((a) => a.name === "Calculator");
    assert.ok(calc);

    // Verify safe spawn invocation (shell: false, sanitized env without tokens)
    let capturedSpawn = null;
    process.env.JARVIS_DEVICE_TOKEN = "super-secret-device-token-value-123456";
    const mockSpawn = (cmd, args, opts) => {
      capturedSpawn = { cmd, args, opts };
      const emitter = new EventEmitter();
      emitter.unref = () => {};
      return emitter;
    };

    const launched = await launchApp(calc.appId, {
      startMenuDirs: [menuDir],
      spawnImpl: mockSpawn
    });
    assert.equal(launched.ok, true);
    assert.equal(capturedSpawn.cmd, "powershell.exe");
    assert.equal(capturedSpawn.opts.shell, false);
    assert.equal(capturedSpawn.opts.env.JARVIS_DEVICE_TOKEN, undefined);
    assert.ok(capturedSpawn.opts.env.JARVIS_TARGET.endsWith("Calculator.lnk"));

    // Launch by unique visible name
    const byName = await launchAppByName("calculator", {
      startMenuDirs: [menuDir],
      spawnImpl: mockSpawn
    });
    assert.equal(byName.ok, true);

    // Ambiguous app name -> rejected with matches
    const dupRes = await launchAppByName("DuplicateApp", {
      startMenuDirs: [menuDir],
      spawnImpl: mockSpawn
    });
    assert.equal(dupRes.ok, false);
    assert.equal(dupRes.matches.length, 2);

    // Unknown app & malicious shell injection strings -> rejected
    const unknownRes = await launchAppByName("NonExistentApp", { startMenuDirs: [menuDir] });
    assert.equal(unknownRes.ok, false);
    const injectionRes = await launchApp("calc; rm -rf /", { startMenuDirs: [menuDir] });
    assert.equal(injectionRes.ok, false);
  } finally {
    delete process.env.JARVIS_DEVICE_TOKEN;
    await fs.rm(menuDir, { recursive: true, force: true });
    await fs.rm(outsideDir, { recursive: true, force: true });
  }
});

test("B5 Credentials: Windows DPAPI adapter, AES-256-GCM fallback, rotation, deletion, and corruption recovery", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-cred-"));
  const credFile = path.join(tmpDir, "device-credentials.json");
  try {
    // 1. Simulated Windows DPAPI adapter
    const dpapiStore = new Map();
    const mockDpapiAdapter = {
      name: "windows-dpapi",
      async isAvailable() { return true; },
      async protect(deviceId, token) {
        const blob = Buffer.from(`dpapi:${deviceId}:${token}`).toString("base64");
        dpapiStore.set(deviceId, blob);
        return blob;
      },
      async unprotect(deviceId, blob) {
        const decoded = Buffer.from(blob, "base64").toString("utf8");
        const prefix = `dpapi:${deviceId}:`;
        if (!decoded.startsWith(prefix)) throw new Error("DPAPI unprotect failed");
        return decoded.slice(prefix.length);
      },
      async deleteSecret(deviceId) {
        dpapiStore.delete(deviceId);
      }
    };

    await saveCredentials(
      { deviceId: "windows-pc-0001", token: "tok_secret_windows_1234567890", platform: "windows" },
      { credentialsFile: credFile, secretStoreAdapter: mockDpapiAdapter }
    );
    const rawOnDisk = await fs.readFile(credFile, "utf8");
    assert.equal(rawOnDisk.includes("tok_secret_windows_1234567890"), false);

    const loadedDpapi = await loadCredentials({
      credentialsFile: credFile,
      secretStoreAdapter: mockDpapiAdapter
    });
    assert.equal(loadedDpapi.deviceId, "windows-pc-0001");
    assert.equal(loadedDpapi.token, "tok_secret_windows_1234567890");
    assert.equal(loadedDpapi.storageBackend, "windows-dpapi");

    // 2. Fallback to AES-256-GCM when DPAPI is unavailable
    const unavailableAdapter = { async isAvailable() { return false; } };
    await saveCredentials(
      { deviceId: "windows-pc-0001", token: "tok_aes_fallback_9876543210", platform: "windows" },
      { credentialsFile: credFile, secretStoreAdapter: unavailableAdapter }
    );
    const rawAesDisk = await fs.readFile(credFile, "utf8");
    assert.equal(rawAesDisk.includes("tok_aes_fallback_9876543210"), false);

    const loadedAes = await loadCredentials({
      credentialsFile: credFile,
      secretStoreAdapter: unavailableAdapter
    });
    assert.equal(loadedAes.token, "tok_aes_fallback_9876543210");
    assert.equal(loadedAes.storageBackend, "aes-256-gcm-file");

    // 3. Token rotation
    await rotateCredentials(
      { deviceId: "windows-pc-0001", newToken: "tok_rotated_windows_abcdefghij" },
      { credentialsFile: credFile, secretStoreAdapter: unavailableAdapter }
    );
    const rotated = await loadCredentials({
      credentialsFile: credFile,
      secretStoreAdapter: unavailableAdapter
    });
    assert.equal(rotated.token, "tok_rotated_windows_abcdefghij");

    // 4. Corrupt credential file recovery -> returns null without crashing
    await fs.writeFile(credFile, "{corrupt json");
    assert.equal(await loadCredentials({ credentialsFile: credFile }), null);

    // 5. Clear credentials deletes both credential file and vault key
    await clearCredentials({ credentialsFile: credFile, secretStoreAdapter: mockDpapiAdapter });
    assert.equal(await loadCredentials({ credentialsFile: credFile }), null);
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});

test("B5 Pairing, Command Polling Lifecycle, Retry Backoff, Cache Persistence, and Revocation (exitCode = 2)", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-life-"));
  const credFile = path.join(tmpDir, "device-credentials.json");
  const allowedDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-win-allowed-"));
  const unavailableAdapter = { async isAvailable() { return false; } };

  try {
    await fs.writeFile(path.join(allowedDir, "report.txt"), "Quarterly Windows Report");

    // 1. Pairing with 8-char code + retry on transient 503
    let redeemAttempts = 0;
    const pairResult = await pair("jarvis-pair://v1?code=wxyz2345", {
      credentialsFile: credFile,
      secretStoreAdapter: unavailableAdapter,
      silent: true,
      baseBackoffMs: 5,
      fetchImpl: async (url, init) => {
        redeemAttempts += 1;
        if (redeemAttempts === 1) {
          return new Response(JSON.stringify({ error: "Temporary overload" }), { status: 503 });
        }
        const body = JSON.parse(init.body);
        assert.equal(body.code, "WXYZ2345");
        assert.equal(body.platform, "windows");
        return new Response(
          JSON.stringify({
            ok: true,
            accountId: "acct-win-user",
            device: { deviceId: "windows-paired-01", platform: "windows" },
            token: "device_token_windows_paired_1234567890"
          }),
          { status: 201 }
        );
      }
    });
    assert.equal(pairResult.ok, true);
    assert.equal(pairResult.deviceId, "windows-paired-01");
    assert.equal(redeemAttempts, 2);

    // 2. Poll cycle: executes command, caches result when ACK fails, and replays cached result on next poll without re-executing
    const cmdId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    const freshCreatedAt = new Date().toISOString();
    let ackShouldFail = true;
    let ackPayloads = [];

    const pollOptions = {
      credentialsFile: credFile,
      secretStoreAdapter: unavailableAdapter,
      allowedDirs: allowedDir,
      silent: true,
      maxRetries: 1,
      fetchImpl: async (url, init) => {
        const u = String(url);
        if (u.endsWith("/heartbeat")) {
          return new Response(JSON.stringify({ ok: true }), { status: 200 });
        }
        if (u.includes("/v1/device/commands?")) {
          return new Response(
            JSON.stringify({
              ok: true,
              commands: [
                {
                  commandId: cmdId,
                  action: "files.read",
                  params: { rootIndex: 0, relativePath: "report.txt" },
                  createdAt: freshCreatedAt
                }
              ]
            }),
            { status: 200 }
          );
        }
        if (u.endsWith("/v1/device/commands/ack")) {
          if (ackShouldFail) {
            return new Response(JSON.stringify({ error: "Gateway timeout" }), { status: 502 });
          }
          ackPayloads.push(JSON.parse(init.body));
          return new Response(JSON.stringify({ ok: true, status: "completed" }), { status: 200 });
        }
        return new Response("Not found", { status: 404 });
      }
    };

    // First poll fails at ACK -> result is persisted to command-results.json
    await assert.rejects(() => pollOnce(pollOptions), /HTTP 502/);
    // Remove report.txt to prove that the second poll uses the cached result instead of re-reading the file!
    await fs.unlink(path.join(allowedDir, "report.txt"));

    ackShouldFail = false;
    await loadResultCache({ credentialsFile: credFile });
    const pollRes = await pollOnce(pollOptions);
    assert.equal(pollRes.ok, true);
    assert.equal(ackPayloads.length, 1);
    assert.equal(ackPayloads[0].result.ok, true);
    assert.equal(ackPayloads[0].result.content, "Quarterly Windows Report");

    // 3. System status validation
    const sys = await systemStatus({ allowedDirs: allowedDir, silent: true });
    assert.equal(sys.ok, true);
    assert.equal(sys.platform, "windows");
    assert.equal(typeof sys.nodeVersion, "string");
    assert.equal(typeof sys.memoryUsagePercent, "number");

    // 4. Device Revocation -> stops polling, deletes credentials, writes revoked-state.json, sets process.exitCode = 2
    const prevExitCode = process.exitCode;
    const revRes = await handleRevocation("HTTP 401: Device revoked", {
      credentialsFile: credFile,
      secretStoreAdapter: unavailableAdapter
    });
    assert.equal(revRes.revoked, true);
    assert.equal(revRes.exitCode, 2);
    assert.equal(process.exitCode, 2);
    assert.equal(getLifecycleState(), "revoked");
    assert.equal(await loadCredentials({ credentialsFile: credFile }), null);
    const revState = JSON.parse(await fs.readFile(revokedStatePath({ credentialsFile: credFile }), "utf8"));
    assert.equal(revState.revoked, true);
    process.exitCode = prevExitCode;
  } finally {
    stopAgent();
    await fs.rm(tmpDir, { recursive: true, force: true });
    await fs.rm(allowedDir, { recursive: true, force: true });
  }
});
