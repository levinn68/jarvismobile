import test from "node:test";
import assert from "node:assert/strict";

import {
  createPairingSession,
  redeemPairingSession,
  getPairingSession
} from "../src/device/pairing.js";
import {
  PairingCoordinator,
  InMemoryPairingCoordinatorNamespace
} from "../src/device/pairing-coordinator.js";
import {
  DistributedRateLimiter,
  InMemoryRateLimiterNamespace
} from "../src/security/distributed-rate-limiter.js";
import {
  enforceAccountRateLimit,
  enforceUserRateLimit,
  enforceDeviceRateLimit,
  enforceIpRateLimit
} from "../src/security/request-guard.js";
import {
  isBlockedIp,
  isBlockedIpv4,
  isBlockedIpv6,
  isBlockedHostname,
  validateSafeEndpoint,
  safeFetchWithDnsRebindingGuard
} from "../src/security/ssrf-guard.js";

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
      return { keys: keys.slice(0, limit), list_complete: true };
    }
  };
}

test("R2-001 [Distributed Pairing Redemption]: Two concurrent redemptions from different execution contexts only produce one success", async () => {
  const kv = createMockKv();
  const coordinatorNs = new InMemoryPairingCoordinatorNamespace();
  const envIsolate1 = {
    DEVICE_REGISTRY: kv,
    PAIRING_COORDINATOR: coordinatorNs,
    DEVICE_TOKEN_SECRET: "device-token-secret-for-r2-testing-32bytes!"
  };
  const envIsolate2 = {
    DEVICE_REGISTRY: kv,
    PAIRING_COORDINATOR: coordinatorNs,
    DEVICE_TOKEN_SECRET: "device-token-secret-for-r2-testing-32bytes!"
  };

  const accountId = "acct-user-1234567890ab";
  const session = await createPairingSession({ kv, accountId, env: envIsolate1 });
  assert.ok(session.code);

  let registeredCount = 0;
  const mockSaveDevice = async (input) => {
    registeredCount++;
    return { ...input, tokenVersion: 1 };
  };
  const mockIssueToken = async (deviceId) => `token_${deviceId}`;

  // Fire two concurrent redemption requests from separate execution contexts (envIsolate1 vs envIsolate2)
  const results = await Promise.allSettled([
    redeemPairingSession({
      kv,
      code: session.code,
      deviceInput: { platform: "windows", name: "Laptop 1", deviceId: "win-device-1" },
      saveDevice: mockSaveDevice,
      issueDeviceToken: mockIssueToken,
      tokenSecret: envIsolate1.DEVICE_TOKEN_SECRET,
      env: envIsolate1
    }),
    redeemPairingSession({
      kv,
      code: session.code,
      deviceInput: { platform: "android", name: "Phone 2", deviceId: "android-device-2" },
      saveDevice: mockSaveDevice,
      issueDeviceToken: mockIssueToken,
      tokenSecret: envIsolate2.DEVICE_TOKEN_SECRET,
      env: envIsolate2
    })
  ]);

  const fulfilled = results.filter((r) => r.status === "fulfilled");
  const rejected = results.filter((r) => r.status === "rejected");

  assert.equal(fulfilled.length, 1, "Exactly one concurrent redemption must succeed");
  assert.equal(rejected.length, 1, "The second concurrent redemption must be rejected");
  assert.equal(registeredCount, 1, "Only one device may be registered for the single-use pairing code");
  assert.match(rejected[0].reason.message, /already been used|redemption already in progress/i);

  // Subsequent redemption attempt must also be rejected
  await assert.rejects(
    () =>
      redeemPairingSession({
        kv,
        code: session.code,
        deviceInput: { platform: "macos", name: "Mac 3" },
        saveDevice: mockSaveDevice,
        issueDeviceToken: mockIssueToken,
        tokenSecret: envIsolate1.DEVICE_TOKEN_SECRET,
        env: envIsolate1
      }),
    /already been used/i
  );
});

