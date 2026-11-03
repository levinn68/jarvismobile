import os from "node:os";
import fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";

const API = (process.env.JARVIS_API_BASE_URL || "https://jarvis-api.alvinorasya1304.workers.dev").replace(/\/+$/, "");
const DEVICE_ID = process.env.JARVIS_DEVICE_ID || "";
const DEVICE_TOKEN = process.env.JARVIS_DEVICE_TOKEN || "";
const POLL_MS = Math.max(5000, Number(process.env.JARVIS_POLL_INTERVAL_MS || 15000));
const MAX_FILE_BYTES = 64 * 1024;
const TEXT_EXTENSIONS = new Set([".txt", ".md", ".json", ".csv", ".xml", ".yaml", ".yml", ".log", ".ini", ".html", ".css", ".js", ".mjs", ".ts", ".kt", ".java", ".py"]);
const appIndex = new Map();
const pendingAcknowledgements = new Map();

async function apiRequest(method, endpoint, token, body) {
  const response = await fetch(API + endpoint, {
    method,
    headers: { Authorization: "Bearer " + token, Accept: "application/json", ...(body ? { "Content-Type": "application/json" } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(15000)
  });
  const text = await response.text();
  let result;
  try { result = text ? JSON.parse(text) : {}; }
  catch { throw new Error("JARVIS API returned invalid JSON (HTTP " + response.status + ")"); }
  if (!response.ok) throw new Error("JARVIS API HTTP " + response.status + ": " + (result.error || "Request failed"));
  return result;
}

async function pair() {
  const pairingCode = (process.env.JARVIS_PAIRING_CODE || "").trim();
  const accountToken = process.env.JARVIS_ACCOUNT_TOKEN || "";
  const deviceId = "windows-" + randomUUID();
  const payload = {
    deviceId, platform: "windows", name: process.env.COMPUTERNAME || os.hostname(),
    osVersion: os.release(), appVersion: "1.0.0",
    capabilities: ["apps.list", "apps.launch", "system.read", "files.read"]
  };

  let result;
  if (pairingCode) {
    const response = await fetch(API + "/v1/pairing/redeem", {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ code: pairingCode, ...payload }),
      signal: AbortSignal.timeout(15000)
    });
    const text = await response.text();
    result = text ? JSON.parse(text) : {};
    if (!response.ok) throw new Error(result.error || ("HTTP " + response.status));
  } else if (accountToken) {
    result = await apiRequest("POST", "/v1/devices/enroll", accountToken, payload);
  } else {
    throw new Error("Set JARVIS_PAIRING_CODE (from Android QR) or JARVIS_ACCOUNT_TOKEN before pairing.");
  }

  const enrolledId = result.device?.deviceId || deviceId;
  process.stdout.write("Pairing succeeded.\nDevice ID: " + enrolledId + "\nDevice token (store securely; shown only once):\n" + result.token + "\n");
  process.stdout.write("Set JARVIS_DEVICE_ID and JARVIS_DEVICE_TOKEN before starting the agent.\n");
}

function allowedRoots() {
  return (process.env.JARVIS_ALLOWED_DIRS || "").split(path.delimiter).map((entry) => entry.trim()).filter(Boolean);
}

async function resolvedRoots() {
  const roots = [];
  for (const entry of allowedRoots()) {
    try {
      const real = await fs.realpath(path.resolve(entry));
      if ((await fs.stat(real)).isDirectory()) roots.push(real);
    } catch (error) {
      process.stderr.write("Ignoring unavailable approved folder: " + entry + " (" + error.message + ")\n");
    }
  }
  return roots.slice(0, 20);
}

export function normalizedForComparison(value) {
  let input = String(value);
  if (process.platform === "win32") {
    const extendedPathPrefix = "\\\\?\\";
    if (input.startsWith(extendedPathPrefix)) input = input.slice(extendedPathPrefix.length);
  }
  let resolved = path.resolve(input);
  if (process.platform === "win32") resolved = resolved.toLocaleLowerCase("en-US");
  return resolved;
}

export function isInside(root, target) {
  const safeRoot = normalizedForComparison(root);
  const safeTargetPath = normalizedForComparison(target);
  const relative = path.relative(safeRoot, safeTargetPath);
  return relative === "" || (!relative.startsWith(".." + path.sep) && relative !== ".." && !path.isAbsolute(relative));
}

