import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { isInside, safeTarget } from "../src/agent.js";

test("approved-root containment rejects parent traversal", () => {
  const root = path.resolve(os.tmpdir(), "jarvis-approved-root");
  assert.equal(isInside(root, path.join(root, "notes", "file.txt")), true);
  assert.equal(isInside(root, path.resolve(root, "..", "secret.txt")), false);
});

test("safeTarget accepts a relative file path inside a canonical root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await fs.writeFile(path.join(root, "note.txt"), "approved");
    assert.equal(path.basename(await safeTarget(root, "note.txt")), "note.txt");
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("safeTarget rejects traversal and absolute paths", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  try {
    await assert.rejects(() => safeTarget(root, "../outside.txt"), /escapes/);
    await assert.rejects(() => safeTarget(root, path.resolve(root, "..", "outside.txt")), /relative path/);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});


test("safeTarget rejects a symlink that resolves outside the approved root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-root-"));
  const outside = await fs.mkdtemp(path.join(os.tmpdir(), "jarvis-outside-"));
  try {
    const secret = path.join(outside, "secret.txt");
    await fs.writeFile(secret, "outside");
    await fs.symlink(secret, path.join(root, "linked-secret.txt"), "file");
    await assert.rejects(() => safeTarget(root, "linked-secret.txt"), /escapes/);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
    await fs.rm(outside, { recursive: true, force: true });
  }
});

test("desktop result cache keeps the original TTL and rejects expired entries", async () => {
  const { buildResultCachePayload, isResultCacheFresh } = await import("../src/agent.js");
  const now = 2_000_000;
  const results = new Map([["fresh", { ok: true }], ["expired", { ok: true }]]);
  const savedAt = new Map([["fresh", now - 30_000], ["expired", now - 16 * 60_000]]);
  const payload = buildResultCachePayload(results, savedAt, now);
  assert.deepEqual(Object.keys(payload), ["fresh"]);
  assert.equal(payload.fresh.savedAt, now - 30_000);
  assert.equal(isResultCacheFresh(now + 1, now), false);
});

test("isCommandExpired rejects missing, malformed, expired, and future-dated command timestamps", async () => {
  const { isCommandExpired, extractPairingCode } = await import("../src/agent.js");
  const now = Date.parse("2026-11-05T10:00:00.000Z");
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T09:58:00.000Z" }, now), false);
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T09:54:00.000Z" }, now), true);
  assert.equal(isCommandExpired({ createdAt: "2026-11-05T10:05:00.000Z" }, now), true);
  assert.equal(isCommandExpired({ createdAt: "invalid" }, now), true);
  assert.equal(isCommandExpired({}, now), true);
  assert.equal(extractPairingCode("jarvis-pair://v1?code=xyzw6789"), "XYZW6789");
  assert.equal(extractPairingCode("xyzw 6789"), "XYZW6789");
});