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
  const base = process.env.APPDATA || path.join(os.homedir(), "AppData", "Roaming");
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

function runProcess(command, args, { input = "", env = process.env, timeoutMs = 6000 } = {}) {
  return new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    let settled = false;
    const child = spawn(command, args, {
      windowsHide: true,
      shell: false,
      stdio: ["pipe", "pipe", "pipe"],
      env
    });
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        try { child.kill(); } catch {}
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
 * Windows DPAPI / Credential Manager adapter using CurrentUser protection scope.
 * Never passes secrets on the command line; passes via stdin only.
 */
const defaultWindowsSecretAdapter = {
  name: "windows-dpapi",
  async isAvailable() {
    if (process.platform !== "win32") return false;
    if (process.env.JARVIS_DISABLE_OS_KEYCHAIN === "1") return false;
    return true;
  },
  async protect(deviceId, token) {
    const psScript = [
      "Add-Type -AssemblyName System.Security;",
      "$plain = [Console]::In.ReadToEnd().Trim();",
      "$bytes = [System.Text.Encoding]::UTF8.GetBytes($plain);",
      "$entropy = [System.Text.Encoding]::UTF8.GetBytes($env:JARVIS_DPAPI_ENTROPY);",
      "$enc = [System.Security.Cryptography.ProtectedData]::Protect($bytes, $entropy, [System.Security.Cryptography.DataProtectionScope]::CurrentUser);",
      "[Console]::Out.Write([Convert]::ToBase64String($enc));"
    ].join(" ");
    return runProcess(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", psScript],
      {
        input: token,
        env: { ...process.env, JARVIS_DPAPI_ENTROPY: `${SERVICE_NAME}:${deviceId}` }
      }
    );
  },
  async unprotect(deviceId, dpapiBlob) {
    const psScript = [
      "Add-Type -AssemblyName System.Security;",
      "$b64 = [Console]::In.ReadToEnd().Trim();",
      "$enc = [Convert]::FromBase64String($b64);",
      "$entropy = [System.Text.Encoding]::UTF8.GetBytes($env:JARVIS_DPAPI_ENTROPY);",
      "$dec = [System.Security.Cryptography.ProtectedData]::Unprotect($enc, $entropy, [System.Security.Cryptography.DataProtectionScope]::CurrentUser);",
      "[Console]::Out.Write([System.Text.Encoding]::UTF8.GetString($dec));"
    ].join(" ");
    return runProcess(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", psScript],
      {
        input: dpapiBlob,
        env: { ...process.env, JARVIS_DPAPI_ENTROPY: `${SERVICE_NAME}:${deviceId}` }
      }
    );
  },
  async deleteSecret() {
    return true;
  }
};

async function ensureVaultKey(options = {}) {
  const kf = keyFilePath(options);
  try {
    const existing = await fs.readFile(kf);
    if (existing.length === 32) return existing;
  } catch {
    // Generate new key file below
  }
  await fs.mkdir(path.dirname(kf), { recursive: true, mode: 0o700 });
  const keyBytes = randomBytes(32);
  const tmp = `${kf}.${randomBytes(6).toString("hex")}.tmp`;
  await fs.writeFile(tmp, keyBytes, { mode: 0o600 });
  await fs.rename(tmp, kf);
  try { await fs.chmod(kf, 0o600); } catch {}
  return keyBytes;
}

function deriveAesKey(rawVaultKey, deviceId, saltHex = "") {
  const hostContext = `${os.hostname()}:${os.userInfo().username || "user"}:${SERVICE_NAME}:${deviceId}:${saltHex}`;
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
  try { await fs.chmod(dir, 0o700); } catch {}
  const tmp = `${filePath}.${randomBytes(6).toString("hex")}.tmp`;
  const serialized = JSON.stringify(data, null, 2);
  await fs.writeFile(tmp, serialized, { mode: 0o600 });
  try { await fs.chmod(tmp, 0o600); } catch {}
  await fs.rename(tmp, filePath);
  try { await fs.chmod(filePath, 0o600); } catch {}
}

export async function saveCredentials(
  { deviceId, token, platform = "windows", accountId = null },
  options = {}
) {
  const validId = validateDeviceId(deviceId);
  const validToken = validateToken(token);
  const file = credentialPath(options);
  const adapter = options.secretStoreAdapter || defaultWindowsSecretAdapter;
  const savedAt = new Date().toISOString();

  let record;
  let usedOsStore = false;
  if (await adapter.isAvailable().catch(() => false)) {
    try {
      if (typeof adapter.setSecret === "function") {
        await adapter.setSecret(validId, validToken);
        record = {
          version: 2,
          deviceId: validId,
          platform: platform || "windows",
          accountId: accountId || null,
          storageBackend: adapter.name || "os-credential-manager",
          savedAt
        };
        usedOsStore = true;
      } else if (typeof adapter.protect === "function") {
        const dpapiBlob = await adapter.protect(validId, validToken);
        if (dpapiBlob) {
          record = {
            version: 2,
            deviceId: validId,
            platform: platform || "windows",
            accountId: accountId || null,
            storageBackend: "windows-dpapi",
            dpapiBlob,
            savedAt
          };
          usedOsStore = true;
        }
      }
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
      platform: platform || "windows",
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

  const adapter = options.secretStoreAdapter || defaultWindowsSecretAdapter;
  let token = null;

  try {
    if (data.storageBackend && data.storageBackend !== "aes-256-gcm-file" && !data.dpapiBlob && !data.encrypted && !data.token) {
      if (typeof adapter.getSecret === "function") {
        token = await adapter.getSecret(data.deviceId);
      }
    } else if (data.storageBackend === "windows-dpapi" && typeof data.dpapiBlob === "string") {
      if (typeof adapter.unprotect === "function") {
        token = await adapter.unprotect(data.deviceId, data.dpapiBlob);
      }
    } else if (data.encrypted && typeof data.encrypted === "object") {
      const vaultKey = await fs.readFile(keyFilePath(options));
      const saltHex = typeof data.encrypted.salt === "string" ? data.encrypted.salt : "";
      const aesKey = deriveAesKey(vaultKey, data.deviceId, saltHex);
      token = decryptAesGcm(data.encrypted, aesKey);
    } else if (typeof data.token === "string") {
      // Legacy B4 compatibility: read existing plaintext token and transparently upgrade if writable
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
    platform: data.platform || "windows",
    accountId: data.accountId || null,
    storageBackend: data.storageBackend || "legacy-file",
    savedAt: data.savedAt || null
  };
}

export async function rotateCredentials(
  { deviceId, newToken, platform = "windows", accountId = null },
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
  const adapter = options.secretStoreAdapter || defaultWindowsSecretAdapter;

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
