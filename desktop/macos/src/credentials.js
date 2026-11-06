import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { spawn } from "node:child_process";
import {
  randomBytes,
  scryptSync,
  createCipheriv,
  createDecipheriv
} from "node:crypto";

const SERVICE_NAME = "com.jarvis.desktop.device";
const DEVICE_ID_REGEX = /^[A-Za-z0-9_-]{8,80}$/;

export function redactToken(input) {
  const raw = String(input ?? "");
  return raw
    .replace(/(Bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, "$1[REDACTED_TOKEN]")
    .replace(/\b([A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{24,})\b/g, "[REDACTED_TOKEN]")
    .replace(/("token"\s*:\s*")[^"]+(")/gi, "$1[REDACTED_TOKEN]$2")
    .replace(/("accountToken"\s*:\s*")[^"]+(")/gi, "$1[REDACTED_TOKEN]$2");
}

export function credentialPath(options = {}) {
  const override = options.credentialsFile || process.env.JARVIS_CREDENTIALS_FILE;
  if (override) return path.resolve(override);
  const base = process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config");
  return path.join(base, "jarvis", "device-credentials.json");
}

export function keyFilePath(options = {}) {
  const credFile = credentialPath(options);
  return path.join(path.dirname(credFile), "device-vault.key");
}

function validateDeviceId(deviceId) {
  const clean = String(deviceId || "").trim();
  if (!DEVICE_ID_REGEX.test(clean)) {
    throw new Error("Invalid deviceId for credential storage");
  }
  return clean;
}

function validateToken(token) {
  const clean = typeof token === "string" ? token.trim() : "";
  if (clean.length < 16 || clean.length > 4096 || /\s/.test(clean)) {
    throw new Error("A valid device token is required");
  }
  return clean;
}

function runProcess(command, args, { input = "", env = process.env, timeoutMs = 5000 } = {}) {
  return new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    let settled = false;
    const child = spawn(command, args, {
      shell: false,
      stdio: ["pipe", "pipe", "pipe"],
      env
    });
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        try { child.kill("SIGKILL"); } catch {}
        reject(new Error(`${command} timed out after ${timeoutMs}ms`));
      }
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      if (stdout.length < 65536) stdout += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk) => {
      if (stderr.length < 16384) stderr += chunk.toString("utf8");
    });
    child.on("error", (err) => {
      clearTimeout(timer);
      if (!settled) {
        settled = true;
        reject(err);
      }
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (settled) return;
      settled = true;
      if (code === 0) {
        resolve(stdout.trim());
      } else {
        reject(new Error(redactToken(stderr.trim() || `${command} exited with code ${code}`)));
      }
    });
    if (input) child.stdin.write(input);
    child.stdin.end();
  });
}

/**
 * Native POSIX Credential Manager Adapter:
 * - macOS (darwin): macOS Keychain via `/usr/bin/security` (stdin interactive mode to avoid CLI arg exposure)
 * - Linux (linux): FreeDesktop Secret Service / libsecret via `secret-tool` (stdin pipe)
 */
const defaultPosixSecretAdapter = {
  get name() {
    return process.platform === "darwin" ? "macos-keychain" : "linux-libsecret";
  },
  async isAvailable() {
    if (process.env.JARVIS_DISABLE_OS_KEYCHAIN === "1") return false;
    if (process.platform === "darwin") {
      try {
        await fs.access("/usr/bin/security");
        return true;
      } catch {
        return false;
      }
    }
    if (process.platform === "linux") {
      if (!process.env.DBUS_SESSION_BUS_ADDRESS) return false;
      try {
        await runProcess("which", ["secret-tool"], { timeoutMs: 2000 });
        return true;
      } catch {
        return false;
      }
    }
    return false;
  },
  async setSecret(deviceId, token) {
    if (process.platform === "darwin") {
      // Pipe command via `security -i` stdin so the token never appears in `ps` argument lists.
      const escapedId = deviceId.replace(/["\\]/g, "");
      const escapedTok = token.replace(/["\\]/g, "");
      const cmdLine = `add-generic-password -U -s "${SERVICE_NAME}" -a "${escapedId}" -w "${escapedTok}"\n`;
      await runProcess("/usr/bin/security", ["-i"], { input: cmdLine });
      return true;
    }
    if (process.platform === "linux") {
      await runProcess(
        "secret-tool",
        [
          "store",
          "--label=JARVIS Desktop Device Token",
          "service",
          SERVICE_NAME,
          "account",
          deviceId
        ],
        { input: token }
      );
      return true;
    }
    throw new Error("OS secret store is not supported on this platform");
  },
  async getSecret(deviceId) {
    if (process.platform === "darwin") {
      return runProcess("/usr/bin/security", [
        "find-generic-password",
        "-s",
        SERVICE_NAME,
        "-a",
        deviceId,
        "-w"
      ]);
    }
    if (process.platform === "linux") {
      return runProcess("secret-tool", [
        "lookup",
        "service",
        SERVICE_NAME,
        "account",
        deviceId
      ]);
    }
    return null;
  },
  async deleteSecret(deviceId) {
    if (process.platform === "darwin") {
      await runProcess("/usr/bin/security", [
        "delete-generic-password",
        "-s",
        SERVICE_NAME,
        "-a",
        deviceId
      ]).catch(() => {});
      return true;
    }
    if (process.platform === "linux") {
      await runProcess("secret-tool", [
        "clear",
        "service",
        SERVICE_NAME,
        "account",
        deviceId
      ]).catch(() => {});
      return true;
    }
    return false;
  }
};

async function ensureVaultKey(options = {}) {
  const kf = keyFilePath(options);
  try {
    const existing = await fs.readFile(kf);
    if (existing.length === 32) {
      await fs.chmod(kf, 0o600).catch(() => {});
      return existing;
    }
  } catch {
    // Generate new key file below
  }
  await fs.mkdir(path.dirname(kf), { recursive: true, mode: 0o700 });
  await fs.chmod(path.dirname(kf), 0o700).catch(() => {});
  const keyBytes = randomBytes(32);
  const tmp = `${kf}.${randomBytes(6).toString("hex")}.tmp`;
  await fs.writeFile(tmp, keyBytes, { mode: 0o600 });
  await fs.chmod(tmp, 0o600).catch(() => {});
  await fs.rename(tmp, kf);
  await fs.chmod(kf, 0o600).catch(() => {});
  return keyBytes;
}

function deriveAesKey(rawVaultKey, deviceId, saltHex = "") {
  let uidPart = "user";
  try {
    const info = os.userInfo();
    uidPart = `${info.uid}:${info.username}`;
  } catch {}
  const hostContext = `${os.hostname()}:${uidPart}:${SERVICE_NAME}:${deviceId}:${saltHex}`;
  return scryptSync(rawVaultKey, hostContext, 32);
}

function encryptAesGcm(token, key, saltHex = "") {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(token, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return {
    iv: iv.toString("base64"),
    tag: tag.toString("base64"),
    ciphertext: encrypted.toString("base64"),
    ...(saltHex ? { salt: saltHex } : {})
  };
}

function decryptAesGcm(envelope, key) {
  if (!envelope?.iv || !envelope?.tag || !envelope?.ciphertext) {
    throw new Error("Incomplete encrypted credential envelope");
  }
  const iv = Buffer.from(envelope.iv, "base64");
  const tag = Buffer.from(envelope.tag, "base64");
  const ciphertext = Buffer.from(envelope.ciphertext, "base64");
  if (iv.length !== 12 || tag.length !== 16) {
    throw new Error("Invalid AES-GCM envelope parameters");
  }
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  return decrypted.toString("utf8");
}

async function atomicWriteJson(filePath, data) {
  const dir = path.dirname(filePath);
  await fs.mkdir(dir, { recursive: true, mode: 0o700 });
  await fs.chmod(dir, 0o700).catch(() => {});
  const tmp = `${filePath}.${randomBytes(6).toString("hex")}.tmp`;
  const serialized = JSON.stringify(data, null, 2);
  await fs.writeFile(tmp, serialized, { mode: 0o600 });
  await fs.chmod(tmp, 0o600).catch(() => {});
  await fs.rename(tmp, filePath);
  await fs.chmod(filePath, 0o600).catch(() => {});
}

export async function saveCredentials(
  { deviceId, token, platform = null, accountId = null },
  options = {}
) {
  const validId = validateDeviceId(deviceId);
  const validToken = validateToken(token);
  const file = credentialPath(options);
  const adapter = options.secretStoreAdapter || defaultPosixSecretAdapter;
  const savedAt = new Date().toISOString();

  let record;
  let usedOsStore = false;
  if (await adapter.isAvailable().catch(() => false)) {
    try {
      await adapter.setSecret(validId, validToken);
      record = {
        version: 2,
        deviceId: validId,
        platform: platform || (process.platform === "darwin" ? "macos" : "linux"),
        accountId: accountId || null,
        storageBackend: adapter.name || "os-credential-manager",
        savedAt
      };
      usedOsStore = true;
    } catch {
      usedOsStore = false;
    }
  }

  if (!usedOsStore) {
    const vaultKey = await ensureVaultKey(options);
    const saltHex = randomBytes(16).toString("hex");
    const aesKey = deriveAesKey(vaultKey, validId, saltHex);
    const encrypted = encryptAesGcm(validToken, aesKey, saltHex);
    record = {
      version: 2,
      deviceId: validId,
      platform: platform || (process.platform === "darwin" ? "macos" : "linux"),
      accountId: accountId || null,
      storageBackend: "aes-256-gcm-file",
      encrypted,
      savedAt
    };
  }

  await atomicWriteJson(file, record);
  return file;
}

export async function loadCredentials(options = {}) {
  const file = credentialPath(options);
  let stat;
  try {
    stat = await fs.stat(file);
  } catch (err) {
    if (err && (err.code === "EACCES" || err.code === "EPERM")) {
      throw new Error("Permission denied reading credentials file");
    }
    return null;
  }

  // Enforce minimum 0o600 permissions on POSIX if group/world bits were loosened
  if (process.platform !== "win32" && (stat.mode & 0o077) !== 0) {
    if (options.rejectInsecurePermissions === true) {
      throw new Error("Credentials file has insecure group/other permissions");
    }
    await fs.chmod(file, 0o600).catch(() => {});
  }

  let raw;
  try {
    raw = await fs.readFile(file, "utf8");
  } catch (err) {
    if (err && (err.code === "EACCES" || err.code === "EPERM")) {
      throw new Error("Permission denied reading credentials file");
    }
    return null;
  }

  let data;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!data || typeof data !== "object") return null;
  if (!DEVICE_ID_REGEX.test(String(data.deviceId || ""))) return null;

  const adapter = options.secretStoreAdapter || defaultPosixSecretAdapter;
  let token = null;

  try {
    if (data.storageBackend && data.storageBackend !== "aes-256-gcm-file" && !data.encrypted && !data.token) {
      if (typeof adapter.getSecret === "function") {
        token = await adapter.getSecret(data.deviceId);
      }
    } else if (data.encrypted && typeof data.encrypted === "object") {
      const vaultKey = await fs.readFile(keyFilePath(options));
      const saltHex = typeof data.encrypted.salt === "string" ? data.encrypted.salt : "";
      const aesKey = deriveAesKey(vaultKey, data.deviceId, saltHex);
      token = decryptAesGcm(data.encrypted, aesKey);
    } else if (typeof data.token === "string") {
      token = data.token;
    }
  } catch {
    return null;
  }

  if (typeof token !== "string" || token.trim().length < 16 || /\s/.test(token.trim())) {
    return null;
  }

  return {
    deviceId: data.deviceId,
    token: token.trim(),
    platform: data.platform || null,
    accountId: data.accountId || null,
    storageBackend: data.storageBackend || "legacy-file",
    savedAt: data.savedAt || null
  };
}

export async function rotateCredentials(
  { deviceId, newToken, platform = null, accountId = null },
  options = {}
) {
  return saveCredentials(
    { deviceId, token: newToken, platform, accountId },
    options
  );
}

export async function clearCredentials(options = {}) {
  const file = credentialPath(options);
  const kf = keyFilePath(options);
  const adapter = options.secretStoreAdapter || defaultPosixSecretAdapter;

  try {
    const raw = await fs.readFile(file, "utf8");
    const parsed = JSON.parse(raw);
    if (parsed?.deviceId && typeof adapter.deleteSecret === "function") {
      await adapter.deleteSecret(parsed.deviceId).catch(() => {});
    }
  } catch {
    // Ignore missing/corrupt file during cleanup
  }

  for (const target of [file, kf]) {
    try {
      await fs.unlink(target);
    } catch {
      // Already absent
    }
  }
}