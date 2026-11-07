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
import {
  MARKETPLACE_SKILLS_CATALOG,
  validateSkillManifest,
  listMarketplaceCatalog,
  manageMarketplaceSkill,
  executeMarketplaceSkill
} from "../src/skills/marketplace.js";
import {
  analyzeMultimodalInput,
  runCreativeGeneration,
  runDeepResearch,
  generateStructuredArtifact
} from "../src/intelligence/multimodal-creative-research.js";
import worker from "../src/index.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");

function createMockKv() {
  const map = new Map();
  return {
    store: map,
    async get(key) {
      return map.has(key) ? map.get(key) : null;
    },
    async put(key, value) {
      map.set(key, String(value));
    },
    async delete(key) {
      map.delete(key);
    },
    async list({ prefix = "" } = {}) {
      const keys = [...map.keys()]
        .filter((k) => k.startsWith(prefix))
        .map((name) => ({ name }));
      return { keys, list_complete: true };
    }
  };
}

test("Batch 8.3 — MCP Integration: JSON-RPC 2.0 envelope, 5 built-in servers, custom registration, permission gate, and audit log", async () => {
  assert.equal(BUILTIN_MCP_SERVERS.length, 5);
  const rpc = buildJsonRpcRequest("tools/call", { name: "fs.read_file" }, 42);
  assert.equal(rpc.jsonrpc, "2.0");
  assert.equal(rpc.id, 42);

  const kv = createMockKv();
  const userId = "123e4567-e89b-12d3-a456-426614174000";

  const initialServers = await listMcpServers(kv, userId);
  assert.equal(initialServers.length, 5);
  assert.equal(initialServers.filter((s) => s.enabled).length, 4);

  // Write tool without userApproved -> requiresApproval: true
  const writeUnapproved = await executeMcpTool(kv, userId, {
    serverId: "mcp_filesystem",
    toolName: "fs.write_file",
    arguments: { path: "README.md", content: "updated" },
    userApproved: false
  });
  assert.equal(writeUnapproved.ok, false);
  assert.equal(writeUnapproved.requiresApproval, true);

  // Write tool with userApproved: true -> succeeds & writes audit log with redacted secrets
  const writeApproved = await executeMcpTool(kv, userId, {
    serverId: "mcp_filesystem",
    toolName: "fs.write_file",
    arguments: { path: "config.env", secret: "Bearer eyJhbGciOiJIUzI1NiJ9.secret" },
    userApproved: true
  });
  assert.equal(writeApproved.ok, true);
  assert.equal(writeApproved.jsonrpcResponse.jsonrpc, "2.0");
  assert.equal(writeApproved.execution.argumentsSummary.includes("eyJhbGciOiJIUzI1NiJ9"), false);

  // Add custom MCP server
  const customSrv = await saveMcpServer(kv, userId, {
    id: "mcp_docker",
    name: "Docker MCP",
    transport: "http_jsonrpc",
    endpoint: "https://mcp.docker.jarvis.io/mcp",
    enabled: true
  });
  assert.equal(customSrv.name, "Docker MCP");
  const afterAdd = await listMcpServers(kv, userId);
  assert.equal(afterAdd.length, 6);
});

test("Batch 8.3 — Skills Marketplace: catalog filtering, manifest validation, install/update/uninstall, and execution", async () => {
  assert.equal(MARKETPLACE_SKILLS_CATALOG.length, 6);
  const kv = createMockKv();
  const userId = "123e4567-e89b-12d3-a456-426614174000";

  const catalog = await listMarketplaceCatalog(kv, userId);
  assert.equal(catalog.marketplaceSkills.length, 6);
  assert.equal(catalog.bundledB7SkillsCount, 29);

  // Install Document Generator
  const installed = await manageMarketplaceSkill(kv, userId, {
    action: "install",
    skillId: "document-generator"
  });
  assert.equal(installed.installed, true);

  // Update Code Reviewer Pro to v2.2.0
  const updated = await manageMarketplaceSkill(kv, userId, {
    action: "update",
    skillId: "code-reviewer-pro",
    version: "2.2.0"
  });
  assert.equal(updated.version, "2.2.0");

  // Import custom skill manifest
  const imported = await manageMarketplaceSkill(kv, userId, {
    action: "import",
    manifest: {
      id: "kubernetes-auditor",
      name: "Kubernetes Cluster Auditor",
      version: "1.1.0",
      category: "Coding",
      permissions: ["Inspect K8s Manifests"],
      dependencies: ["YAML Parser"]
    }
  });
  assert.equal(imported.id, "kubernetes-auditor");

  const execRes = await executeMarketplaceSkill(kv, userId, {
    skillId: "code-reviewer-pro",
    input: "worker/src/index.js"
  });
  assert.equal(execRes.ok, true);
  assert.equal(execRes.version, "2.2.0");
});