export async function safeTarget(root, relativePath, mustExist = true) {
  if (typeof relativePath !== "string" || relativePath.includes("\0") || path.isAbsolute(relativePath)) {
    throw new Error("A relative path inside an approved folder is required.");
  }
  const canonicalRoot = mustExist ? await fs.realpath(root) : path.resolve(root);
  const target = path.resolve(canonicalRoot, relativePath || ".");
  if (!isInside(canonicalRoot, target)) throw new Error("Path escapes the approved folder.");
  const real = mustExist ? await fs.realpath(target) : target;
  if (!isInside(canonicalRoot, real)) throw new Error("Resolved path escapes the approved folder.");
  return real;
}

async function walkFiles(directory, depth, output, limit = 1000) {
  if (depth < 0 || output.length >= limit) return;
  let entries;
  try { entries = await fs.readdir(directory, { withFileTypes: true }); } catch { return; }
  for (const entry of entries) {
    if (output.length >= limit) break;
    if (entry.name.startsWith("$") || entry.name === "System Volume Information") continue;
    const full = path.join(directory, entry.name);
    if (entry.isFile()) output.push(full);
    else if (entry.isDirectory() && depth > 0) await walkFiles(full, depth - 1, output, limit);
  }
}

async function listFiles(params) {
  const roots = await resolvedRoots();
  if (!roots.length) return { ok: false, requiresConfiguration: true, error: "Configure JARVIS_ALLOWED_DIRS with folders the user explicitly approves." };
  const index = Number.isInteger(params.rootIndex) ? params.rootIndex : 0;
  const root = roots[index];
  if (!root) return { ok: false, error: "Invalid rootIndex. Available roots: " + roots.length };
  const directory = await safeTarget(root, params.relativePath || "");
  if (!(await fs.stat(directory)).isDirectory()) return { ok: false, error: "Selected path is not a folder." };
  const entries = await fs.readdir(directory, { withFileTypes: true });
  const items = [];
  for (const entry of entries.slice(0, 200)) {
    const full = path.join(directory, entry.name);
    let info;
    try { info = await fs.stat(full); } catch { continue; }
    items.push({ name: entry.name, isDirectory: entry.isDirectory(), size: entry.isDirectory() ? null : info.size, lastModified: info.mtimeMs });
  }
  return { ok: true, rootIndex: index, relativePath: path.relative(root, directory), items, count: items.length };
}

async function searchFiles(params) {
  const roots = await resolvedRoots();
  if (!roots.length) return { ok: false, requiresConfiguration: true, error: "Configure JARVIS_ALLOWED_DIRS with folders the user explicitly approves." };
  const selected = Number.isInteger(params.rootIndex) ? [[roots[params.rootIndex], params.rootIndex]] : roots.map((root, index) => [root, index]);
  if (selected.some(([root]) => !root)) return { ok: false, error: "Invalid rootIndex." };
  const query = params.query.toLocaleLowerCase();
  const items = [];
  for (const [root, rootIndex] of selected) {
    const files = [];
    await walkFiles(root, 4, files, 1000);
    for (const full of files) {
      if (path.basename(full).toLocaleLowerCase().includes(query)) {
        items.push({ rootIndex, relativePath: path.relative(root, full), name: path.basename(full) });
        if (items.length >= 100) break;
      }
    }
    if (items.length >= 100) break;
  }
  return { ok: true, items, count: items.length, truncated: items.length >= 100 };
}

async function readFile(params) {
  const roots = await resolvedRoots();
  const root = roots[params.rootIndex];
  if (!root) return { ok: false, error: "Invalid rootIndex." };
  const full = await safeTarget(root, params.relativePath);
  const stat = await fs.stat(full);
  if (!stat.isFile()) return { ok: false, error: "Selected path is not a file." };
  if (!TEXT_EXTENSIONS.has(path.extname(full).toLowerCase())) return { ok: false, error: "This connector reads text-based files only." };
  if (stat.size > MAX_FILE_BYTES) return { ok: false, error: "File exceeds the 64 KB read limit." };
  const content = await fs.readFile(full, "utf8");
  return { ok: true, name: path.basename(full), bytes: Buffer.byteLength(content), content };
}

