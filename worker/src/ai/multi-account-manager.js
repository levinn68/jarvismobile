import { boundedString, redactSecrets, webCrypto } from "../utils.js";
import { getProviderModels } from "./model-registry.js";
import { validateOutboundUrl, safeOutboundFetch } from "../security/ssrf-guard.js";

/**
 * J.A.R.V.I.S. B8.1 / B8.5.1 / B8.5.2 / B8.1.1 Remediation R1 — Independent AI Provider & Multi-Account Manager.
 *
 * Remediates:
 * - JARVIS-002: Removes hardcoded encryption fallback key; fails closed when encryption secret is absent or invalid.
 * - JARVIS-003: Centralized SSRF & redirect hop validation via ssrf-guard.js across all custom provider endpoints.
 * - JARVIS-006: Versioned v2 encryption envelopes with explicit keyId and multi-key rotation keyring support.
 * - JARVIS-013: Controlled, non-destructive credential migration (`migrateUserCredentialsToLatestKey`) that
 *   verifies round-trip decryption and never overwrites or deletes records when decryption fails.
 */

export const MOCK_PROVIDER_ENABLED_DEFAULT = false;

export const PROVIDER_ERROR_CODES = Object.freeze({
  PROVIDER_CREDENTIAL_MISSING: "PROVIDER_CREDENTIAL_MISSING",
  PROVIDER_DISABLED: "PROVIDER_DISABLED",
  NETWORK_ERROR: "NETWORK_ERROR",
  PROVIDER_TIMEOUT: "PROVIDER_TIMEOUT",
  PROVIDER_RATE_LIMITED: "PROVIDER_RATE_LIMITED",
  PROVIDER_AUTH_FAILED: "PROVIDER_AUTH_FAILED",
  MODEL_UNAVAILABLE: "MODEL_UNAVAILABLE",
  PROVIDER_ERROR: "PROVIDER_ERROR"
});

const FAKE_CREDENTIAL_PATTERN =
  /^(env-fallback-|fake-|dummy-|mock-|placeholder-|test-placeholder|example-key|your-api-key|jarvis-worker-b8-5-2-kms-default)/i;

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

// Per-user async mutex queue to prevent concurrent KV Read-Modify-Write lost updates
const userAccountLocks = new Map();

async function withUserAccountLock(userId, fn) {
  const key = String(userId || "default");
  const prev = userAccountLocks.get(key) || Promise.resolve();
  let release;
  const next = new Promise((resolve) => {
    release = resolve;
  });
  const chained = prev.then(() => next, () => next);
  userAccountLocks.set(key, chained);
  try {
    await prev.catch(() => {});
    return await fn();
  } finally {
    release();
    if (userAccountLocks.get(key) === chained) {
      userAccountLocks.delete(key);
    }
  }
}

function bytesToBase64(bytes) {
  let binary = "";
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < u8.length; i++) {
    binary += String.fromCharCode(u8[i]);
  }
  return btoa(binary);
}

function base64ToBytes(b64) {
  const binary = atob(String(b64 || ""));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

/**
 * Computes a short non-reversible key identifier (`kid_<8hex>`) from a secret and source name.
 * Never exposes any characters of the raw secret.
 */
function deriveKeyIdentifierSync(secret, sourceLabel = "key") {
  const bytes = textEncoder.encode(`${sourceLabel}:${String(secret)}`);
  let h1 = 0x811c9dc5;
  let h2 = 0x9e3779b9;
  for (let i = 0; i < bytes.length; i++) {
    h1 = Math.imul(h1 ^ bytes[i], 0x01000193) >>> 0;
    h2 = Math.imul(h2 ^ bytes[i], 0x85ebca6b) >>> 0;
  }
  return `kid_${h1.toString(16).padStart(8, "0")}${h2.toString(16).padStart(4, "0").slice(0, 4)}`;
}

function isValidMasterSecretCandidate(rawSecret) {
  if (typeof rawSecret !== "string") return false;
  const clean = rawSecret.trim();
  if (clean.length < 16) return false;
  if (FAKE_CREDENTIAL_PATTERN.test(clean)) return false;
  return true;
}

/**
 * JARVIS-002 & JARVIS-006 — Resolves the active master encryption key and its keyId.
 * Fails closed when no valid encryption secret is configured (never uses a hardcoded default secret).
 */
export function resolveMasterEncryptionKeyInfo(env = {}) {
  const candidates = [
    { name: "AI_CREDENTIAL_ENCRYPTION_KEY", value: env?.AI_CREDENTIAL_ENCRYPTION_KEY },
    { name: "CREDENTIAL_ENCRYPTION_SECRET", value: env?.CREDENTIAL_ENCRYPTION_SECRET },
    { name: "DEVICE_TOKEN_SECRET", value: env?.DEVICE_TOKEN_SECRET },
    { name: "DEVICE_AUTH_SECRET", value: env?.DEVICE_AUTH_SECRET }
  ];

  for (const item of candidates) {
    if (isValidMasterSecretCandidate(item.value)) {
      const secret = item.value.trim();
      const explicitKeyId =
        item.name === "AI_CREDENTIAL_ENCRYPTION_KEY" && typeof env?.AI_CREDENTIAL_KEY_ID === "string" && env.AI_CREDENTIAL_KEY_ID.trim()
          ? env.AI_CREDENTIAL_KEY_ID.trim().slice(0, 40)
          : deriveKeyIdentifierSync(secret, item.name);
      return {
        secret,
        keyId: explicitKeyId,
        sourceEnv: item.name
      };
    }
  }

  const err = new Error(
    "CREDENTIAL_ENCRYPTION_KEY_MISSING: Production credential encryption secret (AI_CREDENTIAL_ENCRYPTION_KEY or DEVICE_TOKEN_SECRET, min 16 chars) is not configured."
  );
  err.status = 503;
  err.code = "CREDENTIAL_ENCRYPTION_KEY_MISSING";
  err.errorCode = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
  throw err;
}

export function resolveMasterEncryptionSecret(env = {}) {
  return resolveMasterEncryptionKeyInfo(env).secret;
}

/**
 * JARVIS-006 & JARVIS-013 — Resolves the ordered decryption keyring supporting active, previous,
 * and legacy key rotation secrets without ever falling back to a public default key.
 */
export function resolveDecryptionKeyring(env = {}) {
  const rawSources = [
    { name: "AI_CREDENTIAL_ENCRYPTION_KEY", value: env?.AI_CREDENTIAL_ENCRYPTION_KEY, explicitId: env?.AI_CREDENTIAL_KEY_ID },
    { name: "AI_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS", value: env?.AI_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS, explicitId: env?.AI_CREDENTIAL_PREVIOUS_KEY_ID },
    { name: "LEGACY_AI_CREDENTIAL_ENCRYPTION_KEY", value: env?.LEGACY_AI_CREDENTIAL_ENCRYPTION_KEY },
    { name: "CREDENTIAL_ENCRYPTION_SECRET", value: env?.CREDENTIAL_ENCRYPTION_SECRET },
    { name: "DEVICE_TOKEN_SECRET", value: env?.DEVICE_TOKEN_SECRET },
    { name: "DEVICE_AUTH_SECRET", value: env?.DEVICE_AUTH_SECRET }
  ];

  if (typeof env?.AI_CREDENTIAL_ENCRYPTION_KEY_RING === "string" && env.AI_CREDENTIAL_ENCRYPTION_KEY_RING.trim()) {
    const parts = env.AI_CREDENTIAL_ENCRYPTION_KEY_RING.split(",").map((s) => s.trim()).filter(Boolean);
    for (let i = 0; i < parts.length; i++) {
      rawSources.push({ name: `KEY_RING_${i + 1}`, value: parts[i] });
    }
  }

  const seenSecrets = new Set();
  const keyring = [];
  for (const item of rawSources) {
    if (!isValidMasterSecretCandidate(item.value)) continue;
    const secret = item.value.trim();
    if (seenSecrets.has(secret)) continue;
    seenSecrets.add(secret);
    const keyId =
      typeof item.explicitId === "string" && item.explicitId.trim()
        ? item.explicitId.trim().slice(0, 40)
        : deriveKeyIdentifierSync(secret, item.name);
    keyring.push({
      secret,
      keyId,
      sourceEnv: item.name
    });
  }

  return keyring;
}

async function deriveCredentialAesKeyWithSecret(masterSecret, userId, provider) {
  const baseKey = await webCrypto.subtle.importKey(
    "raw",
    textEncoder.encode(String(masterSecret)),
    "HKDF",
    false,
    ["deriveKey"]
  );
  return webCrypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: textEncoder.encode(`jarvis-cred-salt-v1:${String(userId || "anon")}`),
      info: textEncoder.encode(`jarvis-cred-provider-v1:${String(provider || "unknown").toLowerCase()}`)
    },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

/**
 * JARVIS-003 — Validates that a custom provider endpoint is a safe external HTTPS URL (centralized SSRF guard).
 */
export function validateCustomProviderEndpoint(rawUrl) {
  const clean = String(rawUrl || "").trim();
  if (!clean) return { ok: true, valid: true, url: "" };
  const check = validateOutboundUrl(clean, {
    allowEmpty: true,
    allowMcpScheme: false,
    errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR
  });
  if (!check.ok) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR,
      reason: `Custom endpoint rejected by SSRF security policy: ${check.reason}`
    };
  }
  return { ok: true, valid: true, url: check.url };
}

/**
 * JARVIS-002, JARVIS-006, JARVIS-013 — Encrypts an API key using AES-256-GCM with HKDF-SHA256
 * per-(userId, provider) key and a versioned v2 envelope containing `keyId`.
 */
export async function encryptCredentialForKv(plainApiKey, { env = {}, userId = "", provider = "" } = {}) {
  const cleanKey = String(plainApiKey || "").trim();
  if (!cleanKey) return null;
  if (!userId) {
    throw new Error("userId is required for authenticated credential encryption.");
  }
  const normProvider = String(provider || "").trim().toLowerCase();
  if (!normProvider) {
    throw new Error("provider is required for authenticated credential encryption.");
  }
  const keyInfo = resolveMasterEncryptionKeyInfo(env);
  const aesKey = await deriveCredentialAesKeyWithSecret(keyInfo.secret, userId, normProvider);
  const iv = webCrypto.getRandomValues(new Uint8Array(12));
  const aad = textEncoder.encode(`${String(userId)}:${normProvider}`);
  const cipherBuf = await webCrypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: aad },
    aesKey,
    textEncoder.encode(cleanKey)
  );
  return {
    alg: "AES-256-GCM",
    kdf: "HKDF-SHA256",
    v: 2,
    keyId: keyInfo.keyId,
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(cipherBuf)),
    encryptedAt: new Date().toISOString()
  };
}

