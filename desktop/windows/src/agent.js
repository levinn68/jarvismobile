import os from "node:os";
import fs from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { createHash, randomUUID, randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
import {
  loadCredentials,
  saveCredentials,
  clearCredentials,
  credentialPath,
  redactToken
} from "./credentials.js";

const API = (process.env.JARVIS_API_BASE_URL || "https://jarvis-api.alvinorasya1304.workers.dev").replace(/\/+$/, "");
let DEVICE_ID = process.env.JARVIS_DEVICE_ID || "";
let DEVICE_TOKEN = process.env.JARVIS_DEVICE_TOKEN || "";
let consecutiveFailures = 0;
let lifecycleState = "stopped";
let pollInFlight = false;
let shutdownRequested = false;
let activePollTimer = null;
let activeAbortController = null;

const POLL_MS = Math.max(5000, Number(process.env.JARVIS_POLL_INTERVAL_MS || 15000));
export const MAX_FILE_BYTES = 60 * 1024; // 60 KB content limit keeps JSON ACK payload under 64 KB
export const COMMAND_TTL_MS = 5 * 60 * 1000;
export const MAX_FUTURE_SKEW_MS = 60 * 1000;
const RESULT_CACHE_TTL_MS = 15 * 60 * 1000;
const RESULT_CACHE_LIMIT = 500;
const UUID_REGEX = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const WINDOWS_RESERVED_NAMES = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;

export const TEXT_EXTENSIONS = new Set([
  ".txt", ".md", ".markdown", ".json", ".csv", ".tsv", ".xml", ".yaml", ".yml",
  ".log", ".ini", ".cfg", ".conf", ".toml", ".properties", ".html", ".htm",
  ".css", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".kt", ".kts",
  ".java", ".py", ".rb", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".cs",
  ".sql", ".sh", ".bat", ".cmd", ".ps1", ".gradle"
]);

const TEXT_BASENAMES = new Set([
  "readme", "license", "makefile", "dockerfile", ".gitignore", ".editorconfig"
]);

const appIndex = new Map();
const pendingAcknowledgements = new Map();
const pendingAcknowledgementSavedAt = new Map();

export const resultCachePath = (options = {}) =>
  path.join(path.dirname(credentialPath(options)), "command-results.json");

export const revokedStatePath = (options = {}) =>
  path.join(path.dirname(credentialPath(options)), "revoked-state.json");

export function getLifecycleState() {
  return lifecycleState;
}

export function setRuntimeIdentity({ deviceId = "", token = "" } = {}) {
  DEVICE_ID = String(deviceId || "");
  DEVICE_TOKEN = String(token || "");
}

export function sanitizeChildEnv(baseEnv = process.env) {
  const clean = {};
  for (const [k, v] of Object.entries(baseEnv || {})) {
    if (/^JARVIS_.*(TOKEN|SECRET|KEY|CODE)/i.test(k)) continue;
    if (/^(DEVICE_TOKEN_SECRET|DEVICE_ENROLLMENT_SECRET|SUPABASE_)/i.test(k)) continue;
    if (/^(AWS_|AZURE_|GOOGLE_|GCP_|GITHUB_|GH_TOKEN|OPENAI_|ANTHROPIC_|GEMINI_|DEEPSEEK_|GROQ_|OPENROUTER_|FISH_|SERPAPI_|NPM_TOKEN|SSH_|PRIVATE_KEY)/i.test(k)) continue;
    clean[k] = v;
  }
  return clean;
}

export function extractPairingCode(rawInput) {
  const raw = String(rawInput || "").trim();
  if (!raw) return "";
  if (raw.toLowerCase().startsWith("jarvis-pair://")) {
    try {
      const parsed = new URL(raw);
      return String(parsed.searchParams.get("code") || "").trim().toUpperCase();
    } catch {
      const match = raw.match(/[?&]code=([A-Za-z0-9]+)/);
      return match ? match[1].toUpperCase() : "";
    }
  }
  return raw.replace(/\s+/g, "").toUpperCase();
}

export function isCommandExpired(command, now = Date.now()) {
  const createdAt = typeof command?.createdAt === "string" ? command.createdAt.trim() : "";
  if (!createdAt) return true;
  const createdMs = Date.parse(createdAt);
  if (!Number.isFinite(createdMs)) return true;
  const ageMs = now - createdMs;
  return ageMs < -MAX_FUTURE_SKEW_MS || ageMs > COMMAND_TTL_MS;
}

export function isResultCacheFresh(savedAt, now = Date.now()) {
  return Number.isFinite(savedAt) && now - savedAt >= 0 && now - savedAt < RESULT_CACHE_TTL_MS;
}

function sanitizeCachedResult(result) {
  if (!result || typeof result !== "object" || Array.isArray(result)) return null;
  try {
    const redacted = redactToken(JSON.stringify(result));
    if (redacted.length > 73728) {
      return { ok: Boolean(result.ok), truncated: true, error: "Cached result exceeded size limit" };
    }
    return JSON.parse(redacted);
  } catch {
    return null;
  }
}

export function buildResultCachePayload(results, savedAtById, now = Date.now()) {
  const valid = [...results.entries()]
    .filter(([id, res]) => {
      if (!UUID_REGEX.test(String(id || ""))) return false;
      const savedAt = savedAtById.get(id);
      return isResultCacheFresh(savedAt, now) && sanitizeCachedResult(res) !== null;
    })
    .slice(-RESULT_CACHE_LIMIT);
  return Object.fromEntries(
    valid.map(([id, result]) => [
      id,
      { savedAt: savedAtById.get(id), result: sanitizeCachedResult(result) }
    ])
  );
}

export async function loadResultCache(options = {}) {
  pendingAcknowledgements.clear();
  pendingAcknowledgementSavedAt.clear();
  try {
    const raw = JSON.parse(await fs.readFile(resultCachePath(options), "utf8"));
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return;
    const now = Date.now();
    for (const [id, entry] of Object.entries(raw)) {
      if (!UUID_REGEX.test(id)) continue;
      const sanitized = sanitizeCachedResult(entry?.result);
      if (entry && isResultCacheFresh(entry.savedAt, now) && sanitized) {
        pendingAcknowledgements.set(id, sanitized);
        pendingAcknowledgementSavedAt.set(id, entry.savedAt);
      }
    }
  } catch {
    // Missing or corrupt cache is safely treated as empty.
  }
}

export async function persistResultCache(options = {}) {
  const now = Date.now();
  const payload = buildResultCachePayload(pendingAcknowledgements, pendingAcknowledgementSavedAt, now);
  const file = resultCachePath(options);
  await fs.mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${randomBytes(6).toString("hex")}.tmp`;
  await fs.writeFile(temporary, JSON.stringify(payload), { mode: 0o600 });
  await fs.rename(temporary, file);
}

export async function clearResultCache(options = {}) {
  pendingAcknowledgements.clear();
  pendingAcknowledgementSavedAt.clear();
  try {
    await fs.unlink(resultCachePath(options));
  } catch {}
}

export function computeBackoffMs(attempt, baseMs = 500, maxMs = 15000, randomFn = Math.random) {
  const exp = Math.min(maxMs, baseMs * Math.pow(2, Math.max(0, attempt - 1)));
  const jitter = Math.floor(Math.max(0, Math.min(1, randomFn())) * Math.max(1, Math.floor(exp * 0.25)));
  return Math.min(maxMs, exp + jitter);
}

function isRetryableStatus(status) {
  return status === 408 || status === 429 || (status >= 500 && status <= 599);
}

export async function apiRequest(method, endpoint, token, body, options = {}) {
  const fetchImpl = options.fetchImpl || globalThis.fetch;
  const baseUrl = (options.baseUrl || API).replace(/\/+$/, "");
  const maxRetries = Number.isInteger(options.maxRetries) ? options.maxRetries : 3;
  const timeoutMs = Number(options.timeoutMs) || 15000;
  const baseBackoffMs = options.baseBackoffMs !== undefined ? Number(options.baseBackoffMs) : 300;

  let lastError;
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    const controller = new AbortController();
    activeAbortController = controller;
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const headers = {
        Accept: "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
        ...(body ? { "Content-Type": "application/json" } : {})
      };
      const response = await fetchImpl(baseUrl + endpoint, {
        method,
        headers,
        ...(body ? { body: JSON.stringify(body) } : {}),
        signal: controller.signal
      });
      const text = await response.text();
      let result;
      try {
        result = text ? JSON.parse(text) : {};
      } catch {
        const parseErr = new Error("JARVIS API returned invalid JSON (HTTP " + response.status + ")");
        parseErr.status = response.status >= 500 ? response.status : 502;
        throw parseErr;
      }
      if (!response.ok) {
        const err = new Error(
          redactToken("JARVIS API HTTP " + response.status + ": " + (result.error || "Request failed"))
        );
        err.status = response.status;
        err.retryAfter = Number(response.headers?.get?.("Retry-After") || result.retryAfter || 0);
        throw err;
      }
      return result;
    } catch (error) {
      lastError = error;
      const status = Number(error?.status || 0);
      // Never retry permanent auth, revocation, or validation errors (400, 401, 403, 404, 422).
      if (status && !isRetryableStatus(status)) {
        throw error;
      }
      if (attempt >= maxRetries || shutdownRequested) {
        throw error;
      }
      const retryDelay =
        error?.retryAfter > 0
          ? Math.min(error.retryAfter * 1000, 15000)
          : computeBackoffMs(attempt, baseBackoffMs, 10000);
      if (retryDelay > 0) {
        await new Promise((resolve) => setTimeout(resolve, retryDelay));
      }
    } finally {
      clearTimeout(timer);
      if (activeAbortController === controller) activeAbortController = null;
    }
  }
  throw lastError || new Error("API request failed");
}

export async function pair(cliArgCode = "", options = {}) {
  lifecycleState = "pairing";
  const pairingCode = extractPairingCode(
    options.pairingCode ?? process.env.JARVIS_PAIRING_CODE ?? cliArgCode ?? ""
  );
  const accountToken = String(options.accountToken ?? process.env.JARVIS_ACCOUNT_TOKEN ?? "").trim();
  const existingCreds = await loadCredentials(options).catch(() => null);
  const deviceId = options.deviceId || existingCreds?.deviceId || "windows-" + randomUUID();
  const payload = {
    deviceId,
    platform: "windows",
    name: String(options.deviceName || process.env.COMPUTERNAME || os.hostname()).slice(0, 80),
    osVersion: os.release(),
    appVersion: "1.1.0",
    capabilities: ["apps.list", "apps.launch", "system.read", "files.read"]
  };

  let result;
  if (pairingCode) {
    if (!/^[A-Za-z0-9]{8}$/.test(pairingCode)) {
      throw new Error("JARVIS_PAIRING_CODE must be an 8-character alphanumeric code from Android.");
    }
    result = await apiRequest(
      "POST",
      "/v1/pairing/redeem",
      "",
      { code: pairingCode, ...payload },
      options
    );
  } else if (accountToken) {
    if (accountToken.length < 16 || /\s/.test(accountToken)) {
      throw new Error("JARVIS_ACCOUNT_TOKEN is invalid or malformed.");
    }
    result = await apiRequest("POST", "/v1/devices/enroll", accountToken, payload, options);
  } else {
    throw new Error("Set JARVIS_PAIRING_CODE (from Android QR) or JARVIS_ACCOUNT_TOKEN before pairing.");
  }

  if (!result?.token || typeof result.token !== "string") {
    throw new Error("Pairing response did not include a valid device token.");
  }
  const enrolledId = result.device?.deviceId || deviceId;
  const accountId = result.accountId || result.device?.accountId || null;
  const file = await saveCredentials(
    { deviceId: enrolledId, token: result.token, platform: "windows", accountId },
    options
  );

  // Immediately purge one-time pairing secrets from process environment memory
  delete process.env.JARVIS_ACCOUNT_TOKEN;
  delete process.env.JARVIS_PAIRING_CODE;

  DEVICE_ID = enrolledId;
  DEVICE_TOKEN = result.token;
  lifecycleState = "connected";

  if (!options.silent) {
    process.stdout.write("Pairing succeeded.\nDevice ID: " + enrolledId + "\n");
    process.stdout.write("Credentials securely stored via: " + file + "\n");
    process.stdout.write("Run: npm start\n");
  }
  return { ok: true, deviceId: enrolledId, accountId, credentialsFile: file };
}

function allowedRoots(options = {}) {
  const raw = options.allowedDirs ?? process.env.JARVIS_ALLOWED_DIRS ?? "";
  const delimiter = raw.includes(";") && !raw.includes(path.delimiter) ? ";" : path.delimiter;
  return String(raw)
    .split(delimiter)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

export async function resolvedRoots(options = {}) {
  const roots = [];
  for (const entry of allowedRoots(options)) {
    try {
      const real = await fs.realpath(path.resolve(entry));
      const stat = await fs.stat(real);
      if (stat.isDirectory()) roots.push(real);
    } catch (error) {
      if (!options.silent) {
        process.stderr.write("Ignoring unavailable approved folder: " + entry + " (" + error.message + ")\n");
      }
    }
  }
  return roots.slice(0, 20);
}

export function normalizedForComparison(value, forceWindowsSemantics = process.platform === "win32") {
  let input = String(value ?? "").normalize("NFC");
  if (forceWindowsSemantics || /^[a-zA-Z]:[\\/]/.test(input) || input.startsWith("\\\\")) {
    if (input.startsWith("\\\\?\\UNC\\")) {
      input = "\\\\" + input.slice("\\\\?\\UNC\\".length);
    } else if (input.startsWith("\\\\?\\")) {
      input = input.slice("\\\\?\\".length);
    }
    let resolved = path.win32.resolve(input.replace(/\//g, "\\"));
    resolved = resolved.replace(/[\\]+$/, "") || resolved;
    return resolved.toLocaleLowerCase("en-US");
  }
  return path.resolve(input);
}

export function isInside(root, target, forceWindowsSemantics = process.platform === "win32") {
  const winMode =
    forceWindowsSemantics ||
    /^[a-zA-Z]:[\\/]/.test(String(root)) ||
    String(root).startsWith("\\\\") ||
    /^[a-zA-Z]:[\\/]/.test(String(target)) ||
    String(target).startsWith("\\\\");
  const safeRoot = normalizedForComparison(root, winMode);
  const safeTargetPath = normalizedForComparison(target, winMode);
  const pathImpl = winMode ? path.win32 : path;
  const relative = pathImpl.relative(safeRoot, safeTargetPath);
  return (
    relative === "" ||
    (!relative.startsWith(".." + pathImpl.sep) &&
      relative !== ".." &&
      !pathImpl.isAbsolute(relative))
  );
}

function validateWindowsRelativePath(relativePath) {
  if (typeof relativePath !== "string") {
    throw new Error("A relative path inside an approved folder is required.");
  }
  if (/[\x00-\x1f\x7f]/.test(relativePath)) {
    throw new Error("Path contains invalid control characters.");
  }
  const normalized = relativePath.normalize("NFC");
  // Reject absolute paths, drive-letter prefixes, UNC paths, and NTFS Alternate Data Streams (:)
  if (
    path.isAbsolute(normalized) ||
    path.win32.isAbsolute(normalized) ||
    /^[a-zA-Z]:/.test(normalized) ||
    normalized.startsWith("\\\\") ||
    normalized.startsWith("//") ||
    normalized.includes(":")
  ) {
    throw new Error("A relative path inside an approved folder is required.");
  }
  const segments = normalized.split(/[\\/]+/).filter(Boolean);
  for (const seg of segments) {
    if (seg === "..") {
      throw new Error("Path escapes the approved folder.");
    }
    // Reject trailing dots or spaces (Windows Win32 normalization bypass) and reserved device names
    if (/[. ]$/.test(seg) && seg !== ".") {
      throw new Error("Invalid Windows path segment trailing characters.");
    }
    if (WINDOWS_RESERVED_NAMES.test(seg)) {
      throw new Error("Windows reserved device names are not allowed.");
    }
  }
  return normalized;
}

export async function safeTarget(root, relativePath, mustExist = true) {
  const cleanRel = validateWindowsRelativePath(relativePath);
  const canonicalRoot = mustExist ? await fs.realpath(root) : path.resolve(root);
  const normalizedRel = cleanRel.replace(/[\\/]+/g, path.sep);
  const target = path.resolve(canonicalRoot, normalizedRel || ".");
  if (!isInside(canonicalRoot, target)) {
    throw new Error("Path escapes the approved folder.");
  }

  // Walk intermediate components with lstat + realpath to block symlink and Windows junction escapes
  const relSegments = normalizedRel.split(path.sep).filter((s) => s && s !== ".");
  let current = canonicalRoot;
  for (let i = 0; i < relSegments.length; i++) {
    current = path.join(current, relSegments[i]);
    try {
      const lst = await fs.lstat(current);
      if (lst.isSymbolicLink()) {
        const resolvedLink = await fs.realpath(current);
        if (!isInside(canonicalRoot, resolvedLink)) {
          throw new Error("Symlink or junction escapes the approved folder.");
        }
      }
    } catch (err) {
      if (err.message?.includes("escapes")) throw err;
      if (mustExist) throw err;
    }
  }

  const real = mustExist ? await fs.realpath(target) : target;
  if (!isInside(canonicalRoot, real)) {
    throw new Error("Resolved path escapes the approved folder.");
  }
  return real;
}

export function isBinaryBuffer(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0) return false;
  const sampleLen = Math.min(buffer.length, 8192);
  let suspiciousControlBytes = 0;
  for (let i = 0; i < sampleLen; i++) {
    const byte = buffer[i];
    if (byte === 0x00) return true; // NUL byte -> binary file
    if (byte < 0x07 || (byte > 0x0e && byte < 0x20 && byte !== 0x1b)) {
      suspiciousControlBytes += 1;
    }
  }
  return suspiciousControlBytes / sampleLen > 0.1;
}

function isSupportedTextFile(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (TEXT_EXTENSIONS.has(ext)) return true;
  const base = path.basename(filePath).toLowerCase();
  return TEXT_BASENAMES.has(base);
}

async function walkFiles(canonicalRoot, directory, depth, output, limit = 1000, visited = new Set()) {
  if (depth < 0 || output.length >= limit) return;
  let realDir;
  try {
    realDir = await fs.realpath(directory);
  } catch {
    return;
  }
  if (!isInside(canonicalRoot, realDir)) return;
  if (visited.has(realDir)) return;
  visited.add(realDir);

  let entries;
  try {
    entries = await fs.readdir(realDir, { withFileTypes: true });
  } catch {
    return;
  }

  for (const entry of entries) {
    if (output.length >= limit) break;
    if (entry.name.startsWith("$") || entry.name === "System Volume Information") continue;
    const full = path.join(realDir, entry.name);
    let realFull;
    try {
      const lst = await fs.lstat(full);
      realFull = lst.isSymbolicLink() ? await fs.realpath(full) : full;
      if (!isInside(canonicalRoot, realFull)) continue;
      const st = await fs.stat(realFull);
      if (st.isFile()) {
        output.push(realFull);
      } else if (st.isDirectory() && depth > 0) {
        await walkFiles(canonicalRoot, realFull, depth - 1, output, limit, visited);
      }
    } catch {
      continue;
    }
  }
}

export async function listFiles(params = {}, options = {}) {
  const roots = await resolvedRoots(options);
  if (!roots.length) {
    return {
      ok: false,
      requiresConfiguration: true,
      error: "Configure JARVIS_ALLOWED_DIRS with folders the user explicitly approves."
    };
  }
  const index = Number.isInteger(params.rootIndex) ? params.rootIndex : 0;
  const root = roots[index];
  if (!root) return { ok: false, error: "Invalid rootIndex. Available roots: " + roots.length };
  let directory;
  try {
    directory = await safeTarget(root, params.relativePath || "");
  } catch (err) {
    return { ok: false, error: err.message };
  }
  const dirStat = await fs.stat(directory).catch(() => null);
  if (!dirStat || !dirStat.isDirectory()) {
    return { ok: false, error: "Selected path is not a folder." };
  }
  const entries = await fs.readdir(directory, { withFileTypes: true });
  const items = [];
  for (const entry of entries.slice(0, 200)) {
    if (entry.name.startsWith("$") || entry.name === "System Volume Information") continue;
    const full = path.join(directory, entry.name);
    try {
      const realFull = await fs.realpath(full);
      if (!isInside(root, realFull)) continue; // Skip symlinks/junctions escaping root
      const info = await fs.stat(realFull);
      items.push({
        name: entry.name,
        isDirectory: info.isDirectory(),
        size: info.isDirectory() ? null : info.size,
        lastModified: info.mtimeMs
      });
    } catch {
      continue;
    }
  }
  return {
    ok: true,
    rootIndex: index,
    relativePath: path.relative(root, directory),
    items,
    count: items.length
  };
}

export async function searchFiles(params = {}, options = {}) {
  const roots = await resolvedRoots(options);
  if (!roots.length) {
    return {
      ok: false,
      requiresConfiguration: true,
      error: "Configure JARVIS_ALLOWED_DIRS with folders the user explicitly approves."
    };
  }
  const query = String(params.query || "").trim().toLocaleLowerCase();
  if (!query || query.length > 200) {
    return { ok: false, error: "A valid search query (1-200 characters) is required." };
  }
  const selected = Number.isInteger(params.rootIndex)
    ? [[roots[params.rootIndex], params.rootIndex]]
    : roots.map((root, index) => [root, index]);
  if (selected.some(([root]) => !root)) return { ok: false, error: "Invalid rootIndex." };

  const items = [];
  for (const [root, rootIndex] of selected) {
    const files = [];
    await walkFiles(root, root, 4, files, 1000);
    for (const full of files) {
      if (path.basename(full).toLocaleLowerCase().includes(query)) {
        items.push({
          rootIndex,
          relativePath: path.relative(root, full),
          name: path.basename(full)
        });
        if (items.length >= 100) break;
      }
    }
    if (items.length >= 100) break;
  }
  return { ok: true, items, count: items.length, truncated: items.length >= 100 };
}

export async function readFile(params = {}, options = {}) {
  const roots = await resolvedRoots(options);
  if (!roots.length) {
    return {
      ok: false,
      requiresConfiguration: true,
      error: "Configure JARVIS_ALLOWED_DIRS with folders the user explicitly approves."
    };
  }
  if (!Number.isInteger(params.rootIndex)) {
    return { ok: false, error: "Invalid rootIndex." };
  }
  const root = roots[params.rootIndex];
  if (!root) return { ok: false, error: "Invalid rootIndex." };

  let full;
  try {
    full = await safeTarget(root, params.relativePath);
  } catch (err) {
    return { ok: false, error: err.message };
  }

  if (!isSupportedTextFile(full)) {
    return { ok: false, error: "This connector reads text-based files only." };
  }

  let lstatBefore;
  try {
    lstatBefore = await fs.lstat(full);
  } catch {
    return { ok: false, error: "File not found." };
  }
  if (!lstatBefore.isFile() || lstatBefore.isSymbolicLink()) {
    return { ok: false, error: "Selected path is not a regular file." };
  }
  if (lstatBefore.size > MAX_FILE_BYTES) {
    return { ok: false, error: "File exceeds the 64 KB read limit." };
  }

  if (typeof options.beforeOpenHook === "function") {
    await options.beforeOpenHook(full);
  }

  const openFlags = fsConstants.O_RDONLY | (fsConstants.O_NOFOLLOW || 0);
  let handle;
  try {
    handle = await fs.open(full, openFlags);
    const [statAfter, lstatAfter, realAfter] = await Promise.all([
      handle.stat(),
      fs.lstat(full),
      fs.realpath(full)
    ]);

    if (
      !statAfter.isFile() ||
      !lstatAfter.isFile() ||
      lstatAfter.isSymbolicLink() ||
      !isInside(root, realAfter)
    ) {
      return { ok: false, error: "File changed or replaced during access (TOCTOU protection)." };
    }
    if (
      lstatBefore.ino &&
      statAfter.ino &&
      (lstatBefore.ino !== statAfter.ino || lstatBefore.dev !== statAfter.dev)
    ) {
      return { ok: false, error: "File inode changed during access (TOCTOU protection)." };
    }
    if (statAfter.size > MAX_FILE_BYTES) {
      return { ok: false, error: "File exceeds the 64 KB read limit." };
    }

    const buffer = Buffer.alloc(Math.min(statAfter.size || MAX_FILE_BYTES, MAX_FILE_BYTES + 1));
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    if (bytesRead > MAX_FILE_BYTES) {
      return { ok: false, error: "File exceeds the 64 KB read limit." };
    }
    const slice = buffer.subarray(0, bytesRead);
    if (isBinaryBuffer(slice)) {
      return { ok: false, error: "Binary files are not permitted; text files only." };
    }
    const content = slice.toString("utf8");
    return {
      ok: true,
      name: path.basename(full),
      bytes: Buffer.byteLength(content, "utf8"),
      content
    };
  } catch (err) {
    return { ok: false, error: redactToken(err.message || "Unable to read file") };
  } finally {
    if (handle) await handle.close().catch(() => {});
  }
}

function getStartMenuDirs(options = {}) {
  if (Array.isArray(options.startMenuDirs) && options.startMenuDirs.length) {
    return options.startMenuDirs;
  }
  if (process.env.JARVIS_WINDOWS_START_MENU_DIRS) {
    return process.env.JARVIS_WINDOWS_START_MENU_DIRS
      .split(path.delimiter)
      .map((s) => s.trim())
      .filter(Boolean);
  }
  return [
    process.env.APPDATA && path.join(process.env.APPDATA, "Microsoft", "Windows", "Start Menu", "Programs"),
    process.env.ProgramData && path.join(process.env.ProgramData, "Microsoft", "Windows", "Start Menu", "Programs")
  ].filter(Boolean);
}

export async function startMenuShortcuts(options = {}) {
  const folders = getStartMenuDirs(options);
  const output = [];
  appIndex.clear();

  for (const folder of folders) {
    let canonicalFolder;
    try {
      canonicalFolder = await fs.realpath(folder);
    } catch {
      continue;
    }

    async function visit(dir, depth) {
      if (depth < 0 || output.length >= 500) return;
      let entries;
      try {
        entries = await fs.readdir(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const entry of entries) {
        if (output.length >= 500) break;
        const full = path.join(dir, entry.name);
        try {
          const realFull = await fs.realpath(full);
          if (!isInside(canonicalFolder, realFull)) continue;
          const st = await fs.stat(realFull);
          if (st.isDirectory() && depth > 0) {
            await visit(realFull, depth - 1);
          } else if (st.isFile() && [".lnk", ".appref-ms"].includes(path.extname(entry.name).toLowerCase())) {
            const ext = path.extname(entry.name);
            const visibleName = path.basename(entry.name, ext).trim();
            if (!visibleName) continue;
            const appId = createHash("sha256").update(realFull.toLowerCase()).digest("base64url").slice(0, 24);
            appIndex.set(appId, { appId, name: visibleName, targetPath: realFull });
            output.push({ appId, name: visibleName });
          }
        } catch {
          continue;
        }
      }
    }

    await visit(canonicalFolder, 3);
  }

  return output.sort((a, b) => a.name.localeCompare(b.name));
}

export async function launchApp(appId, options = {}) {
  const cleanId = String(appId || "").trim();
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(cleanId)) {
    return { ok: false, error: "Invalid application identifier." };
  }
  const apps = await startMenuShortcuts(options);
  if (!apps.some((app) => app.appId === cleanId)) {
    return {
      ok: false,
      error: "App is not in the Windows Start Menu allowlist. Refresh apps.list first."
    };
  }
  const entry = appIndex.get(cleanId);
  if (!entry?.targetPath) {
    return { ok: false, error: "Application target could not be resolved." };
  }

  const spawnImpl = options.spawnImpl || spawn;
  return new Promise((resolve) => {
    let settled = false;
    try {
      const child = spawnImpl(
        "powershell.exe",
        [
          "-NoProfile",
          "-NonInteractive",
          "-WindowStyle",
          "Hidden",
          "-Command",
          "Start-Process -FilePath $env:JARVIS_TARGET"
        ],
        {
          windowsHide: true,
          shell: false,
          stdio: "ignore",
          env: { ...sanitizeChildEnv(process.env), JARVIS_TARGET: entry.targetPath }
        }
      );
      child.on("error", (err) => {
        if (!settled) {
          settled = true;
          resolve({ ok: false, error: redactToken("Failed to launch application: " + err.message) });
        }
      });
      setTimeout(() => {
        if (!settled) {
          settled = true;
          try { child.unref?.(); } catch {}
          resolve({ ok: true, launchedAppId: cleanId, name: entry.name });
        }
      }, 60);
    } catch (err) {
      if (!settled) {
        settled = true;
        resolve({ ok: false, error: redactToken("Failed to launch application: " + err.message) });
      }
    }
  });
}

export async function launchAppByName(appName, options = {}) {
  const wanted = String(appName || "").trim();
  if (!wanted || wanted.length > 100 || /[\x00-\x1f\x7f]/.test(wanted)) {
    return { ok: false, error: "A valid app name is required." };
  }
  const wantedLower = wanted.toLocaleLowerCase();
  const apps = await startMenuShortcuts(options);
  const matches = apps.filter((app) => app.name.trim().toLocaleLowerCase() === wantedLower);
  if (matches.length !== 1) {
    return {
      ok: false,
      error: matches.length
        ? "More than one installed app has that exact name. Use apps.list and choose a specific entry."
        : "No installed app matched that exact name. Refresh apps.list and check the visible name.",
      matches: matches.map((app) => ({ appId: app.appId, name: app.name }))
    };
  }
  return launchApp(matches[0].appId, options);
}

function checkNetworkConnected() {
  try {
    const nets = os.networkInterfaces() || {};
    for (const list of Object.values(nets)) {
      for (const iface of list || []) {
        if (!iface.internal && iface.address) return true;
      }
    }
  } catch {}
  return false;
}

export async function systemStatus(options = {}) {
  const totalMem = os.totalmem();
  const freeMem = os.freemem();
  const usedMem = Math.max(0, totalMem - freeMem);
  const cpus = os.cpus() || [];
  const roots = await resolvedRoots(options);
  return {
    ok: true,
    deviceStatus: "online",
    lifecycleState,
    hostname: os.hostname(),
    platform: "windows",
    osType: os.type(),
    osVersion: os.release(),
    osRelease: typeof os.version === "function" ? os.version() : os.release(),
    architecture: os.arch(),
    nodeVersion: process.version,
    cpuCount: cpus.length,
    cpuModel: cpus[0]?.model ? String(cpus[0].model).trim().slice(0, 120) : "unknown",
    totalMemoryBytes: totalMem,
    freeMemoryBytes: freeMem,
    usedMemoryBytes: usedMem,
    memoryUsagePercent: totalMem > 0 ? Math.round((usedMem / totalMem) * 100) : 0,
    uptimeSeconds: Math.floor(os.uptime()),
    networkConnected: checkNetworkConnected(),
    allowedFolderCount: roots.length
  };
}

export async function execute(command, options = {}) {
  if (!command || typeof command !== "object") {
    return { ok: false, error: "Invalid command payload." };
  }
  const params = command.params && typeof command.params === "object" ? command.params : {};
  switch (command.action) {
    case "system.status":
      return systemStatus(options);
    case "apps.list":
      return { ok: true, apps: await startMenuShortcuts(options) };
    case "apps.launch":
      return launchApp(params.appId, options);
    case "apps.launch_label":
      return launchAppByName(params.appName, options);
    case "files.list":
      return listFiles(params, options);
    case "files.search":
      return searchFiles(params, options);
    case "files.read":
      return readFile(params, options);
    default:
      return { ok: false, error: "Unsupported Windows connector action." };
  }
}

export async function handleRevocation(reason = "Device revoked or token rejected", options = {}) {
  lifecycleState = "revoked";
  shutdownRequested = true;
  if (activePollTimer) {
    clearTimeout(activePollTimer);
    activePollTimer = null;
  }
  if (activeAbortController) {
    try { activeAbortController.abort(); } catch {}
    activeAbortController = null;
  }
  const revokedDeviceId = DEVICE_ID;
  DEVICE_ID = "";
  DEVICE_TOKEN = "";

  await clearCredentials(options).catch(() => {});
  await clearResultCache(options).catch(() => {});

  try {
    const revFile = revokedStatePath(options);
    await fs.mkdir(path.dirname(revFile), { recursive: true, mode: 0o700 });
    await fs.writeFile(
      revFile,
      JSON.stringify({
        revoked: true,
        deviceId: revokedDeviceId || null,
        reason: redactToken(reason),
        revokedAt: new Date().toISOString()
      }, null, 2),
      { mode: 0o600 }
    );
  } catch {}

  process.exitCode = 2;
  return { revoked: true, exitCode: 2 };
}

export async function pollOnce(options = {}) {
  if (pollInFlight) return { skipped: true, reason: "poll_in_flight" };
  if (lifecycleState === "revoked" || shutdownRequested) {
    return { skipped: true, reason: lifecycleState };
  }
  pollInFlight = true;
  try {
    await apiRequest(
      "POST",
      "/v1/devices/" + encodeURIComponent(DEVICE_ID) + "/heartbeat",
      DEVICE_TOKEN,
      undefined,
      options
    );
    lifecycleState = "connected";

    const response = await apiRequest(
      "GET",
      "/v1/device/commands?deviceId=" + encodeURIComponent(DEVICE_ID),
      DEVICE_TOKEN,
      undefined,
      options
    );

    const commands = Array.isArray(response?.commands) ? response.commands : [];
    for (const command of commands) {
      if (shutdownRequested || lifecycleState === "revoked") break;
      if (!command?.commandId || !UUID_REGEX.test(String(command.commandId))) continue;

      let result = pendingAcknowledgements.get(command.commandId);
      if (result && !isResultCacheFresh(pendingAcknowledgementSavedAt.get(command.commandId))) {
        pendingAcknowledgements.delete(command.commandId);
        pendingAcknowledgementSavedAt.delete(command.commandId);
        result = undefined;
      }

      if (!result) {
        if (isCommandExpired(command)) {
          result = { ok: false, expired: true, error: "Command expired or has an invalid timestamp." };
        } else {
          try {
            result = await execute(command, options);
          } catch (error) {
            result = { ok: false, error: redactToken(String(error.message || error)).slice(0, 500) };
          }
        }
        pendingAcknowledgements.set(command.commandId, result);
        pendingAcknowledgementSavedAt.set(command.commandId, Date.now());
        await persistResultCache(options);
      }

      await apiRequest(
        "POST",
        "/v1/device/commands/ack",
        DEVICE_TOKEN,
        {
          deviceId: DEVICE_ID,
          commandId: command.commandId,
          result
        },
        options
      );
      pendingAcknowledgements.delete(command.commandId);
      pendingAcknowledgementSavedAt.delete(command.commandId);
      await persistResultCache(options);
    }
    return { ok: true, processed: commands.length };
  } finally {
    pollInFlight = false;
  }
}

export async function resolveIdentity(options = {}) {
  if (DEVICE_ID && DEVICE_TOKEN) return { deviceId: DEVICE_ID, token: DEVICE_TOKEN };
  const stored = await loadCredentials(options);
  if (stored) {
    DEVICE_ID = stored.deviceId;
    DEVICE_TOKEN = stored.token;
    if (!options.silent) {
      process.stdout.write("Loaded credentials from " + credentialPath(options) + "\n");
    }
    return stored;
  }
  return null;
}

export function stopAgent() {
  shutdownRequested = true;
  if (lifecycleState !== "revoked") lifecycleState = "stopped";
  if (activePollTimer) {
    clearTimeout(activePollTimer);
    activePollTimer = null;
  }
  if (activeAbortController) {
    try { activeAbortController.abort(); } catch {}
    activeAbortController = null;
  }
}

export async function run(options = {}) {
  const targetPlatform = options.platformOverride || process.platform;
  if (targetPlatform !== "win32") {
    throw new Error("This connector build targets Windows.");
  }
  shutdownRequested = false;
  lifecycleState = "startup";

  await resolveIdentity(options);
  await loadResultCache(options);
  if (!DEVICE_ID || !DEVICE_TOKEN) {
    throw new Error(
      "Missing credentials. Pair first (JARVIS_PAIRING_CODE=XXXXXXXX npm run pair) or set JARVIS_DEVICE_ID and JARVIS_DEVICE_TOKEN."
    );
  }

  const onSignal = () => {
    stopAgent();
  };
  process.once("SIGINT", onSignal);
  process.once("SIGTERM", onSignal);

  if (!options.silent) {
    process.stdout.write("JARVIS Windows connector started for " + DEVICE_ID + ".\n");
    process.stdout.write("Approved folder roots: " + (await resolvedRoots(options)).length + ".\n");
  }

  try {
    while (!shutdownRequested) {
      try {
        await pollOnce(options);
        consecutiveFailures = 0;
      } catch (error) {
        consecutiveFailures += 1;
        const status = Number(error?.status || 0);
        if (!options.silent) {
          process.stderr.write("Sync error: " + redactToken(String(error.message || error)) + "\n");
        }
        if (status === 401 || status === 404) {
          if (!options.silent) {
            process.stderr.write("Device token rejected or device revoked. Clearing local credentials and exiting.\n");
          }
          await handleRevocation(error.message || "HTTP " + status, options);
          return { exitCode: 2, state: "revoked" };
        }
        lifecycleState = consecutiveFailures > 1 ? "reconnecting" : "offline";
        if (options.maxIterations && consecutiveFailures >= options.maxIterations) break;
        const delay = computeBackoffMs(consecutiveFailures, POLL_MS, 120000);
        await new Promise((resolve) => {
          activePollTimer = setTimeout(resolve, delay);
        });
        continue;
      }
      if (options.maxIterations && options.maxIterations <= 1) break;
      await new Promise((resolve) => {
        activePollTimer = setTimeout(resolve, options.pollIntervalMs || POLL_MS);
      });
    }
  } finally {
    process.removeListener("SIGINT", onSignal);
    process.removeListener("SIGTERM", onSignal);
  }
  return { exitCode: process.exitCode || 0, state: lifecycleState };
}

export function generateServiceConfig(options = {}) {
  const nodePath = options.nodePath || process.execPath;
  const scriptPath = options.scriptPath || fileURLToPath(import.meta.url);
  const taskName = options.taskName || "JARVISWindowsCompanionAgent";
  return {
    platform: "windows",
    taskName,
    schtasksCreateCommand: `schtasks /Create /SC ONLOGON /TN "${taskName}" /TR "\"${nodePath}\" \"${scriptPath}\"" /RL LIMITED /F`,
    schtasksQueryCommand: `schtasks /Query /TN "${taskName}"`,
    schtasksDeleteCommand: `schtasks /Delete /TN "${taskName}" /F`
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  process.on("unhandledRejection", (reason) => {
    process.stderr.write("JARVIS Windows connector unhandled rejection: " + redactToken(reason) + "\n");
  });
  const mode = process.argv[2] || "run";
  if (mode === "--print-service-config" || mode === "service-config") {
    process.stdout.write(JSON.stringify(generateServiceConfig(), null, 2) + "\n");
  } else {
    (mode === "pair" ? pair(process.argv[3] || "") : run()).catch((error) => {
      process.stderr.write("JARVIS Windows connector: " + redactToken(error.message) + "\n");
      if (!process.exitCode) process.exitCode = 1;
    });
  }
}
