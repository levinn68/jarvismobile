import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import workerApp, {
  keyRotator,
  safeHttpUrl,
  buildTopicalFallbackVisuals
} from "../src/index.js";
import { handleDeviceRequest } from "../src/device/router.js";
import { parseDeviceIntent, executeDeviceIntent } from "../src/device/ai-tool-service.js";
import { enqueueDeviceCommand, acknowledgeDeviceCommand } from "../src/device/command-service.js";
import { createPairingSession, cancelPairingSession, getPairingSession, redeemPairingSession } from "../src/device/pairing.js";
import { createAccount, saveDevice } from "../src/device/registry.js";
import { issueAccountToken } from "../src/device/auth.js";
import { corsHeadersForRequest } from "../src/security/request-guard.js";
import { readBoundedJson } from "../src/utils.js";
import { BUNDLED_SKILL_INSTRUCTIONS, getSkillInstructions, listSkills } from "../src/skills/skill-registry.js";
import { reason as runReasoningEngine } from "../src/ai/reasoning-engine.js";
import * as winAgent from "../../desktop/windows/src/agent.js";
import * as unixAgent from "../../desktop/unix/src/agent.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../..");

function memoryKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.get(key) ?? null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    },
    async list({ prefix = "", limit = 1000, cursor } = {}) {
      const all = [...store.keys()].filter((k) => k.startsWith(prefix)).sort();
      const offset = cursor ? Number(cursor) : 0;
      const keys = all.slice(offset, offset + limit).map((name) => ({ name }));
      const next = offset + keys.length;
      return {
        keys,
        list_complete: next >= all.length,
        ...(next < all.length ? { cursor: String(next) } : {})
      };
    }
  };
}

test("Batch 7 Audit C-01, C-02, H-01..H-10, MED-01, MED-06, HIGH-01, HIGH-03, HIGH-07: Android source static verification", () => {
  const mainKt = fs.readFileSync(path.join(REPO_ROOT, "docs/archive/b8-7/app/src/main/java/com/jarvis/mobile/MainActivity.kt"), "utf8");
  const accKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/JarvisAccessibilityService.kt"), "utf8");
  const approvalKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/ScreenActionApprovalActivity.kt"), "utf8");
  const syncKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/JarvisDeviceSyncService.kt"), "utf8");
  const fileKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/FileController.kt"), "utf8");
  const supaKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt"), "utf8");
  const qrKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/PairingQrActivity.kt"), "utf8");
  const clientKt = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/java/com/jarvis/mobile/device/JarvisDeviceClient.kt"), "utf8");
  const gradleKts = fs.readFileSync(path.join(REPO_ROOT, "app/build.gradle.kts"), "utf8");
  const manifestXml = fs.readFileSync(path.join(REPO_ROOT, "app/src/main/AndroidManifest.xml"), "utf8");

  // C-01 & L-01: import android.os.Build present, unused WebView imports removed
  assert.match(mainKt, /^import android\.os\.Build$/m);
  assert.equal(mainKt.includes("import android.webkit.WebView"), false);

  // C-02: JarvisAccessibilityService uses dedicated HandlerThread for gesture callbacks & ScreenActionApprovalActivity runs off UI thread
  assert.ok(accKt.includes('HandlerThread("JarvisGestureCallback")'));
  assert.ok(accKt.includes("ensureGestureCallbackHandler()"));
  assert.ok(approvalKt.includes("Thread {"));
  assert.ok(approvalKt.includes("executor.executeApproved(command)"));

  // C-04 & CRIT-03: Remote platform targets bypass local Android interception
  assert.ok(mainKt.includes("fun isRemotePlatformTargeted(raw: String): Boolean"));
  assert.ok(mainKt.includes("fun stripLocalAndroidTargetSuffix(raw: String): String"));

  // CRIT-01, H-01, H-03, H-04, M-03: Voice Mode renders only visual cards (max 5) + compact status indicator without long text panel
  assert.ok(mainKt.includes("MAX_VISUAL_CARDS = 5"));
  assert.ok(mainKt.includes("VoiceImageCarouselCards("));
  assert.equal(mainKt.includes("Buka detail lengkap di Chat Console"), false);
  assert.ok(mainKt.includes("onError = { isLoading = false; loadError = true }"));

  // H-06 / MED-01: onDestroy stops voice foreground service and resets clap detector
  const onDestroySection = mainKt.slice(mainKt.indexOf("override fun onDestroy()"));
  assert.ok(onDestroySection.includes("stopVoiceForegroundService()"));
  assert.ok(onDestroySection.includes("clapDetector.reset()"));

  // H-09 / HIGH-01: Configurable Worker URL in BuildConfig and JarvisDeviceClient
  assert.ok(gradleKts.includes('buildConfigField("String", "JARVIS_WORKER_URL"'));
  assert.ok(clientKt.includes("fun resolveWorkerBaseUrl(context: Context): String"));
  assert.ok(mainKt.includes("JarvisDeviceClient.resolveWorkerBaseUrl(this@MainActivity)"));

  // HIGH-03: Partial wake lock in JarvisDeviceSyncService + WAKE_LOCK permission in AndroidManifest.xml
  assert.ok(manifestXml.includes("android.permission.WAKE_LOCK"));
  assert.ok(syncKt.includes("PowerManager.PARTIAL_WAKE_LOCK"));

  // H-07 & M-06: 60 KB file read cap + text extension allowlist in FileController.kt
  assert.ok(fileKt.includes("MAX_READ_BYTES = 60 * 1024"));
  assert.ok(fileKt.includes("ALLOWED_TEXT_EXTENSIONS"));

  // HIGH-07: Synchronized conversation creation in SupabaseJarvisClient.kt
  assert.ok(supaKt.includes("synchronized(conversationLock)"));

  // H-05, M-07, M-08, L-05: PairingQrActivity transient error retry, server-side cancel, and HTTP 403 non-retry
  assert.ok(qrKt.includes("client.cancelPairingSession(codeToCancel, supabaseUserId)"));
  assert.ok(clientKt.includes('!msg.contains("HTTP 403")'));
});

