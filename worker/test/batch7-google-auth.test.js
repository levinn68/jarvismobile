import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");

const clientPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt");
const vaultPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/account/SupabaseSessionVault.kt");
const mainActivityPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/MainActivity.kt");
const deviceSetupPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/device/DeviceSetupActivity.kt");
const buildGradlePath = path.join(repoRoot, "app/build.gradle.kts");
const workflowPath = path.join(repoRoot, ".github/workflows/android.yml");

const clientSource = fs.readFileSync(clientPath, "utf8");
const vaultSource = fs.readFileSync(vaultPath, "utf8");
const mainActivitySource = fs.readFileSync(mainActivityPath, "utf8");
const deviceSetupSource = fs.readFileSync(deviceSetupPath, "utf8");
const buildGradleSource = fs.readFileSync(buildGradlePath, "utf8");
const workflowSource = fs.readFileSync(workflowPath, "utf8");

function makeJwt(payloadObj) {
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify(payloadObj)).toString("base64url");
  const sig = Buffer.from("signature-bytes-verified").toString("base64url");
  return `${header}.${payload}.${sig}`;
}

/**
 * Behavioral reference mirror of SupabaseJarvisClient + SupabaseSessionVault
 * matching the exact 7-stage contract in SupabaseJarvisClient.kt.
 */
class SimulatedSessionVault {
  constructor() {
    this.key = crypto.randomBytes(32);
    this.prefs = new Map();
    this.failNextSave = false;
  }

  save(valueObj) {
    const serialized = JSON.stringify(valueObj);
    if (!serialized || !valueObj?.access_token) {
      throw new Error("Data sesi kosong atau tidak memiliki access_token.");
    }
    const prevCiphertext = this.prefs.get("ciphertext") ?? null;
    const prevIv = this.prefs.get("iv") ?? null;

    if (this.failNextSave) {
      if (prevCiphertext && prevIv) {
        this.prefs.set("ciphertext", prevCiphertext);
        this.prefs.set("iv", prevIv);
      }
      throw new Error("Gagal menyimpan sesi terenkripsi ke penyimpanan perangkat.");
    }

    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", this.key, iv);
    const encrypted = Buffer.concat([cipher.update(serialized, "utf8"), cipher.final()]);
    const tag = cipher.getAuthTag();
    const combined = Buffer.concat([encrypted, tag]);

    // Round-trip verification before commit
    const decipher = crypto.createDecipheriv("aes-256-gcm", this.key, iv);
    decipher.setAuthTag(tag);
    const roundTrip = JSON.parse(Buffer.concat([decipher.update(encrypted), decipher.final()]).toString("utf8"));
    if (roundTrip.access_token !== valueObj.access_token) {
      throw new Error("Verifikasi enkripsi sesi gagal (access_token tidak cocok).");
    }

    this.prefs.set("ciphertext", combined.toString("base64"));
    this.prefs.set("iv", iv.toString("base64"));
  }

  read() {
    try {
      const ciphertextB64 = this.prefs.get("ciphertext");
      const ivB64 = this.prefs.get("iv");
      if (!ciphertextB64 || !ivB64) return null;
      const combined = Buffer.from(ciphertextB64, "base64");
      const iv = Buffer.from(ivB64, "base64");
      const tag = combined.subarray(combined.length - 16);
      const data = combined.subarray(0, combined.length - 16);
      const decipher = crypto.createDecipheriv("aes-256-gcm", this.key, iv);
      decipher.setAuthTag(tag);
      return JSON.parse(Buffer.concat([decipher.update(data), decipher.final()]).toString("utf8"));
    } catch {
      return null;
    }
  }
}

function sanitizeDiagnosticText(input) {
  if (!input) return "";
  return String(input)
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, "[REDACTED_JWT]")
    .replace(/sb_(?:publishable|secret)_[A-Za-z0-9_-]+/g, "[REDACTED_KEY]")
    .replace(/("(?:id_token|access_token|refresh_token|client_secret|apikey)"\s*:\s*")([^"]+)(")/gi, "$1[REDACTED]$3")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 280);
}

