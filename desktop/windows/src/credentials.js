import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";

/**
 * Local device credential storage for JARVIS Windows agent.
 * Prefer %APPDATA%\\jarvis\\device-credentials.json. Restrictive ACLs are
 * best-effort on Windows (mode bits are largely ignored by NTFS).
 */
export function credentialPath() {
  const override = process.env.JARVIS_CREDENTIALS_FILE;
  if (override) return path.resolve(override);
  const base = process.env.APPDATA || path.join(os.homedir(), "AppData", "Roaming");
  return path.join(base, "jarvis", "device-credentials.json");
}

export async function loadCredentials() {
  const file = credentialPath();
  try {
    const raw = await fs.readFile(file, "utf8");
    const data = JSON.parse(raw);
    if (!data?.deviceId || !data?.token) return null;
    if (!/^[A-Za-z0-9_-]{8,80}$/.test(data.deviceId)) return null;
    if (typeof data.token !== "string" || data.token.length < 16) return null;
    return {
      deviceId: data.deviceId,
      token: data.token,
      platform: data.platform || "windows",
      savedAt: data.savedAt || null
    };
  } catch {
    return null;
  }
}

export async function saveCredentials({ deviceId, token, platform }) {
  if (!/^[A-Za-z0-9_-]{8,80}$/.test(deviceId)) {
    throw new Error("Invalid deviceId for credential storage");
  }
  if (!token || typeof token !== "string") {
    throw new Error("Device token is required");
  }
  const file = credentialPath();
  await fs.mkdir(path.dirname(file), { recursive: true });
  const payload = JSON.stringify(
    {
      deviceId,
      token,
      platform: platform || "windows",
      savedAt: new Date().toISOString()
    },
    null,
    2
  );
  await fs.writeFile(file, payload, { mode: 0o600 });
  return file;
}

export async function clearCredentials() {
  const file = credentialPath();
  try {
    await fs.unlink(file);
  } catch {
    // absent is fine
  }
}