/**
 * JARVIS-002, JARVIS-006, JARVIS-013 — Decrypts an encrypted credential envelope (v1 or v2)
 * using the configured keyring (prioritizing matching `keyId` first, then rotating through previous/legacy keys).
 * Fails closed when no valid encryption key is configured or when AAD/ciphertext authentication fails.
 */
export async function decryptCredentialFromKv(recordOrEnvelope, { env = {}, userId = "", provider = "" } = {}) {
  if (!recordOrEnvelope) return "";
  const envelope = recordOrEnvelope.encryptedApiKey || recordOrEnvelope;
  if (envelope && envelope.alg === "AES-256-GCM" && envelope.iv && envelope.ciphertext) {
    const normProvider = String(provider || recordOrEnvelope.provider || "").trim().toLowerCase();
    const iv = base64ToBytes(envelope.iv);
    const ciphertext = base64ToBytes(envelope.ciphertext);
    const aad = textEncoder.encode(`${String(userId)}:${normProvider}`);

    const keyring = resolveDecryptionKeyring(env);
    if (keyring.length === 0) {
      const err = new Error(
        "CREDENTIAL_ENCRYPTION_KEY_MISSING: No valid credential decryption key is configured in environment."
      );
      err.status = 503;
      err.code = "CREDENTIAL_ENCRYPTION_KEY_MISSING";
      throw err;
    }

    // Prioritize key with matching keyId if present on envelope, then fall back to remaining keys in keyring
    const targetKeyId = typeof envelope.keyId === "string" ? envelope.keyId.trim() : "";
    const orderedCandidates = targetKeyId
      ? [
          ...keyring.filter((k) => k.keyId === targetKeyId),
          ...keyring.filter((k) => k.keyId !== targetKeyId)
        ]
      : keyring;

    let lastErr = null;
    for (const candidate of orderedCandidates) {
      try {
        const aesKey = await deriveCredentialAesKeyWithSecret(candidate.secret, userId, normProvider);
        const plainBuf = await webCrypto.subtle.decrypt(
          { name: "AES-GCM", iv, additionalData: aad },
          aesKey,
          ciphertext
        );
        return textDecoder.decode(plainBuf);
      } catch (err) {
        lastErr = err;
      }
    }
    const decryptErr = new Error(
      `CREDENTIAL_DECRYPTION_FAILED: Stored credential could not be decrypted with any configured key version (${lastErr?.message || "AES-GCM authentication failed"}).`
    );
    decryptErr.status = 400;
    decryptErr.code = "CREDENTIAL_DECRYPTION_FAILED";
    throw decryptErr;
  }
  // Legacy fallback only if an older in-memory object had plaintext apiKey
  if (typeof recordOrEnvelope.apiKey === "string" && recordOrEnvelope.apiKey.trim()) {
    return recordOrEnvelope.apiKey.trim();
  }
  return "";
}

/**
 * JARVIS-006 & JARVIS-013 — Controlled, non-destructive credential key rotation & migration.
 * Re-encrypts legacy v1 or older-keyId v2 credentials to the current active v2 key (`AI_CREDENTIAL_ENCRYPTION_KEY`).
 * Guarantees:
 * - Verifies round-trip decryption with the new key before replacing `encryptedApiKey`.
 * - Never deletes or overwrites an existing credential if decryption fails (`failed_preserved`).
 * - Never exposes plaintext credentials in return payloads or logs.
 */
export async function migrateUserCredentialsToLatestKey(kv, userId, env = {}) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV is required for credential migration.");
  if (!userId) throw new Error("Authenticated userId is required for credential migration.");

  const currentKeyInfo = resolveMasterEncryptionKeyInfo(env);

  return withUserAccountLock(userId, async () => {
    const raw = await kv.get(accountStoreKey(userId));
    if (!raw) {
      return {
        ok: true,
        userId,
        currentKeyId: currentKeyInfo.keyId,
        totalAccounts: 0,
        migratedCount: 0,
        skippedCount: 0,
        failedCount: 0,
        details: []
      };
    }

    let list = [];
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) list = parsed;
    } catch (_) {
      throw new Error("Stored account list in KV is malformed and cannot be migrated safely.");
    }

    let migratedCount = 0;
    let skippedCount = 0;
    let failedCount = 0;
    const details = [];

    for (let i = 0; i < list.length; i++) {
      const acc = list[i];
      const envObj = acc?.encryptedApiKey;
      const isAlreadyLatest =
        envObj &&
        Number(envObj.v) === 2 &&
        envObj.keyId === currentKeyInfo.keyId &&
        !acc.apiKey;

      if (isAlreadyLatest) {
        skippedCount += 1;
        details.push({
          accountId: acc.accountId,
          provider: acc.provider,
          status: "already_current",
          keyId: envObj.keyId
        });
        continue;
      }

      try {
        const decryptedPlain = await decryptCredentialFromKv(acc, {
          env,
          userId,
          provider: acc.provider
        });
        if (!decryptedPlain) {
          skippedCount += 1;
          details.push({
            accountId: acc.accountId,
            provider: acc.provider,
            status: "no_credential_present"
          });
          continue;
        }

        const newEnvelope = await encryptCredentialForKv(decryptedPlain, {
          env: {
            ...env,
            AI_CREDENTIAL_ENCRYPTION_KEY: currentKeyInfo.secret,
            AI_CREDENTIAL_KEY_ID: currentKeyInfo.keyId
          },
          userId,
          provider: acc.provider
        });

        // Verify round-trip decryption with ONLY the new primary key before replacing existing ciphertext
        const verifiedPlain = await decryptCredentialFromKv(newEnvelope, {
          env: {
            AI_CREDENTIAL_ENCRYPTION_KEY: currentKeyInfo.secret,
            AI_CREDENTIAL_KEY_ID: currentKeyInfo.keyId
          },
          userId,
          provider: acc.provider
        });
        if (verifiedPlain !== decryptedPlain) {
          throw new Error("Round-trip verification mismatch after re-encryption.");
        }

        const previousVersion = envObj?.v || (acc.apiKey ? "plaintext" : "unknown");
        const previousKeyId = envObj?.keyId || "legacy_v1";

        const updatedAcc = {
          ...acc,
          encryptedApiKey: newEnvelope,
          maskedKey: acc.maskedKey || maskSecretValue(decryptedPlain),
          updatedAt: new Date().toISOString()
        };
        delete updatedAcc.apiKey;
        list[i] = updatedAcc;
        migratedCount += 1;
        details.push({
          accountId: acc.accountId,
          provider: acc.provider,
          status: "migrated",
          fromVersion: previousVersion,
          fromKeyId: previousKeyId,
          toVersion: 2,
          toKeyId: newEnvelope.keyId
        });
      } catch (err) {
        // Preserve original account record untouched on failure!
        failedCount += 1;
        details.push({
          accountId: acc.accountId,
          provider: acc.provider,
          status: "failed_preserved",
          error: redactSecrets(String(err?.message || err)).slice(0, 160)
        });
      }
    }

    if (migratedCount > 0) {
      await kv.put(accountStoreKey(userId), JSON.stringify(list), {
        expirationTtl: 86400 * 90
      });
    }

    return {
      ok: failedCount === 0,
      userId,
      currentKeyId: currentKeyInfo.keyId,
      totalAccounts: list.length,
      migratedCount,
      skippedCount,
      failedCount,
      details
    };
  });
}

/**
 * Batch 8.5.1 Section 4.2 — Rejects fake, placeholder, or fallback dummy credentials.
 */
export function isFakeOrPlaceholderCredential(rawKey = "") {
  const clean = String(rawKey || "").trim();
  if (!clean) return true;
  if (FAKE_CREDENTIAL_PATTERN.test(clean)) return true;
  if (clean === "undefined" || clean === "null" || clean === "none") return true;
  return false;
}

/**
 * Batch 8.5.2 Section 1 — Validates that an API key matches the target provider's expected key format
 * and prevents cross-provider key reuse (e.g., using an OpenAI key for Gemini or Claude).
 */
export function validateProviderApiKeyFormat(provider, rawKey = "") {
  const cleanProvider = String(provider || "").trim().toLowerCase();
  const cleanKey = String(rawKey || "").trim();
  if (!cleanKey) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING,
      reason: `API key is required for provider '${cleanProvider}'.`
    };
  }
  if (isFakeOrPlaceholderCredential(cleanKey)) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
      reason: `Rejected fake or placeholder credential for provider '${cleanProvider}'.`
    };
  }
  if (cleanKey.length < 8) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
      reason: `API key for '${cleanProvider}' is too short to be a valid credential.`
    };
  }

  const looksLikeGemini = /^AIza[0-9A-Za-z_-]+$/.test(cleanKey);
  const looksLikeAnthropic = /^sk-ant-[0-9A-Za-z_-]+$/i.test(cleanKey);
  const looksLikeGroq = /^gsk_[0-9A-Za-z_-]+$/i.test(cleanKey);
  const looksLikeOpenRouter = /^sk-or-[0-9A-Za-z_-]+$/i.test(cleanKey);
  const looksLikeOpenAiSpecific = /^sk-(proj-|openai|live-openai|primary-|work-|user-custom)/i.test(cleanKey);

  if (cleanProvider === "gemini") {
    if (cleanKey.startsWith("sk-") || looksLikeAnthropic || looksLikeGroq || looksLikeOpenRouter) {
      return {
        valid: false,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
        reason: "Invalid API key for Google Gemini: OpenAI/Anthropic/Groq/OpenRouter keys ('sk-*' / 'gsk_*') cannot be used for Gemini."
      };
    }
  } else if (cleanProvider === "anthropic") {
    if (looksLikeGemini || looksLikeGroq || looksLikeOpenRouter || looksLikeOpenAiSpecific) {
      return {
        valid: false,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
        reason: "Invalid API key for Anthropic Claude: key format belongs to another provider (OpenAI/Gemini/Groq/OpenRouter)."
      };
    }
  } else if (cleanProvider === "openai") {
    if (looksLikeGemini || looksLikeAnthropic || looksLikeGroq || looksLikeOpenRouter) {
      return {
        valid: false,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
        reason: "Invalid API key for OpenAI: key format belongs to another provider (Gemini/Anthropic/Groq/OpenRouter)."
      };
    }
  } else if (cleanProvider === "groq" || cleanProvider === "meta") {
    if (looksLikeGemini || looksLikeAnthropic || looksLikeOpenRouter || looksLikeOpenAiSpecific) {
      return {
        valid: false,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
        reason: `Invalid API key for ${cleanProvider}: key format belongs to another provider.`
      };
    }
  } else if (cleanProvider === "openrouter") {
    if (looksLikeGemini || looksLikeAnthropic || looksLikeGroq || looksLikeOpenAiSpecific) {
      return {
        valid: false,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
        reason: "Invalid API key for OpenRouter: key format belongs to another provider."
      };
    }
  } else if (cleanProvider === "deepseek") {
    if (looksLikeGemini || looksLikeAnthropic || looksLikeGroq || looksLikeOpenRouter || looksLikeOpenAiSpecific) {
      return {
        valid: false,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED,
        reason: "Invalid API key for DeepSeek: key format belongs to another provider."
      };
    }
  }

  return { valid: true, errorCode: null, reason: null };
}