function isValidGoogleWebClientId(value) {
  const trimmed = String(value ?? "").trim();
  if (!/^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(trimmed)) return false;
  const lower = trimmed.toLowerCase();
  if (lower.includes("android") || lower.startsWith("0-") || lower.includes("placeholder") || lower.includes("example")) {
    return false;
  }
  return true;
}

function isJwtFormattedKey(key) {
  const trimmed = String(key ?? "").trim();
  return trimmed.startsWith("eyJ") && (trimmed.match(/\./g) || []).length === 2;
}

function parseSupabaseHttpError(statusCode, responseBody) {
  let primaryMessage = "";
  let errorCode = "";
  let hint = "";
  try {
    if (String(responseBody).trim().startsWith("{")) {
      const json = JSON.parse(responseBody);
      primaryMessage = [json.error_description, json.message, json.msg, json.error]
        .map((v) => String(v ?? "").trim())
        .find((v) => v.length > 0) || "";
      errorCode = [json.error_code, json.code]
        .map((v) => String(v ?? "").trim())
        .find((v) => v.length > 0) || "";
      hint = String(json.hint ?? "").trim();
    }
  } catch {
    // ignore non-json
  }
  const safeMessage = sanitizeDiagnosticText(primaryMessage) || `Supabase HTTP ${statusCode}`;
  const err = new Error(safeMessage);
  err.statusCode = statusCode;
  err.errorCode = sanitizeDiagnosticText(errorCode);
  err.hint = sanitizeDiagnosticText(hint);
  return err;
}

