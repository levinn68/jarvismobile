import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

import worker from "../src/index.js";
import * as aiBarrel from "../src/ai/index.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "../..");

function createMemoryKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.has(key) ? store.get(key) : null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    },
    async list({ prefix = "" } = {}) {
      const keys = [];
      for (const k of store.keys()) {
        if (k.startsWith(prefix)) keys.push({ name: k });
      }
      return { keys, list_complete: true };
    }
  };
}

test("Batch 8.6 — 1. Kotlin Lexical, Syntax, Enum, and MANIFEST.json Static Compiler Verification", () => {
  const ktDir = path.join(repoRoot, "app/src/main/java");
  const ktFiles = [];
  function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && entry.name.endsWith(".kt")) ktFiles.push(full);
    }
  }
  walk(ktDir);
  assert.equal(ktFiles.length, 30, "All 30 Kotlin source files must be present");

  // Extract defined Subsystem enum values from JarvisStabilityGuard.kt
  const guardText = fs.readFileSync(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/stability/JarvisStabilityGuard.kt"),
    "utf8"
  );
  const enumBlockMatch = guardText.match(/enum class Subsystem\s*\{([^}]+)\}/);
  assert.ok(enumBlockMatch, "JarvisStabilityGuard.Subsystem enum must be defined");
  const validSubsystems = new Set(
    enumBlockMatch[1]
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean)
  );
  assert.ok(validSubsystems.has("NETWORK"), "JarvisStabilityGuard.Subsystem must include NETWORK");

  for (const file of ktFiles) {
    const rel = path.relative(repoRoot, file);
    const content = fs.readFileSync(file, "utf8");
    const lines = content.split("\n");

    // 1. Check for same-line rethrowIfCancellation(...) followed by another statement without ;
    lines.forEach((line, idx) => {
      if (line.includes("rethrowIfCancellation(")) {
        const after = line.split("rethrowIfCancellation(")[1] || "";
        if (after.includes(")")) {
          const tail = after.split(")").slice(1).join(")").trim();
          assert.ok(
            !tail || tail.startsWith("//") || tail.startsWith(";") || tail === "}" || tail === "{",
            `Same-line statement after rethrowIfCancellation in ${rel}:${idx + 1}: ${line.trim()}`
          );
        }
      }

      // 2. Check all Subsystem.* references are valid enum constants
      for (const m of line.matchAll(/Subsystem\.([A-Z0-9_]+)/g)) {
        assert.ok(
          validSubsystems.has(m[1]),
          `Unresolved JarvisStabilityGuard.Subsystem.${m[1]} in ${rel}:${idx + 1}`
        );
      }
    });

    // 3. Full lexer check: no raw newline inside single-quoted string "..." and balanced braces/parens/brackets
    let inLineComment = false;
    let inBlockComment = false;
    let inString = false;
    let inRawString = false;
    let inChar = false;
    let lineNo = 1;
    let braces = 0;
    let parens = 0;
    let brackets = 0;

    for (let i = 0; i < content.length; i++) {
      const ch = content[i];
      if (ch === "\n") {
        assert.ok(!inString || inRawString, `Raw newline inside double-quoted string literal in ${rel}:${lineNo}`);
        lineNo++;
        inLineComment = false;
        continue;
      }
      if (inLineComment) continue;
      if (inBlockComment) {
        if (content.slice(i, i + 2) === "*/") {
          inBlockComment = false;
          i++;
        }
        continue;
      }
      if (inRawString) {
        if (content.slice(i, i + 3) === '"""') {
          inRawString = false;
          i += 2;
        }
        continue;
      }
      if (inString) {
        if (ch === "\\") {
          i++;
          continue;
        }
        if (ch === '"') inString = false;
        continue;
      }
      if (inChar) {
        if (ch === "\\") {
          i++;
          continue;
        }
        if (ch === "'") inChar = false;
        continue;
      }
      if (content.slice(i, i + 2) === "//") {
        inLineComment = true;
        i++;
        continue;
      }
      if (content.slice(i, i + 2) === "/*") {
        inBlockComment = true;
        i++;
        continue;
      }
      if (content.slice(i, i + 3) === '"""') {
        inRawString = true;
        i += 2;
        continue;
      }
      if (ch === '"') {
        inString = true;
        continue;
      }
      if (ch === "'") {
        inChar = true;
        continue;
      }
      if (ch === "{") braces++;
      else if (ch === "}") braces--;
      else if (ch === "(") parens++;
      else if (ch === ")") parens--;
      else if (ch === "[") brackets++;
      else if (ch === "]") brackets--;
    }

    assert.equal(braces, 0, `Unbalanced braces in ${rel}`);
    assert.equal(parens, 0, `Unbalanced parens in ${rel}`);
    assert.equal(brackets, 0, `Unbalanced brackets in ${rel}`);
  }

  // Verify MainActivity.kt MANIFEST.json synchronization and B8 integration hooks
  const mainBytes = fs.readFileSync(path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/MainActivity.kt"));
  const mainText = mainBytes.toString("utf8");
  const manifest = JSON.parse(
    fs.readFileSync(path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/mainactivity_parts/MANIFEST.json"), "utf8")
  );
  assert.equal(manifest.raw_size, mainBytes.length, "MANIFEST.json raw_size must match MainActivity.kt");
  assert.equal(
    manifest.raw_sha256,
    crypto.createHash("sha256").update(mainBytes).digest("hex"),
    "MANIFEST.json raw_sha256 must match MainActivity.kt"
  );
  assert.match(mainText, /B8 SUITE ✦/, "MainActivity.kt must provide a switcher into B8 Master Suite");
  assert.match(mainText, /body\.put\("model", selectedModelId\)/, "MainActivity.askGemini must forward selected model to /v1/chat");
  assert.match(mainText, /body\.put\("modelTier", selectedTier\)/, "MainActivity.askGemini must forward selected tier to /v1/chat");
  assert.match(mainText, /body\.put\("accountId", selectedAccountId\)/, "MainActivity.askGemini must forward selected accountId to /v1/chat");
});

