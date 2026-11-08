/**
 * Distributed Rate Limiter (Cloudflare Durable Objects) — B8.1.1 R2
 *
 * Implements atomic, distributed rate limiting across all Cloudflare Worker isolates.
 * Guarantees that rate limits cannot be bypassed by hopping execution contexts,
 * distributing requests across multiple edge locations, or sending concurrent bursts.
 */

export class DistributedRateLimiter {
  constructor(state, env = {}) {
    this.state = state;
    this.env = env;
    this.initialized = false;
    this.counters = new Map();
  }

  async initialize() {
    if (this.initialized) return;
    if (this.state?.storage) {
      const stored = await this.state.storage.get("counters");
      if (stored && typeof stored === "object") {
        for (const [k, v] of Object.entries(stored)) {
          this.counters.set(k, v);
        }
      }
    }
    this.initialized = true;
  }

  async persist() {
    if (this.state?.storage) {
      const obj = {};
      for (const [k, v] of this.counters.entries()) {
        obj[k] = v;
      }
      await this.state.storage.put("counters", obj);
    }
  }

  /**
   * Evaluates rate limit atomically.
   */
  async checkLimit(payload = {}) {
    await this.initialize();
    const scope = String(payload.scope || "default").replace(/[^a-zA-Z0-9_-]/g, "");
    const subject = String(payload.subject || "anonymous").replace(/[^a-zA-Z0-9_.:-]/g, "").slice(0, 100);
    const limit = Math.max(1, Number(payload.limit) || 60);
    const windowSeconds = Math.max(1, Number(payload.windowSeconds) || 60);
    const nowMs = Number(payload.nowMs) || Date.now();
    const cost = Math.max(1, Number(payload.cost) || 1);

    const windowIndex = Math.floor(nowMs / (windowSeconds * 1000));
    const partitionKey = `${scope}:${subject}`;
    const windowKey = `${partitionKey}:w${windowIndex}`;

    const currentCount = Number(this.counters.get(windowKey) || 0);

    if (currentCount + cost > limit) {
      const elapsedSecondsInWindow = Math.floor((nowMs / 1000) % windowSeconds);
      const retryAfter = Math.max(1, windowSeconds - elapsedSecondsInWindow);
      return {
        allowed: false,
        count: currentCount,
        limit,
        remaining: 0,
        retryAfter,
        scope,
        subject
      };
    }

    const nextCount = currentCount + cost;
    this.counters.set(windowKey, nextCount);

    // Prune stale window entries older than 2 windows for this partition
    for (const key of this.counters.keys()) {
      if (key.startsWith(`${partitionKey}:w`)) {
        const idx = parseInt(key.slice(`${partitionKey}:w`.length), 10);
        if (Number.isFinite(idx) && idx < windowIndex - 2) {
          this.counters.delete(key);
        }
      }
    }

    await this.persist();

    return {
      allowed: true,
      count: nextCount,
      limit,
      remaining: Math.max(0, limit - nextCount),
      retryAfter: 0,
      scope,
      subject
    };
  }

  async reset(payload = {}) {
    await this.initialize();
    const scope = String(payload.scope || "").trim();
    const subject = String(payload.subject || "").trim();

    if (scope && subject) {
      const partitionKey = `${scope}:${subject}`;
      for (const key of this.counters.keys()) {
        if (key.startsWith(`${partitionKey}:`)) {
          this.counters.delete(key);
        }
      }
    } else {
      this.counters.clear();
    }

    await this.persist();
    return { ok: true };
  }

  async fetch(request) {
    const url = new URL(request.url);
    const body = request.method === "POST" ? await request.json().catch(() => ({})) : {};

    if (url.pathname === "/check") {
      const res = await this.checkLimit(body);
      return new Response(JSON.stringify(res), {
        status: res.allowed ? 200 : 429,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (url.pathname === "/reset") {
      const res = await this.reset(body);
      return new Response(JSON.stringify(res), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }

    return new Response(JSON.stringify({ error: "Not found" }), { status: 404 });
  }
}

/**
 * In-memory simulated Durable Object namespace for testing multi-isolate rate limits.
 */
export class InMemoryRateLimiterNamespace {
  constructor() {
    this.instances = new Map();
  }

  idFromName(name) {
    return { name: String(name) };
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
      this.instances.set(key, new DistributedRateLimiter(state));
    }
    const instance = this.instances.get(key);
    return {
      async checkLimit(payload) {
        return await instance.checkLimit(payload);
      },
      async reset(payload) {
        return await instance.reset(payload);
      },
      async fetch(input, init) {
        const req = input instanceof Request ? input : new Request(input, init);
        return await instance.fetch(req);
      }
    };
  }
}

// Global registry instance shared across simulated isolate boundaries in Node.js
export const globalRateLimiter = new InMemoryRateLimiterNamespace();

/**
 * Invoke a Durable Object rate limiter through its fetch handler.
 * This works with the Worker stub API and remains compatible with in-memory test doubles.
 */
export async function checkRateLimiter(stub, payload = {}) {
  if (stub && typeof stub.checkLimit === "function") {
    return await stub.checkLimit(payload);
  }
  if (!stub || typeof stub.fetch !== "function") {
    throw new Error("RATE_LIMITER binding is unavailable or invalid");
  }
  const response = await stub.fetch(new Request("https://rate-limiter/check", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  }));
  const result = await response.json().catch(() => ({}));
  if (!response.ok && result?.allowed !== false) {
    throw new Error(result?.error || "Rate limiter request failed");
  }
  return result;
}
