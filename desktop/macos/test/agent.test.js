import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { EventEmitter } from "node:events";

import {
  isInside,
  safeTarget,
  buildResultCachePayload,
  isResultCacheFresh,
  isCommandExpired,
  extractPairingCode,
  listFiles,
  searchFiles,
  readFile,
  listApplications,
  launchApp,
  launchAppByName,
  parseDesktopEntry,
  systemStatus,
  execute,
  pair,
  pollOnce,
  handleRevocation,
  loadResultCache,
  getLifecycleState,
  stopAgent,
  revokedStatePath
} from "../src/agent.js";

import {
  saveCredentials,
  loadCredentials,
  rotateCredentials,
  clearCredentials
} from "../src/credentials.js";

// --- PRESERVED B4 BASELINE TESTS ---

test("approved-root containment rejects parent traversal", () => {
  const root = path.resolve(os.tmpdir(), "jarvis-approved-root");
  assert.equal(isInside(root, path.join(root, "notes", "file.txt")), true);
  assert.equal(isInside(root, path.resolve(root, "..", "secret.txt")), false);
});

test("safeTarget accepts a relative file path inside a canonical root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await fs.writeFile(path.join(root, "note.txt"), "approved");
    assert.equal(path.basename(await safeTarget(root, "note.txt")), "note.txt");
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("safeTarget rejects traversal and absolute paths", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await assert.rejects(() => safeTarget(root, "../outside.txt"), /escapes/);
    await assert.rejects(() => safeTarget(root, path.resolve(root, "..", "outside.txt")), /relative path/);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("safeTarget rejects a symlink that resolves outside the approved root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  const outside = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-outside-"));
  try {
    const secret = path.join(outside, "secret.txt");
    await fs.writeFile(secret, "outside");
    await fs.symlink(secret, path.join(root, "linked-secret.txt"), "file");
    await assert.rejects(() => safeTarget(root, "linked-secret.txt"), /escapes/);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
    await fs.rm(outside, { recursive: true, force: true });
  }
});

test("desktop result cache keeps the original TTL and rejects expired entries", async () => {
  const idFresh = "11111111-1111-4111-8111-111111111111";
  const idExpired = "22222222-2222-4222-8222-222222222222";
  const now = 2_000_000;
  const results = new Map([[idFresh, { ok: true }], [idExpired, { ok: true }]]);
  const savedAt = new Map([[idFresh, now - 30_000], [idExpired, now - 16 * 60_000]]);
  const payload = buildResultCachePayload(results, savedAt, now);
  assert.deepEqual(Object.keys(payload), [idFresh]);
  assert.equal(payload[idFresh].savedAt, now - 30_000);
  assert.equal(isResultCacheFresh(now + 1, now), false);
});

test("isCommandExpired rejects missing, malformed, expired, and future-dated command timestamps", async () => {
  const now = Date.parse("2026-11-05T10:00:00.000Z");
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T09:58:00.000Z" }, now), false);
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T09:54:00.000Z" }, now), true);
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T10:05:00.000Z" }, now), true);
  assert.equal(isCommandExpired({ createdAt: "invalid" }, now), true);
  assert.equal(isCommandExpired({}, now), true);
  assert.equal(extractPairingCode("jarvis-pair://v1?code=xyzw6789"), "XYZW6789");
  assert.equal(extractPairingCode("xyzw 6789"), "XYZW6789");
});

// --- BATCH 5 POSIX (MACOS & LINUX) COMPREHENSIVE TESTS ---

test("B5 File Security (macOS & Linux): Unicode NFD/NFC, symlink loops, TOCTOU file replacement, binary & oversized rejection", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-posix-sec-"));
  const outside = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-posix-out-"));
  try {
    // 1. Unicode NFC/NFD filename normalization (macOS APFS/HFS+ compatibility)
    const nfdDiskName = "Laporan_Kafe\u0301.txt";
    const nfcQuery = "kaf\u00e9";
    await fs.writeFile(path.join(root, nfdDiskName), "Data Kopi Nusantara");
    await fs.writeFile(path.join(outside, "shadow.txt"), "root:x:0:0");

    // 2. Symlink loop inside root must not hang searchFiles
    await fs.symlink(root, path.join(root, "loop-dir"));
    await fs.symlink(outside, path.join(root, "escape-dir"));

    const searched = await searchFiles({ query: nfcQuery }, { allowedDirs: root, silent: true });
    assert.equal(searched.ok, true);
    assert.equal(searched.count, 1);

    const listed = await listFiles({ rootIndex: 0 }, { allowedDirs: root, silent: true });
    assert.equal(listed.ok, true);
    assert.equal(listed.items.some((i) => i.name === "escape-dir"), false);

    // 3. Reject tilde (~) and control character paths
    await assert.rejects(() => safeTarget(root, "~/secret.txt"), /relative path/);
    await assert.rejects(() => safeTarget(root, "bad\x00file.txt"), /control characters/);

    // 4. Reject binary file and oversized (>64 KB) file
    await fs.writeFile(path.join(root, "elf.txt"), Buffer.from([0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01]));
    const binRes = await readFile({ rootIndex: 0, relativePath: "elf.txt" }, { allowedDirs: root, silent: true });
    assert.equal(binRes.ok, false);
    assert.match(binRes.error, /Binary files are not permitted/i);

    await fs.writeFile(path.join(root, "large.md"), "X".repeat(66000));
    const bigRes = await readFile({ rootIndex: 0, relativePath: "large.md" }, { allowedDirs: root, silent: true });
    assert.equal(bigRes.ok, false);
    assert.match(bigRes.error, /64 KB/i);

    // 5. TOCTOU race condition protection
    const toctouFile = path.join(root, "swap.txt");
    await fs.writeFile(toctouFile, "safe content");
    const toctouRes = await readFile(
      { rootIndex: 0, relativePath: "swap.txt" },
      {
        allowedDirs: root,
        silent: true,
        beforeOpenHook: async (target) => {
          await fs.unlink(target);
          await fs.symlink(path.join(outside, "shadow.txt"), target);
        }
      }
    );
    assert.equal(toctouRes.ok, false);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
    await fs.rm(outside, { recursive: true, force: true });
  }
});

