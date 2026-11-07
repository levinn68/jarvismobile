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

export const TEXT_EXTENSIONS = new Set([
  ".txt", ".md", ".markdown", ".json", ".csv", ".tsv", ".xml", ".yaml", ".yml",
  ".log", ".ini", ".cfg", ".conf", ".toml", ".properties", ".html", ".htm",
  ".css", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".kt", ".kts",
  ".java", ".py", ".rb", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".cs",
  ".sql", ".sh", ".zsh", ".bash", ".gradle", ".plist"
]);

const TEXT_BASENAMES = new Set([
  "readme", "license", "makefile", "dockerfile", ".gitignore", ".editorconfig"
]);

const appIndex = new Map();
const bundleIndex = new Map();
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

export function platformName(options = {}) {
  const p = options.platformOverride || process.platform;
  if (p === "darwin" || p === "macos") return "macos";
  if (p === "linux") return "linux";
  throw new Error("This connector supports macOS and Linux only.");
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
      return isResultCacheFresh(savedAtById.get(id), now) && sanitizeCachedResult(res) !== null;
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
    // Missing or invalid cache is treated as empty.
  }
}

export async function persistResultCache(options = {}) {
  const payload = buildResultCachePayload(pendingAcknowledgements, pendingAcknowledgementSavedAt);
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
  const platform = platformName(options);
  const existingCreds = await loadCredentials(options).catch(() => null);
  const deviceId = options.deviceId || existingCreds?.deviceId || platform + "-" + randomUUID();
  const payload = {
    deviceId,
    platform,
    name: String(options.deviceName || os.hostname()).slice(0, 80),
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
    { deviceId: enrolledId, token: result.token, platform, accountId },
    options
  );

  // Immediately clear one-time pairing secrets from process environment memory
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
  return String(raw)
    .split(path.delimiter)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

export async function resolvedRoots(options = {}) {
  const roots = [];
  for (const entry of allowedRoots(options)) {
    try {
      const real = await fs.realpath(path.resolve(entry));
      if ((await fs.stat(real)).isDirectory()) roots.push(real);
    } catch (error) {
      if (!options.silent) {
        process.stderr.write("Ignoring unavailable approved folder: " + entry + " (" + error.message + ")\n");
      }
    }
  }
  return roots.slice(0, 20);
}

export function normalizedPosixPath(value, caseInsensitive = false) {
  let resolved = path.resolve(String(value ?? "").normalize("NFC"));
  if (caseInsensitive) {
    resolved = resolved.toLocaleLowerCase("en-US");
  }
  return resolved;
}

export function isInside(root, target, options = {}) {
  const caseInsensitive = Boolean(options.caseInsensitive);
  const safeRoot = normalizedPosixPath(root, caseInsensitive);
  const safeTargetPath = normalizedPosixPath(target, caseInsensitive);
  const relative = path.relative(safeRoot, safeTargetPath);
  return (
    relative === "" ||
    (!relative.startsWith(".." + path.sep) &&
      relative !== ".." &&
      !path.isAbsolute(relative))
  );
}

function validatePosixRelativePath(relativePath) {
  if (typeof relativePath !== "string") {
    throw new Error("A relative path inside an approved folder is required.");
  }
  if (/[\x00-\x1f\x7f]/.test(relativePath)) {
    throw new Error("Path contains invalid control characters.");
  }
  // Normalize Unicode NFC so macOS APFS/HFS+ NFD/NFC edge cases are canonicalized
  const normalized = relativePath.normalize("NFC");
  if (path.isAbsolute(normalized) || normalized.startsWith("~")) {
    throw new Error("A relative path inside an approved folder is required.");
  }
  const segments = normalized.split("/").filter(Boolean);
  for (const seg of segments) {
    if (seg === "..") {
      throw new Error("Path escapes the approved folder.");
    }
  }
  return normalized;
}

export async function safeTarget(root, relativePath, mustExist = true) {
  const cleanRel = validatePosixRelativePath(relativePath);
  const canonicalRoot = mustExist ? await fs.realpath(root) : path.resolve(root);
  const target = path.resolve(canonicalRoot, cleanRel || ".");
  if (!isInside(canonicalRoot, target)) {
    throw new Error("Path escapes the approved folder.");
  }

  // Stepwise component lstat + realpath check to catch intermediate symlink escapes
  // and support Unicode NFC/NFD equivalence across macOS APFS/HFS+ and Linux filesystems.
  const segments = cleanRel.split("/").filter((s) => s && s !== ".");
  let current = canonicalRoot;
  for (let i = 0; i < segments.length; i++) {
    let seg = segments[i];
    let candidate = path.join(current, seg);
    let lst;
    try {
      lst = await fs.lstat(candidate);
    } catch (err) {
      if (err && err.code === "ENOENT") {
        const entries = await fs.readdir(current).catch(() => []);
        const match = entries.find((e) => e.normalize("NFC") === seg.normalize("NFC"));
        if (match) {
          candidate = path.join(current, match);
          lst = await fs.lstat(candidate);
        }
      }
      if (!lst) {
        if (mustExist) throw err;
        current = candidate;
        continue;
      }
    }
    if (lst.isSymbolicLink()) {
      const resolvedLink = await fs.realpath(candidate);
      if (!isInside(canonicalRoot, resolvedLink)) {
        throw new Error("Resolved path escapes the approved folder.");
      }
    }
    current = candidate;
  }

  const real = mustExist ? await fs.realpath(current) : current;
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
    if (byte === 0x00) return true;
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
    if (entry.name.startsWith(".") && entry.name !== ".well-known") continue;
    const full = path.join(realDir, entry.name);
    try {
      const lst = await fs.lstat(full);
      const realFull = lst.isSymbolicLink() ? await fs.realpath(full) : full;
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
  let entries;
  try {
    entries = await fs.readdir(directory, { withFileTypes: true });
  } catch (err) {
    return { ok: false, error: "Permission denied or unable to read directory." };
  }
  const items = [];
  for (const entry of entries.slice(0, 200)) {
    const full = path.join(directory, entry.name);
    try {
      const realFull = await fs.realpath(full);
      if (!isInside(root, realFull)) continue;
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
  const query = String(params.query || "").trim().normalize("NFC").toLocaleLowerCase();
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
      const baseNormalized = path.basename(full).normalize("NFC").toLocaleLowerCase();
      if (baseNormalized.includes(query)) {
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

function parseMacosInfoPlist(xmlText) {
  if (typeof xmlText !== "string" || !xmlText.includes("<plist")) return {};
  const extractKey = (keyName) => {
    const regex = new RegExp(`<key>\\s*${keyName}\\s*</key>\\s*<string>([^<]+)</string>`, "i");
    const match = xmlText.match(regex);
    return match ? match[1].trim() : null;
  };
  const bundleId = extractKey("CFBundleIdentifier");
  const displayName = extractKey("CFBundleDisplayName") || extractKey("CFBundleName");
  return {
    bundleId: bundleId && /^[A-Za-z0-9.-]{3,160}$/.test(bundleId) ? bundleId : null,
    displayName: displayName || null
  };
}

export function parseDesktopEntry(content, filename = "") {
  if (typeof content !== "string") return null;
  const lines = content.split(/\r?\n/);
  let inDesktopEntry = false;
  const fields = {};
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    if (line.startsWith("[") && line.endsWith("]")) {
      inDesktopEntry = line === "[Desktop Entry]";
      continue;
    }
    if (!inDesktopEntry) continue;
    const eqIdx = line.indexOf("=");
    if (eqIdx <= 0) continue;
    const key = line.slice(0, eqIdx).trim();
    const val = line.slice(eqIdx + 1).trim();
    if (!(key in fields)) fields[key] = val;
  }
  if (!fields.Name) return null;
  if (fields.Type && fields.Type !== "Application") return null;
  if (/^true$/i.test(fields.NoDisplay || "") || /^true$/i.test(fields.Hidden || "")) return null;
  if (!fields.Exec) return null;

  const desktopId = filename.endsWith(".desktop") ? filename.slice(0, -8) : filename;
  return {
    name: fields.Name,
    exec: fields.Exec,
    desktopId: desktopId || null
  };
}

function getApplicationFolders(platform, options = {}) {
  if (Array.isArray(options.appDirs) && options.appDirs.length) {
    return options.appDirs;
  }
  const envOverride =
    platform === "macos"
      ? process.env.JARVIS_MACOS_APP_DIRS
      : process.env.JARVIS_LINUX_APP_DIRS;
  if (envOverride) {
    return envOverride.split(path.delimiter).map((s) => s.trim()).filter(Boolean);
  }
  const home = os.homedir();
  return platform === "macos"
    ? ["/Applications", "/System/Applications", "/System/Applications/Utilities", path.join(home, "Applications")]
    : [
        "/usr/share/applications",
        "/usr/local/share/applications",
        "/var/lib/flatpak/exports/share/applications",
        "/var/lib/snapd/desktop/applications",
        path.join(home, ".local", "share", "applications")
      ];
}

export async function listApplications(options = {}) {
  const platform = platformName(options);
  const folders = getApplicationFolders(platform, options);
  const output = [];
  appIndex.clear();
  bundleIndex.clear();

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

          if (platform === "macos" && st.isDirectory() && entry.name.endsWith(".app")) {
            const plistPath = path.join(realFull, "Contents", "Info.plist");
            const plistRaw = await fs.readFile(plistPath, "utf8").catch(() => "");
            const plistMeta = parseMacosInfoPlist(plistRaw);
            const visibleName = entry.name.slice(0, -4);
            const appId = createHash("sha256").update(realFull).digest("base64url").slice(0, 24);
            const appEntry = {
              appId,
              name: visibleName,
              bundleId: plistMeta.bundleId || undefined,
              targetPath: realFull
            };
            appIndex.set(appId, appEntry);
            if (plistMeta.bundleId) {
              bundleIndex.set(plistMeta.bundleId.toLowerCase(), appEntry);
            }
            output.push({
              appId,
              name: visibleName,
              ...(plistMeta.bundleId ? { bundleId: plistMeta.bundleId } : {})
            });
          } else if (platform === "linux" && st.isFile() && entry.name.endsWith(".desktop")) {
            const data = await fs.readFile(realFull, "utf8").catch(() => "");
            const parsed = parseDesktopEntry(data, entry.name);
            if (!parsed) continue;
            const appId = createHash("sha256").update(realFull).digest("base64url").slice(0, 24);
            const appEntry = {
              appId,
              name: parsed.name,
              desktopId: parsed.desktopId,
              targetPath: realFull
            };
            appIndex.set(appId, appEntry);
            if (parsed.desktopId) {
              bundleIndex.set(parsed.desktopId.toLowerCase(), appEntry);
              bundleIndex.set((parsed.desktopId + ".desktop").toLowerCase(), appEntry);
            }
            output.push({
              appId,
              name: parsed.name,
              ...(parsed.desktopId ? { desktopId: parsed.desktopId } : {})
            });
          } else if (st.isDirectory() && depth > 0 && !entry.name.endsWith(".app")) {
            await visit(realFull, depth - 1);
          }
        } catch {
          continue;
        }
      }
    }

    await visit(canonicalFolder, 2);
  }

  return output.sort((a, b) => a.name.localeCompare(b.name));
}

export async function launchApp(appId, options = {}) {
  const cleanId = String(appId || "").trim();
  if (!cleanId || cleanId.length > 200 || /[^A-Za-z0-9_.-]/.test(cleanId) || cleanId.includes("..")) {
    return { ok: false, error: "Invalid application identifier." };
  }

  const platform = platformName(options);
  await listApplications(options);

  const entry = appIndex.get(cleanId) || bundleIndex.get(cleanId.toLowerCase());
  if (!entry) {
    return {
      ok: false,
      error: "App is not in the current application allowlist. Refresh apps.list first."
    };
  }

  const spawnImpl = options.spawnImpl || spawn;
  const command = platform === "macos" ? "open" : "gio";
  const args =
    platform === "macos"
      ? entry.bundleId && cleanId.toLowerCase() === entry.bundleId.toLowerCase()
        ? ["-b", entry.bundleId]
        : ["-a", entry.targetPath]
      : ["launch", entry.targetPath];

  return new Promise((resolve) => {
    let settled = false;
    try {
      const child = spawnImpl(command, args, {
        shell: false,
        stdio: "ignore",
        env: sanitizeChildEnv(process.env)
      });
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
          resolve({
            ok: true,
            launchedAppId: entry.appId,
            name: entry.name,
            ...(entry.bundleId ? { bundleId: entry.bundleId } : {}),
            ...(entry.desktopId ? { desktopId: entry.desktopId } : {})
          });
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
  const apps = await listApplications(options);

  // Also check if user supplied an exact bundleId or desktopId
  const directBundle = bundleIndex.get(wantedLower);
  if (directBundle) {
    return launchApp(directBundle.appId, options);
  }

  const matches = apps.filter((app) => app.name.trim().toLocaleLowerCase() === wantedLower);
  if (matches.length !== 1) {
    return {
      ok: false,      error: matches.length
        ? "More than one installed app has that exact name. Use apps.list and choose a specific entry."
        : "No installed app matched that exact name. Refresh apps.list and check the visible name.",
      matches: matches.map((app) => ({ appId: app.appId, name: app.name }))
    };
  }
  return launchApp(matches[0].appId, options);
}

async function readLinuxDistroInfo(osReleasePath = "/etc/os-release") {
  try {
    const raw = await fs.readFile(osReleasePath, "utf8");
    const info = {};
    for (const line of raw.split(/\r?\n/)) {
      const m = line.match(/^([A-Z_]+)=(.+)$/);
      if (!m) continue;
      info[m[1]] = m[2].replace(/^["']|["']$/g, "").trim();
    }
    return {
      id: info.ID || "linux",
      name: info.PRETTY_NAME || info.NAME || "Linux",
      versionId: info.VERSION_ID || null
    };
  } catch {
    return null;
  }
}

async function readMacosProductVersion(
  plistPath = "/System/Library/CoreServices/SystemVersion.plist"
) {
  try {
    const raw = await fs.readFile(plistPath, "utf8");
    const match = raw.match(/<key>\s*ProductVersion\s*<\/key>\s*<string>([^<]+)<\/string>/i);
    return match ? match[1].trim() : null;
  } catch {
    return null;
  }
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
  const platform = platformName(options);
  const totalMem = os.totalmem();
  const freeMem = os.freemem();
  const usedMem = Math.max(0, totalMem - freeMem);
  const cpus = os.cpus() || [];
  const roots = await resolvedRoots(options);

  const distro = platform === "linux" ? await readLinuxDistroInfo(options.osReleasePath) : null;
  const macosVersion = platform === "macos" ? await readMacosProductVersion(options.macosVersionPlistPath) : null;

  return {
    ok: true,
    deviceStatus: "online",
    lifecycleState,
    hostname: os.hostname(),
    platform,
    osType: os.type(),
    osVersion: os.release(),
    kernelVersion: os.release(),
    ...(distro ? { distro } : {}),
    ...(macosVersion ? { macosProductVersion: macosVersion } : {}),
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
      return { ok: true, apps: await listApplications(options) };
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
      return { ok: false, error: "Unsupported desktop connector action." };
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
  const platform = platformName(options);
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
    process.stdout.write("JARVIS " + platform + " connector started for " + DEVICE_ID + ".\n");
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
  const platform = platformName(options);
  const nodePath = options.nodePath || process.execPath;
  const scriptPath = options.scriptPath || fileURLToPath(import.meta.url);
  if (platform === "macos") {
    return {
      platform: "macos",
      label: "com.jarvis.desktop.agent",
      plistPath: path.join(os.homedir(), "Library", "LaunchAgents", "com.jarvis.desktop.agent.plist"),
      plistXml: [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        '<plist version="1.0"><dict>',
        '  <key>Label</key><string>com.jarvis.desktop.agent</string>',
        '  <key>ProgramArguments</key><array>',
        `    <string>${nodePath}</string>`,
        `    <string>${scriptPath}</string>`,
        '  </array>',
        '  <key>RunAtLoad</key><true/>',
        '  <key>KeepAlive</key><true/>',
        '</dict></plist>'
      ].join("\n")
    };
  }
  return {
    platform: "linux",
    unitName: "jarvis-desktop-agent.service",
    unitPath: path.join(os.homedir(), ".config", "systemd", "user", "jarvis-desktop-agent.service"),
    unitFile: [
      "[Unit]",
      "Description=JARVIS Desktop Companion Agent",
      "After=network-online.target",
      "",
      "[Service]",
      "Type=simple",
      `ExecStart="${nodePath}" "${scriptPath}"`,
      "Restart=on-failure",
      "RestartSec=10",
      "",
      "[Install]",
      "WantedBy=default.target"
    ].join("\n")
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  process.on("unhandledRejection", (reason) => {
    process.stderr.write("JARVIS desktop connector unhandled rejection: " + redactToken(reason) + "\n");
  });
  const mode = process.argv[2] || "run";
  if (mode === "--print-service-config" || mode === "service-config") {
    process.stdout.write(JSON.stringify(generateServiceConfig(), null, 2) + "\n");
  } else {
    (mode === "pair" ? pair(process.argv[3] || "") : run()).catch((error) => {
      process.stderr.write("JARVIS desktop connector: " + redactToken(error.message) + "\n");
      if (!process.exitCode) process.exitCode = 1;
    });
  }
}
