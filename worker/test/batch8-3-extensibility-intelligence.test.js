import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  BUILTIN_MCP_SERVERS,
  buildJsonRpcRequest,
  listMcpServers,
  saveMcpServer,
  executeMcpTool
} from "../src/mcp/mcp-manager.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test("batch8-3: MCP manager exposes builtin servers", () => {
  const servers = listMcpServers();
  assert.ok(Array.isArray(servers));
  assert.ok(servers.length > 0);
});

test("batch8-3: buildJsonRpcRequest shapes correctly", () => {
  const req = buildJsonRpcRequest("tools/list", { }, "1");
  assert.equal(req.jsonrpc, "2.0");
  assert.equal(req.method, "tools/list");
  assert.equal(req.id, "1");
});

test("batch8-3: SQL migration strings contain required tables", () => {
  // Lightweight static check against known migration fragments used by the manager
  const sqlCode = fs.readFileSync(path.join(__dirname, "../src/mcp/mcp-manager.js"), "utf8");
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_mcp_servers/);
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_installed_skills/);
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_generated_artifacts/);
  assert.match(sqlCode, /FORCE ROW LEVEL SECURITY/);
});