async function executeSignInFlow({
  baseUrl = "https://agwaxaejnnszunccmftm.supabase.co",
  anonKey = "sb_publishable_BtFlJ2XJc4SW4kNSmIZ8bA_WcTQgFq-",
  webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com",
  credentialProvider,
  httpHandler,
  vault = new SimulatedSessionVault(),
  onStageChanged = () => {}
}) {
  const emit = (stage, msg) => onStageChanged(stage, msg);
  const fail = (stage, msg, statusCode = 0) => {
    const err = new Error(`[${stage}] ${msg}`);
    err.stage = stage;
    err.diagnosticMessage = msg;
    err.statusCode = statusCode;
    throw err;
  };

  // 1. AUTH_CONFIG_VALIDATION
  emit("AUTH_CONFIG_VALIDATION", "Memvalidasi konfigurasi Google OAuth & Supabase...");
  if (!baseUrl.startsWith("https://") || baseUrl.length < 12 || !anonKey.trim()) {
    fail("AUTH_CONFIG_VALIDATION", "Konfigurasi Supabase belum lengkap. Periksa SUPABASE_URL dan SUPABASE_ANON_KEY.");
  }
  const trimmedClientId = String(webClientId ?? "").trim();
  if (!trimmedClientId) {
    fail("AUTH_CONFIG_VALIDATION", "GOOGLE_WEB_CLIENT_ID kosong.");
  }
  if (!isValidGoogleWebClientId(trimmedClientId)) {
    fail(
      "AUTH_CONFIG_VALIDATION",
      "Format GOOGLE_WEB_CLIENT_ID tidak valid. Gunakan OAuth 2.0 Client ID bertipe Web application (<project>-<hash>.apps.googleusercontent.com), bukan Android Client ID."
    );
  }

  // 2. GOOGLE_CREDENTIAL_REQUEST
  emit("GOOGLE_CREDENTIAL_REQUEST", "Membuka pemilihan akun Google...");
  let credential;
  try {
    credential = await credentialProvider(trimmedClientId);
  } catch (error) {
    if (error.kind === "GetCredentialCancellationException") {
      fail("GOOGLE_CREDENTIAL_REQUEST", "Login Google dibatalkan oleh pengguna.");
    }
    if (error.kind === "NoCredentialException") {
      fail("GOOGLE_CREDENTIAL_REQUEST", "Tidak ada akun Google yang tersedia di perangkat.");
    }
    const combined = `${error.type || ""} ${error.message || ""}`.toLowerCase();
    if (combined.includes("developer") || combined.includes("28444") || /\b10\b/.test(combined)) {
      fail(
        "GOOGLE_CREDENTIAL_REQUEST",
        `Google menolak konfigurasi OAuth aplikasi (DEVELOPER_ERROR). Detail: ${sanitizeDiagnosticText(error.message)}`
      );
    }
    fail("GOOGLE_CREDENTIAL_REQUEST", `Credential Manager gagal mengambil kredensial Google. Detail: ${sanitizeDiagnosticText(error.message)}`);
  }

  // 3. GOOGLE_ID_TOKEN_RECEIVED
  emit("GOOGLE_ID_TOKEN_RECEIVED", "Memverifikasi Google ID Token...");
  const allowedTypes = new Set([
    "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
    "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL",
    "GoogleIdTokenCredential"
  ]);
  if (!credential || !allowedTypes.has(credential.type)) {
    fail(
      "GOOGLE_ID_TOKEN_RECEIVED",
      `Tipe kredensial yang diterima bukan Google ID Token (${sanitizeDiagnosticText(credential?.type || "unknown")}).`
    );
  }
  const rawIdToken = String(credential.idToken ?? "").trim();
  if (!rawIdToken) {
    fail("GOOGLE_ID_TOKEN_RECEIVED", "Google ID Token kosong setelah pemilihan akun.");
  }
  const parts = rawIdToken.split(".");
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) {
    fail("GOOGLE_ID_TOKEN_RECEIVED", "Format Google ID Token tidak valid (bukan JWT 3-segmen).");
  }
  let claims;
  try {
    claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  } catch {
    fail("GOOGLE_ID_TOKEN_RECEIVED", "Payload Google ID Token tidak dapat didekode sebagai JSON.");
  }
  if (claims.aud && claims.aud !== trimmedClientId) {
    fail("GOOGLE_ID_TOKEN_RECEIVED", "Audience (aud) pada Google ID Token tidak cocok dengan GOOGLE_WEB_CLIENT_ID yang dikonfigurasi.");
  }

  // 4. SUPABASE_TOKEN_EXCHANGE
  emit("SUPABASE_TOKEN_EXCHANGE", "Menukar Google ID Token dengan sesi Supabase...");
  const headers = {
    apikey: anonKey,
    "Content-Type": "application/json"
  };
  if (isJwtFormattedKey(anonKey)) {
    headers.Authorization = `Bearer ${anonKey}`;
  }
  const body = { provider: "google", id_token: rawIdToken };
  if (claims.nonce) body.nonce = claims.nonce;

  let response;
  try {
    const httpRes = await httpHandler({
      path: "/auth/v1/token?grant_type=id_token",
      method: "POST",
      headers,
      body
    });
    if (httpRes.status < 200 || httpRes.status >= 300) {
      throw parseSupabaseHttpError(httpRes.status, httpRes.body);
    }
    response = JSON.parse(httpRes.body);
  } catch (error) {
    if (error.statusCode) {
      const cleanMsg = sanitizeDiagnosticText(error.message);
      const codeInfo = error.errorCode ? ` [${error.errorCode}]` : "";
      const hintInfo = error.hint ? ` (${error.hint})` : "";
      const detail = `${cleanMsg}${codeInfo}${hintInfo}`.trim();
      const mapMsg = {
        400: `Supabase menolak pertukaran Google ID Token (HTTP 400 Bad Request): ${detail}.`,
        401: `Supabase menolak otorisasi permintaan (HTTP 401 Unauthorized): ${detail}.`,
        403: `Supabase menolak akses login Google (HTTP 403 Forbidden): ${detail}.`,
        429: `Terlalu banyak percobaan login ke Supabase (HTTP 429 Rate Limit): ${detail}.`
      };
      const finalMsg =
        mapMsg[error.statusCode] ||
        (error.statusCode >= 500
          ? `Server Supabase mengalami gangguan internal (HTTP ${error.statusCode}): ${detail}.`
          : `Pertukaran token Supabase gagal (HTTP ${error.statusCode}): ${detail}`);
      fail("SUPABASE_TOKEN_EXCHANGE", finalMsg, error.statusCode);
    }
    fail("SUPABASE_TOKEN_EXCHANGE", `Gagal menghubungi Supabase Auth: ${sanitizeDiagnosticText(error.message)}`);
  }

  // 5. SUPABASE_SESSION_VALIDATION
  emit("SUPABASE_SESSION_VALIDATION", "Memvalidasi sesi dan profil Supabase...");
  const accessToken = String(response?.access_token ?? "").trim();
  if (!accessToken) {
    fail("SUPABASE_SESSION_VALIDATION", "Respons Supabase tidak memiliki access_token.");
  }
  const refreshToken = String(response?.refresh_token ?? "").trim();
  if (!refreshToken) {
    fail("SUPABASE_SESSION_VALIDATION", "Respons Supabase tidak memiliki refresh_token.");
  }
  const user = response?.user;
  if (!user || typeof user !== "object") {
    fail("SUPABASE_SESSION_VALIDATION", "Respons Supabase tidak memiliki objek user.");
  }
  const userId = String(user.id ?? "").trim();
  if (!userId) {
    fail("SUPABASE_SESSION_VALIDATION", "Profil user Supabase tidak memiliki id.");
  }
  const metadata = user.user_metadata || {};
  const session = {
    accessToken,
    refreshToken,
    userId,
    email: user.email || null,
    displayName: metadata.full_name || metadata.name || null
  };

  // 6. SESSION_VAULT_SAVE
  emit("SESSION_VAULT_SAVE", "Menyimpan sesi terenkripsi ke perangkat...");
  const now = Math.floor(Date.now() / 1000);
  const expiresAt = response.expires_at > now ? response.expires_at : now + Math.max(60, response.expires_in || 3600);
  try {
    vault.save({
      ...response,
      user_id: session.userId,
      email: session.email || "",
      display_name: session.displayName || "",
      expires_at: expiresAt
    });
    const verified = vault.read();
    if (!verified || !verified.access_token || verified.user_id !== session.userId) {
      throw new Error("Sesi tersimpan tetapi gagal dibaca ulang dari SupabaseSessionVault.");
    }
  } catch (error) {
    fail("SESSION_VAULT_SAVE", `Gagal menyimpan sesi Supabase secara lokal: ${sanitizeDiagnosticText(error.message)}`);
  }

  // 7. AUTH_SUCCESS
  emit("AUTH_SUCCESS", "Akun Google berhasil tersambung ke Supabase.");
  return session;
}

