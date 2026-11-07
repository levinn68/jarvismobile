import test from "node:test";
import assert from "node:assert/strict";

import worker from "../src/index.js";
import {
  validateOutboundUrl,
  validateOutboundUrlWithDns,
  safeOutboundFetch
} from "../src/security/ssrf-guard.js";
import {
  resolveMasterEncryptionKeyInfo,
  resolveMasterEncryptionSecret,
  encryptCredentialForKv,
  decryptCredentialFromKv,
  migrateUserCredentialsToLatestKey,
  saveUserAiAccount,
  listUserAiAccounts
} from "../src/ai/multi-account-manager.js";
import {
  saveMcpServer,
  discoverMcpServerTools,
  executeMcpTool
} from "../src/mcp/mcp-manager.js";
import {
  manageMarketplaceSkill,
  executeMarketplaceSkill
} from "../src/skills/marketplace.js";
import {
  inspectBinarySignature,
  analyzeMultimodalInput,
  runDeepResearch,
  runDeepResearchAsync,
  buildValidPdfBytes,
  generateStructuredArtifact,
  generateAndPersistArtifact,
  getStoredArtifact
} from "../src/intelligence/multimodal-creative-research.js";
import {
  createPairingSession,
  redeemPairingSession,
  cancelPairingSession
} from "../src/device/pairing.js";
import { enforceAccountRateLimit } from "../src/security/request-guard.js";
import { createTaskOrchestrator } from "../src/core/task-orchestrator.js";

function createMockKv() {
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
    async list({ prefix = "", limit = 1000 } = {}) {
      const keys = [...store.keys()]
        .filter((k) => k.startsWith(prefix))
        .sort()
        .map((name) => ({ name }));
      return { keys: keys.slice(0, limit), list_complete: true, cursor: "" };
    }
  };
}