test("R2-001 [Pairing Error Recovery]: Failed request does not leave pairing session in corrupted state", async () => {
  const kv = createMockKv();
  const coordinatorNs = new InMemoryPairingCoordinatorNamespace();
  const env = {
    DEVICE_REGISTRY: kv,
    PAIRING_COORDINATOR: coordinatorNs,
    DEVICE_TOKEN_SECRET: "device-token-secret-for-r2-testing-32bytes!"
  };

  const accountId = "acct-user-recovery-test";
  const session = await createPairingSession({ kv, accountId, env });

  // 1. Attempt redemption with unsupported platform -> throws error
  await assert.rejects(
    () =>
      redeemPairingSession({
        kv,
        code: session.code,
        deviceInput: { platform: "smart_fridge_os", name: "Fridge" }, // invalid platform
        saveDevice: async () => {},
        issueDeviceToken: async () => {},
        tokenSecret: env.DEVICE_TOKEN_SECRET,
        env
      }),
    /Unsupported pairing platform/i
  );

  // 2. Proves recovery: Session is NOT stuck in "redeeming" or corrupted, legitimate retry succeeds!
  const retryResult = await redeemPairingSession({
    kv,
    code: session.code,
    deviceInput: { platform: "android", name: "ROG Phone 6" },
    saveDevice: async (input) => ({ ...input, tokenVersion: 1 }),
    issueDeviceToken: async (id) => `token_${id}`,
    tokenSecret: env.DEVICE_TOKEN_SECRET,
    env
  });

  assert.equal(retryResult.ok, true);
  assert.equal(retryResult.device.platform, "android");
});

test("R2-002 [Distributed Rate Limiting]: Rate limit cannot be bypassed by hopping execution contexts or concurrent bursts", async () => {
  const limiterNs = new InMemoryRateLimiterNamespace();
  const kv = createMockKv();

  // Create 3 simulated distinct Worker isolates sharing the same Durable Object namespace
  const isolate1 = { RATE_LIMITER: limiterNs, DEVICE_REGISTRY: kv };
  const isolate2 = { RATE_LIMITER: limiterNs, DEVICE_REGISTRY: kv };
  const isolate3 = { RATE_LIMITER: limiterNs, DEVICE_REGISTRY: kv };

  const userId = "user-ratelimit-distributed-123";
  const limit = 5;

  // Dispatch 5 concurrent requests spread across isolates 1, 2, and 3
  const calls = [
    enforceAccountRateLimit(isolate1, "ai_chat", userId, limit),
    enforceAccountRateLimit(isolate2, "ai_chat", userId, limit),
    enforceAccountRateLimit(isolate3, "ai_chat", userId, limit),
    enforceAccountRateLimit(isolate1, "ai_chat", userId, limit),
    enforceAccountRateLimit(isolate2, "ai_chat", userId, limit)
  ];

  const results = await Promise.all(calls);
  for (let i = 0; i < limit; i++) {
    assert.equal(results[i].allowed, true, `Call ${i + 1} of ${limit} should be allowed`);
  }

  // 6th call on isolate3 must be blocked!
  const sixthCall = await enforceAccountRateLimit(isolate3, "ai_chat", userId, limit);
  assert.equal(sixthCall.allowed, false, "6th call across any isolate must be blocked");
  assert.ok(sixthCall.retryAfter > 0);

  // Dedicated helpers: enforceUserRateLimit, enforceDeviceRateLimit, enforceIpRateLimit
  const userRate = await enforceUserRateLimit(isolate1, "u1", "chat", 2);
  assert.equal(userRate.allowed, true);
  const deviceRate = await enforceDeviceRateLimit(isolate2, "d1", "poll", 2);
  assert.equal(deviceRate.allowed, true);
  const ipRate = await enforceIpRateLimit(isolate3, "198.51.100.1", "web", 2);
  assert.equal(ipRate.allowed, true);
});