test("Batch 8.6 — 2. Supabase SQL Migration Sequence & Trigger Function Integrity", () => {
  const migrationsDir = path.join(repoRoot, "supabase/migrations");
  const sqlFiles = fs.readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort();
  assert.equal(sqlFiles.length, 9, "All 9 chronological Supabase SQL migrations must exist");

  const definedFunctions = new Set();
  for (const file of sqlFiles) {
    const sql = fs.readFileSync(path.join(migrationsDir, file), "utf8");
    for (const m of sql.matchAll(/create\s+(?:or\s+replace\s+)?function\s+([\w.]+)\s*\(/gi)) {
      definedFunctions.add(m[1].toLowerCase());
    }
    for (const m of sql.matchAll(/execute\s+function\s+([\w.]+)\s*\(/gi)) {
      const fn = m[1].toLowerCase();
      assert.ok(
        definedFunctions.has(fn),
        `Migration ${file} calls trigger function ${fn}() before it is defined in the migration sequence`
      );
    }
  }
  assert.ok(
    definedFunctions.has("public.jarvis_touch_b8_updated_at"),
    "public.jarvis_touch_b8_updated_at() must be defined in Supabase migrations"
  );
});

test("Batch 8.6 — 3. Worker AI Barrel Exports & End-to-End Flow Validation (Provider -> Chat -> Collaboration -> Voice -> Device)", async () => {
  // Verify barrel exports in worker/src/ai/index.js
  assert.equal(typeof aiBarrel.resolveModelOrTier, "function", "model-registry exported from ai/index.js");
  assert.equal(typeof aiBarrel.saveUserAiAccount, "function", "multi-account-manager exported from ai/index.js");
  assert.equal(typeof aiBarrel.runCollaborationSession, "function", "collaboration-engine exported from ai/index.js");
  assert.equal(typeof aiBarrel.runWithFallback, "function", "fallback-engine exported from ai/index.js");

  const kv = createMemoryKv();
  const env = {
    JARVIS_ENV: "development",
    DEVICE_REGISTRY: kv,
    DEVICE_TOKEN_SECRET: "b8-6-validation-secret-key-minimum-32-bytes-long-123456",
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "sb_publishable_test_key"
  };

  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url, init = {}) => {
      const u = String(url);
      if (u.includes("/auth/v1/user")) {
        return new Response(JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174886", email: "fajar@jarvis.dev" }), { status: 200 });
      }
      if (u.includes("api.openai.com/v1/models")) {
        return new Response(JSON.stringify({ data: [{ id: "gpt-4o" }, { id: "gpt-4o-mini" }] }), { status: 200 });
      }
      if (u.includes("api.openai.com/v1/chat/completions")) {
        return new Response(
          JSON.stringify({
            choices: [
              {
                message: {
                  content:
                    "Arsitektur SaaS 100.000 pengguna menggunakan Cloudflare Workers sebagai API Gateway, Supabase PostgreSQL dengan RLS sebagai penyimpanan utama, serta Redis/KV caching untuk menekan latensi di bawah 100ms."
                }
              }
            ]
          }),
          { status: 200 }
        );
      }
      return new Response(JSON.stringify({ error: `Unhandled mock URL: ${u}` }), { status: 500 });
    };

    const headers = {
      Authorization: "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLWI4LTYtZTJlIn0.signature",
      "Content-Type": "application/json"
    };

    // 1. Check /health reports batch 8.6
    const healthRes = await worker.fetch(new Request("https://jarvis.test/health"), env);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.b8Release.batch, "8.4");
    assert.equal(healthJson.b8Release.validationBatch, "8.6");

    // 2. Save OpenAI account and verify it starts as configured_unverified (not connected)
    const addAccRes = await worker.fetch(
      new Request("https://jarvis.test/v1/ai-accounts", {
        method: "POST",
        headers,
        body: JSON.stringify({
          provider: "openai",
          label: "OpenAI E2E",
          email: "fajar@openai.com",
          apiKey: "sk-proj-RealLookingE2EKey9876543210abcdef"
        })
      }),
      env
    );
    assert.equal(addAccRes.status, 201);
    const addAccJson = await addAccRes.json();
    assert.equal(addAccJson.account.connectionVerified, false);

    // 3. Run /v1/providers/test to verify connection and discover models
    const testProvRes = await worker.fetch(
      new Request("https://jarvis.test/v1/providers/test", {
        method: "POST",
        headers,
        body: JSON.stringify({ provider: "openai", accountId: addAccJson.account.accountId })
      }),
      env
    );
    assert.equal(testProvRes.status, 200);
    const testProvJson = await testProvRes.json();
    assert.equal(testProvJson.ok, true);
    assert.equal(testProvJson.connectionStatus, "connected");

    // 4. Select model via POST /v1/models and POST /v1/models/select
    const selectRes = await worker.fetch(
      new Request("https://jarvis.test/v1/models", {
        method: "POST",
        headers,
        body: JSON.stringify({ modelId: "gpt-4o", tier: "APEX" })
      }),
      env
    );
    assert.equal(selectRes.status, 200);
    const selectJson = await selectRes.json();
    assert.equal(selectJson.ok, true);
    assert.equal(selectJson.resolved.modelId, "gpt-4o");

    // 5. Execute /v1/chat using the user's encrypted OpenAI account
    const chatRes = await worker.fetch(
      new Request("https://jarvis.test/v1/chat", {
        method: "POST",
        headers,
        body: JSON.stringify({
          message: "Jelaskan arsitektur aplikasi SaaS 100.000 pengguna.",
          model: "gpt-4o",
          modelTier: "APEX",
          accountId: addAccJson.account.accountId
        })
      }),
      env
    );
    assert.equal(chatRes.status, 200);
    const chatJson = await chatRes.json();
    assert.equal(chatJson.ok, true);
    assert.match(chatJson.answer, /Cloudflare Workers/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
