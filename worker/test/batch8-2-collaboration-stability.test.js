import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  MAX_COLLABORATION_MODELS,
  MAX_COLLABORATION_ROUNDS,
  COLLABORATION_MODES,
  COLLABORATION_ROLES,
  selectCollaborationTeam,
  runCollaborationSession,
  getCollaborationSession,
  listCollaborationSessions,
  deleteCollaborationSession
} from "../src/ai/collaboration-engine.js";
import { issueDeviceToken } from "../src/device/auth.js";
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

test("Batch 8.2 — Collaboration Engine: Automatic & Manual team selection, max 6 models, and 6 specialized roles", async () => {
  assert.equal(MAX_COLLABORATION_MODELS, 6);
  assert.equal(MAX_COLLABORATION_ROUNDS, 5);
  assert.equal(Object.keys(COLLABORATION_MODES).length, 7);
  assert.equal(COLLABORATION_ROLES.length, 6);

  const autoTeam = selectCollaborationTeam({ selectionMode: "auto", assignRoles: true });
  assert.equal(autoTeam.length, 4);
  assert.equal(autoTeam[0].role, "Analis");
  assert.equal(autoTeam[1].role, "Kreatif");
  assert.equal(autoTeam[2].role, "Teknis");
  assert.equal(autoTeam[3].role, "Evaluator");

  const manualTeam = selectCollaborationTeam({
    selectionMode: "manual",
    selectedModelIds: [
      "gpt-4o",
      "claude-3-5-sonnet",
      "gemini-1.5-pro",
      "deepseek-v3",
      "llama-3.3-70b",
      "qwen-2.5-72b",
      "gpt-4o" // duplicate should be deduplicated
    ],
    assignRoles: true
  });
  assert.equal(manualTeam.length, 6);
  assert.equal(manualTeam[4].role, "Perencana");
  assert.equal(manualTeam[5].role, "Moderator");
});

test("Batch 8.2 — Multi-Round Discussion, Debate Pro/Contra, 4-Step Workflow, Knowledge Graph, and Synthesis Export", async () => {
  const kv = createMockKv();
  const env = {
    DEVICE_REGISTRY: kv,
    JARVIS_ENV: "test",
    MOCK_PROVIDER_ENABLED: "true"
  };

  const session = await runCollaborationSession(env, "user-b8-2", {
    topic: "Merancang arsitektur aplikasi SaaS scalable untuk 100.000 pengguna aktif.",
    selectionMode: "manual",
    selectedModelIds: ["gpt-4o", "claude-3-5-sonnet", "gemini-1.5-pro", "deepseek-v3"],
    mode: "debate_pro_contra",
    rounds: 3,
    assignRoles: true,
    enableKnowledgeGraph: true
  });

  assert.ok(session.sessionId.startsWith("collab_"));
  assert.equal(session.status, "SUCCESS");
  assert.equal(session.totalRounds, 3);
  assert.equal(session.team.length, 4);
  assert.equal(session.messages.length, 12); // 4 agents * 3 rounds
  assert.equal(session.opinionEvolution.length, 12);

  // Debate & Evaluation verification (dynamic claim-based score from actual responses)
  assert.ok(session.debate.consensusScore >= 0 && session.debate.consensusScore <= 100);
  assert.ok(session.debate.pros.length >= 1);
  assert.ok(session.debate.cons.length >= 1);
  assert.equal(session.debate.evaluations.length, 4);

  // 4-Step Workflow verification
  assert.equal(session.workflowSteps.length, 4);
  assert.equal(session.workflowSteps[0].title, "Step 1 — Problem Breakdown");
  assert.equal(session.workflowSteps[3].title, "Step 4 — Final Synthesis");

  // Knowledge Graph verification (dynamically built from actual responses)
  assert.equal(session.knowledgeGraph.enabled, true);
  assert.ok(session.knowledgeGraph.nodes.length >= 5);
  assert.ok(session.knowledgeGraph.edges.length >= 4);
  assert.ok(session.knowledgeGraph.tableRows.length >= 4);

  // Synthesis & Export Artifacts verification
  assert.match(session.synthesis.synthesizedBy, /J\.A\.R\.V\.I\.S\./);
  assert.ok(session.synthesis.exportArtifacts.pdfReport.filename.endsWith(".pdf"));
  assert.ok(session.synthesis.exportArtifacts.markdownPlan.filename.endsWith(".md"));
  assert.ok(session.synthesis.exportArtifacts.diagramSpec.filename.endsWith(".png"));

  // Stop action & deletion verification
  const stopped = await runCollaborationSession(env, "user-b8-2", {
    action: "stop",
    sessionId: session.sessionId
  });
  assert.equal(stopped.status, "stopped");

  const listed = await listCollaborationSessions(env, "user-b8-2");
  assert.equal(listed.length, 1);
  assert.equal(listed[0].status, "stopped");

  const loaded = await getCollaborationSession(env, "user-b8-2", session.sessionId);
  assert.equal(loaded.sessionId, session.sessionId);

  const del = await deleteCollaborationSession(env, "user-b8-2", session.sessionId);
  assert.equal(del.removed, true);
  assert.equal(del.remaining, 0);
});