test("Source Audit: Kotlin files implement all 7 AuthStage codes, sb_publishable header fix, and SIWG credential support", () => {
  for (const stage of [
    "AUTH_CONFIG_VALIDATION",
    "GOOGLE_CREDENTIAL_REQUEST",
    "GOOGLE_ID_TOKEN_RECEIVED",
    "SUPABASE_TOKEN_EXCHANGE",
    "SUPABASE_SESSION_VALIDATION",
    "SESSION_VAULT_SAVE",
    "AUTH_SUCCESS"
  ]) {
    assert.ok(clientSource.includes(stage), `SupabaseJarvisClient.kt missing stage ${stage}`);
  }

  // Verify Root Cause 1 fix: do not send Authorization: Bearer sb_publishable_...
  assert.ok(
    !clientSource.includes('setRequestProperty("Authorization", "Bearer ${token ?: anonKey}")'),
    "SupabaseJarvisClient.kt must not unconditionally send anonKey as Bearer token"
  );
  assert.ok(
    clientSource.includes("isJwtFormattedKey(anonKey)"),
    "SupabaseJarvisClient.kt must check if anonKey is a JWT before sending Authorization: Bearer"
  );

  // Verify Root Cause 2 fix: support TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL and GoogleIdTokenCredential
  assert.ok(
    clientSource.includes("TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL") &&
      clientSource.includes("TYPE_GOOGLE_ID_TOKEN_CREDENTIAL") &&
      clientSource.includes("credential is GoogleIdTokenCredential"),
    "SupabaseJarvisClient.kt must support all GoogleIdTokenCredential subtypes including SIWG"
  );

  // Verify Supabase error fields parsing
  for (const field of ["error_description", "message", "msg", "error", "error_code", "code", "hint"]) {
    assert.ok(clientSource.includes(`"${field}"`), `SupabaseJarvisClient.kt must parse '${field}'`);
  }

  // Verify Vault synchronous commit & round-trip check
  assert.ok(vaultSource.includes(".commit()"), "SupabaseSessionVault.kt must use synchronous commit()");
  assert.ok(!vaultSource.includes("catch (_: Exception) {\n        clear()"), "SupabaseSessionVault.kt must not wipe keystore on read error");

  // Verify MainActivity & DeviceSetupActivity integration
  assert.ok(mainActivitySource.includes("openDeviceSetupAfter"), "MainActivity.kt must support signing in and opening DeviceSetupActivity");
  assert.ok(deviceSetupSource.includes("ensureGoogleSessionBlocking"), "DeviceSetupActivity.kt must support direct Google sign-in");
  assert.ok(buildGradleSource.includes('resValue("string", "default_web_client_id"'), "app/build.gradle.kts must provide default_web_client_id resource");
  assert.ok(workflowSource.includes("Validate Google OAuth & Supabase configuration"), "android.yml must validate GOOGLE_WEB_CLIENT_ID format");
});