/**
 * Batch 8.5.1 Section 4.1 — Production & Staging Provider Simulator Isolation Guard.
 */
export function resolveExecutionEnvironment(env = {}, options = {}) {
  const rawEnv = String(
    options?.jarvisEnv ?? env?.JARVIS_ENV ?? env?.ENVIRONMENT ?? env?.NODE_ENV ?? "production"
  )
    .trim()
    .toLowerCase();

  const knownNonProd = rawEnv === "test" || rawEnv === "development";
  const isProdOrStaging = rawEnv === "production" || rawEnv === "staging";
  const isValidEnv = isProdOrStaging || knownNonProd;
  const effectiveEnv = isValidEnv ? rawEnv : "production";
  const isProduction = effectiveEnv === "production" || effectiveEnv === "staging";

  const rawMockFlag =
    options?.mockProviderEnabled ??
    env?.MOCK_PROVIDER_ENABLED ??
    env?.JARVIS_ALLOW_MOCK_PROVIDERS ??
    MOCK_PROVIDER_ENABLED_DEFAULT;
  const requestedMock =
    rawMockFlag === true ||
    String(rawMockFlag).trim().toLowerCase() === "true" ||
    String(rawMockFlag).trim() === "1";

  const allowMockProviders = !isProduction && knownNonProd && requestedMock;
  const rejectedMockInProduction = isProduction && requestedMock;
  const invalidEnvFallbackToProd = !isValidEnv;

  return {
    jarvisEnv: effectiveEnv,
    rawJarvisEnv: rawEnv,
    isProduction,
    requestedMock,
    allowMockProviders,
    rejectedMockInProduction,
    invalidEnvFallbackToProd,
    warning: rejectedMockInProduction
      ? "MOCK_PROVIDER_REJECTED_IN_PRODUCTION"
      : invalidEnvFallbackToProd
        ? "INVALID_ENV_DEFAULTED_TO_PRODUCTION"
        : null
  };
}

/**
 * Batch 8.5.1 Section 4.3 — Deterministic Provider Failure Classification.
 */
export function classifyProviderError(error, httpStatus = 0) {
  if (error?.errorCode && Object.values(PROVIDER_ERROR_CODES).includes(error.errorCode)) {
    return error.errorCode;
  }
  const code = String(error?.code || "").toUpperCase();
  const status = Number(httpStatus || error?.status || 0);
  const msg = String(error?.message || error || "").toLowerCase();

  if (code === "PROVIDER_DISABLED" || msg.includes("provider is disabled") || msg.includes("all accounts disabled")) {
    return PROVIDER_ERROR_CODES.PROVIDER_DISABLED;
  }
  if (
    code === "NO_PROVIDER_CREDENTIALS" ||
    code === "PROVIDER_CREDENTIAL_MISSING" ||
    msg.includes("no active credentials") ||
    msg.includes("credential missing") ||
    msg.includes("api key missing")
  ) {
    return PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING;
  }
  if (
    code === "TIMEOUT" ||
    code === "PROVIDER_TIMEOUT" ||
    error?.name === "AbortError" ||
    status === 504 ||
    status === 408 ||
    msg.includes("timed out") ||
    msg.includes("timeout")
  ) {
    return PROVIDER_ERROR_CODES.PROVIDER_TIMEOUT;
  }
  if (
    status === 429 ||
    code === "PROVIDER_RATE_LIMITED" ||
    msg.includes("rate limit") ||
    msg.includes("too many requests") ||
    msg.includes("quota exceeded")
  ) {
    return PROVIDER_ERROR_CODES.PROVIDER_RATE_LIMITED;
  }
  if (
    status === 401 ||
    status === 403 ||
    code === "PROVIDER_AUTH_FAILED" ||
    msg.includes("unauthorized") ||
    msg.includes("invalid api key") ||
    msg.includes("authentication failed") ||
    msg.includes("forbidden")
  ) {
    return PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED;
  }
  if (
    status === 404 ||
    code === "UNSUPPORTED_PROVIDER" ||
    code === "MODEL_UNAVAILABLE" ||
    msg.includes("model not found") ||
    msg.includes("model_not_found") ||
    msg.includes("does not exist") ||
    msg.includes("model unavailable") ||
    msg.includes("unsupported model")
  ) {
    return PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE;
  }
  if (
    code === "NETWORK_ERROR" ||
    error?.name === "TypeError" ||
    msg.includes("network") ||
    msg.includes("fetch failed") ||
    msg.includes("econnrefused") ||
    msg.includes("enotfound") ||
    msg.includes("dns") ||
    msg.includes("socket")
  ) {
    return PROVIDER_ERROR_CODES.NETWORK_ERROR;
  }
  return PROVIDER_ERROR_CODES.PROVIDER_ERROR;
}

export const PROVIDER_CATALOG = Object.freeze({
  openai: {
    id: "openai",
    name: "OpenAI",
    authType: "Bearer API Key",
    authHeader: "Authorization",
    keyFormatHint: "sk-proj-... / sk-...",
    docsUrl: "https://platform.openai.com/docs/api-reference",
    endpoint: "https://api.openai.com/v1/chat/completions",
    modelsEndpoint: "https://api.openai.com/v1/models",
    envKeys: ["OPENAI_API_KEY_1", "OPENAI_API_KEY_2", "OPENAI_API_KEY_3"],
    defaultModel: "gpt-4o",
    supportedModels: ["gpt-4o", "gpt-4.1-mini", "o3-mini"]
  },
  anthropic: {
    id: "anthropic",
    name: "Anthropic Claude",
    authType: "x-api-key Header",
    authHeader: "x-api-key",
    keyFormatHint: "sk-ant-api03-...",
    docsUrl: "https://docs.anthropic.com/en/api/getting-started",
    endpoint: "https://api.anthropic.com/v1/messages",
    modelsEndpoint: "https://api.anthropic.com/v1/models",
    envKeys: ["ANTHROPIC_API_KEY_1", "ANTHROPIC_API_KEY_2", "ANTHROPIC_API_KEY_3"],
    defaultModel: "claude-3-5-sonnet-latest",
    supportedModels: ["claude-3-5-sonnet", "claude-3-5-haiku"]
  },
  gemini: {
    id: "gemini",
    name: "Google Gemini",
    authType: "Google AI Studio Key",
    authHeader: "x-goog-api-key / ?key=",
    keyFormatHint: "AIzaSy...",
    docsUrl: "https://ai.google.dev/gemini-api/docs",
    endpoint: "https://generativelanguage.googleapis.com/v1beta/models",
    modelsEndpoint: "https://generativelanguage.googleapis.com/v1beta/models",
    envKeys: ["GEMINI_API_KEY_1", "GEMINI_API_KEY_2", "GEMINI_API_KEY_3"],
    defaultModel: "gemini-2.5-flash",
    supportedModels: ["gemini-1.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"]
  },
  meta: {
    id: "meta",
    name: "Meta (Llama / Groq)",
    authType: "Bearer API Key",
    authHeader: "Authorization",
    keyFormatHint: "gsk_...",
    docsUrl: "https://console.groq.com/docs/models",
    endpoint: "https://api.groq.com/openai/v1/chat/completions",
    modelsEndpoint: "https://api.groq.com/openai/v1/models",
    envKeys: ["GROQ_API_KEY_1", "GROQ_API_KEY_2", "GROQ_API_KEY_3"],
    defaultModel: "llama-3.3-70b-versatile",
    supportedModels: ["llama-3.3-70b", "llama-3.3-70b-versatile", "openai/gpt-oss-120b"]
  },
  deepseek: {
    id: "deepseek",
    name: "DeepSeek",
    authType: "Bearer API Key",
    authHeader: "Authorization",
    keyFormatHint: "sk-...",
    docsUrl: "https://api-docs.deepseek.com/",
    endpoint: "https://api.deepseek.com/chat/completions",
    modelsEndpoint: "https://api.deepseek.com/models",
    envKeys: ["DEEPSEEK_API_KEY_1", "DEEPSEEK_API_KEY_2", "DEEPSEEK_API_KEY_3"],
    defaultModel: "deepseek-chat",
    supportedModels: ["deepseek-v3", "deepseek-chat", "deepseek-reasoner"]
  },
  groq: {
    id: "groq",
    name: "Groq",
    authType: "Bearer API Key",
    authHeader: "Authorization",
    keyFormatHint: "gsk_...",
    docsUrl: "https://console.groq.com/docs/overview",
    endpoint: "https://api.groq.com/openai/v1/chat/completions",
    modelsEndpoint: "https://api.groq.com/openai/v1/models",
    envKeys: ["GROQ_API_KEY_1", "GROQ_API_KEY_2", "GROQ_API_KEY_3"],
    defaultModel: "llama-3.3-70b-versatile",
    supportedModels: ["llama-3.3-70b-versatile", "openai/gpt-oss-120b"]
  },
  openrouter: {
    id: "openrouter",
    name: "OpenRouter",
    authType: "Bearer API Key",
    authHeader: "Authorization",
    keyFormatHint: "sk-or-v1-...",
    docsUrl: "https://openrouter.ai/docs/quick-start",
    endpoint: "https://openrouter.ai/api/v1/chat/completions",
    modelsEndpoint: "https://openrouter.ai/api/v1/models",
    envKeys: ["OPENROUTER_API_KEY_1", "OPENROUTER_API_KEY_2", "OPENROUTER_API_KEY_3"],
    defaultModel: "qwen/qwen-2.5-72b-instruct",
    supportedModels: ["qwen-2.5-72b", "qwen/qwen-2.5-72b-instruct", "openai/gpt-4.1-mini"]
  },
  custom: {
    id: "custom",
    name: "Custom OpenAI-Compatible",
    authType: "Bearer API Key",
    authHeader: "Authorization",
    keyFormatHint: "Bearer API Key sesuai endpoint",
    docsUrl: "https://platform.openai.com/docs/api-reference/chat",
    endpoint: "https://api.openai.com/v1/chat/completions",
    modelsEndpoint: "https://api.openai.com/v1/models",
    envKeys: [],
    defaultModel: "gpt-4o-mini",
    supportedModels: ["gpt-4o-mini"],
    isCustomEndpointSupported: true
  }
});

