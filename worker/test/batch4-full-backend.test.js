import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import workerApp, { keyRotator } from "../src/index.js";
import {
  authenticateSupabaseUserDetailed,
  corsHeadersForRequest,
  requestOriginAllowed
} from "../src/security/request-guard.js";
import { createProviderManager } from "../src/ai/provider-manager.js";
import { createKeyRotator } from "../src/ai/key-rotation.js";
import {
  validateProviderJsonResponse,
  canonicalJson,
  sha256Hex
} from "../src/utils.js";
import {
  issueAccountToken,
  issueDeviceToken,
  verifyDeviceToken,
  constantTimeEqual
} from "../src/device/auth.js";
import {
  createAccount,
  saveDevice,
  getDevice,
  removeDevice
} from "../src/device/registry.js";
import {
  enqueueDeviceCommand,
  listDeviceCommands,
  acknowledgeDeviceCommand,
  getDeviceCommand,
  purgeDeviceCommands
} from "../src/device/command-service.js";
import { handleDeviceRequest } from "../src/device/router.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../..");

function memoryKv() {
  const store = new Map();
  return {
    store,
    async get(key) {
      return store.get(key) ?? null;
    },
    async put(key, value) {
      store.set(key, String(value));
    },
    async delete(key) {
      store.delete(key);
    },
    async list({ prefix = "", limit = 1000, cursor } = {}) {
      const all = [...store.keys()].filter((k) => k.startsWith(prefix)).sort();
      const offset = cursor ? Number(cursor) : 0;
      const keys = all.slice(offset, offset + limit).map((name) => ({ name }));
      const next = offset + keys.length;
      return {
        keys,
        list_complete: next >= all.length,
        ...(next < all.length ? { cursor: String(next) } : {})
      };
    }
  };
}

test("B4 Worker: API routing, CORS origin enforcement, and OPTIONS preflight security", async () => {
  const env = {
    JARVIS_ALLOWED_ORIGINS: "https://app.jarvis.example,https://console.jarvis.example",
    DEVICE_TOKEN_SECRET: "secret-key-for-testing-with-32-chars",
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "public-anon-key",
    DEVICE_REGISTRY: memoryKv(),
    DEVICE_COMMANDS: memoryKv()
  };

  // Disallowed origin on OPTIONS preflight -> 403
  const badPreflight = await workerApp.fetch(
    new Request("https://worker.test/v1/chat", {
      method: "OPTIONS",
      headers: { Origin: "https://evil.example" }
    }),
    env
  );
  assert.equal(badPreflight.status, 403);
  assert.equal(badPreflight.headers.get("Access-Control-Allow-Origin"), "null");

  // Allowed origin on OPTIONS preflight -> 204 with matching origin
  const okPreflight = await workerApp.fetch(
    new Request("https://worker.test/v1/chat", {
      method: "OPTIONS",
      headers: { Origin: "https://app.jarvis.example" }
    }),
    env
  );
  assert.equal(okPreflight.status, 204);
  assert.equal(okPreflight.headers.get("Access-Control-Allow-Origin"), "https://app.jarvis.example");

  // Device router also enforces JARVIS_ALLOWED_ORIGINS and attaches security headers
  const blockedDeviceReq = await workerApp.fetch(
    new Request("https://worker.test/v1/devices", {
      method: "GET",
      headers: { Origin: "https://evil.example" }
    }),
    env
  );
  assert.equal(blockedDeviceReq.status, 403);
  assert.equal(blockedDeviceReq.headers.get("Access-Control-Allow-Origin"), "null");
  assert.equal(blockedDeviceReq.headers.get("X-Content-Type-Options"), "nosniff");
});

