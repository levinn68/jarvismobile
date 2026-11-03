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