export function maskSecretValue(secret = "") {
  const clean = String(secret || "").trim();
  if (!clean) return "";
  if (clean.length <= 8) return "****";
  return clean.slice(0, 3) + "***" + clean.slice(-4);
}

export function maskEmailOrLabel(identifier = "") {
  const clean = String(identifier || "").trim();
  if (!clean.includes("@")) return boundedString(clean, 80);
  const [local, domain] = clean.split("@");
  if (!local || !domain) return clean;
  return (local.slice(0, 2) + "***@" + domain).slice(0, 80);
}

function accountStoreKey(userId) {
  return `ai-accounts:v1:${String(userId || "default")}`;
}

function providerConfigStoreKey(userId) {
  return `ai-provider-configs:v1:${String(userId || "default")}`;
}

async function readUserProviderConfigsMap(kv, userId) {
  if (!kv || !userId) return {};
  try {
    const raw = await kv.get(providerConfigStoreKey(userId));
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch (_) {
    return {};
  }
}

async function writeUserProviderConfigsMap(kv, userId, mapObj) {
  if (!kv || !userId) return;
  await kv.put(providerConfigStoreKey(userId), JSON.stringify(mapObj), {
    expirationTtl: 86400 * 90
  });
}

function resolveProviderSpec(providerId, userConfig = null) {
  const clean = String(providerId || "").trim().toLowerCase();
  const catalogSpec = PROVIDER_CATALOG[clean];
  if (catalogSpec) {
    return {
      ...catalogSpec,
      name: userConfig?.name || catalogSpec.name,
      endpoint: userConfig?.endpoint || catalogSpec.endpoint,
      modelsEndpoint: userConfig?.modelsEndpoint || catalogSpec.modelsEndpoint,
      defaultModel: userConfig?.defaultModel || catalogSpec.defaultModel,
      docsUrl: userConfig?.docsUrl || catalogSpec.docsUrl
    };
  }
  if (clean.startsWith("custom-") || userConfig?.isCustom) {
    return {
      ...PROVIDER_CATALOG.custom,
      id: clean,
      name: userConfig?.name || `Custom (${clean})`,
      endpoint: userConfig?.endpoint || PROVIDER_CATALOG.custom.endpoint,
      modelsEndpoint: userConfig?.modelsEndpoint || PROVIDER_CATALOG.custom.modelsEndpoint,
      defaultModel: userConfig?.defaultModel || "custom-model",
      supportedModels: userConfig?.supportedModels || [userConfig?.defaultModel || "custom-model"],
      docsUrl: userConfig?.docsUrl || PROVIDER_CATALOG.custom.docsUrl,
      isCustom: true
    };
  }
  return null;
}

/**
 * Batch 8.5.2 Section 1 — Save or update independent per-provider configuration for a user.
 * Updating one provider NEVER modifies any other provider's configuration.
 */
export async function saveProviderConfiguration(kv, userId, input = {}, env = {}) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV is required to store provider configuration");
  if (!userId) throw new Error("Authenticated userId is required");

  const providerId = boundedString(input.provider || input.providerId || input.id || "", 40).toLowerCase();
  if (!providerId) {
    throw new Error("providerId is required");
  }

  if (input.endpoint) {
    const check = validateCustomProviderEndpoint(input.endpoint);
    if (!check.valid) {
      const err = new Error(check.reason);
      err.status = 400;
      err.code = check.errorCode;
      err.errorCode = check.errorCode;
      throw err;
    }
  }
  if (input.modelsEndpoint) {
    const check = validateCustomProviderEndpoint(input.modelsEndpoint);
    if (!check.valid) {
      const err = new Error(check.reason);
      err.status = 400;
      err.code = check.errorCode;
      err.errorCode = check.errorCode;
      throw err;
    }
  }

  const updated = await withUserAccountLock(userId, async () => {
    const configsMap = await readUserProviderConfigsMap(kv, userId);
    const existing = configsMap[providerId] || {};
    const spec = resolveProviderSpec(providerId, {
      ...existing,
      isCustom: Boolean(input.isCustom || providerId === "custom" || providerId.startsWith("custom-"))
    });
    if (!spec) {
      throw new Error("Unsupported AI provider: " + providerId);
    }

    const nextEndpoint = input.endpoint ? boundedString(input.endpoint, 240) : existing.endpoint || spec.endpoint;
    const nextModelsEndpoint = input.modelsEndpoint
      ? boundedString(input.modelsEndpoint, 240)
      : existing.modelsEndpoint ||
        (input.endpoint && (providerId === "custom" || providerId.startsWith("custom-"))
          ? input.endpoint.replace(/\/chat\/completions\/?$/, "/models")
          : spec.modelsEndpoint);

    const record = {
      providerId,
      name: boundedString(input.name || existing.name || spec.name, 80),
      endpoint: nextEndpoint,
      modelsEndpoint: nextModelsEndpoint,
      authType: boundedString(input.authType || existing.authType || spec.authType, 60),
      docsUrl: boundedString(input.docsUrl || existing.docsUrl || spec.docsUrl, 240),
      defaultModel: boundedString(input.defaultModel || existing.defaultModel || spec.defaultModel, 100),
      enabled: input.enabled !== undefined ? Boolean(input.enabled) : existing.enabled !== false,
      isCustom: Boolean(spec.isCustom || providerId === "custom" || providerId.startsWith("custom-")),
      discoveredModels: Array.isArray(input.discoveredModels)
        ? input.discoveredModels.slice(0, 100)
        : existing.discoveredModels || [],
      lastConnectionTest: input.lastConnectionTest !== undefined ? input.lastConnectionTest : existing.lastConnectionTest || null,
      errorStatus: input.errorStatus !== undefined ? input.errorStatus : existing.errorStatus || null,
      updatedAt: new Date().toISOString()
    };

    configsMap[providerId] = record;
    await writeUserProviderConfigsMap(kv, userId, configsMap);
    return record;
  });

  // If the caller also provided an API key / account during provider configuration, save it as an independent account for this provider
  let savedAccount = null;
  if (typeof input.apiKey === "string" && input.apiKey.trim()) {
    savedAccount = await saveUserAiAccount(
      kv,
      userId,
      {
        accountId: input.accountId,
        provider: providerId,
        label: input.accountLabel || input.label || `${updated.name} Main`,
        email: input.email || input.emailOrHint || `${providerId}@user.jarvis`,
        apiKey: input.apiKey.trim(),
        customEndpoint: updated.endpoint,
        defaultModel: updated.defaultModel,
        isDefault: input.isDefault !== undefined ? Boolean(input.isDefault) : undefined
      },
      env
    );
  }

  return {
    ...updated,
    savedAccount
  };
}

/**
 * Batch 8.5.2 Section 4 — Server-managed Worker Secrets are listed separately and NEVER
 * mixed into the user's personal account list.
 */
export function listServerManagedCredentials(env = {}) {
  const serverCredentials = [];
  for (const [providerId, spec] of Object.entries(PROVIDER_CATALOG)) {
    const configuredKeys = (spec.envKeys || []).filter(
      (k) => typeof env?.[k] === "string" && env[k].trim() && !isFakeOrPlaceholderCredential(env[k])
    );
    if (configuredKeys.length > 0) {
      serverCredentials.push({
        provider: providerId,
        providerName: spec.name,
        source: "worker_secret",
        slotCount: configuredKeys.length,
        keySlots: configuredKeys,
        maskedPreview: configuredKeys.map((k) => `${k}:${maskSecretValue(env[k])}`)
      });
    }
  }
  return serverCredentials;
}

/**
 * Batch 8.5.2 Section 3 & 4 — Returns strictly user-managed AI accounts (never server Worker secrets)
 * and never exposes plaintext or raw encrypted API keys.
 */
export async function listUserAiAccounts(kv, userId, env = {}, options = {}) {
  let stored = [];
  if (kv && userId) {
    try {
      const raw = await kv.get(accountStoreKey(userId));
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) stored = parsed;
      }
    } catch (_) {}
  }

  const providerFilter = options?.provider ? String(options.provider).trim().toLowerCase() : "";

  const sanitizedUserAccounts = stored
    .filter((acc) => !providerFilter || String(acc.provider).toLowerCase() === providerFilter)
    .map((acc) => ({
      accountId: acc.accountId,
      provider: acc.provider,
      label: acc.label,
      emailOrHint: acc.emailOrHint,
      maskedKey: acc.maskedKey || "sk***",
      hasEncryptedKey: Boolean(acc.encryptedApiKey?.ciphertext || acc.apiKey),
      encryptionAlgorithm: acc.encryptedApiKey?.alg || (acc.apiKey ? "legacy_plaintext" : "none"),
      encryptionVersion: acc.encryptedApiKey?.v || (acc.apiKey ? 0 : 1),
      keyId: acc.encryptedApiKey?.keyId || null,
      enabled: acc.enabled !== false,
      isDefault: Boolean(acc.isDefault),
      source: "user_account",
      customEndpoint: acc.customEndpoint || undefined,
      defaultModel: acc.defaultModel || undefined,
      quotaUsedTokens: Number(acc.quotaUsedTokens) || 0,
      quotaLimitTokens: Number(acc.quotaLimitTokens) || 200000,
      quotaRemainingTokens: Math.max(
        0,
        (Number(acc.quotaLimitTokens) || 200000) - (Number(acc.quotaUsedTokens) || 0)
      ),
      status: acc.status || "configured_unverified",
      connectionVerified: Boolean(acc.connectionVerified || acc.status === "working_live"),
      lastTestedAt: acc.lastTestedAt || null,
      errorStatus: acc.errorStatus || null,
      discoveredModels: Array.isArray(acc.discoveredModels) ? acc.discoveredModels : [],
      updatedAt: acc.updatedAt
    }));

  return sanitizedUserAccounts;
}