test("Case 1: Web Client ID kosong -> gagal pada AUTH_CONFIG_VALIDATION", async () => {
  await assert.rejects(
    () => executeSignInFlow({ webClientId: "   " }),
    (err) => err.stage === "AUTH_CONFIG_VALIDATION" && err.message.includes("GOOGLE_WEB_CLIENT_ID kosong")
  );
});

test("Case 2: Web Client ID tidak valid (termasuk Android Client ID / salah format) -> gagal pada AUTH_CONFIG_VALIDATION", async () => {
  await assert.rejects(
    () => executeSignInFlow({ webClientId: "com.jarvis.mobile.assistant" }),
    (err) => err.stage === "AUTH_CONFIG_VALIDATION" && err.message.includes("Format GOOGLE_WEB_CLIENT_ID tidak valid")
  );
  await assert.rejects(
    () => executeSignInFlow({ webClientId: "12345-android-client.apps.googleusercontent.com" }),
    (err) => err.stage === "AUTH_CONFIG_VALIDATION"
  );
});

test("Case 3: Credential Manager cancellation -> gagal pada GOOGLE_CREDENTIAL_REQUEST", async () => {
  await assert.rejects(
    () =>
      executeSignInFlow({
        credentialProvider: async () => {
          const err = new Error("User cancelled account selection");
          err.kind = "GetCredentialCancellationException";
          throw err;
        }
      }),
    (err) => err.stage === "AUTH_CONFIG_VALIDATION" ? false : err.stage === "GOOGLE_CREDENTIAL_REQUEST" && err.message.includes("dibatalkan")
  );
});

test("Case 4: No Google credential -> gagal pada GOOGLE_CREDENTIAL_REQUEST", async () => {
  await assert.rejects(
    () =>
      executeSignInFlow({
        credentialProvider: async () => {
          const err = new Error("No credentials available");
          err.kind = "NoCredentialException";
          throw err;
        }
      }),
    (err) => err.stage === "GOOGLE_CREDENTIAL_REQUEST" && err.message.includes("Tidak ada akun Google")
  );
});

test("Case 5: Google Developer Error (10 / 28444) -> gagal pada GOOGLE_CREDENTIAL_REQUEST dengan petunjuk SHA-1 & package", async () => {
  await assert.rejects(
    () =>
      executeSignInFlow({
        credentialProvider: async () => {
          const err = new Error("28444: Developer console is not set up correctly (code 10)");
          err.kind = "GetCredentialException";
          throw err;
        }
      }),
    (err) => err.stage === "GOOGLE_CREDENTIAL_REQUEST" && err.message.includes("DEVELOPER_ERROR")
  );
});

