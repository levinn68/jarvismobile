/**
 * Distributed Pairing Coordinator (Cloudflare Durable Objects) — B8.1.1 R2
 *
 * Implements atomic single-redemption locking across multiple Cloudflare Worker isolates.
 * Prevents race conditions where two concurrent requests from different execution contexts
 * could redeem the same pairing code or register duplicate devices.
 */

export class PairingCoordinator {
  constructor(state, env = {}) {
    this.state = state;
    this.env = env;
    this.initialized = false;
    this.data = {
      status: "available", // available | redeeming | redeemed | locked | cancelled
      attempts: 0,
      leaseExpiresAt: 0,
      leaseGeneration: 0,
      claimingDeviceId: null,
      redeemedAt: null,
      redeemedDeviceId: null,
      createdAt: null
    };
  }

  async initialize() {
    if (this.initialized) return;
    if (this.state?.storage) {
      const stored = await this.state.storage.get("session_data");
      if (stored && typeof stored === "object") {
        this.data = { ...this.data, ...stored };
      }
    }
    this.initialized = true;
  }

  async persist() {
    if (this.state?.storage) {
      await this.state.storage.put("session_data", this.data);
    }
  }

  async acquireLock(payload = {}) {
    await this.initialize();
    const nowMs = Number(payload.nowMs) || Date.now();
    const ttlSeconds = Math.max(5, Number(payload.ttlSeconds) || 30);
    const deviceId = payload.deviceId ? String(payload.deviceId).trim() : null;

    if (this.data.status === "locked") {
      return {
        ok: false,
        status: "locked",
        error: "Pairing code locked after too many attempts"
      };
    }

    if (this.data.status === "redeemed") {
      return {
        ok: false,
        status: "redeemed",
        error: "Pairing code has already been used"
      };
    }

    if (this.data.status === "cancelled") {
      return {
        ok: false,
        status: "cancelled",
        error: "Pairing session has been cancelled"
      };
    }

    if (this.data.status === "redeeming") {
      if (nowMs < this.data.leaseExpiresAt) {
        return {
          ok: false,
          status: "concurrent_conflict",
          error: "Pairing code redemption already in progress by another request"
        };
      }
      // Lease has expired; allow recovery / retry from expired in-flight request
    }

    // Increment a monotonic fencing token whenever a lease is acquired or recovered.
    this.data.leaseGeneration = Number(this.data.leaseGeneration || 0) + 1;
    const fencingToken = this.data.leaseGeneration;
    this.data.status = "redeeming";
    this.data.leaseExpiresAt = nowMs + ttlSeconds * 1000;
    this.data.claimingDeviceId = deviceId;
    await this.persist();

    return {
      ok: true,
      status: "lock_acquired",
      leaseExpiresAt: this.data.leaseExpiresAt,
      fencingToken
    };
  }

  isCurrentLease(payload = {}, nowMs = Date.now()) {
    const token = Number(payload.fencingToken);
    const owner = payload.deviceId ? String(payload.deviceId).trim() : null;
    return this.data.status === "redeeming" &&
      Number.isFinite(token) &&
      token === Number(this.data.leaseGeneration) &&
      nowMs < Number(this.data.leaseExpiresAt || 0) &&
      (!owner || !this.data.claimingDeviceId || owner === this.data.claimingDeviceId);
  }

  async commit(payload = {}) {
    await this.initialize();
    const nowMs = Number(payload.nowMs) || Date.now();
    if (!this.isCurrentLease(payload, nowMs)) {
      return { ok: false, status: "stale_lease", error: "Pairing lease is stale or no longer owned by this request" };
    }
    this.data.status = "redeemed";
    this.data.redeemedAt = new Date(nowMs).toISOString();
    this.data.redeemedDeviceId = payload.deviceId ? String(payload.deviceId).trim() : null;
    this.data.leaseExpiresAt = 0;
    this.data.claimingDeviceId = null;
    await this.persist();

    return {
      ok: true,
      status: "redeemed",
      redeemedAt: this.data.redeemedAt,
      deviceId: this.data.redeemedDeviceId
    };
  }

  async rollback(payload = {}) {
    await this.initialize();
    const nowMs = Number(payload.nowMs) || Date.now();
    if (!this.isCurrentLease(payload, nowMs)) {
      return { ok: false, status: "stale_lease", error: "Pairing lease is stale or no longer owned by this request" };
    }
    const isAttemptFailed = payload.isAttemptFailed !== false;
    const maxAttempts = Number(payload.maxAttempts) || 8;

    if (isAttemptFailed) this.data.attempts = (this.data.attempts || 0) + 1;
    this.data.status = this.data.attempts >= maxAttempts ? "locked" : "available";
    this.data.leaseExpiresAt = 0;
    this.data.claimingDeviceId = null;
    await this.persist();

    return { ok: true, status: this.data.status, attempts: this.data.attempts };
  }

  async cancel(payload = {}) {
    await this.initialize();
    this.data.status = "cancelled";
    this.data.leaseExpiresAt = 0;
    await this.persist();

    return {
      ok: true,
      status: "cancelled"
    };
  }

  async getState() {
    await this.initialize();
    return { ok: true, ...this.data };
  }

  async fetch(request) {
    const url = new URL(request.url);
    const body = request.method === "POST" ? await request.json().catch(() => ({})) : {};

    if (url.pathname === "/acquire") {
      const res = await this.acquireLock(body);
      const status = res.ok ? 200 : res.status === "concurrent_conflict" ? 409 : 400;
      return new Response(JSON.stringify(res), {
        status,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (url.pathname === "/commit") {
      const res = await this.commit(body);
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (url.pathname === "/rollback") {
      const res = await this.rollback(body);
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (url.pathname === "/cancel") {
      const res = await this.cancel(body);
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (url.pathname === "/state") {
      const res = await this.getState();
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    return new Response(JSON.stringify({ error: "Not found" }), { status: 404 });
  }
}

/**
 * In-memory simulated Durable Object namespace for test environments
 * and multi-context execution tests without full Miniflare runtime.
 */
export class InMemoryPairingCoordinatorNamespace {
  constructor() {
    this.instances = new Map();
  }

  idFromName(name) {
    return { name: String(name).toUpperCase() };
  }

  get(id) {
    const key = id.name || String(id);
    if (!this.instances.has(key)) {
      const mockStorage = new Map();
      const state = {
        storage: {
          async get(k) {
            return mockStorage.get(k) || null;
          },
          async put(k, v) {
            mockStorage.set(k, v);
          },
          async delete(k) {
            mockStorage.delete(k);
          }
        }
      };
      this.instances.set(key, new PairingCoordinator(state));
    }
    const instance = this.instances.get(key);
    return {
      async acquireLock(payload) {
        return await instance.acquireLock(payload);
      },
      async commit(payload) {
        return await instance.commit(payload);
      },
      async rollback(payload) {
        return await instance.rollback(payload);
      },
      async cancel(payload) {
        return await instance.cancel(payload);
      },
      async getState() {
        return await instance.getState();
      },
      async fetch(input, init) {
        const req = input instanceof Request ? input : new Request(input, init);
        return await instance.fetch(req);
      }
    };
  }
}

// Global registry instance shared across simulated isolate boundaries in Node.js
export const globalPairingCoordinator = new InMemoryPairingCoordinatorNamespace();