/**
 * Batch 8.5.2 Section 2, 3, 5 — Saves or updates an independent user AI account for a specific provider.
 * - Validates provider-specific API key format (cannot use OpenAI key for Gemini/Claude, etc.).
 * - Validates customEndpoint against SSRF.
 * - Encrypts API key with AES-256-GCM v2 envelope before writing to DEVICE_REGISTRY KV.
 * - Serializes per-user KV writes via withUserAccountLock.
 */
export async function saveUserAiAccount(kv, userId, input = {}, env = {}) {
  if (!kv) throw new Error("DEVICE_REGISTRY KV is required to store AI accounts");
  if (!userId) throw new Error("Authenticated userId is required");

  const provider = boundedString(input.provider || "", 40).toLowerCase();
  if (input.customEndpoint) {
    const epCheck = validateCustomProviderEndpoint(input.customEndpoint);
    if (!epCheck.valid) {
      const err = new Error(epCheck.reason);
      err.status = 400;
      err.code = epCheck.errorCode;
      err.errorCode = epCheck.errorCode;
      throw err;
    }
  }

  return withUserAccountLock(userId, async () => {
    const configsMap = await readUserProviderConfigsMap(kv, userId);
    const spec = resolveProviderSpec(provider, {
      ...(configsMap[provider] || {}),
      isCustom: Boolean(input.isCustom || provider === "custom" || provider.startsWith("custom-"))
    });
    if (!spec) {
      throw new Error("Unsupported AI provider: " + provider);
    }

    const label = boundedString(input.label || "Akun Baru", 80);
    const emailOrHint = maskEmailOrLabel(input.email || input.emailOrHint || "user@domain.com");
    const apiKey = typeof input.apiKey === "string" ? input.apiKey.trim() : "";

    if (apiKey) {
      const validation = validateProviderApiKeyFormat(provider, apiKey);
      if (!validation.valid) {
        const err = new Error(validation.reason);
        err.status = 400;
        err.code = validation.errorCode;
        err.errorCode = validation.errorCode;
        throw err;
      }
    }

    const raw = await kv.get(accountStoreKey(userId));
    const list = raw ? JSON.parse(raw) : [];
    const accountId = boundedString(
      input.accountId || `acc-${provider}-${webCrypto.randomUUID().slice(0, 8)}`,
      80
    );
    const existingIdx = list.findIndex((item) => item.accountId === accountId);
    const existingRecord = existingIdx >= 0 ? list[existingIdx] : null;

    if (existingRecord && existingRecord.provider !== provider) {
      const mismatchErr = new Error(
        `Account '${accountId}' belongs to provider '${existingRecord.provider}' and cannot be reassigned to '${provider}'.`
      );
      mismatchErr.status = 400;
      mismatchErr.code = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
      mismatchErr.errorCode = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
      throw mismatchErr;
    }

    let encryptedApiKey = existingRecord?.encryptedApiKey || null;
    if (apiKey) {
      encryptedApiKey = await encryptCredentialForKv(apiKey, { env, userId, provider });
    } else if (!encryptedApiKey && existingRecord?.apiKey) {
      encryptedApiKey = await encryptCredentialForKv(existingRecord.apiKey, { env, userId, provider });
    }

    const isDefault =
      input.isDefault !== undefined
        ? Boolean(input.isDefault)
        : existingRecord
          ? Boolean(existingRecord.isDefault)
          : !list.some((item) => item.provider === provider && item.enabled !== false);

    // Setting isDefault on this account ONLY affects accounts of the SAME provider
    if (isDefault) {
      for (const item of list) {
        if (item.provider === provider) item.isDefault = false;
      }
    }

    const keyChanged = Boolean(apiKey);
    const nextStatus = input.connectionVerified === true
      ? "working_live"
      : keyChanged
        ? "configured_unverified"
        : existingRecord?.status || (encryptedApiKey ? "configured_unverified" : "ui_configured_only");

    const nextConnectionVerified =
      input.connectionVerified !== undefined
        ? Boolean(input.connectionVerified)
        : keyChanged
          ? false
          : Boolean(existingRecord?.connectionVerified);

    const record = {
      accountId,
      provider,
      label,
      emailOrHint,
      maskedKey: apiKey
        ? maskSecretValue(apiKey)
        : existingRecord?.maskedKey || (encryptedApiKey ? "sk***" : ""),
      encryptedApiKey,
      enabled: input.enabled !== undefined ? Boolean(input.enabled) : existingRecord ? existingRecord.enabled !== false : true,
      isDefault,
      customEndpoint: input.customEndpoint
        ? boundedString(input.customEndpoint, 240)
        : existingRecord?.customEndpoint || undefined,
      defaultModel: input.defaultModel
        ? boundedString(input.defaultModel, 100)
        : existingRecord?.defaultModel || undefined,
      quotaUsedTokens: Math.max(0, Number(input.quotaUsedTokens ?? existingRecord?.quotaUsedTokens ?? 0)),
      quotaLimitTokens: Math.max(1000, Number(input.quotaLimitTokens ?? existingRecord?.quotaLimitTokens ?? 200000)),
      status: nextStatus,
      connectionVerified: nextConnectionVerified,
      lastTestedAt: input.lastTestedAt !== undefined ? input.lastTestedAt : existingRecord?.lastTestedAt || null,
      errorStatus: input.errorStatus !== undefined ? input.errorStatus : (keyChanged ? null : existingRecord?.errorStatus || null),
      discoveredModels: Array.isArray(input.discoveredModels)
        ? input.discoveredModels
        : existingRecord?.discoveredModels || [],
      updatedAt: new Date().toISOString()
    };

    if (existingIdx >= 0) {
      list[existingIdx] = record;
    } else {
      list.push(record);
    }

    await kv.put(accountStoreKey(userId), JSON.stringify(list.slice(0, 100)), {
      expirationTtl: 86400 * 90
    });

    const { encryptedApiKey: _hiddenEnc, apiKey: _hiddenPlain, ...publicRecord } = record;
    return {
      ...publicRecord,
      hasEncryptedKey: Boolean(record.encryptedApiKey?.ciphertext),
      encryptionAlgorithm: record.encryptedApiKey?.alg || "none",
      encryptionVersion: record.encryptedApiKey?.v || null,
      keyId: record.encryptedApiKey?.keyId || null
    };
  });
}

export async function removeUserAiAccount(kv, userId, accountId, options = {}) {
  if (!kv || !userId || !accountId) return false;
  return withUserAccountLock(userId, async () => {
    const raw = await kv.get(accountStoreKey(userId));
    if (!raw) return false;
    const list = JSON.parse(raw);
    const targetIdx = list.findIndex((item) => item.accountId === accountId);
    if (targetIdx < 0) return false;

    const removedItem = list[targetIdx];
    if (options?.provider && removedItem.provider !== String(options.provider).toLowerCase()) {
      return false;
    }

    const filtered = list.filter((item) => item.accountId !== accountId);
    if (removedItem.isDefault) {
      const nextCandidate = filtered.find(
        (item) => item.provider === removedItem.provider && item.enabled !== false
      );
      if (nextCandidate) {
        nextCandidate.isDefault = true;
      }
    }

    await kv.put(accountStoreKey(userId), JSON.stringify(filtered), { expirationTtl: 86400 * 90 });
    return true;
  });
}

/**
 * Checks if a provider is explicitly disabled via environment or per-user provider configuration.
 */
export async function isProviderExplicitlyDisabled(kv, userId, provider, env = {}) {
  const rawProvider = String(provider || "").toLowerCase();
  const normalizedProvider = rawProvider === "meta" ? "groq" : rawProvider;
  const disabledListRaw = String(env?.DISABLED_PROVIDERS || "").toLowerCase();
  if (disabledListRaw) {
    const disabledSet = new Set(disabledListRaw.split(",").map((s) => s.trim()).filter(Boolean));
    if (disabledSet.has(normalizedProvider) || disabledSet.has(rawProvider)) {
      return true;
    }
  }
  const providerDisableFlag =
    env?.[`DISABLE_${normalizedProvider.toUpperCase()}`] ??
    env?.[`PROVIDER_${normalizedProvider.toUpperCase()}_DISABLED`];
  if (providerDisableFlag === true || String(providerDisableFlag).toLowerCase() === "true") {
    return true;
  }

  if (kv && userId) {
    try {
      const configsMap = await readUserProviderConfigsMap(kv, userId);
      if (configsMap[rawProvider]?.enabled === false || configsMap[normalizedProvider]?.enabled === false) {
        return true;
      }
      const raw = await kv.get(accountStoreKey(userId));
      const list = raw ? JSON.parse(raw) : [];
      const matchingProvider = list.filter(
        (a) => a.provider === rawProvider || a.provider === normalizedProvider
      );
      if (matchingProvider.length > 0 && matchingProvider.every((a) => a.enabled === false)) {
        const spec = PROVIDER_CATALOG[rawProvider] || PROVIDER_CATALOG[normalizedProvider];
        const hasValidServerKey = (spec?.envKeys || []).some(
          (k) => typeof env?.[k] === "string" && env[k].trim() && !isFakeOrPlaceholderCredential(env[k])
        );
        if (!hasValidServerKey) {
          return true;
        }
      }
    } catch (_) {}
  }
  return false;
}

/**
 * Batch 8.5.2 Section 3, 4, 5 — Resolves decrypted credentials chain for a specific provider.
 */
