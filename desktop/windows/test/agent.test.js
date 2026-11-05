import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { isInside, safeTarget, normalizedForComparison } from "../src/agent.js";

test("approved-root containment rejects parent traversal", () => {
  const root = path.resolve("C:\\JARVIS\\Allowed");
  assert.equal(isInside(root, path.join(root, "notes", "file.txt")), true);
  assert.equal(isInside(root, path.resolve(root, "..", "secret.txt")), false);
});

test("safeTarget accepts a relative file path within the selected root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await fs.writeFile(path.join(root, "note.txt"), "approved");
    const canonicalRoot = await fs.realpath(root);
    const real = await fs.realpath(path.join(root, "note.txt"));
    assert.equal(isInside(canonicalRoot, real), true, JSON.stringify({
      canonicalRoot, real, normalizedRoot: normalizedForComparison(canonicalRoot),
      normalizedReal: normalizedForComparison(real)
    }));
    assert.equal(path.basename(await safeTarget(root, "note.txt")), "note.txt");
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("safeTarget rejects traversal and absolute paths", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    // On Windows hosts backslash traversal is blocked by the escapes check.
    // On non-Windows CI the same string may fail earlier at realpath (ENOENT).
    await assert.rejects(
      () => safeTarget(root, "..\\outside.txt"),
      (err) => /escapes|ENOENT|no such file/i.test(String(err.message || err))
    );
    await assert.rejects(() => safeTarget(root, path.resolve(root, "..", "outside.txt")), /relative path/);
    await assert.rejects(() => safeTarget(root, "../outside.txt"), /escapes/);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});


test("result cache preserves original timestamps instead of extending TTL on every write", async () => {
  const { buildResultCachePayload, isResultCacheFresh } = await import("../src/agent.js");
  const now = 1_000_000;
  const results = new Map([
    ["fresh", { ok: true }],
    ["expired", { ok: true }]
  ]);
  const savedAt = new Map([
    ["fresh", now - 60_000],
    ["expired", now - 16 * 60_000]
  ]);
  const payload = buildResultCachePayload(results, savedAt, now);
  assert.deepEqual(Object.keys(payload), ["fresh"]);
  assert.equal(payload.fresh.savedAt, now - 60_000);
  assert.deepEqual(payload.fresh.result, { ok: true });
  assert.equal(isResultCacheFresh(now - 60_000, now), true);
  assert.equal(isResultCacheFresh(now - 16 * 60_000, now), false);
  assert.equal(isResultCacheFresh(now + 1, now), false);
});
