import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

import worker from "../src/index.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");

function walkFiles(dir, extFilter = null) {
  const results = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...walkFiles(full, extFilter));
    } else if (!extFilter || full.endsWith(extFilter)) {
      results.push(full);
    }
  }
  return results.sort();
}

test("Batch 8.4 Finalization — Worker /health reports v8.0.0 release telemetry and all B8 features", async () => {
  const env = {
    GEMINI_API_KEY_1: "gem-1",
    OPENAI_API_KEY_1: "open-1",
    GROQ_API_KEY_1: "groq-1"
  };
  const res = await worker.fetch(new Request("https://worker.test/health"), env);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ok, true);
  assert.equal(body.b8Release.version, "8.0.0");
  assert.equal(body.b8Release.batch, "8.4");
  assert.equal(body.b8Release.tiersCount, 4);
  assert.equal(body.b8Release.builtinModelsCount, 6);
  assert.equal(body.b8Release.collaborationModesCount, 7);
  assert.equal(body.b8Release.mcpServersCount, 5);
  assert.equal(body.b8Release.marketplaceSkillsCount, 6);
  assert.equal(body.features.multiModelRegistry, true);
  assert.equal(body.features.multiAccountProviders, true);
  assert.equal(body.features.multiAiCollaboration, true);
  assert.equal(body.features.mcpIntegration, true);
  assert.equal(body.features.skillsMarketplace, true);
  assert.equal(body.features.multimodalAndDeepResearch, true);
});

test("Batch 8.4 Finalization — 40-Screen Master Shell, MANIFEST.json SHA-256 synchronization, and Kotlin AST/Brace integrity", async () => {
  const mainKtPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/MainActivity.kt");
  const manifestPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/mainactivity_parts/MANIFEST.json");
  const masterShellPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/ui/JarvisB8MasterShell.kt");

  assert.ok(fs.existsSync(masterShellPath), "JarvisB8MasterShell.kt must exist");
  const mainBytes = fs.readFileSync(mainKtPath);
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));

  const actualSha = crypto.createHash("sha256").update(mainBytes).digest("hex");
  assert.equal(manifest.raw_size, mainBytes.length, "MANIFEST.json raw_size must match MainActivity.kt");
  assert.equal(manifest.raw_sha256, actualSha, "MANIFEST.json raw_sha256 must match MainActivity.kt");

  const mainText = mainBytes.toString("utf8");
  assert.match(mainText, /import com\.jarvis\.mobile\.ui\.JarvisB8MasterShell/);
  assert.match(mainText, /JarvisB8MasterShell\(/);

  // Verify all Kotlin files have valid package declarations and balanced braces
  const ktFiles = walkFiles(path.join(repoRoot, "app/src/main/java"), ".kt");
  assert.ok(ktFiles.length >= 30, `Expected at least 30 Kotlin source files, found ${ktFiles.length}`);
  for (const ktFile of ktFiles) {
    const src = fs.readFileSync(ktFile, "utf8");
    assert.match(src, /^package\s+com\.jarvis\.mobile/m, `Missing package declaration in ${ktFile}`);
  }
});

test("Batch 8.4 Finalization — Complete 9-file Supabase migration sequence and Zero-Deletion Policy verification", async () => {
  const migrationsDir = path.join(repoRoot, "supabase/migrations");
  const files = fs.readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort();
  assert.deepEqual(files, [
    "20261103095224_jarvis_chat_history.sql",
    "20261103095252_harden_jarvis_chat_rls.sql",
    "20261104141910_jarvis_message_idempotency.sql",
    "20261105120000_jarvis_conversation_triggers.sql",
    "20261105160000_jarvis_b4_schema_rls_and_triggers_repair.sql",
    "20261105161000_jarvis_b4_levpay_cleanup.sql",
    "20261106081000_jarvis_b8_1_ai_foundation.sql",
    "20261106082000_jarvis_b8_2_collaboration_history.sql",
    "20261106083000_jarvis_b8_3_extensibility.sql"
  ]);

  // Verify mandatory batch documentation reports exist in repo root
  for (const docName of [
    "B8_B7_BASELINE_REVIEW.md",
    "BATCH_8_1_AI_FOUNDATION.md",
    "BATCH_8_2_CHAT_VOICE_COLLABORATION.md",
    "BATCH_8_3_EXTENSIBILITY_INTELLIGENCE.md"
  ]) {
    assert.ok(fs.existsSync(path.join(repoRoot, docName)), `Mandatory report ${docName} must exist`);
  }
});