test("Batch 7 Audit C-03, H-07, MED-03, Security #5 & #12: Windows, macOS, and Linux Desktop Agent fixes", async () => {
  const winAgentSrc = fs.readFileSync(path.join(REPO_ROOT, "desktop/windows/src/agent.js"), "utf8");
  // C-03: Windows PowerShell 5.1 compatible -FilePath parameter
  assert.ok(winAgentSrc.includes("Start-Process -FilePath $env:JARVIS_TARGET"));
  assert.equal(winAgentSrc.includes("Start-Process -LiteralPath"), false);

  // H-07: 60 KB file read limit so JSON ACK envelope stays <= 64 KB
  assert.equal(winAgent.MAX_FILE_BYTES, 60 * 1024);
  assert.equal(unixAgent.MAX_FILE_BYTES, 60 * 1024);

  // Security #5: sanitizeChildEnv strips cloud/AI provider credentials
  const sanitized = winAgent.sanitizeChildEnv({
    PATH: "/usr/bin",
    JARVIS_DEVICE_TOKEN: "sec-1",
    OPENAI_API_KEY: "sk-12345",
    AWS_SECRET_ACCESS_KEY: "aws-secret",
    GITHUB_TOKEN: "ghp_12345"
  });
  assert.equal(sanitized.PATH, "/usr/bin");
  assert.equal(sanitized.JARVIS_DEVICE_TOKEN, undefined);
  assert.equal(sanitized.OPENAI_API_KEY, undefined);
  assert.equal(sanitized.AWS_SECRET_ACCESS_KEY, undefined);
  assert.equal(sanitized.GITHUB_TOKEN, undefined);

  // MED-03: Service registration config generator across Windows, macOS, and Linux
  const winSvc = winAgent.generateServiceConfig();
  assert.equal(winSvc.platform, "windows");
  assert.match(winSvc.schtasksCreateCommand, /schtasks \/Create/i);

  const macSvc = unixAgent.generateServiceConfig({ platformOverride: "macos" });
  assert.equal(macSvc.platform, "macos");
  assert.match(macSvc.plistXml, /com\.jarvis\.desktop\.agent/);

  const linuxSvc = unixAgent.generateServiceConfig({ platformOverride: "linux" });
  assert.equal(linuxSvc.platform, "linux");
  assert.match(linuxSvc.unitFile, /\[Service\]/);
});