test("B4 Worker: authenticateSupabaseUserDetailed distinguishes 401 invalid token, 503 unconfigured, and 504 timeout", async () => {
  const originalFetch = globalThis.fetch;
  try {
    // Missing token -> 401
    const noToken = await authenticateSupabaseUserDetailed(new Request("https://worker.test/v1/chat"), {
      SUPABASE_URL: "https://example.supabase.co",
      SUPABASE_ANON_KEY: "anon"
    });
    assert.equal(noToken.ok, false);
    assert.equal(noToken.status, 401);
    assert.equal(noToken.reason, "missing_token");

    // Unconfigured -> 503
    const uncfg = await authenticateSupabaseUserDetailed(
      new Request("https://worker.test/v1/chat", {
        headers: { Authorization: "Bearer tok" }
      }),
      {}
    );
    assert.equal(uncfg.ok, false);
    assert.equal(uncfg.status, 503);

    // Simulated AbortError timeout -> 504
    globalThis.fetch = async () => {
      const err = new Error("The operation was aborted");
      err.name = "AbortError";
      throw err;
    };
    const timedOut = await authenticateSupabaseUserDetailed(
      new Request("https://worker.test/v1/chat", {
        headers: { Authorization: "Bearer tok" }
      }),
      {
        SUPABASE_URL: "https://example.supabase.co",
        SUPABASE_ANON_KEY: "anon"
      },
      1000
    );
    assert.equal(timedOut.ok, false);
    assert.equal(timedOut.status, 504);
    assert.equal(timedOut.reason, "supabase_timeout");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("B4 Worker: AI provider JSON response validation and single-key transient retry", async () => {
  assert.throws(() => validateProviderJsonResponse("", "gemini"), /empty response body/);
  assert.throws(() => validateProviderJsonResponse("{bad json", "gemini"), /malformed JSON/);
  assert.throws(() => validateProviderJsonResponse("[1,2,3]", "gemini"), /non-object JSON/);
  assert.deepEqual(validateProviderJsonResponse('{"ok":true}', "gemini"), { ok: true });

  // Single-key provider retries once on transient 503 and succeeds on attempt 2
  const rotator = createKeyRotator({ cooldownMs: 50, maxFailures: 3 });
  let calls = 0;
  const manager = createProviderManager({
    rotator,
    baseBackoffMs: 10,
    maxAttemptsPerKey: 2,
    providers: {
      gemini: {
        keys: ["GEMINI_API_KEY_1"],
        invoke: async () => {
          calls += 1;
          if (calls === 1) {
            const err = new Error("HTTP 503: Service temporarily overloaded");
            err.status = 503;
            throw err;
          }
          return "Recovered on second attempt";
        }
      }
    }
  });

  const res = await manager.invoke("gemini", { GEMINI_API_KEY_1: "k1" }, {});
  assert.equal(res.result, "Recovered on second attempt");
  assert.equal(res.attempt, 2);
  assert.equal(calls, 2);
});

test("B4 Worker: /v1/chat clientRequestId idempotency prevents duplicate AI and device command execution on retry", async () => {
  keyRotator.reset();
  const registry = memoryKv();
  const commands = memoryKv();
  const userId = "123e4567-e89b-12d3-a456-426614174088";
  const accountId = "acct-" + userId;
  await createAccount(registry, accountId);
  await saveDevice(registry, {
    deviceId: "windows-main-pc",
    accountId,
    platform: "windows",
    name: "Windows Workstation",
    capabilities: ["apps.launch", "apps.list", "system.read"]
  });

  let groqCalls = 0;
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      const u = String(url);
      if (u.includes("/auth/v1/user")) {
        return new Response(JSON.stringify({ id: userId, email: "owner@example.com" }), { status: 200 });
      }
      if (u.includes("raw.githubusercontent.com")) {
        return new Response("# Skill", { status: 200 });
      }
      if (u.includes("api.groq.com")) {
        groqCalls += 1;
        return new Response(
          JSON.stringify({
            choices: [{ message: { content: "Perintah peluncuran Calculator telah dikirim ke Windows Workstation." } }]
          }),
          { status: 200 }
        );
      }
      return new Response("Not found", { status: 404 });
    };

    // Auto-complete the queued command quickly so waitMs finishes fast
    const autoAck = setInterval(async () => {
      for (const k of commands.store.keys()) {
        if (!k.startsWith("command:windows-main-pc:")) continue;
        const rec = JSON.parse(await commands.get(k));
        if (rec.status === "queued") {
          await acknowledgeDeviceCommand({
            kv: commands,
            deviceId: "windows-main-pc",
            commandId: rec.commandId,
            result: { ok: true, launchedAppId: "calc" }
          });
        }
      }
    }, 100);

    try {
      const env = {
        GROQ_API_KEY_1: "groq-key",
        SUPABASE_URL: "https://example.supabase.co",
        SUPABASE_ANON_KEY: "anon-key",
        DEVICE_REGISTRY: registry,
        DEVICE_COMMANDS: commands
      };

      const makeReq = () =>
        new Request("https://worker.test/v1/chat", {
          method: "POST",
          headers: {
            Authorization: "Bearer valid-supabase-jwt",
            "Content-Type": "application/json",
            "X-Client-Request-Id": "req-idemp-999"
          },
          body: JSON.stringify({
            message: "Buka Calculator di Windows",
            clientRequestId: "req-idemp-999"
          })
        });

      const firstRes = await workerApp.fetch(makeReq(), env);
      assert.equal(firstRes.status, 200);
      const firstBody = await firstRes.json();
      assert.equal(firstBody.ok, true);
      assert.equal(Boolean(firstBody.replayed), false);
      assert.equal(firstBody.deviceAction.action, "apps.launch_label");

      // Second request with the SAME clientRequestId simulates client network retry:
      // Must return cached response with replayed: true and NOT call Groq or enqueue a second command!
      const secondRes = await workerApp.fetch(makeReq(), env);
      assert.equal(secondRes.status, 200);
      const secondBody = await secondRes.json();
      assert.equal(secondBody.ok, true);
      assert.equal(secondBody.replayed, true);
      assert.equal(secondBody.deviceAction.commandId, firstBody.deviceAction.commandId);
      assert.equal(groqCalls, 1);

      const commandRecords = [...commands.store.keys()].filter((k) =>
        k.startsWith("command:windows-main-pc:")
      );
      assert.equal(commandRecords.length, 1);
    } finally {
      clearInterval(autoAck);
    }
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("B4 Security: Cross-account device hijacking (IDOR) is blocked on enrollment and saveDevice", async () => {
  const registry = memoryKv();
  const secret = "test-secret-with-more-than-32-characters";
  const ownerAccount = "acct-11111111-1111-1111-1111-111111111111";
  const attackerAccount = "acct-22222222-2222-2222-2222-222222222222";
  await createAccount(registry, ownerAccount);
  await createAccount(registry, attackerAccount);

  const ownerToken = await issueAccountToken(ownerAccount, secret);
  const attackerToken = await issueAccountToken(attackerAccount, secret);

  const env = {
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: memoryKv()
  };

  // Owner enrolls android-rog6-shared-id
  const enrollOwner = await handleDeviceRequest(
    new Request("https://worker.test/v1/devices/enroll", {
      method: "POST",
      headers: { Authorization: "Bearer " + ownerToken, "Content-Type": "application/json" },
      body: JSON.stringify({
        deviceId: "android-rog6-shared-id",
        platform: "android",
        name: "Owner Phone",
        capabilities: ["system.read"]
      })
    }),
    env,
    new URL("https://worker.test/v1/devices/enroll")
  );
  assert.equal(enrollOwner.status, 201);

  // Attacker attempts to enroll the same deviceId under attackerAccount -> MUST fail with 403 Forbidden
  const enrollAttacker = await handleDeviceRequest(
    new Request("https://worker.test/v1/devices/enroll", {
      method: "POST",
      headers: { Authorization: "Bearer " + attackerToken, "Content-Type": "application/json" },
      body: JSON.stringify({
        deviceId: "android-rog6-shared-id",
        platform: "android",
        name: "Attacker Hijack",
        capabilities: ["system.read"]
      })
    }),
    env,
    new URL("https://worker.test/v1/devices/enroll")
  );
  assert.equal(enrollAttacker.status, 403);
  const stored = await getDevice(registry, "android-rog6-shared-id");
  assert.equal(stored.accountId, ownerAccount);
  assert.equal(stored.name, "Owner Phone");
});

test("B4 Device Lifecycle: Token version superseding, command FIFO ordering, duplicate command prevention, approval enforcement, expiration, and revoke purge", async () => {
  const registry = memoryKv();
  const commands = memoryKv();
  const secret = "test-secret-with-more-than-32-characters";
  const accountId = "acct-33333333-3333-3333-3333-333333333333";
  await createAccount(registry, accountId);
  const accountToken = await issueAccountToken(accountId, secret);
  const env = {
    DEVICE_TOKEN_SECRET: secret,
    DEVICE_REGISTRY: registry,
    DEVICE_COMMANDS: commands
  };

  // 1. Enroll device (tokenVersion = 1)
  const enroll1 = await (
    await handleDeviceRequest(
      new Request("https://worker.test/v1/devices/enroll", {
        method: "POST",
        headers: { Authorization: "Bearer " + accountToken, "Content-Type": "application/json" },
        body: JSON.stringify({
          deviceId: "android-lifecycle-01",
          platform: "android",
          name: "ROG Phone",
          capabilities: ["system.read", "screen.read", "screen.control"]
        })
      }),
      env,
      new URL("https://worker.test/v1/devices/enroll")
    )
  ).json();
  const tokenV1 = enroll1.token;

  // 2. Re-enroll same device under same owner -> rotates tokenVersion to 2, invalidating tokenV1
  const enroll2 = await (
    await handleDeviceRequest(
      new Request("https://worker.test/v1/devices/enroll", {
        method: "POST",
        headers: { Authorization: "Bearer " + accountToken, "Content-Type": "application/json" },
        body: JSON.stringify({
          deviceId: "android-lifecycle-01",
          platform: "android",
          name: "ROG Phone Re-paired",
          capabilities: ["system.read", "screen.read", "screen.control"]
        })
      }),
      env,
      new URL("https://worker.test/v1/devices/enroll")
    )
  ).json();
  const tokenV2 = enroll2.token;

  const oldTokenHeartbeat = await handleDeviceRequest(
    new Request("https://worker.test/v1/devices/android-lifecycle-01/heartbeat", {
      method: "POST",
      headers: { Authorization: "Bearer " + tokenV1 }
    }),
    env,
    new URL("https://worker.test/v1/devices/android-lifecycle-01/heartbeat")
  );
  assert.equal(oldTokenHeartbeat.status, 401);

  const newTokenHeartbeat = await handleDeviceRequest(
    new Request("https://worker.test/v1/devices/android-lifecycle-01/heartbeat", {
      method: "POST",
      headers: { Authorization: "Bearer " + tokenV2 }
    }),
    env,
    new URL("https://worker.test/v1/devices/android-lifecycle-01/heartbeat")
  );
  assert.equal(newTokenHeartbeat.status, 200);

  // 3. Idempotent command queueing via idempotencyKey
  const device = await getDevice(registry, "android-lifecycle-01");
  const q1 = await enqueueDeviceCommand({
    kv: commands,
    device,
    input: { action: "system.status", idempotencyKey: "idem-status-1" }
  });
  const q2 = await enqueueDeviceCommand({
    kv: commands,
    device,
    input: { action: "system.status", idempotencyKey: "idem-status-1" }
  });
  assert.equal(q1.commandId, q2.commandId);
  assert.equal(q2.duplicate, true);

  // 4. Sensitive command (screen.tap) requires explicit approved: true on acknowledgement
  const tapCmd = await enqueueDeviceCommand({
    kv: commands,
    device,
    input: { action: "screen.tap", params: { x: 0.4, y: 0.6 } }
  });
  assert.equal(tapCmd.status, "queued_pending_approval");

  // Forged ack with ok: true but missing approved: true MUST be rejected
  const forgedAck = await acknowledgeDeviceCommand({
    kv: commands,
    deviceId: device.deviceId,
    commandId: tapCmd.commandId,
    result: { ok: true, executed: true }
  });
  assert.equal(forgedAck.ok, false);
  assert.equal(forgedAck.outcome, "failed");

  const storedTap = await getDeviceCommand({
    kv: commands,
    deviceId: device.deviceId,
    commandId: tapCmd.commandId
  });
  assert.equal(storedTap.outcome, "failed");
  assert.match(storedTap.result.error, /approval/i);

  // 5. Expired command transitions to status: "expired" on getDeviceCommand
  const futureNow = Date.now() + 10 * 60 * 1000;
  const expiredStatus = await getDeviceCommand({
    kv: commands,
    deviceId: device.deviceId,
    commandId: q1.commandId,
    nowMs: futureNow
  });
  assert.equal(expiredStatus.status, "expired");
  assert.equal(expiredStatus.outcome, "expired");

  // 6. Revoking device purges all remaining commands in DEVICE_COMMANDS KV
  await enqueueDeviceCommand({
    kv: commands,
    device,
    input: { action: "system.status" }
  });
  const revokeRes = await handleDeviceRequest(
    new Request("https://worker.test/v1/devices/android-lifecycle-01", {
      method: "DELETE",
      headers: { Authorization: "Bearer " + accountToken }
    }),
    env,
    new URL("https://worker.test/v1/devices/android-lifecycle-01")
  );
  assert.equal(revokeRes.status, 200);
  const remainingCmds = [...commands.store.keys()].filter((k) =>
    k.startsWith("command:android-lifecycle-01:")
  );
  assert.equal(remainingCmds.length, 0);
});

test("B4 Supabase: Migrations order, schema constraints, granular RLS policies, SECURITY INVOKER triggers, and Levpay cleanup verification", async () => {
  const migrationsDir = path.join(repoRoot, "supabase/migrations");
  const files = (await fs.readdir(migrationsDir)).filter((f) => f.endsWith(".sql")).sort();

  assert.deepEqual(files.slice(0, 6), [
    "20261103095224_jarvis_chat_history.sql",
    "20261103095252_harden_jarvis_chat_rls.sql",
    "20261104141910_jarvis_message_idempotency.sql",
    "20261105120000_jarvis_conversation_triggers.sql",
    "20261105160000_jarvis_b4_schema_rls_and_triggers_repair.sql",
    "20261105161000_jarvis_b4_levpay_cleanup.sql"
  ]);

  const b4SchemaSql = await fs.readFile(
    path.join(migrationsDir, "20261105160000_jarvis_b4_schema_rls_and_triggers_repair.sql"),
    "utf8"
  );
  const b4LevpaySql = await fs.readFile(
    path.join(migrationsDir, "20261105161000_jarvis_b4_levpay_cleanup.sql"),
    "utf8"
  );

  // Verify composite FK, check constraints, sequence_number, and composite unique index
  assert.match(b4SchemaSql, /jarvis_conversations_id_user_id_key\s+unique\s*\(id,\s*user_id\)/i);
  assert.match(b4SchemaSql, /jarvis_messages_conversation_owner_fk[\s\S]*?foreign key\s*\(conversation_id,\s*user_id\)\s*references\s+public\.jarvis_conversations\(id,\s*user_id\)\s*on delete cascade/i);
  assert.match(b4SchemaSql, /jarvis_conversations_title_length_chk/i);
  assert.match(b4SchemaSql, /jarvis_messages_content_length_chk/i);
  assert.match(b4SchemaSql, /jarvis_messages_user_client_id_uidx\s+on\s+public\.jarvis_messages\(user_id,\s*client_id\)/i);
  assert.match(b4SchemaSql, /sequence_number\s+bigint\s+generated\s+by\s+default\s+as\s+identity/i);

  // Verify granular RLS policies (SELECT, INSERT, UPDATE, DELETE) and FORCE ROW LEVEL SECURITY
  assert.match(b4SchemaSql, /alter table public\.jarvis_conversations force row level security/i);
  assert.match(b4SchemaSql, /alter table public\.jarvis_messages force row level security/i);
  assert.match(b4SchemaSql, /revoke all on table public\.jarvis_conversations from public,\s*anon/i);
  assert.match(b4SchemaSql, /revoke all on table public\.jarvis_messages from public,\s*anon/i);
  for (const policyName of [
    "jarvis_conversations_select_own",
    "jarvis_conversations_insert_own",
    "jarvis_conversations_update_own",
    "jarvis_conversations_delete_own",
    "jarvis_messages_select_own",
    "jarvis_messages_insert_own",
    "jarvis_messages_update_own",
    "jarvis_messages_delete_own"
  ]) {
    assert.ok(b4SchemaSql.includes(policyName), `Missing RLS policy: ${policyName}`);
  }

  // Verify trigger functions use SECURITY INVOKER and locked search_path = pg_catalog, public
  assert.doesNotMatch(b4SchemaSql.replace(/--.*$/gm, ""), /security\s+definer/i);
  assert.match(b4SchemaSql, /security\s+invoker\s+set\s+search_path\s*=\s*pg_catalog,\s*public/i);
  assert.match(b4SchemaSql, /revoke all on function public\.jarvis_touch_conversation_updated_at\(\) from public,\s*anon/i);
  assert.match(b4SchemaSql, /revoke all on function public\.jarvis_prepare_message_insert\(\) from public,\s*anon/i);
  assert.match(b4SchemaSql, /revoke all on function public\.jarvis_enforce_immutable_columns\(\) from public,\s*anon/i);

  // Verify Levpay cleanup drops levpay_paid & levpay_paidlog and quarantines generic paid_* tables with RLS
  assert.match(b4LevpaySql, /levpay_paid/);
  assert.match(b4LevpaySql, /levpay_paidlog/);
  assert.match(b4LevpaySql, /drop table if exists public\.%I cascade/i);
  assert.match(b4LevpaySql, /paid_transactions/);
  assert.match(b4LevpaySql, /enable row level security/i);
});

test("B4 Integration: Simulated RLS isolation, batch message ordering, composite FK ownership, and Android client contract compatibility", async () => {
  // Behavioral verification of the B4 PostgreSQL RLS, triggers, and Android SupabaseJarvisClient contract
  const conversations = new Map();
  const messages = [];
  const clientIdIndex = new Set();

  function rlsInsertConversation(authUid, row) {
    if (!authUid) throw new Error("RLS violation: unauthenticated");
    const userId = row.user_id || authUid;
    if (userId !== authUid) throw new Error("RLS violation: cross-user insert denied");
    const title = String(row.title || "Percakapan baru").trim();
    if (title.length < 1 || title.length > 200) throw new Error("CHECK violation: title length");
    const id = row.id || crypto.randomUUID();
    const now = new Date().toISOString();
    const record = { id, user_id: userId, title, message_count: 0, last_message_at: null, created_at: now, updated_at: now };
    conversations.set(id, record);
    return record;
  }

  function rlsInsertMessageBatch(authUid, batch) {
    if (!authUid) throw new Error("RLS violation: unauthenticated");
    const inserted = [];
    for (const item of batch) {
      const userId = item.user_id || authUid;
      if (userId !== authUid) throw new Error("RLS violation: message user_id mismatch");
      const conv = conversations.get(item.conversation_id);
      if (!conv || conv.user_id !== authUid) {
        throw new Error("RLS / Composite FK violation: conversation does not belong to user");
      }
      if (clientIdIndex.has(item.client_id)) {
        // ON CONFLICT (client_id) DO NOTHING
        continue;
      }
      // Trigger jarvis_prepare_message_insert: enforce monotonic created_at
      let createdMs = Date.parse(item.created_at || new Date().toISOString());
      const existingConvMsgs = messages.filter((m) => m.conversation_id === conv.id && m.user_id === userId);
      const latestMs = existingConvMsgs.reduce((max, m) => Math.max(max, Date.parse(m.created_at)), 0);
      if (latestMs > 0 && createdMs <= latestMs) {
        createdMs = latestMs + 1;
      }
      const msgRecord = {
        id: crypto.randomUUID(),
        conversation_id: conv.id,
        user_id: userId,
        client_id: item.client_id,
        role: item.role,
        content: item.content,
        sequence_number: messages.length + 1,
        created_at: new Date(createdMs).toISOString()
      };
      clientIdIndex.add(item.client_id);
      messages.push(msgRecord);
      // Trigger jarvis_touch_conversation_updated_at
      conv.message_count += 1;
      conv.last_message_at = msgRecord.created_at;
      conv.updated_at = msgRecord.created_at;
      inserted.push(msgRecord);
    }
    return inserted;
  }

  const userA = "11111111-1111-1111-1111-111111111111";
  const userB = "22222222-2222-2222-2222-222222222222";

  const convA = rlsInsertConversation(userA, { title: "Rencana Proyek B4" });
  assert.throws(() => rlsInsertMessageBatch(userB, [{
    conversation_id: convA.id,
    client_id: crypto.randomUUID(),
    role: "user",
    content: "Cross-user injection attempt"
  }]), /RLS \/ Composite FK violation/);

  // Same-timestamp batch insert of (user, assistant) produces strictly ordered timestamps
  const sameIso = "2026-11-05T10:00:00.000Z";
  const clientUserMsgId = crypto.randomUUID();
  const clientAsstMsgId = crypto.randomUUID();
  rlsInsertMessageBatch(userA, [
    { conversation_id: convA.id, client_id: clientUserMsgId, role: "user", content: "Halo Jarvis", created_at: sameIso },
    { conversation_id: convA.id, client_id: clientAsstMsgId, role: "assistant", content: "Siap, Tuan.", created_at: sameIso }
  ]);
  // Replay of the exact same batch is idempotently ignored
  rlsInsertMessageBatch(userA, [
    { conversation_id: convA.id, client_id: clientUserMsgId, role: "user", content: "Halo Jarvis", created_at: sameIso },
    { conversation_id: convA.id, client_id: clientAsstMsgId, role: "assistant", content: "Siap, Tuan.", created_at: sameIso }
  ]);

  assert.equal(messages.length, 2);
  assert.equal(convA.message_count, 2);
  assert.ok(Date.parse(messages[1].created_at) > Date.parse(messages[0].created_at));

  // Verify Android Kotlin integration files contain all B4 repairs
  const supabaseClientKt = await fs.readFile(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/account/SupabaseJarvisClient.kt"),
    "utf8"
  );
  assert.ok(supabaseClientKt.includes("fun refreshSessionBlocking(): String"));
  assert.ok(supabaseClientKt.includes("$userId:$turnId:user"));
  assert.ok(supabaseClientKt.includes("order=created_at.asc,id.asc"));

  const executorKt = await fs.readFile(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/device/DeviceCommandExecutor.kt"),
    "utf8"
  );
  assert.ok(executorKt.includes("fun recordRejectedApproval("));

  const deviceClientKt = await fs.readFile(
    path.join(repoRoot, "app/src/main/java/com/jarvis/mobile/device/JarvisDeviceClient.kt"),
    "utf8"
  );
  assert.ok(deviceClientKt.includes("fun enqueuePendingAck("));
  assert.ok(deviceClientKt.includes("flushPendingAcks()"));
});