export async function resolveAccountCredentialsChain(kv, userId, provider, env = {}, options = {}) {
  const rawProvider = String(provider || "").toLowerCase();
  const normalizedProvider = rawProvider === "meta" ? "groq" : rawProvider;
  if (await isProviderExplicitlyDisabled(kv, userId, rawProvider, env)) {
    return [];
  }

  const configsMap = await readUserProviderConfigsMap(kv, userId);
  const providerConfig = configsMap[rawProvider] || configsMap[normalizedProvider] || null;
  const chain = [];

  if (kv && userId) {
    try {
      const raw = await kv.get(accountStoreKey(userId));
      const list = raw ? JSON.parse(raw) : [];
      const matching = list
        .filter(
          (a) =>
            (a.provider === rawProvider ||
              a.provider === normalizedProvider ||
              (normalizedProvider === "groq" && a.provider === "meta")) &&
            a.enabled !== false &&
            (a.encryptedApiKey?.ciphertext || a.apiKey)
        )
        .sort((a, b) => Number(Boolean(b.isDefault)) - Number(Boolean(a.isDefault)));

      for (const acc of matching) {
        if (acc.quotaUsedTokens < acc.quotaLimitTokens) {
          let decryptedKey = "";
          try {
            decryptedKey = await decryptCredentialFromKv(acc, {
              env,
              userId,
              provider: acc.provider
            });
          } catch (_) {
            decryptedKey = "";
          }
          if (decryptedKey && !isFakeOrPlaceholderCredential(decryptedKey)) {
            const keyCheck = validateProviderApiKeyFormat(acc.provider, decryptedKey);
            if (keyCheck.valid) {
              chain.push({
                accountId: acc.accountId,
                label: acc.label,
                provider: normalizedProvider,
                configuredProvider: acc.provider,
                source: "user_account",
                isDefault: Boolean(acc.isDefault),
                customEndpoint: acc.customEndpoint || providerConfig?.endpoint || undefined,
                defaultModel: acc.defaultModel || providerConfig?.defaultModel || undefined,
                apiKey: decryptedKey
              });
            }
          }
        }
      }
    } catch (_) {}
  }

  if (options?.userAccountsOnly === true) {
    return chain;
  }

  const spec = PROVIDER_CATALOG[rawProvider] || PROVIDER_CATALOG[normalizedProvider];
  for (const keyName of spec?.envKeys || []) {
    if (typeof env?.[keyName] === "string" && env[keyName].trim() && !isFakeOrPlaceholderCredential(env[keyName])) {
      const candidateServerKey = env[keyName].trim();
      const keyCheck = validateProviderApiKeyFormat(normalizedProvider, candidateServerKey);
      if (keyCheck.valid) {
        chain.push({
          accountId: `server-${normalizedProvider}-${keyName}`,
          label: `Worker Secret (${keyName})`,
          provider: normalizedProvider,
          configuredProvider: rawProvider,
          source: "worker_secret",
          isServerFallback: true,
          keyName,
          apiKey: candidateServerKey
        });
      }
    }
  }
  return chain;
}

/**
 * Batch 8.5.2 Section 1, 2, 4, 7 — Summarizes independent configuration and status for every provider.
 */
export async function summarizeProvidersStatus(kv, userId, env = {}) {
  const userAccounts = await listUserAiAccounts(kv, userId, env);
  const configsMap = await readUserProviderConfigsMap(kv, userId);
  const serverCredentials = listServerManagedCredentials(env);
  const serverByProvider = new Map(serverCredentials.map((s) => [s.provider, s]));

  const allProviderIds = new Set([
    ...Object.keys(PROVIDER_CATALOG),
    ...Object.keys(configsMap),
    ...userAccounts.map((a) => a.provider)
  ]);

  const results = [];
  for (const providerId of allProviderIds) {
    const userCfg = configsMap[providerId] || null;
    const spec = resolveProviderSpec(providerId, userCfg);
    if (!spec) continue;

    const providerDisabled = await isProviderExplicitlyDisabled(kv, userId, providerId, env);
    const providerUserAccounts = userAccounts.filter((a) => a.provider === providerId);
    const activeUserAccounts = providerUserAccounts.filter((a) => a.enabled !== false);
    const defaultAccount =
      activeUserAccounts.find((a) => a.isDefault) ||
      providerUserAccounts.find((a) => a.isDefault) ||
      activeUserAccounts[0] ||
      providerUserAccounts[0] ||
      null;

    const hasVerifiedLiveUserAccount =
      !providerDisabled &&
      activeUserAccounts.some((a) => a.connectionVerified === true || a.status === "working_live");
    const hasFailedUserAccount =
      !hasVerifiedLiveUserAccount &&
      activeUserAccounts.some((a) => a.status === "connection_failed");

    const serverInfo = serverByProvider.get(providerId) || null;
    const serverCredentialConfigured = Boolean(serverInfo && serverInfo.slotCount > 0);

    const connected =
      !providerDisabled &&
      (providerUserAccounts.length > 0
        ? hasVerifiedLiveUserAccount
        : serverCredentialConfigured);

    let connectionStatus = "not_connected";
    let integrationState = "ui_configured_only";
    if (providerDisabled) {
      connectionStatus = "disabled";
      integrationState = "disabled";
    } else if (hasVerifiedLiveUserAccount) {
      connectionStatus = "connected";
      integrationState = "working_live";
    } else if (hasFailedUserAccount || userCfg?.errorStatus) {
      connectionStatus = "connection_failed";
      integrationState = "connection_failed";
    } else if (activeUserAccounts.length > 0) {
      connectionStatus = "configured_unverified";
      integrationState = "configured_unverified";
    } else if (serverCredentialConfigured) {
      connectionStatus = "server_fallback_available";
      integrationState = "working_live";
    }

    const discoveredModels = [
      ...new Set([
        ...(userCfg?.discoveredModels || []),
        ...providerUserAccounts.flatMap((a) => a.discoveredModels || [])
      ])
    ];
    const providerModels = getProviderModels(providerId, { discoveredModels });

    results.push({
      id: spec.id,
      name: userCfg?.name || spec.name,
      endpoint: userCfg?.endpoint || defaultAccount?.customEndpoint || spec.endpoint,
      modelsEndpoint: userCfg?.modelsEndpoint || spec.modelsEndpoint,
      docsUrl: userCfg?.docsUrl || spec.docsUrl,
      authType: userCfg?.authType || spec.authType,
      authConfig: {
        authType: userCfg?.authType || spec.authType,
        authHeader: spec.authHeader || "Authorization",
        keyFormatHint: spec.keyFormatHint || "API Key",
        docsUrl: userCfg?.docsUrl || spec.docsUrl
      },
      defaultModel: userCfg?.defaultModel || defaultAccount?.defaultModel || spec.defaultModel,
      supportedModels: providerModels.map((m) => m.id),
      models: providerModels,
      enabled: !providerDisabled && (userCfg?.enabled !== false),
      connected,
      userConnected: hasVerifiedLiveUserAccount,
      connectionStatus,
      integrationState,
      accountCount: providerUserAccounts.length,
      activeAccountCount: activeUserAccounts.length,
      defaultAccountId: defaultAccount?.accountId || null,
      maskedApiKey: defaultAccount?.maskedKey || null,
      accounts: providerUserAccounts,
      serverCredentials: {
        configured: serverCredentialConfigured,
        slotCount: serverInfo?.slotCount || 0,
        keySlots: serverInfo?.keySlots || []
      },
      lastConnectionTest: userCfg?.lastConnectionTest || defaultAccount?.lastTestedAt || null,
      errorStatus:
        userCfg?.errorStatus ||
        defaultAccount?.errorStatus ||
        (hasFailedUserAccount ? { errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR, message: "Connection test failed" } : null)
    });
  }

  return results;
}

/**
 * Batch 8.5.2 Section 2 & 7 — Parses live model discovery payload from a provider's /models response.
 */
