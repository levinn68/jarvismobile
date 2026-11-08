import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("batch8-6: final system validation - core modules exist", () => {
  const required = [
    "../src/index.js",
    "../src/adaptive-personality.js",
    "../src/security/request-guard.js",
    "../src/security/ssrf-guard.js",
    "../src/skills/marketplace.js",
    "../src/mcp/mcp-manager.js"
  ];
  for (const rel of required) {
    const full = path.join(__dirname, rel);
    assert.ok(fs.existsSync(full), `missing ${rel}`);
  }
});

test("batch8-6: no obvious TODO/FIXME left in critical paths", () => {
  const critical = [
    path.join(__dirname, "../src/security/request-guard.js"),
    path.join(__dirname, "../src/security/ssrf-guard.js")
  ];
  for (const f of critical) {
    const txt = fs.readFileSync(f, "utf8");
    assert.ok(!/TODO\(CRITICAL\)|FIXME\(CRITICAL\)/i.test(txt), `critical marker in ${f}`);
  }
});