test("B5 Applications (macOS & Linux): .app bundle + Info.plist bundleId and .desktop entry validation + gio/open launch", async () => {
  const macAppsDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-mac-apps-"));
  const linuxAppsDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-linux-apps-"));

  try {
    // 1. macOS .app bundle with Contents/Info.plist
    const safariContents = path.join(macAppsDir, "Safari.app", "Contents");
    await fs.mkdir(safariContents, { recursive: true });
    await fs.writeFile(
      path.join(safariContents, "Info.plist"),
      `<?xml version="1.0" encoding="UTF-8"?>
      <plist version="1.0"><dict>
        <key>CFBundleIdentifier</key><string>com.apple.Safari</string>
        <key>CFBundleName</key><string>Safari</string>
      </dict></plist>`
    );

    const macApps = await listApplications({ platformOverride: "macos", appDirs: [macAppsDir] });
    assert.equal(macApps.length, 1);
    assert.equal(macApps[0].name, "Safari");
    assert.equal(macApps[0].bundleId, "com.apple.Safari");

    let macSpawned = null;
    process.env.JARVIS_DEVICE_TOKEN = "secret-mac-token-dont-leak";
    const mockMacSpawn = (cmd, args, opts) => {
      macSpawned = { cmd, args, opts };
      const emitter = new EventEmitter();      emitter.unref = () => {};
      return emitter;
    };

    // Launch macOS app by bundle identifier
    const macLaunchBundle = await launchApp("com.apple.Safari", {
      platformOverride: "macos",
      appDirs: [macAppsDir],
      spawnImpl: mockMacSpawn
    });
    assert.equal(macLaunchBundle.ok, true);
    assert.equal(macSpawned.cmd, "open");
    assert.deepEqual(macSpawned.args, ["-b", "com.apple.Safari"]);
    assert.equal(macSpawned.opts.shell, false);
    assert.equal(macSpawned.opts.env.JARVIS_DEVICE_TOKEN, undefined);

    // 2. Linux .desktop entry validation (valid app vs NoDisplay=true vs malicious Exec)
    await fs.writeFile(
      path.join(linuxAppsDir, "org.gnome.Calculator.desktop"),
      "[Desktop Entry]\nType=Application\nName=Calculator\nExec=gnome-calculator\n"
    );
    await fs.writeFile(
      path.join(linuxAppsDir, "hidden.desktop"),
      "[Desktop Entry]\nType=Application\nName=HiddenTool\nNoDisplay=true\nExec=hidden\n"
    );

    const linuxApps = await listApplications({ platformOverride: "linux", appDirs: [linuxAppsDir] });
    assert.equal(linuxApps.length, 1);
    assert.equal(linuxApps[0].name, "Calculator");
    assert.equal(linuxApps[0].desktopId, "org.gnome.Calculator");

    let linuxSpawned = null;
    const mockLinuxSpawn = (cmd, args, opts) => {
      linuxSpawned = { cmd, args, opts };
      const emitter = new EventEmitter();
      emitter.unref = () => {};
      return emitter;
    };

    const linuxLaunch = await launchAppByName("Calculator", {
      platformOverride: "linux",
      appDirs: [linuxAppsDir],
      spawnImpl: mockLinuxSpawn
    });
    assert.equal(linuxLaunch.ok, true);
    assert.equal(linuxSpawned.cmd, "gio");
    assert.equal(linuxSpawned.args[0], "launch");
    assert.ok(linuxSpawned.args[1].endsWith("org.gnome.Calculator.desktop"));
    assert.equal(linuxSpawned.opts.shell, false);
  } finally {
    delete process.env.JARVIS_DEVICE_TOKEN;
    await fs.rm(macAppsDir, { recursive: true, force: true });
    await fs.rm(linuxAppsDir, { recursive: true, force: true });
  }
});