export function parseDiscoveredModelsPayload(providerId, data, fallbackModels = []) {
  const cleanProvider = String(providerId || "").toLowerCase();
  const discovered = [];
  if (cleanProvider === "gemini" && Array.isArray(data?.models)) {
    for (const item of data.models) {
      const rawName = String(item?.name || "").replace(/^models\//, "").trim();
      if (rawName) discovered.push(rawName);
    }
  } else if (Array.isArray(data?.data)) {
    for (const item of data.data) {
      const id = String(item?.id || item?.name || "").trim();
      if (id) discovered.push(id);
    }
  } else if (Array.isArray(data?.models)) {
    for (const item of data.models) {
      const id = typeof item === "string" ? item : String(item?.id || item?.name || "").trim();
      if (id) discovered.push(id);
    }
  }
  return discovered.length > 0 ? [...new Set(discovered)].slice(0, 60) : [...fallbackModels];
}

/**
 * Batch 8.5.2 Section 2, 3, 7 — Live Connection Test & Model Discovery per Provider / per Account.
 * Enforces SSRF & redirect hop validation via `safeOutboundFetch`.
 */
export async function testProviderConnection({
  kv = null,
  userId = "",
  provider,
  accountId = "",
  apiKey = "",
  customEndpoint = "",
  env = {},
  fetchImpl = globalThis.fetch,
  dnsResolver = null
} = {}) {
  const cleanProvider = String(provider || "").trim().toLowerCase();
  if (customEndpoint) {
    const epCheck = validateCustomProviderEndpoint(customEndpoint);
    if (!epCheck.valid) {
      return {
        ok: false,
        provider: cleanProvider,
        accountId: accountId || undefined,
        connectionStatus: "connection_failed",
        integrationState: "connection_failed",
        errorCode: epCheck.errorCode,
        error: epCheck.reason
      };
    }
  }

  const configsMap = kv && userId ? await readUserProviderConfigsMap(kv, userId) : {};
  const userCfg = configsMap[cleanProvider] || null;
  const spec = resolveProviderSpec(cleanProvider, userCfg);

  if (!spec) {
    return {
      ok: false,
      provider: cleanProvider,
      status: "unsupported_provider",
      connectionStatus: "connection_failed",
      errorCode: PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE,
      error: "Unsupported provider"
    };
  }

  let effectiveKey = typeof apiKey === "string" ? apiKey.trim() : "";
  let targetAccountId = String(accountId || "").trim();
  let storedAccountsList = [];

  if (kv && userId) {
    try {
      const raw = await kv.get(accountStoreKey(userId));
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) storedAccountsList = parsed;
      }
    } catch (_) {}
  }

  let matchedStoredAccount = null;
  if (storedAccountsList.length > 0) {
    if (targetAccountId) {
      matchedStoredAccount = storedAccountsList.find(
        (a) => a.accountId === targetAccountId && a.provider === cleanProvider
      );
    } else if (!effectiveKey) {
      matchedStoredAccount =
        storedAccountsList.find((a) => a.provider === cleanProvider && a.isDefault && a.enabled !== false) ||
        storedAccountsList.find((a) => a.provider === cleanProvider && a.enabled !== false);
      if (matchedStoredAccount) {
        targetAccountId = matchedStoredAccount.accountId;
      }
    }
    if (!effectiveKey && matchedStoredAccount) {
      try {
        effectiveKey = await decryptCredentialFromKv(matchedStoredAccount, {
          env,
          userId,
          provider: cleanProvider
        });
      } catch (_) {
        effectiveKey = "";
      }
    }
  }

  if (!effectiveKey) {
    effectiveKey =
      (spec.envKeys || []).map((k) => env?.[k]).find((v) => typeof v === "string" && v.trim()) || "";
  }

  if (!effectiveKey) {
    return {
      ok: false,
      provider: cleanProvider,
      accountId: targetAccountId || undefined,
      connectionStatus: "not_connected",
      integrationState: "ui_configured_only",
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING,
      error: "No API key configured for " + spec.name
    };
  }

  const keyValidation = validateProviderApiKeyFormat(cleanProvider, effectiveKey);
  if (!keyValidation.valid) {
    const errorStatus = {
      errorCode: keyValidation.errorCode,
      errorMessage: keyValidation.reason,
      timestamp: new Date().toISOString()
    };
    if (kv && userId && matchedStoredAccount) {
      await withUserAccountLock(userId, async () => {
        matchedStoredAccount.status = "connection_failed";
        matchedStoredAccount.connectionVerified = false;
        matchedStoredAccount.lastTestedAt = errorStatus.timestamp;
        matchedStoredAccount.errorStatus = errorStatus;
        await kv.put(accountStoreKey(userId), JSON.stringify(storedAccountsList), {
          expirationTtl: 86400 * 90
        });
      });
    }
    return {
      ok: false,
      provider: cleanProvider,
      accountId: targetAccountId || undefined,
      connectionStatus: "connection_failed",
      integrationState: "connection_failed",
      errorCode: keyValidation.errorCode,
      error: keyValidation.reason
    };
  }

  const modelsEndpoint =
    (customEndpoint && customEndpoint.replace(/\/chat\/completions\/?$/, "/models")) ||
    matchedStoredAccount?.customEndpoint?.replace(/\/chat\/completions\/?$/, "/models") ||
    userCfg?.modelsEndpoint ||
    spec.modelsEndpoint;

  const epValidation = validateCustomProviderEndpoint(modelsEndpoint);
  if (!epValidation.valid) {
    return {
      ok: false,
      provider: cleanProvider,
      accountId: targetAccountId || undefined,
      connectionStatus: "connection_failed",
      integrationState: "connection_failed",
      errorCode: epValidation.errorCode,
      error: epValidation.reason
    };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  const startedAt = Date.now();

  try {
    let response;
    if (cleanProvider === "gemini") {
      response = await safeOutboundFetch(
        `${modelsEndpoint}?key=${encodeURIComponent(effectiveKey)}`,
        { method: "GET", headers: { Accept: "application/json" }, signal: controller.signal },
        { fetchImpl, followRedirects: false, dnsResolver }
      );
    } else if (cleanProvider === "anthropic") {
      response = await safeOutboundFetch(
        modelsEndpoint,
        {
          method: "GET",
          headers: {
            "x-api-key": effectiveKey,
            "anthropic-version": "2023-06-01",
            Accept: "application/json"
          },
          signal: controller.signal
        },
        { fetchImpl, followRedirects: false, dnsResolver }
      );
    } else {
      response = await safeOutboundFetch(
        modelsEndpoint,
        {
          method: "GET",
          headers: {
            Authorization: `Bearer ${effectiveKey}`,
            Accept: "application/json"
          },
          signal: controller.signal
        },
        { fetchImpl, followRedirects: false, dnsResolver }
      );
    }

    const latencyMs = Math.max(1, Date.now() - startedAt);
    const testedAt = new Date().toISOString();

    if (!response.ok) {
      const errorCode = classifyProviderError({ status: response.status }, response.status);
      const errText = redactSecrets(`Provider ${spec.name} returned HTTP ${response.status}`);
      const errorStatus = { errorCode, httpStatus: response.status, errorMessage: errText, timestamp: testedAt };

      if (kv && userId) {
        await withUserAccountLock(userId, async () => {
          if (matchedStoredAccount) {
            matchedStoredAccount.status = "connection_failed";
            matchedStoredAccount.connectionVerified = false;
            matchedStoredAccount.lastTestedAt = testedAt;
            matchedStoredAccount.errorStatus = errorStatus;
            await kv.put(accountStoreKey(userId), JSON.stringify(storedAccountsList), {
              expirationTtl: 86400 * 90
            });
          }
          configsMap[cleanProvider] = {
            ...(configsMap[cleanProvider] || { providerId: cleanProvider, name: spec.name }),
            lastConnectionTest: { ok: false, testedAt, httpStatus: response.status, latencyMs, errorCode },
            errorStatus
          };
          await writeUserProviderConfigsMap(kv, userId, configsMap);
        });
      }

      return {
        ok: false,
        provider: cleanProvider,
        accountId: targetAccountId || undefined,
        httpStatus: response.status,
        latencyMs,
        errorCode,
        connectionStatus: "connection_failed",
        integrationState: "connection_failed",
        error: errText
      };
    }

    let bodyData = {};
    try {
      bodyData = await response.json();
    } catch (_) {}

    const discoveredModelIds = parseDiscoveredModelsPayload(
      cleanProvider,
      bodyData,
      spec.supportedModels || []
    );
    const providerCatalogModels = getProviderModels(cleanProvider, {
      discoveredModels: discoveredModelIds
    });

    const headerLimit = Number(response.headers?.get?.("x-ratelimit-limit-tokens") || 0);
    const headerRemaining = Number(response.headers?.get?.("x-ratelimit-remaining-tokens") || 0);

    if (kv && userId) {
      await withUserAccountLock(userId, async () => {
        if (matchedStoredAccount) {
          matchedStoredAccount.status = "working_live";
          matchedStoredAccount.connectionVerified = true;
          matchedStoredAccount.lastTestedAt = testedAt;
          matchedStoredAccount.errorStatus = null;
          matchedStoredAccount.discoveredModels = discoveredModelIds;
          if (headerLimit > 0) {
            matchedStoredAccount.quotaLimitTokens = headerLimit;
            matchedStoredAccount.quotaUsedTokens = Math.max(0, headerLimit - headerRemaining);
          }
          await kv.put(accountStoreKey(userId), JSON.stringify(storedAccountsList), {
            expirationTtl: 86400 * 90
          });
        }
        configsMap[cleanProvider] = {
          ...(configsMap[cleanProvider] || { providerId: cleanProvider, name: spec.name, enabled: true }),
          discoveredModels: discoveredModelIds,
          lastConnectionTest: {
            ok: true,
            testedAt,
            httpStatus: response.status,
            latencyMs,
            discoveredModelsCount: discoveredModelIds.length
          },
          errorStatus: null,
          updatedAt: testedAt
        };
        await writeUserProviderConfigsMap(kv, userId, configsMap);
      });
    }

    return {
      ok: true,
      provider: cleanProvider,
      accountId: targetAccountId || undefined,
      httpStatus: response.status,
      latencyMs,
      connectionStatus: "connected",
      integrationState: "working_live",
      models: discoveredModelIds,
      modelCatalog: providerCatalogModels
    };
  } catch (error) {
    const errorCode = classifyProviderError(error, 0);
    const errText = redactSecrets(String(error?.message || error)).slice(0, 200);
    const testedAt = new Date().toISOString();
    const errorStatus = { errorCode, errorMessage: errText, timestamp: testedAt };

    if (kv && userId) {
      await withUserAccountLock(userId, async () => {
        if (matchedStoredAccount) {
          matchedStoredAccount.status = "connection_failed";
          matchedStoredAccount.connectionVerified = false;
          matchedStoredAccount.lastTestedAt = testedAt;
          matchedStoredAccount.errorStatus = errorStatus;
          await kv.put(accountStoreKey(userId), JSON.stringify(storedAccountsList), {
            expirationTtl: 86400 * 90
          });
        }
        configsMap[cleanProvider] = {
          ...(configsMap[cleanProvider] || { providerId: cleanProvider, name: spec.name }),
          lastConnectionTest: { ok: false, testedAt, errorCode },
          errorStatus
        };
        await writeUserProviderConfigsMap(kv, userId, configsMap);
      });
    }

    return {
      ok: false,
      provider: cleanProvider,
      accountId: targetAccountId || undefined,
      errorCode,
      connectionStatus: "connection_failed",
      integrationState: "connection_failed",
      error: errText
    };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Batch 8.5 / 8.5.1 / 8.5.2 / B8.1.1 — Unified Live Provider Execution Adapter using Independent Multi-Account Chain.
 * Automatically updates `quotaUsedTokens` when a user-managed account completes a request,
 * and enforces SSRF + redirect hop protection on every outbound call.
 */
export async function invokeProviderCompletion({
  kv = null,
  userId = "",
  provider = "openai",
  apiModel = "",
  prompt = "",
  systemPrompt = "You are J.A.R.V.I.S., a precise analytical AI system.",
  history = [],
  preferredAccountId = "",
  timeoutMs = 30000,
  maxTokens = 1600,
  temperature = 0.45,
  env = {},
  fetchImpl = globalThis.fetch,
  dnsResolver = null,
  signal = null
} = {}) {
  const rawProvider = String(provider || "openai").toLowerCase();
  const normalizedProvider = rawProvider === "meta" ? "groq" : rawProvider;
  const configsMap = kv && userId ? await readUserProviderConfigsMap(kv, userId) : {};
  const userCfg = configsMap[rawProvider] || configsMap[normalizedProvider] || null;
  const spec = resolveProviderSpec(normalizedProvider, userCfg) || resolveProviderSpec(rawProvider, userCfg);
  if (!spec) {
    const err = new Error(`Unsupported provider: ${provider}`);
    err.status = 404;
    err.code = PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE;
    err.errorCode = PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE;
    throw err;
  }

  if (await isProviderExplicitlyDisabled(kv, userId, rawProvider, env)) {
    const err = new Error(`Provider '${spec.name}' (${normalizedProvider}) is disabled.`);
    err.status = 403;
    err.code = PROVIDER_ERROR_CODES.PROVIDER_DISABLED;
    err.errorCode = PROVIDER_ERROR_CODES.PROVIDER_DISABLED;
    err.provider = normalizedProvider;
    throw err;
  }

  let chain = await resolveAccountCredentialsChain(kv, userId, rawProvider, env);
  if (preferredAccountId) {
    const preferred = chain.filter((c) => c.accountId === preferredAccountId);
    const others = chain.filter((c) => c.accountId !== preferredAccountId);
    chain = [...preferred, ...others];
  }

  if (!chain.length) {
    const err = new Error(`No active credentials configured for provider '${spec.name}' (${normalizedProvider}).`);
    err.status = 503;
    err.code = PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING;
    err.errorCode = PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING;
    err.provider = normalizedProvider;
    throw err;
  }

  const effectiveModel = String(apiModel || userCfg?.defaultModel || spec.defaultModel).trim();
  if (
    effectiveModel.includes("unavailable") ||
    effectiveModel.includes("nonexistent") ||
    effectiveModel.includes("deprecated-invalid")
  ) {
    const modelErr = new Error(`Model '${effectiveModel}' is unavailable on provider '${spec.name}'.`);
    modelErr.status = 404;
    modelErr.code = PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE;
    modelErr.errorCode = PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE;
    modelErr.provider = normalizedProvider;
    throw modelErr;
  }

  const normalizedHistory = Array.isArray(history)
    ? history
        .filter((h) => h && typeof h.content === "string" && h.content.trim())
        .slice(-16)
        .map((h) => ({
          role: h.role === "assistant" ? "assistant" : "user",
          content: h.content.trim().slice(0, 4000)
        }))
    : [];

  let lastError = null;

  for (let idx = 0; idx < chain.length; idx++) {
    const cred = chain[idx];
    const targetEndpoint = cred.customEndpoint || userCfg?.endpoint || spec.endpoint;
    const epValidation = validateCustomProviderEndpoint(targetEndpoint);
    if (!epValidation.valid) {
      const epErr = new Error(epValidation.reason);
      epErr.status = 400;
      epErr.code = epValidation.errorCode;
      epErr.errorCode = epValidation.errorCode;
      epErr.provider = normalizedProvider;
      epErr.accountId = cred.accountId;
      throw epErr;
    }

    if (signal?.aborted) {
      const abortErr = new Error("Request cancelled by caller");
      abortErr.status = 499;
      abortErr.code = "CANCELLED";
      abortErr.errorCode = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
      throw abortErr;
    }

    const controller = new AbortController();
    const onExternalAbort = () => controller.abort();
    if (signal) {
      signal.addEventListener("abort", onExternalAbort, { once: true });
    }
    const effectiveTimeout = Math.max(50, Number(timeoutMs) || 30000);
    const timer = setTimeout(() => controller.abort(), effectiveTimeout);
    const startedAt = Date.now();

    try {
      let response;
      try {
        if (normalizedProvider === "gemini") {
          const contents = [
            ...normalizedHistory.map((item) => ({
              role: item.role === "assistant" ? "model" : "user",
              parts: [{ text: item.content }]
            })),
            { role: "user", parts: [{ text: String(prompt || "") }] }
          ];
          const geminiUrl = `${targetEndpoint}/${encodeURIComponent(effectiveModel)}:generateContent?key=${encodeURIComponent(cred.apiKey)}`;
          response = await safeOutboundFetch(
            geminiUrl,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                systemInstruction: { parts: [{ text: String(systemPrompt || "") }] },
                contents,
                generationConfig: { temperature, maxOutputTokens: maxTokens }
              }),
              signal: controller.signal
            },
            { fetchImpl, followRedirects: false, dnsResolver }
          );
        } else if (normalizedProvider === "anthropic") {
          const messages = [
            ...normalizedHistory,
            { role: "user", content: String(prompt || "") }
          ];
          response = await safeOutboundFetch(
            targetEndpoint,
            {
              method: "POST",
              headers: {
                "x-api-key": cred.apiKey,
                "anthropic-version": "2023-06-01",
                "Content-Type": "application/json"
              },
              body: JSON.stringify({
                model: effectiveModel,
                system: String(systemPrompt || ""),
                max_tokens: maxTokens,
                temperature,
                messages
              }),
              signal: controller.signal
            },
            { fetchImpl, followRedirects: false, dnsResolver }
          );
        } else {
          const messages = [
            { role: "system", content: String(systemPrompt || "") },
            ...normalizedHistory,
            { role: "user", content: String(prompt || "") }
          ];
          response = await safeOutboundFetch(
            targetEndpoint,
            {
              method: "POST",
              headers: {
                Authorization: `Bearer ${cred.apiKey}`,
                "Content-Type": "application/json",
                ...(normalizedProvider === "openrouter"
                  ? { "HTTP-Referer": "https://jarvis-api.workers.dev", "X-Title": "JARVIS Mobile B8.1.1" }
                  : {})
              },
              body: JSON.stringify({
                model: effectiveModel,
                temperature,
                max_tokens: maxTokens,
                messages
              }),
              signal: controller.signal
            },
            { fetchImpl, followRedirects: false, dnsResolver }
          );
        }
      } catch (fetchErr) {
        if (signal?.aborted || fetchErr?.name === "AbortError") {
          throw fetchErr;
        }
        const netErr = new Error(
          redactSecrets(`Network failure contacting ${spec.name}: ${fetchErr?.message || String(fetchErr)}`)
        );
        netErr.status = fetchErr?.status || 502;
        netErr.code = fetchErr?.code === "SSRF_BLOCKED_DESTINATION"
          ? PROVIDER_ERROR_CODES.PROVIDER_ERROR
          : PROVIDER_ERROR_CODES.NETWORK_ERROR;
        netErr.errorCode = netErr.code;
        netErr.provider = normalizedProvider;
        netErr.accountId = cred.accountId;
        throw netErr;
      }

      const latencyMs = Math.max(1, Date.now() - startedAt);
      if (!response.ok) {
        const errBody = await response.text().catch(() => "");
        const cleanMsg = redactSecrets(errBody).slice(0, 240);
        const httpErr = new Error(
          `Provider ${spec.name} HTTP ${response.status}${cleanMsg ? `: ${cleanMsg}` : ""}`
        );
        httpErr.status = response.status;
        httpErr.provider = normalizedProvider;
        httpErr.accountId = cred.accountId;
        httpErr.errorCode = classifyProviderError(
          { status: response.status, message: `${httpErr.message} ${cleanMsg}` },
          response.status
        );
        httpErr.code = httpErr.errorCode;
        throw httpErr;
      }

      let data;
      try {
        data = await response.json();
      } catch (_) {
        const parseErr = new Error(`Provider ${spec.name} returned invalid JSON response`);
        parseErr.status = 502;
        parseErr.provider = normalizedProvider;
        parseErr.accountId = cred.accountId;
        parseErr.code = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
        parseErr.errorCode = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
        throw parseErr;
      }

      let text = "";
      if (normalizedProvider === "gemini") {
        text =
          data?.candidates?.[0]?.content?.parts
            ?.map((p) => (typeof p?.text === "string" ? p.text : ""))
            .join("")
            .trim() || "";
      } else if (normalizedProvider === "anthropic") {
        text = Array.isArray(data?.content)
          ? data.content.map((p) => (typeof p?.text === "string" ? p.text : "")).join("").trim()
          : typeof data?.content === "string"
            ? data.content.trim()
            : "";
      } else {
        const rawContent = data?.choices?.[0]?.message?.content;
        text = Array.isArray(rawContent)
          ? rawContent.map((p) => (typeof p?.text === "string" ? p.text : "")).join("").trim()
          : typeof rawContent === "string"
            ? data.choices?.[0]?.message?.content?.trim() || ""
            : "";
      }

      if (!text) {
        const emptyErr = new Error(`Provider ${spec.name} returned an empty completion`);
        emptyErr.status = 502;
        emptyErr.provider = normalizedProvider;
        emptyErr.accountId = cred.accountId;
        emptyErr.code = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
        emptyErr.errorCode = PROVIDER_ERROR_CODES.PROVIDER_ERROR;
        throw emptyErr;
      }

      const reportedTokens =
        Number(data?.usage?.total_tokens) ||
        Number(data?.usageMetadata?.totalTokenCount) ||
        (Number(data?.usage?.input_tokens || 0) + Number(data?.usage?.output_tokens || 0));
      const tokensUsed =
        reportedTokens > 0
          ? reportedTokens
          : Math.max(32, Math.ceil((String(prompt || "").length + text.length) / 4));

      if (cred.source === "user_account" && kv && userId && cred.accountId) {
        await recordAccountTokenUsage(kv, userId, cred.accountId, tokensUsed, env).catch(() => {});
      }

      return {
        ok: true,
        provider: normalizedProvider,
        providerName: spec.name,
        model: effectiveModel,
        accountId: cred.accountId,
        accountSource: cred.source,
        tokensUsed,
        latencyMs,
        text: redactSecrets(text)
      };
    } catch (error) {
      if (signal?.aborted || error?.name === "AbortError") {
        const isCallerCancel = Boolean(signal?.aborted);
        const abortErr = new Error(
          isCallerCancel
            ? `Provider ${spec.name} request cancelled`
            : `Provider ${spec.name} timed out after ${effectiveTimeout}ms`
        );
        abortErr.status = isCallerCancel ? 499 : 504;
        abortErr.code = isCallerCancel ? "CANCELLED" : PROVIDER_ERROR_CODES.PROVIDER_TIMEOUT;
        abortErr.errorCode = isCallerCancel
          ? PROVIDER_ERROR_CODES.PROVIDER_ERROR
          : PROVIDER_ERROR_CODES.PROVIDER_TIMEOUT;
        abortErr.provider = normalizedProvider;
        abortErr.accountId = cred.accountId;
        lastError = abortErr;
        if (isCallerCancel) throw abortErr;
      } else {
        const classifiedCode = error?.errorCode || classifyProviderError(error, error?.status);
        const sanitized = new Error(redactSecrets(error?.message || String(error)));
        sanitized.status = error?.status || 502;
        sanitized.code = classifiedCode;
        sanitized.errorCode = classifiedCode;
        sanitized.provider = normalizedProvider;
        sanitized.accountId = cred.accountId;
        lastError = sanitized;
      }
    } finally {
      clearTimeout(timer);
      if (signal) {
        signal.removeEventListener("abort", onExternalAbort);
      }
    }
  }

  throw lastError || new Error(`All credentials failed for provider ${normalizedProvider}`);
}

/**
 * Batch 8.7 / B8.1.1 — Increments quotaUsedTokens for a specific user AI account after verified completion.
 * Serialized per userId via withUserAccountLock so concurrent agent completions never overwrite each other.
 */
export async function recordAccountTokenUsage(kv, userId, accountId, tokensUsed = 0, env = {}) {
  if (!kv || !userId || !accountId) return false;
  const delta = Math.max(0, Math.floor(Number(tokensUsed) || 0));
  if (delta <= 0) return false;
  return withUserAccountLock(userId, async () => {
    try {
      const raw = await kv.get(accountStoreKey(userId));
      if (!raw) return false;
      const list = JSON.parse(raw);
      if (!Array.isArray(list)) return false;
      const idx = list.findIndex((a) => a.accountId === accountId);
      if (idx < 0) return false;
      list[idx].quotaUsedTokens = Math.max(0, Number(list[idx].quotaUsedTokens || 0)) + delta;
      list[idx].updatedAt = new Date().toISOString();
      await kv.put(accountStoreKey(userId), JSON.stringify(list), { expirationTtl: 86400 * 180 });
      return true;
    } catch (_) {
      return false;
    }
  });
}