// JARVIS Desktop Agent — macOS (Node.js)
import os from "node:os";
import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { loadCredentials, saveCredentials, credentialPath } from "./credentials.js";

const API = (process.env.JARVIS_API_BASE_URL || "https://jarvis-api.alvinorasya1304.workers.dev").replace(/\/+$/, "");
let DEVICE_ID = process.env.JARVIS_DEVICE_ID || "";
let DEVICE_TOKEN = process.env.JARVIS_DEVICE_TOKEN || "";
const POLL_MS = Math.max(5000, Number(process.env.JARVIS_POLL_INTERVAL_MS || 15000));
const MAX_FILE_BYTES = 64 * 1024;
const TEXT_EXTENSIONS = new Set([".txt", ".md", ".json", ".csv", ".xml", ".yaml", ".yml", ".log", ".ini", ".html", ".css", ".js", ".mjs", ".ts", ".kt", ".java", ".py"]);
const appIndex = new Map();
const pendingAcknowledgements = new Map();
let consecutiveFailures = 0;

function platformName() {
  if (process.platform === "darwin") return "macos";
  if (process.platform === "linux") return "linux";
  throw new Error("This connector supports macOS and Linux only.");
}

async function apiRequest(method, endpoint, token, body) {
  const response = await fetch(API + endpoint, {
    method,
    headers: { Authorization: "Bearer " + token, Accept: "application/json", ...(body ? { "Content-Type": "application/json" } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  const text = await response.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  if (!response.ok) {
    const err = new Error(data.error || data.message || ("HTTP " + response.status));
    err.status = response.status;
    err.data = data;
    throw err;
  }
  return data;
}

async function pair() {
  const code = (process.env.JARVIS_PAIRING_CODE || process.argv[3] || "").trim().toUpperCase();
  if (!/^[A-Z0-9]{6,12}$/.test(code)) throw new Error("Provide pairing code: npm run pair -- <CODE>");
  const name = process.env.JARVIS_DEVICE_NAME || (os.hostname() + "-" + platformName());
  const result = await apiRequest("POST", "/v1/devices/pair/redeem", null, {
    code,
    platform: platformName(),
    name,
    capabilities: ["files.list", "files.read", "apps.list", "apps.launch", "system.read"]
  });
  const deviceId = result.deviceId || result.device?.deviceId;
  const token = result.token || result.deviceToken || result.device?.token;
  if (!deviceId || !token) throw new Error("Pairing response missing deviceId/token");
  await saveCredentials({ deviceId, token, platform: platformName() });
  DEVICE_ID = deviceId;
  DEVICE_TOKEN = token;
  console.log("Paired as", deviceId, "credentials:", credentialPath());
  return result;
}

// NOTE: Full agent implementation is in the release ZIP and unix agent (shared Node runtime).
// This stub ensures package structure; apply full agent from desktop/unix/src/agent.js if incomplete.
export function isInside(root, target) {
  const resolvedRoot = path.resolve(root);
  const resolvedTarget = path.resolve(target);
  return resolvedTarget === resolvedRoot || resolvedTarget.startsWith(resolvedRoot + path.sep);
}

export async function safeTarget(root, relativePath, mustExist = true) {
  if (!relativePath || typeof relativePath !== "string") throw new Error("relative path required");
  if (path.isAbsolute(relativePath)) throw new Error("relative path required");
  const target = path.resolve(root, relativePath);
  if (!isInside(root, target)) throw new Error("path escapes approved root");
  if (mustExist) {
    const st = await fs.lstat(target);
    if (st.isSymbolicLink()) {
      const real = await fs.realpath(target);
      if (!isInside(root, real)) throw new Error("symlink escapes approved root");
    }
  }
  return target;
}

async function run() {
  if (process.argv[2] === "pair") {
    await pair();
    return;
  }
  const creds = await loadCredentials();
  if (!creds && !(DEVICE_ID && DEVICE_TOKEN)) {
    console.error("No credentials. Run: npm run pair -- <CODE>");
    process.exitCode = 1;
    return;
  }
  if (creds) {
    DEVICE_ID = creds.deviceId;
    DEVICE_TOKEN = creds.token;
  }
  console.log("JARVIS macOS agent online", DEVICE_ID);
  // Poll loop is identical to unix agent — prefer launching desktop/unix agent on macOS
  // or copy full agent.js from desktop/unix/src/agent.js for complete command execution.
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  run().catch((error) => {
    process.stderr.write("JARVIS desktop connector: " + error.message + "\n");
    process.exitCode = 1;
  });
}