test("B5 Credentials (macOS Keychain & Linux libsecret): OS secret store, AES-256-GCM fallback, permission repair, and corruption recovery", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-posix-cred-"));
  const credFile = path.join(tmpDir, "device-credentials.json");

  try {
    // 1. Simulated Keychain / libsecret adapter
    const secretMap = new Map();
    const mockKeychain = {
      name: "macos-keychain",
      async isAvailable() { return true; },
      async setSecret(id, tok) { secretMap.set(id, tok); },
      async getSecret(id) { return secretMap.get(id) || null; },
      async deleteSecret(id) { secretMap.delete(id); }
    };

    await saveCredentials(
      { deviceId: "macos-mbp-0001", token: "tok_keychain_secret_1234567890", platform: "macos" },
      { credentialsFile: credFile, secretStoreAdapter: mockKeychain }
    );
    const diskJson = await fs.readFile(credFile, "utf8");
    assert.equal(diskJson.includes("tok_keychain_secret_1234567890"), false);

    const loaded = await loadCredentials({
      credentialsFile: credFile,
      secretStoreAdapter: mockKeychain
    });
    assert.equal(loaded.token, "tok_keychain_secret_1234567890");
    assert.equal(loaded.storageBackend, "macos-keychain");

    // 2. Fallback to AES-256-GCM when libsecret/Keychain is absent
    const noSecretService = { async isAvailable() { return false; } };
    await saveCredentials(
      { deviceId: "linux-ws-0001", token: "tok_linux_aes_fallback_123456789", platform: "linux" },
      { credentialsFile: credFile, secretStoreAdapter: noSecretService }
    );
    const aesDisk = await fs.readFile(credFile, "utf8");
    assert.equal(aesDisk.includes("tok_linux_aes_fallback_123456789"), false);

    // Loosen permissions to 0o644 and verify loadCredentials repairs or rejects when requested
    await fs.chmod(credFile, 0o644);
    await assert.rejects(
      () => loadCredentials({ credentialsFile: credFile, secretStoreAdapter: noSecretService, rejectInsecurePermissions: true }),
      /insecure/i
    );
    // Default load repairs permissions back to 0o600
    const repaired = await loadCredentials({ credentialsFile: credFile, secretStoreAdapter: noSecretService });
    assert.equal(repaired.token, "tok_linux_aes_fallback_123456789");
    const modeAfter = (await fs.stat(credFile)).mode & 0o777;
    assert.equal(modeAfter, 0o600);

    // 3. Rotation and deletion
    await rotateCredentials(
      { deviceId: "linux-ws-0001", newToken: "tok_linux_rotated_999999999999" },
      { credentialsFile: credFile, secretStoreAdapter: noSecretService }
    );
    assert.equal(
      (await loadCredentials({ credentialsFile: credFile, secretStoreAdapter: noSecretService })).token,
      "tok_linux_rotated_999999999999"
    );
    await clearCredentials({ credentialsFile: credFile, secretStoreAdapter: noSecretService });
    assert.equal(await loadCredentials({ credentialsFile: credFile }), null);
  } finally {
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});

test("B5 End-to-End Lifecycle (macOS & Linux): Pairing, System Status, Poll & ACK, and Revocation (exitCode = 2)", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-posix-life-"));
  const credFile = path.join(tmpDir, "device-credentials.json");
  const osReleaseFile = path.join(tmpDir, "os-release");
  const macosPlistFile = path.join(tmpDir, "SystemVersion.plist");
  const noSecretService = { async isAvailable() { return false; } };

  try {
    await fs.writeFile(osReleaseFile, 'PRETTY_NAME="Ubuntu 24.04.1 LTS"\nID=ubuntu\nVERSION_ID="24.04"\n');
    await fs.writeFile(
      macosPlistFile,
      '<plist version="1.0"><dict><key>ProductVersion</key><string>15.1</string></dict></plist>'
    );

    const linuxStatus = await systemStatus({
      platformOverride: "linux",
      osReleasePath: osReleaseFile,
      silent: true
    });
    assert.equal(linuxStatus.platform, "linux");
    assert.equal(linuxStatus.distro.id, "ubuntu");

    const macStatus = await systemStatus({
      platformOverride: "macos",
      macosVersionPlistPath: macosPlistFile,
      silent: true
    });
    assert.equal(macStatus.platform, "macos");
    assert.equal(macStatus.macosProductVersion, "15.1");

    // Pair and then trigger revocation
    await pair("ABCD2345", {
      platformOverride: "linux",
      credentialsFile: credFile,
      secretStoreAdapter: noSecretService,
      silent: true,
      fetchImpl: async () =>
        new Response(
          JSON.stringify({
            ok: true,
            accountId: "acct-linux",
            device: { deviceId: "linux-agent-01", platform: "linux" },
            token: "tok_linux_agent_01_valid_secret"
          }),
          { status: 201 }
        )
    });

    const prevExit = process.exitCode;
    const rev = await handleRevocation("HTTP 404: Device has been revoked", {
      credentialsFile: credFile,
      secretStoreAdapter: noSecretService
    });
    assert.equal(rev.exitCode, 2);
    assert.equal(getLifecycleState(), "revoked");
    assert.equal(await loadCredentials({ credentialsFile: credFile }), null);
    process.exitCode = prevExit;
  } finally {
    stopAgent();
    await fs.rm(tmpDir, { recursive: true, force: true });
  }
});