test("B8.1.1 R1 [JARVIS-001, JARVIS-011, JARVIS-012]: Real MCP JSON-RPC/SSE execution, strict boolean approval, and SSRF validation at save & execute time", async () => {
  const kv = createMockKv();
  const userId = "a1111111-b222-c333-d444-e55555555555";

  // 1. JARVIS-011: Non-boolean truthy values ("false", "true", "1", 1, {}) MUST NOT bypass approval gate
  for (const nonBoolValue of ["false", "true", "0", "1", 1, {}, []]) {
    const res = await executeMcpTool(kv, userId, {
      serverId: "mcp_filesystem",
      toolName: "fs.write_file",
      arguments: { path: "notes.txt", content: "hello" },
      userApproved: nonBoolValue
    });
    assert.equal(res.ok, false, `Expected userApproved=${JSON.stringify(nonBoolValue)} to be blocked`);
    assert.equal(res.requiresApproval, true);
    assert.match(res.approvalRejectedReason, /strict boolean true/i);
  }

  // 2. Approval token mismatch is rejected even if userApproved === true
  const badTokenRes = await executeMcpTool(kv, userId, {
    serverId: "mcp_filesystem",
    toolName: "fs.write_file",
    arguments: { path: "notes.txt", content: "hello" },
    userApproved: true,
    approvalToken: "forged_wrong_digest_0000"
  });
  assert.equal(badTokenRes.ok, false);
  assert.equal(badTokenRes.requiresApproval, true);

  // 3. Valid approval writes real file to virtual workspace and fs.read_file reads back the exact written content
  const writeOk = await executeMcpTool(kv, userId, {
    serverId: "mcp_filesystem",
    toolName: "fs.write_file",
    arguments: { path: "docs/spec.txt", content: "Real persisted MCP content" },
    userApproved: true
  });
  assert.equal(writeOk.ok, true);
  assert.equal(writeOk.status, "completed");

  const readOk = await executeMcpTool(kv, userId, {
    serverId: "mcp_filesystem",
    toolName: "fs.read_file",
    arguments: { path: "docs/spec.txt" }
  });
  assert.equal(readOk.ok, true);
  assert.equal(readOk.jsonrpcResponse.result.content[0].text, "Real persisted MCP content");

  // 4. JARVIS-012: Custom MCP registration rejects localhost, IPv6 loopback, mapped IPv6, and private IPs
  for (const badEndpoint of [
    "https://localhost:9001/mcp",
    "https://localhost./mcp",
    "https://127.0.0.1/mcp",
    "https://[::1]/mcp",
    "https://[::ffff:127.0.0.1]/mcp",
    "https://100.64.0.1/mcp"
  ]) {
    await assert.rejects(
      () =>
        saveMcpServer(kv, userId, {
          id: "mcp_bad",
          name: "Bad MCP",
          transport: "http_jsonrpc",
          endpoint: badEndpoint
        }),
      /https:\/\/|SSRF|loopback|private|internal/i
    );
  }

  // 5. JARVIS-001: Remote MCP server connects via JSON-RPC initialize + tools/call and propagates real response or failure
  await saveMcpServer(kv, userId, {
    id: "mcp_remote_ci",
    name: "Remote CI MCP",
    transport: "http_jsonrpc",
    endpoint: "https://mcp.ci.jarvis.io/rpc",
    enabled: true,
    tools: [
      { name: "ci.status", description: "Check CI pipeline status", permission: "read_only" }
    ]
  });

  const rpcCalls = [];
  const mockMcpFetch = async (_url, init) => {
    const body = JSON.parse(init.body);
    rpcCalls.push(body.method);
    if (body.method === "initialize") {
      return new Response(
        JSON.stringify({
          jsonrpc: "2.0",
          id: body.id,
          result: {
            protocolVersion: "2024-11-05",
            capabilities: { tools: {} },
            serverInfo: { name: "Remote CI MCP", version: "1.0.0" }
          }
        }),
        { status: 200, headers: { "Content-Type": "application/json", "Mcp-Session-Id": "sess_123" } }
      );
    }
    if (body.method === "tools/list") {
      return new Response(
        JSON.stringify({
          jsonrpc: "2.0",
          id: body.id,
          result: { tools: [{ name: "ci.status", description: "Pipeline status" }] }
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }
    return new Response(
      JSON.stringify({
        jsonrpc: "2.0",
        id: body.id,
        result: {
          content: [{ type: "text", text: "Pipeline #42 GREEN (all 155 checks passed)" }],
          isError: false
        }
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  };

  const discovery = await discoverMcpServerTools(kv, userId, {
    serverId: "mcp_remote_ci",
    fetchImpl: mockMcpFetch
  });
  assert.equal(discovery.ok, true);
  assert.equal(discovery.tools.length, 1);

  const remoteExec = await executeMcpTool(kv, userId, {
    serverId: "mcp_remote_ci",
    toolName: "ci.status",
    arguments: { branch: "main" },
    fetchImpl: mockMcpFetch
  });
  assert.equal(remoteExec.ok, true);
  assert.equal(remoteExec.status, "completed");
  assert.match(remoteExec.jsonrpcResponse.result.content[0].text, /Pipeline #42 GREEN/);
  assert.ok(rpcCalls.includes("initialize") && rpcCalls.includes("tools/call"));

  // 6. JARVIS-001: Remote MCP server HTTP 500 or network failure MUST return ok: false and status: "failed" (never "completed")
  const failedExec = await executeMcpTool(kv, userId, {
    serverId: "mcp_remote_ci",
    toolName: "ci.status",
    arguments: { branch: "main" },
    fetchImpl: async () => new Response("Internal Server Error", { status: 500 })
  });
  assert.equal(failedExec.ok, false);
  assert.equal(failedExec.status, "failed");
  assert.equal(failedExec.execution.status, "failed");
});

test("B8.1.1 R1 [JARVIS-002, JARVIS-006, JARVIS-013]: Fail-closed encryption secret, v2 keyId envelope, key rotation, and non-destructive credential migration", async () => {
  const kv = createMockKv();
  const userId = "user-key-rotation-001";

  // 1. JARVIS-002: Missing encryption secret fails closed (no hardcoded default key)
  assert.throws(
    () => resolveMasterEncryptionSecret({}),
    /CREDENTIAL_ENCRYPTION_KEY_MISSING/
  );
  await assert.rejects(
    () => encryptCredentialForKv("sk-proj-secret1234567890", { env: {}, userId, provider: "openai" }),
    /CREDENTIAL_ENCRYPTION_KEY_MISSING/
  );

  // 2. JARVIS-006: Encrypt with Key V1 (old key), then rotate to Key V2 while keeping Key V1 in AI_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS
  const oldKey = "old-master-encryption-secret-v1-at-least-32-chars!!";
  const newKey = "new-master-encryption-secret-v2-at-least-32-chars!!";

  await saveUserAiAccount(
    kv,
    userId,
    {
      accountId: "acc-rot-openai",
      provider: "openai",
      label: "OpenAI Rotated",
      apiKey: "sk-proj-RotatedOpenAiKey99887766"
    },
    {
      AI_CREDENTIAL_ENCRYPTION_KEY: oldKey,
      AI_CREDENTIAL_KEY_ID: "kid_old_2025"
    }
  );

  // Simulate a second account encrypted with an unknown/corrupted key to test non-destructive migration failure handling
  const unrelatedKey = "unrelated-corrupted-key-that-is-no-longer-in-keyring!";
  const badEnvelope = await encryptCredentialForKv("sk-ant-api03-UnrecoverableKey111", {
    env: { AI_CREDENTIAL_ENCRYPTION_KEY: unrelatedKey },
    userId,
    provider: "anthropic"
  });
  const rawStored = JSON.parse(await kv.get(`ai-accounts:v1:${userId}`));
  rawStored.push({
    accountId: "acc-unrecoverable-claude",
    provider: "anthropic",
    label: "Claude Old",
    encryptedApiKey: badEnvelope,
    enabled: true
  });
  await kv.put(`ai-accounts:v1:${userId}`, JSON.stringify(rawStored));

  // Rotate environment to newKey + oldKey as previous
  const rotatedEnv = {
    AI_CREDENTIAL_ENCRYPTION_KEY: newKey,
    AI_CREDENTIAL_KEY_ID: "kid_new_2026",
    AI_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS: oldKey,
    AI_CREDENTIAL_PREVIOUS_KEY_ID: "kid_old_2025"
  };

  // Decrypting the old account still works seamlessly before migration
  const decBefore = await decryptCredentialFromKv(rawStored[0], {
    env: rotatedEnv,
    userId,
    provider: "openai"
  });
  assert.equal(decBefore, "sk-proj-RotatedOpenAiKey99887766");

  // 3. JARVIS-013: Run controlled migration -> migrates acc-rot-openai to kid_new_2026 and preserves acc-unrecoverable-claude without deleting it!
  const migReport = await migrateUserCredentialsToLatestKey(kv, userId, rotatedEnv);
  assert.equal(migReport.migratedCount, 1);
  assert.equal(migReport.failedCount, 1);
  assert.equal(migReport.currentKeyId, "kid_new_2026");

  const afterMigrationList = JSON.parse(await kv.get(`ai-accounts:v1:${userId}`));
  assert.equal(afterMigrationList.length, 2, "Failed account record must be preserved, never deleted");
  const migratedOpenAi = afterMigrationList.find((a) => a.accountId === "acc-rot-openai");
  assert.equal(migratedOpenAi.encryptedApiKey.v, 2);
  assert.equal(migratedOpenAi.encryptedApiKey.keyId, "kid_new_2026");

  // Now decrypt migratedOpenAi using ONLY newKey (without oldKey in env!)
  const decWithNewOnly = await decryptCredentialFromKv(migratedOpenAi, {
    env: { AI_CREDENTIAL_ENCRYPTION_KEY: newKey, AI_CREDENTIAL_KEY_ID: "kid_new_2026" },
    userId,
    provider: "openai"
  });
  assert.equal(decWithNewOnly, "sk-proj-RotatedOpenAiKey99887766");
});

test("B8.1.1 R1 [JARVIS-003]: Comprehensive SSRF guard blocks IPv4/IPv6 private, mapped, octal/hex/decimal IPs, trailing-dot hosts, DNS rebinding, and unsafe redirects", async () => {
  const blockedUrls = [
    "http://api.openai.com/v1/models",
    "https://localhost/v1",
    "https://localhost./v1",
    "https://sub.localhost/v1",
    "https://service.local/v1",
    "https://db.internal/v1",
    "https://127.0.0.1/v1",
    "https://127.1/v1",
    "https://2130706433/v1", // 127.0.0.1 decimal
    "https://0x7f000001/v1", // 127.0.0.1 hex
    "https://0177.0.0.1/v1", // 127.0.0.1 octal
    "https://0.0.0.0/v1",
    "https://10.0.0.1/v1",
    "https://100.64.0.1/v1", // CGNAT
    "https://100.100.100.200/latest/meta-data/", // Cloud metadata in CGNAT
    "https://169.254.169.254/latest/meta-data/",
    "https://172.16.0.1/v1",
    "https://172.31.255.255/v1",
    "https://192.168.1.1/v1",
    "https://198.18.0.1/v1",
    "https://[::1]/v1",
    "https://[::]/v1",
    "https://[::ffff:127.0.0.1]/v1",
    "https://[::ffff:7f00:1]/v1",
    "https://[::ffff:169.254.169.254]/v1",
    "https://[fe80::1]/v1",
    "https://[fc00::1]/v1",
    "https://[fd00:ec2::254]/v1",
    "https://metadata.google.internal/computeMetadata/v1/",
    "https://metadata.google.internal./computeMetadata/v1/",
    "https://127.0.0.1.nip.io/v1",
    "https://user:pass@api.openai.com/v1",
    "https://api.openai.com:6379/v1"
  ];

  for (const url of blockedUrls) {
    const res = validateOutboundUrl(url);
    assert.equal(res.ok, false, `Expected SSRF guard to block ${url}`);
  }

  // DNS rebinding check blocks public domain that resolves to 127.0.0.1 or 169.254.169.254
  const rebindCheck = await validateOutboundUrlWithDns("https://rebind.attacker.com/v1", {
    dnsResolver: async () => ["169.254.169.254"]
  });
  assert.equal(rebindCheck.ok, false);
  assert.match(rebindCheck.reason, /DNS rebinding blocked/i);

  // Redirect hop to internal metadata IP is blocked by safeOutboundFetch
  await assert.rejects(
    () =>
      safeOutboundFetch(
        "https://public-api.jarvis.io/v1/data",
        { method: "GET" },
        {
          followRedirects: true,
          fetchImpl: async () =>
            new Response("", {
              status: 302,
              headers: { Location: "https://169.254.169.254/latest/meta-data/" }
            })
        }
      ),
    /Redirect hop blocked by SSRF guard/i
  );

  // Credential-bearing request with followRedirects: false rejects any 3xx redirect immediately
  await assert.rejects(
    () =>
      safeOutboundFetch(
        "https://public-api.jarvis.io/v1/chat",
        { method: "POST" },
        {
          followRedirects: false,
          fetchImpl: async () =>
            new Response("", {
              status: 307,
              headers: { Location: "https://another-public.jarvis.io/v1/chat" }
            })
        }
      ),
    /Outbound redirect.*blocked/i
  );
});

test("B8.1.1 R1 [JARVIS-004 & JARVIS-005]: Multi-isolate pairing redemption atomicity, token failure rollback, and multi-isolate rate limiting", async () => {
  const kv = createMockKv();
  const accountId = "acct-multi-isolate-test-0001";
  await kv.put(`account:${accountId}`, JSON.stringify({ accountId, status: "active" }));

  // 1. JARVIS-004: If issueDeviceToken fails after saveDevice succeeds, device creation is rolled back and code remains pending
  const session1 = await createPairingSession({ kv, accountId, deviceHint: { name: "Rollback Test" } });
  let savedDevicesCount = 0;
  await assert.rejects(
    () =>
      redeemPairingSession({
        kv,
        code: session1.code,
        deviceInput: {
          deviceId: "windows-rollback-dev-01",
          platform: "windows",
          name: "Rollback Win"
        },
        saveDevice: async (dev) => {
          savedDevicesCount += 1;
          await kv.put(`device:${dev.deviceId}`, JSON.stringify(dev));
          await kv.put(`account-device:${dev.accountId}:${dev.deviceId}`, "1");
          return { ...dev, tokenVersion: 1 };
        },
        issueDeviceToken: async () => {
          throw new Error("Simulated KMS signing failure");
        },
        tokenSecret: "secret-key-for-test"
      }),
    /Simulated KMS signing failure/
  );
  assert.equal(savedDevicesCount, 1);
  assert.equal(await kv.get("device:windows-rollback-dev-01"), null, "Device record must be rolled back on token failure");
  assert.equal(await kv.get(`account-device:${accountId}:windows-rollback-dev-01`), null);

  // Now redeem session1 across 5 concurrent simulated Worker isolates (`bypassIsolateLock: true`) -> only 1 succeeds!
  const multiIsolateAttempts = await Promise.allSettled(
    Array.from({ length: 5 }, (_, idx) =>
      redeemPairingSession({
        kv,
        code: session1.code,
        bypassIsolateLock: true,
        deviceInput: {
          deviceId: `windows-isolate-dev-${idx + 1}`,
          platform: "windows",
          name: `Win Isolate ${idx + 1}`
        },
        saveDevice: async (dev) => {
          await kv.put(`device:${dev.deviceId}`, JSON.stringify(dev));
          return { ...dev, tokenVersion: 1 };
        },
        issueDeviceToken: async (devId) => `token_for_${devId}`,
        tokenSecret: "secret-key-for-test"
      })
    )
  );
  const winners = multiIsolateAttempts.filter((r) => r.status === "fulfilled");
  const losers = multiIsolateAttempts.filter((r) => r.status === "rejected");
  assert.equal(winners.length, 1, "Exactly 1 isolate must win pairing redemption");
  assert.equal(losers.length, 4, "All other concurrent isolates must be rejected");

  // 2. JARVIS-005: Multi-isolate rate limiter (`bypassIsolateLock: true`) enforces exact quota across 8 concurrent requests
  const quotaLimit = 3;
  const rlMultiIsolate = await Promise.all(
    Array.from({ length: 8 }, () =>
      enforceAccountRateLimit(kv, "multi_isolate_chat", "user-iso-99", quotaLimit, Date.now(), {
        bypassIsolateLock: true
      })
    )
  );
  assert.equal(rlMultiIsolate.filter((r) => r.allowed).length, quotaLimit);
  assert.equal(rlMultiIsolate.filter((r) => !r.allowed).length, 8 - quotaLimit);
});

test("B8.1.1 R1 [JARVIS-007, JARVIS-008, JARVIS-009, JARVIS-010, JARVIS-014]: Real Marketplace skills, truthful Deep Research, real Multimodal byte inspection, and binary %PDF-1.4 generation", async () => {
  const kv = createMockKv();
  const userId = "user-intel-b811-test";

  // 1. JARVIS-007: Marketplace skills perform real analysis on actual input
  const reviewRes = await executeMarketplaceSkill(kv, userId, {
    skillId: "code-reviewer-pro",
    input: "function bad(x) { eval(x); element.innerHTML = x; const ok = Boolean(payload.userApproved); }"
  });
  assert.equal(reviewRes.ok, true);
  assert.ok(reviewRes.structuredResult.findingsCount >= 3, "Must detect eval, innerHTML, and Boolean(userApproved)");
  assert.equal(reviewRes.structuredResult.verdict, "ACTION_REQUIRED_CRITICAL");

  const statsRes = await executeMarketplaceSkill(kv, userId, {
    skillId: "data-analyst-chart",
    input: "metric,value\nlatency,10\nlatency,20\nlatency,30\nlatency,40"
  });
  assert.equal(statsRes.ok, true);
  assert.equal(statsRes.structuredResult.statistics.count, 4);
  assert.equal(statsRes.structuredResult.statistics.mean, 25);
  assert.equal(statsRes.structuredResult.statistics.median, 25);

  // 2. JARVIS-008: Deep Research marks claims UNVERIFIED when no sources exist, and VERIFIED when corroborated by >= 2 sources
  const unverifiedResearch = runDeepResearch({ topic: "Quantum Resistant TLS Handshakes" });
  assert.equal(unverifiedResearch.ok, true);
  assert.equal(unverifiedResearch.verificationStatus, "UNVERIFIED");
  assert.equal(unverifiedResearch.factCheckMatrix.every((c) => c.status === "UNVERIFIED"), true);

  const verifiedResearch = await runDeepResearchAsync({
    topic: "PostgreSQL Row Level Security Isolation",
    claims: ["PostgreSQL Row Level Security Isolation enforces per-tenant access control policies."],
    sources: [
      {
        title: "PostgreSQL Official Documentation: Row Security Policies",
        url: "https://www.postgresql.org/docs/current/ddl-rowsecurity.html",
        snippet: "PostgreSQL Row Level Security Isolation enforces per-tenant access control policies on tables."
      },
      {
        title: "Supabase RLS Architecture Guide",
        url: "https://supabase.com/docs/guides/database/postgres/row-level-security",
        snippet: "Row Level Security in PostgreSQL enforces per-tenant access control policies for multi-tenant isolation."
      }
    ]
  });
  assert.equal(verifiedResearch.ok, true);
  assert.equal(verifiedResearch.verificationStatus, "VERIFIED");
  assert.equal(verifiedResearch.factCheckMatrix[0].status, "VERIFIED");
  assert.equal(verifiedResearch.factCheckMatrix[0].corroboratingSourcesCount, 2);

  // 3. JARVIS-009: Multimodal analysis inspects real PNG binary dimensions, real PDF bytes, and rejects corrupted PDF/image bytes
  // Construct a valid 1x1 PNG header with width=320, height=180 in IHDR
  const pngBytes = new Uint8Array([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x01, 0x40, // width = 320
    0x00, 0x00, 0x00, 0xb4, // height = 180
    0x08, 0x06, 0x00, 0x00, 0x00
  ]);
  const pngBase64 = Buffer.from(pngBytes).toString("base64");
  const mmPng = analyzeMultimodalInput({
    fileName: "hud_mockup.png",
    mimeType: "image/png",
    contentBase64: pngBase64
  });
  assert.equal(mmPng.ok, true);
  assert.equal(mmPng.modality, "image_vision");
  assert.equal(mmPng.binaryMetadata.width, 320);
  assert.equal(mmPng.binaryMetadata.height, 180);

  // Corrupted PDF attachment is rejected with CORRUPTED_PDF_ATTACHMENT
  const badPdf = analyzeMultimodalInput({
    fileName: "broken.pdf",
    mimeType: "application/pdf",
    contentBase64: Buffer.from("NOT_A_PDF_FILE_AT_ALL").toString("base64")
  });
  assert.equal(badPdf.ok, false);
  assert.equal(badPdf.errorCode, "CORRUPTED_PDF_ATTACHMENT");

  // 4. JARVIS-010 & JARVIS-014: PDF export generates real %PDF-1.4 binary with xref and %%EOF, not Markdown text
  const pdfArtifact = await generateAndPersistArtifact(kv, userId, {
    format: "pdf",
    title: "Laporan Keamanan B8.1.1",
    topic: "Audit & Remediasi Sistem"
  });
  assert.equal(pdfArtifact.ok, true);
  assert.equal(pdfArtifact.mimeType, "application/pdf");
  assert.equal(pdfArtifact.filename.endsWith(".pdf"), true);
  assert.equal(pdfArtifact.content.startsWith("%PDF-1.4\n"), true);
  assert.equal(pdfArtifact.content.includes("\nxref\n"), true);
  assert.equal(pdfArtifact.content.trimEnd().endsWith("%%EOF"), true);
  assert.equal(pdfArtifact.integrityVerified, true);

  const decodedPdfBytes = new Uint8Array(Buffer.from(pdfArtifact.contentBase64, "base64"));
  const sig = inspectBinarySignature(decodedPdfBytes);
  assert.equal(sig.format, "pdf");
  assert.equal(sig.validBinary, true);
  assert.equal(sig.details.pageCount, 1);

  const stored = await getStoredArtifact(kv, userId, pdfArtifact.artifactId);
  assert.ok(stored);
  assert.equal(stored.sha256, pdfArtifact.sha256);

  // 5. Task orchestrator fails closed when no authorizer is supplied
  const orchestrator = createTaskOrchestrator({
    handlers: { ping: async () => "pong" }
  });
  const unauthTask = await orchestrator.run({ type: "ping" });
  assert.equal(unauthTask.ok, false);
  assert.equal(unauthTask.status, "blocked");
});
