import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import workerApp from "../src/index.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("batch8-1: worker exports default fetch handler", () => {
  assert.equal(typeof workerApp.fetch, "function");
});

test("batch8-1: AI foundation migration strings present", () => {
  const src = fs.readFileSync(path.join(__dirname, "../src/ai/multi-account-manager.js"), "utf8");
  assert.match(src, /create table if not exists public\.jarvis_ai_accounts/i);
  assert.match(src, /create table if not exists public\.jarvis_model_preferences/i);
  assert.match(src, /force row level security/i);
});