test("R2-003 [SSRF CIDR & IP Validation]: Blocks loopback, private, link-local, cloud metadata, multicast, and IPv6 variants", async () => {
  // IPv4 blocked checks
  assert.equal(isBlockedIp("127.0.0.1"), true, "IPv4 loopback must be blocked");
  assert.equal(isBlockedIp("127.12.34.56"), true, "Entire 127.0.0.0/8 must be blocked");
  assert.equal(isBlockedIp("10.0.0.1"), true, "RFC 1918 10.0.0.0/8 must be blocked");
  assert.equal(isBlockedIp("172.16.0.1"), true, "RFC 1918 172.16.0.0/12 must be blocked");
  assert.equal(isBlockedIp("172.31.255.255"), true, "RFC 1918 172.31.255.255 must be blocked");
  assert.equal(isBlockedIp("192.168.1.1"), true, "RFC 1918 192.168.0.0/16 must be blocked");
  assert.equal(isBlockedIp("169.254.169.254"), true, "Cloud metadata 169.254.169.254 must be blocked");
  assert.equal(isBlockedIp("100.64.0.1"), true, "Carrier-grade NAT 100.64.0.0/10 must be blocked");
  assert.equal(isBlockedIp("0.0.0.0"), true, "0.0.0.0/8 must be blocked");
  assert.equal(isBlockedIp("224.0.0.1"), true, "Multicast 224.0.0.0/4 must be blocked");
  assert.equal(isBlockedIp("240.0.0.1"), true, "Reserved 240.0.0.0/4 must be blocked");

  // IPv6 blocked checks
  assert.equal(isBlockedIp("::1"), true, "IPv6 loopback ::1 must be blocked");
  assert.equal(isBlockedIp("::"), true, "IPv6 unspecified :: must be blocked");
  assert.equal(isBlockedIp("fe80::1"), true, "IPv6 link-local fe80::/10 must be blocked");
  assert.equal(isBlockedIp("fc00::1"), true, "IPv6 ULA fc00::/7 must be blocked");
  assert.equal(isBlockedIp("fd12:3456:789a::1"), true, "IPv6 ULA fd00:: must be blocked");
  assert.equal(isBlockedIp("::ffff:127.0.0.1"), true, "IPv4-mapped IPv6 loopback must be blocked");
  assert.equal(isBlockedIp("::ffff:169.254.169.254"), true, "IPv4-mapped cloud metadata must be blocked");

  // Public IP addresses must be allowed
  assert.equal(isBlockedIp("1.1.1.1"), false, "Public Cloudflare DNS 1.1.1.1 must be allowed");
  assert.equal(isBlockedIp("8.8.8.8"), false, "Public Google DNS 8.8.8.8 must be allowed");
  assert.equal(isBlockedIp("93.184.216.34"), false, "Public example.com IP must be allowed");
  assert.equal(isBlockedIp("2606:4700:4700::1111"), false, "Public Cloudflare IPv6 must be allowed");

  // Hostnames
  assert.equal(isBlockedHostname("localhost"), true);
  assert.equal(isBlockedHostname("api.internal"), true);
  assert.equal(isBlockedHostname("service.local"), true);
  assert.equal(isBlockedHostname("metadata.google.internal"), true);
});

test("R2-003 [SSRF DNS Resolution]: Rejects hostnames resolving to internal IP addresses (fail-closed)", async () => {
  // Mock DNS resolver pointing malicious-domain.com to cloud metadata IP
  const customDnsResolver = async (host) => {
    if (host === "rebind.attacker.com") return ["169.254.169.254"];
    if (host === "internal.attacker.com") return ["10.0.0.5"];
    if (host === "loopback.attacker.com") return ["::1"];
    if (host === "safe.custom-api.com") return ["93.184.216.34"];
    return [];
  };

  // 1. Hostname resolving to metadata IP
  const res1 = await validateSafeEndpoint("https://rebind.attacker.com/v1/chat", {
    dnsResolver: customDnsResolver
  });
  assert.equal(res1.ok, false);
  assert.match(res1.reason, /resolves to blocked internal\/private IP '169.254.169.254'/);

  // 2. Hostname resolving to RFC 1918 private IP
  const res2 = await validateSafeEndpoint("https://internal.attacker.com/models", {
    dnsResolver: customDnsResolver
  });
  assert.equal(res2.ok, false);
  assert.match(res2.reason, /resolves to blocked internal\/private IP '10.0.0.5'/);

  // 3. Hostname resolving to IPv6 loopback
  const res3 = await validateSafeEndpoint("https://loopback.attacker.com/models", {
    dnsResolver: customDnsResolver
  });
  assert.equal(res3.ok, false);
  assert.match(res3.reason, /resolves to blocked internal\/private IP '::1'/);

  // 4. Hostname resolving to legitimate public IP
  const res4 = await validateSafeEndpoint("https://safe.custom-api.com/v1/chat", {
    dnsResolver: customDnsResolver
  });
  assert.equal(res4.ok, true);
  assert.equal(res4.hostname, "safe.custom-api.com");
});