async function startMenuShortcuts() {
  const folders = [
    process.env.APPDATA && path.join(process.env.APPDATA, "Microsoft", "Windows", "Start Menu", "Programs"),
    process.env.ProgramData && path.join(process.env.ProgramData, "Microsoft", "Windows", "Start Menu", "Programs")
  ].filter(Boolean);
  const output = [];
  async function visit(folder, depth) {
    if (depth < 0 || output.length >= 500) return;
    let entries;
    try { entries = await fs.readdir(folder, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      const full = path.join(folder, entry.name);
      if (entry.isDirectory()) await visit(full, depth - 1);
      else if (entry.isFile() && path.extname(entry.name).toLowerCase() === ".lnk") {
        const appId = createHash("sha256").update(full.toLowerCase()).digest("base64url").slice(0, 24);
        appIndex.set(appId, full);
        output.push({ appId, name: path.basename(entry.name, ".lnk") });
      }
      if (output.length >= 500) break;
    }
  }
  appIndex.clear();
  for (const folder of folders) await visit(folder, 3);
  return output.sort((a, b) => a.name.localeCompare(b.name));
}

async function launchApp(appId) {
  const apps = await startMenuShortcuts();
  if (!apps.some((app) => app.appId === appId)) return { ok: false, error: "App is not in the Windows Start Menu allowlist. Refresh apps.list first." };
  const target = appIndex.get(appId);
  const child = spawn("powershell.exe", ["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", "Start-Process -LiteralPath $env:JARVIS_TARGET"], {
    windowsHide: true, stdio: "ignore", env: { ...process.env, JARVIS_TARGET: target }
  });
  child.on("error", () => {});
  child.unref();
  return { ok: true, launchedAppId: appId };
}

async function launchAppByName(appName) {
  const wanted = String(appName || "").trim().toLocaleLowerCase();
  if (!wanted || wanted.length > 100) return { ok: false, error: "A valid app name is required." };
  const apps = await startMenuShortcuts();
  const matches = apps.filter((app) => app.name.trim().toLocaleLowerCase() === wanted);
  if (matches.length !== 1) {
    return {
      ok: false,
      error: matches.length ? "More than one installed app has that exact name. Use apps.list and choose a specific entry." : "No installed app matched that exact name. Refresh apps.list and check the visible name.",
      matches: matches.map((app) => ({ appId: app.appId, name: app.name }))
    };
  }
  return launchApp(matches[0].appId);
}

async function execute(command) {
  const params = command.params || {};
  switch (command.action) {
    case "system.status":
      return {
        ok: true, hostname: os.hostname(), platform: process.platform, osVersion: os.release(),
        architecture: os.arch(), cpuCount: os.cpus().length, totalMemoryBytes: os.totalmem(),
        freeMemoryBytes: os.freemem(), uptimeSeconds: os.uptime(),
        allowedFolderCount: (await resolvedRoots()).length
      };
    case "apps.list": return { ok: true, apps: await startMenuShortcuts() };
    case "apps.launch": return launchApp(params.appId);
    case "apps.launch_label": return launchAppByName(params.appName);
    case "files.list": return listFiles(params);
    case "files.search": return searchFiles(params);
    case "files.read": return readFile(params);
    default: return { ok: false, error: "Unsupported Windows connector action." };
  }
}

async function pollOnce() {
  await apiRequest("POST", "/v1/devices/" + encodeURIComponent(DEVICE_ID) + "/heartbeat", DEVICE_TOKEN);
  const response = await apiRequest("GET", "/v1/device/commands?deviceId=" + encodeURIComponent(DEVICE_ID), DEVICE_TOKEN);
  for (const command of response.commands || []) {
    if (!command?.commandId) continue;
    let result = pendingAcknowledgements.get(command.commandId);
    if (!result) {
      try { result = await execute(command); }
      catch (error) { result = { ok: false, error: String(error.message || error).slice(0, 500) }; }
      pendingAcknowledgements.set(command.commandId, result);
    }
    await apiRequest("POST", "/v1/device/commands/ack", DEVICE_TOKEN, { deviceId: DEVICE_ID, commandId: command.commandId, result });
    pendingAcknowledgements.delete(command.commandId);
  }
}

async function run() {
  if (process.platform !== "win32") throw new Error("This connector build targets Windows.");
  if (!DEVICE_ID || !DEVICE_TOKEN) throw new Error("Set JARVIS_DEVICE_ID and JARVIS_DEVICE_TOKEN first.");
  process.stdout.write("JARVIS Windows connector started for " + DEVICE_ID + ".\n");
  process.stdout.write("Approved folder roots: " + (await resolvedRoots()).length + ".\n");
  for (;;) {
    try { await pollOnce(); }
    catch (error) { process.stderr.write("Sync error: " + String(error.message || error) + "\n"); }
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const mode = process.argv[2] || "run";
  (mode === "pair" ? pair() : run()).catch((error) => {
    process.stderr.write("JARVIS Windows connector: " + error.message + "\n");
    process.exitCode = 1;
  });
}