test("Batch 8.3 — Multimodal Intelligence, Creative AI, Deep Research & Artifact Generation", async () => {
  const multimodal = analyzeMultimodalInput({
    fileName: "architecture_spec.pdf",
    mimeType: "application/pdf",
    content: "Scalable SaaS architecture with RLS and multi-account failover"
  });
  assert.equal(multimodal.ok, true);
  assert.equal(multimodal.modality, "pdf_document");

  const imgGen = runCreativeGeneration({ mediaType: "image", prompt: "Holographic JARVIS core" });
  assert.equal(imgGen.ok, true);
  assert.equal(imgGen.mediaType, "image");

  const vidGen = runCreativeGeneration({ mediaType: "video", prompt: "Orb transition sequence" });
  assert.equal(vidGen.ok, true);
  assert.equal(vidGen.storyboardScenes.length, 3);

  const research = runDeepResearch({ topic: "Zero-Downtime Database Migrations" });
  assert.equal(research.ok, true);
  assert.equal(research.researchPlan.length, 4);
  assert.equal(research.factCheckMatrix.length, 2);

  const pres = generateStructuredArtifact({ format: "presentation", title: "JARVIS B8 Architecture" });
  assert.equal(pres.ok, true);
  assert.equal(pres.slides.length, 3);

  const sheet = generateStructuredArtifact({ format: "spreadsheet", title: "JARVIS B8 Metrics" });
  assert.equal(sheet.ok, true);
  assert.equal(sheet.rows.length, 3);

  const chart = generateStructuredArtifact({ format: "chart", title: "Model Intelligence Comparison" });
  assert.equal(chart.ok, true);
  assert.equal(chart.datasets.length, 2);
});

test("Batch 8.3 — Worker HTTP Endpoints & Storyboard Screens 34-40 Static Verification", async () => {
  const kv = createMockKv();
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    if (String(url).includes("/auth/v1/user")) {
      return new Response(
        JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174000", email: "fajar@example.com" }),
        { status: 200 }
      );
    }
    return new Response(JSON.stringify({ ok: true }), { status: 200 });
  };

  try {
    const env = {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon-key",
      DEVICE_REGISTRY: kv
    };
    const headers = { Authorization: "Bearer valid-jwt", "Content-Type": "application/json" };

    const mcpRes = await worker.fetch(new Request("https://worker.test/v1/mcp/servers", { headers }), env);
    assert.equal(mcpRes.status, 200);
    const mcpBody = await mcpRes.json();
    assert.equal(mcpBody.activeCount, 4);

    const mktRes = await worker.fetch(new Request("https://worker.test/v1/skills/marketplace?category=Coding", { headers }), env);
    assert.equal(mktRes.status, 200);
    const mktBody = await mktRes.json();
    assert.equal(mktBody.ok, true);
    assert.equal(mktBody.marketplaceSkills.length, 2);

    const docRes = await worker.fetch(
      new Request("https://worker.test/v1/intelligence/generate-doc", {
        method: "POST",
        headers,
        body: JSON.stringify({ format: "markdown", title: "Rencana_B8" })
      }),
      env
    );
    assert.equal(docRes.status, 200);
  } finally {
    globalThis.fetch = origFetch;
  }

  const uiPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/ui/JarvisExtensibilitySettingsScreens.kt");
  const sqlPath = path.join(repoRoot, "supabase/migrations/20261106083000_jarvis_b8_3_extensibility.sql");
  assert.ok(fs.existsSync(uiPath), "JarvisExtensibilitySettingsScreens.kt must exist");
  assert.ok(fs.existsSync(sqlPath), "20261106083000_jarvis_b8_3_extensibility.sql must exist");

  const uiCode = fs.readFileSync(uiPath, "utf8");
  assert.match(uiCode, /fun JarvisMcpServersScreen/);
  assert.match(uiCode, /fun JarvisSkillsMarketplaceScreen/);
  assert.match(uiCode, /fun JarvisSkillDetailScreen/);
  assert.match(uiCode, /fun JarvisExtensibilityAndSettingsSuite/);

  const sqlCode = fs.readFileSync(sqlPath, "utf8");
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_mcp_servers/);
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_installed_skills/);
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_generated_artifacts/);
  assert.match(sqlCode, /FORCE ROW LEVEL SECURITY/);
});