test("R2-003 [DNS Rebinding Defense]: Detects dynamic DNS flip to internal IP at connection time and blocks connection", async () => {
  let dnsLookupCount = 0;
  // Simulates DNS Rebinding: 1st lookup returns benign public IP, 2nd lookup flips to cloud metadata!
  const rebindingResolver = async (host) => {
    dnsLookupCount++;
    if (dnsLookupCount === 1) {
      return ["93.184.216.34"]; // Initial validation: benign public IP
    }
    return ["169.254.169.254"]; // Connection time: flipped to metadata IP!
  };

  let actualFetchCalled = false;
  const mockFetch = async () => {
    actualFetchCalled = true;
    return new Response(JSON.stringify({ ok: true }));
  };

  // Execution must detect DNS rebinding and abort before connection!
  await assert.rejects(
    () =>
      safeFetchWithDnsRebindingGuard(
        "https://dynamic-rebind.target.com/v1/complete",
        { method: "POST" },
        { dnsResolver: rebindingResolver, fetchImpl: mockFetch }
      ),
    /DNS Rebinding detected! Host 'dynamic-rebind.target.com' resolved to blocked IP/
  );

  assert.equal(actualFetchCalled, false, "Connection to rebinding target must NEVER be established");
});

test("R2-004 [Pairing Coordinator State]: Coordinator lease and terminal state transitions remain consistent", async () => {
  const coordinatorNs = new InMemoryPairingCoordinatorNamespace();
  const coordinator = coordinatorNs.get({ name: "TESTCODE" });

  const state1 = await coordinator.getState();
  assert.equal(state1.status, "available");
  assert.equal(state1.attempts, 0);

  // Lock acquisition
  const acq = await coordinator.acquireLock({ code: "TESTCODE", deviceId: "dev-1", nowMs: 1000 });
  assert.equal(acq.ok, true);
  assert.equal(acq.status, "lock_acquired");

  // Concurrent lock attempt while locked -> conflict
  const acqConflict = await coordinator.acquireLock({ code: "TESTCODE", deviceId: "dev-2", nowMs: 2000 });
  assert.equal(acqConflict.ok, false);
  assert.equal(acqConflict.status, "concurrent_conflict");

  // Commit
  const commitRes = await coordinator.commit({ code: "TESTCODE", deviceId: "dev-1", fencingToken: acq.fencingToken, nowMs: 3000 });
  assert.equal(commitRes.ok, true);
  assert.equal(commitRes.status, "redeemed");

  // Further attempts after commit -> redeemed
  const acqAfterCommit = await coordinator.acquireLock({ code: "TESTCODE", deviceId: "dev-3", nowMs: 4000 });
  assert.equal(acqAfterCommit.ok, false);
  assert.equal(acqAfterCommit.status, "redeemed");
});

test("rate limiter rejects a cost that would exceed the configured limit", async () => {
  const limiter = new DistributedRateLimiter();
  const first = await limiter.checkLimit({ scope: "cost-bound", subject: "user-a", limit: 5, cost: 4, nowMs: 1000 });
  assert.equal(first.allowed, true);
  const second = await limiter.checkLimit({ scope: "cost-bound", subject: "user-a", limit: 5, cost: 2, nowMs: 1001 });
  assert.equal(second.allowed, false);
  assert.equal(second.count, 4);
});

test("stale pairing lease cannot commit or roll back a newer lease", async () => {
  const coordinator = new PairingCoordinator();
  const first = await coordinator.acquireLock({ code: "FENCE001", deviceId: "dev-a", nowMs: 1000, ttlSeconds: 5 });
  assert.equal(first.ok, true);
  const second = await coordinator.acquireLock({ code: "FENCE001", deviceId: "dev-b", nowMs: 7000, ttlSeconds: 30 });
  assert.equal(second.ok, true);
  assert.notEqual(first.fencingToken, second.fencingToken);

  const staleCommit = await coordinator.commit({ code: "FENCE001", deviceId: "dev-a", fencingToken: first.fencingToken, nowMs: 7100 });
  assert.equal(staleCommit.ok, false);
  assert.equal(staleCommit.status, "stale_lease");

  const staleRollback = await coordinator.rollback({ code: "FENCE001", deviceId: "dev-a", fencingToken: first.fencingToken, nowMs: 7101 });
  assert.equal(staleRollback.ok, false);
  assert.equal(staleRollback.status, "stale_lease");

  const currentCommit = await coordinator.commit({ code: "FENCE001", deviceId: "dev-b", fencingToken: second.fencingToken, nowMs: 7200 });
  assert.equal(currentCommit.ok, true);
  assert.equal(currentCommit.status, "redeemed");
});