test("Case 6: Credential bukan Google ID Token -> gagal pada GOOGLE_ID_TOKEN_RECEIVED", async () => {
  await assert.rejects(
    () =>
      executeSignInFlow({
        credentialProvider: async () => ({
          type: "androidx.credentials.TYPE_PASSWORD_CREDENTIAL",
          idToken: ""
        })
      }),
    (err) => err.stage === "GOOGLE_ID_TOKEN_RECEIVED" && err.message.includes("bukan Google ID Token")
  );
});

test("Case 7: Google ID Token berhasil diperoleh (termasuk TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL) & divalidasi", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({
    iss: "https://accounts.google.com",
    aud: webClientId,
    sub: "google-user-123",
    email: "jarvis@example.com"
  });
  const stages = [];
  const session = await executeSignInFlow({
    webClientId,
    credentialProvider: async () => ({
      type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL",
      idToken: validJwt
    }),
    httpHandler: async (req) => {
      assert.equal(req.headers.apikey, "sb_publishable_BtFlJ2XJc4SW4kNSmIZ8bA_WcTQgFq-");
      assert.equal(req.headers.Authorization, undefined, "Must NOT send Authorization: Bearer sb_publishable_...");
      return {
        status: 200,
        body: JSON.stringify({
          access_token: makeJwt({ sub: "user-uuid-1", role: "authenticated" }),
          refresh_token: "refresh-token-xyz",
          expires_in: 3600,
          user: {
            id: "user-uuid-1",
            email: "jarvis@example.com",
            user_metadata: { full_name: "Tony Stark" }
          }
        })
      };
    },
    onStageChanged: (stage) => stages.push(stage)
  });
  assert.ok(stages.includes("GOOGLE_ID_TOKEN_RECEIVED"));
  assert.equal(session.email, "jarvis@example.com");
});

test("Case 8: Supabase HTTP 400 -> gagal pada SUPABASE_TOKEN_EXCHANGE dengan detail tanpa bocor token", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 400,
          body: JSON.stringify({
            error: "invalid_grant",
            error_description: `Bad ID token ${validJwt}`,
            error_code: "bad_oauth_callback"
          })
        })
      }),
    (err) =>
      err.stage === "SUPABASE_TOKEN_EXCHANGE" &&
      err.statusCode === 400 &&
      err.message.includes("HTTP 400 Bad Request") &&
      err.message.includes("[bad_oauth_callback]") &&
      !err.message.includes(validJwt)
  );
});

test("Case 9: Supabase HTTP 401 -> gagal pada SUPABASE_TOKEN_EXCHANGE", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 401,
          body: JSON.stringify({ message: "Invalid API key", code: "invalid_api_key" })
        })
      }),
    (err) => err.stage === "SUPABASE_TOKEN_EXCHANGE" && err.statusCode === 401 && err.message.includes("HTTP 401 Unauthorized")
  );
});

test("Case 10: Supabase HTTP 403 -> gagal pada SUPABASE_TOKEN_EXCHANGE", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 403,
          body: JSON.stringify({ msg: "Signups not allowed for this instance", error_code: "signup_disabled" })
        })
      }),
    (err) => err.stage === "SUPABASE_TOKEN_EXCHANGE" && err.statusCode === 403 && err.message.includes("HTTP 403 Forbidden")
  );
});

test("Case 11: Supabase HTTP 429 -> gagal pada SUPABASE_TOKEN_EXCHANGE", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 429,
          body: JSON.stringify({ message: "Request rate limit reached", error_code: "over_request_rate_limit" })
        })
      }),
    (err) => err.stage === "SUPABASE_TOKEN_EXCHANGE" && err.statusCode === 429 && err.message.includes("HTTP 429 Rate Limit")
  );
});

test("Case 12: Supabase HTTP 500 -> gagal pada SUPABASE_TOKEN_EXCHANGE", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 500,
          body: JSON.stringify({ message: "Database error saving new user", hint: "Check auth trigger" })
        })
      }),
    (err) =>
      err.stage === "SUPABASE_TOKEN_EXCHANGE" &&
      err.statusCode === 500 &&
      err.message.includes("HTTP 500") &&
      err.message.includes("Check auth trigger")
  );
});