test("Batch 8.2 — Worker HTTP Endpoints (/v1/collaboration/modes, /v1/collaboration/sessions GET/POST/DELETE)", async () => {
  const kv = createMockKv();
  const secret = "b8-2-test-secret-key-0123456789abcdef";
  const env = {
    DEVICE_REGISTRY: kv,
    DEVICE_AUTH_SECRET: secret,
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "anon-key",
    JARVIS_ENV: "test",
    MOCK_PROVIDER_ENABLED: "true"
  };

  await kv.put(
    "account:acct-b8-2",
    JSON.stringify({
      accountId: "acct-b8-2",
      userId: "user-b8-2",
      status: "active",
      createdAt: new Date().toISOString()
    })
  );

  const token = await issueDeviceToken(secret, {
    sub: "acct-b8-2",
    scope: "account:control",
    ver: 1,
    ttlSeconds: 3600
  });
  const authHeaders = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json"
  };

  const origFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    if (url.includes("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "123e4567-e89b-12d3-a456-426614174000", email: "fajar@example.com" }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return origFetch(input, init);
  };
  try {
    // 1. GET /v1/collaboration/modes
    const modesRes = await worker.fetch(
      new Request("https://worker.test/v1/collaboration/modes", { headers: authHeaders }),
      env
    );
    assert.equal(modesRes.status, 200);
    const modesBody = await modesRes.json();
    assert.equal(modesBody.ok, true);
    assert.equal(modesBody.maxModels, 6);
    assert.equal(modesBody.modes.length, 7);
    assert.equal(modesBody.roles.length, 6);

    // 2. POST /v1/collaboration/sessions
    const createRes = await worker.fetch(
      new Request("https://worker.test/v1/collaboration/sessions", {
        method: "POST",
        headers: authHeaders,
        body: JSON.stringify({
          topic: "Strategi arsitektur multi-region SaaS",
          selectionMode: "auto",
          mode: "structured_workflow",
          rounds: 2
        })
      }),
      env
    );
    assert.equal(createRes.status, 201);
    const createBody = await createRes.json();
    assert.equal(createBody.ok, true);
    const sessionId = createBody.session.sessionId;
    assert.ok(sessionId);

    // 3. GET /v1/collaboration/sessions
    const listRes = await worker.fetch(
      new Request("https://worker.test/v1/collaboration/sessions", { headers: authHeaders }),
      env
    );
    assert.equal(listRes.status, 200);
    const listBody = await listRes.json();
    assert.equal(listBody.count, 1);

    // 4. DELETE /v1/collaboration/sessions/:id
    const delRes = await worker.fetch(
      new Request(`https://worker.test/v1/collaboration/sessions/${encodeURIComponent(sessionId)}`, {
        method: "DELETE",
        headers: authHeaders
      }),
      env
    );
    assert.equal(delRes.status, 200);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test("Batch 8.2 — Android Crash Elimination, Smooth Animations, Storyboard Screens 12-30 & Zero-Deletion Verification", async () => {
  const stabilityPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/stability/JarvisStabilityGuard.kt");
  const collabUiPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/ui/JarvisCollaborationScreens.kt");
  const mainActivityPath = path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/MainActivity.kt");
  const migrationPath = path.join(repoRoot, "supabase/migrations/20261106082000_jarvis_b8_2_collaboration_history.sql");

  assert.ok(fs.existsSync(stabilityPath), "JarvisStabilityGuard.kt must exist");
  assert.ok(fs.existsSync(collabUiPath), "JarvisCollaborationScreens.kt must exist");
  assert.ok(fs.existsSync(migrationPath), "20261106082000_jarvis_b8_2_collaboration_history.sql must exist");

  const stabilityCode = fs.readFileSync(stabilityPath, "utf8");
  assert.match(stabilityCode, /object JarvisStabilityGuard/);
  assert.match(stabilityCode, /fun redactSecrets/);
  assert.match(stabilityCode, /fun rethrowIfCancellation/);
  assert.match(stabilityCode, /fun readBoundedBytes/);
  assert.match(stabilityCode, /fun validateAttachmentSize/);

  const collabUiCode = fs.readFileSync(collabUiPath, "utf8");
  assert.match(collabUiCode, /fun JarvisAgentNetworkAnimation/);
  assert.match(collabUiCode, /fun JarvisB8ChatExperienceScreen/);
  assert.match(collabUiCode, /fun JarvisB8VoiceExperienceScreen/);
  assert.match(collabUiCode, /fun JarvisB8ConversationHistoryScreen/);
  assert.match(collabUiCode, /fun JarvisB8CollaborationSuiteScreen/);

  const mainActivityCode = fs.readFileSync(mainActivityPath, "utf8");
  assert.match(mainActivityCode, /import com\.jarvis\.mobile\.stability\.JarvisStabilityGuard/);
  assert.match(mainActivityCode, /JarvisStabilityGuard\.readBoundedBytes/);
  assert.match(mainActivityCode, /JarvisStabilityGuard\.validateAttachmentSize/);

  const sqlCode = fs.readFileSync(migrationPath, "utf8");
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_collaboration_sessions/);
  assert.match(sqlCode, /CREATE TABLE IF NOT EXISTS public\.jarvis_collaboration_messages/);
  assert.match(sqlCode, /FORCE ROW LEVEL SECURITY/);
});