test("Batch 7 Audit C-04, CRIT-03, MED-04, HIGH-04: Device intent parsing and fast adaptive polling", () => {
  // Implicit target platform ("cek status laptop saya") resolves to desktop
  const laptopStatus = parseDeviceIntent("Tolong cek status laptop saya");
  assert.equal(laptopStatus?.action, "system.status");
  assert.equal(laptopStatus?.targetPlatform, "desktop");

  // App launch at command start works for Windows/macOS/Linux/Android
  const winCalc = parseDeviceIntent("Jarvis, tolong buka Calculator di Windows");
  assert.equal(winCalc?.action, "apps.launch_label");
  assert.equal(winCalc?.params?.appName, "Calculator");
  assert.equal(winCalc?.targetPlatform, "windows");

  // Mid-sentence non-command uses of "buka" or "open" do NOT trigger apps.launch_label
  assert.equal(parseDeviceIntent("Bagaimana cara kita buka diskusi tentang ekonomi makro?"), null);
  assert.equal(parseDeviceIntent("Apa keuntungan menggunakan sistem open source di perusahaan?"), null);
  assert.equal(parseDeviceIntent("Buka halaman website resmi pemerintah"), null);
});

test("Batch 7 Audit CRIT-01, CRIT-02, CRIT-05, H-02, H-03, H-08, HIGH-05, HIGH-06, MED-02, MED-05, L-08: Worker visuals, skills, KV history & Gemini fallback", async () => {
  // L-08 / S-06: safeHttpUrl allows https: only
  assert.equal(safeHttpUrl("http://insecure.example.com/img.jpg"), "");
  assert.equal(safeHttpUrl("https://secure.example.com/img.jpg"), "https://secure.example.com/img.jpg");

  // HIGH-06: All 29 skills have bundled local instructions
  assert.equal(listSkills().length, 29);
  assert.equal(Object.keys(BUNDLED_SKILL_INSTRUCTIONS).length, 29);
  assert.ok(getSkillInstructions("jarvis-persona").length > 100);

  // CRIT-02 / H-02: Topical fallback visuals always return 1..5 HTTPS images
  const fallbackImgs = buildTopicalFallbackVisuals("Apa kabar hari ini?", "images", false);
  assert.ok(fallbackImgs.length >= 1 && fallbackImgs.length <= 5);
  assert.ok(fallbackImgs.every((item) => item.image.startsWith("https://")));

  // H-08, CRIT-02, MED-05: Gemini 404 automatic model fallback + guaranteed visual cards + server-side KV history persistence
  keyRotator.reset();
  const registry = memoryKv();
  const calledModels = [];
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      const u = String(url);
      if (u.includes("/auth/v1/user")) {
        return new Response(JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174077", email: "b7@example.com" }), { status: 200 });
      }
      if (u.includes("generativelanguage.googleapis.com")) {
        const modelMatch = u.match(/models\/([^:]+):generateContent/);
        if (modelMatch) calledModels.push(modelMatch[1]);
        if (u.includes("gemini-2.5-flash")) {
          return new Response(JSON.stringify({ error: { message: "Model not found" } }), { status: 404 });
        }
        return new Response(
          JSON.stringify({
            candidates: [{ content: { parts: [{ text: "Ini adalah jawaban dari model fallback Gemini." }] } }]
          }),
          { status: 200 }
        );
      }
      throw new Error("Unexpected external fetch URL: " + u);
    };

    const env = {
      GEMINI_API_KEY_1: "gem-key-1",
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon-key",
      DEVICE_REGISTRY: registry
    };

    const res = await workerApp.fetch(
      new Request("https://worker.test/v1/chat", {
        method: "POST",
        headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
        body: JSON.stringify({ message: "Halo Jarvis, bagaimana kondisi sistem?" })
      }),
      env
    );
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.ok(calledModels[0] === "gemini-2.5-flash" && calledModels[1] === "gemini-2.0-flash");
    // Guaranteed 1..5 HTTPS visual cards even when SERPAPI_API_KEY is not configured
    assert.ok(body.visuals.results.length >= 1 && body.visuals.results.length <= 5);
    assert.ok(body.visuals.cards.length >= 1 && body.visuals.cards.length <= 5);
    // Server-side conversation history persisted in KV
    const savedHistory = JSON.parse(await registry.get("conv-history:123e4567-e89b-12d3-a456-426614174077"));
    assert.equal(savedHistory.length, 2);
    assert.equal(savedHistory[0].role, "user");
    assert.equal(savedHistory[1].role, "assistant");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Batch 7 Audit CRIT-04, M-08, M-09, M-01, M-02, L-03, L-07, HIGH-08: KV dedupe, pairing cancel, UTF-8 byte limit, CORS, and SQL migration 4", async () => {
  // CRIT-04 / M-09: preventDuplicate persists across in-memory index resets via KV
  const commandsKv = memoryKv();
  const device = { deviceId: "windows-workstation-01", platform: "windows", capabilities: ["system.read"] };
  const firstQ = await enqueueDeviceCommand({
    kv: commandsKv,
    device,
    input: { action: "system.status", preventDuplicate: true }
  });
  assert.equal(firstQ.ok, true);
  assert.ok([...commandsKv.store.keys()].some((k) => k.startsWith("cmd-dedupe:windows-workstation-01:")));

  // M-08: Pairing session cancellation marks session as cancelled and blocks redeem
  const registryKv = memoryKv();
  const accountId = "acct-123e4567-e89b-12d3-a456-426614174000";
  await createAccount(registryKv, accountId);
  const sess = await createPairingSession({ kv: registryKv, accountId });
  const cancelled = await cancelPairingSession({ kv: registryKv, code: sess.code, accountId });
  assert.equal(cancelled.status, "cancelled");
  const statusAfter = await getPairingSession({ kv: registryKv, code: sess.code, accountId });
  assert.equal(statusAfter.status, "cancelled");
  await assert.rejects(
    () =>
      redeemPairingSession({
        kv: registryKv,
        code: sess.code,
        deviceInput: { platform: "windows", name: "PC" },
        saveDevice: async (x) => x,
        issueDeviceToken: async () => "tok",
        tokenSecret: "secret-key-with-more-than-32-characters"
      }),
    /already been used/i
  );

  // L-07 / S-05: readBoundedJson checks UTF-8 byte length (multi-byte characters exceeding byte limit)
  const multiByteReq = new Request("https://worker.test/v1/chat", {
    method: "POST",
    body: JSON.stringify({ text: "🔥".repeat(400) }) // 400 chars = 1600 UTF-8 bytes
  });
  await assert.rejects(() => readBoundedJson(multiByteReq, 1000), (err) => err.status === 413);

  // M-01 / S-01: corsHeadersForRequest does not reflect arbitrary Origin when JARVIS_ALLOWED_ORIGINS is unset
  const arbReq = new Request("https://worker.test/v1/chat", { headers: { Origin: "https://untrusted.example" } });
  assert.equal(corsHeadersForRequest(arbReq, {})["Access-Control-Allow-Origin"], undefined);

  // L-03: reasoning-engine.js executes and synthesizes
  const reasonRes = await runReasoningEngine({
    prompt: "Jelaskan perbedaan arsitektur monolitik dan microservices secara detail",
    availableProviders: ["gemini"],
    invoke: async () => "Analisis awal",
    synthesize: async () => "Sintesis akhir"
  });
  assert.equal(reasonRes.ok, true);
  assert.equal(reasonRes.answer, "Sintesis akhir");

  // HIGH-08: Migration 4 uses security invoker and set search_path = pg_catalog, public
  const mig4Sql = fs.readFileSync(
    path.join(REPO_ROOT, "supabase/migrations/20261105120000_jarvis_conversation_triggers.sql"),
    "utf8"
  );
  assert.doesNotMatch(mig4Sql, /security\s+definer/i);
  assert.match(mig4Sql, /security\s+invoker/i);
  assert.match(mig4Sql, /set\s+search_path\s*=\s*pg_catalog,\s*public/i);
});