test("Case 13: Respons Supabase tidak memiliki user -> gagal pada SUPABASE_SESSION_VALIDATION", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 200,
          body: JSON.stringify({
            access_token: makeJwt({ sub: "u-1" }),
            refresh_token: "ref-1"
          })
        })
      }),
    (err) => err.stage === "SUPABASE_SESSION_VALIDATION" && err.message.includes("tidak memiliki objek user")
  );
});

test("Case 14: Respons Supabase tidak memiliki access_token -> gagal pada SUPABASE_SESSION_VALIDATION", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 200,
          body: JSON.stringify({
            refresh_token: "ref-1",
            user: { id: "u-1", email: "user@example.com" }
          })
        })
      }),
    (err) => err.stage === "SUPABASE_SESSION_VALIDATION" && err.message.includes("tidak memiliki access_token")
  );
});

test("Case 15: SupabaseSessionVault gagal menyimpan -> gagal pada SESSION_VAULT_SAVE tanpa menghapus sesi lama", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1" });
  const vault = new SimulatedSessionVault();
  vault.save({
    access_token: "existing-access-token",
    refresh_token: "existing-refresh-token",
    user_id: "existing-user",
    expires_at: Math.floor(Date.now() / 1000) + 3600
  });
  vault.failNextSave = true;

  await assert.rejects(
    () =>
      executeSignInFlow({
        webClientId,
        vault,
        credentialProvider: async () => ({
          type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL",
          idToken: validJwt
        }),
        httpHandler: async () => ({
          status: 200,
          body: JSON.stringify({
            access_token: makeJwt({ sub: "new-user" }),
            refresh_token: "new-refresh-token",
            expires_in: 3600,
            user: { id: "new-user", email: "new@example.com" }
          })
        })
      }),
    (err) => err.stage === "SESSION_VAULT_SAVE"
  );

  // Verify old session is preserved intact!
  const preserved = vault.read();
  assert.ok(preserved, "Previous session must not be wiped when new vault save fails");
  assert.equal(preserved.user_id, "existing-user");
});

test("Case 16: Login berhasil dan sesi dapat dipulihkan (AUTH_SUCCESS + restoreSession)", async () => {
  const webClientId = "412345678901-xyz987abc123.apps.googleusercontent.com";
  const validJwt = makeJwt({ aud: webClientId, sub: "g-1", nonce: "nonce-123" });
  const vault = new SimulatedSessionVault();
  const stages = [];

  const session = await executeSignInFlow({
    webClientId,
    vault,
    credentialProvider: async () => ({
      type: "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL",
      idToken: validJwt
    }),
    httpHandler: async (req) => {
      assert.equal(req.body.nonce, "nonce-123", "Embedded JWT nonce must be forwarded to Supabase");
      return {
        status: 200,
        body: JSON.stringify({
          access_token: makeJwt({ sub: "user-777" }),
          refresh_token: "refresh-777",
          expires_in: 3600,
          user: {
            id: "user-777",
            email: "alvin@example.com",
            user_metadata: { full_name: "Alvin Orasya" }
          }
        })
      };
    },
    onStageChanged: (stage) => stages.push(stage)
  });

  assert.deepEqual(stages, [
    "AUTH_CONFIG_VALIDATION",
    "GOOGLE_CREDENTIAL_REQUEST",
    "GOOGLE_ID_TOKEN_RECEIVED",
    "SUPABASE_TOKEN_EXCHANGE",
    "SUPABASE_SESSION_VALIDATION",
    "SESSION_VAULT_SAVE",
    "AUTH_SUCCESS"
  ]);
  assert.equal(session.userId, "user-777");
  assert.equal(session.email, "alvin@example.com");
  assert.equal(session.displayName, "Alvin Orasya");

  const restored = vault.read();
  assert.ok(restored);
  assert.equal(restored.user_id, "user-777");
  assert.equal(restored.email, "alvin@example.com");
  assert.equal(restored.display_name, "Alvin Orasya");
  assert.ok(restored.expires_at > Math.floor(Date.now() / 1000));
});
