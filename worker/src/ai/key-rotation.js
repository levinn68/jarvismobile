export function createKeyRotator({
  cooldownMs = 30000,
  maxFailures = 2,
  circuitBreakerThreshold = 4,
  circuitOpenMs = 60000
} = {}) {
  const state = new Map();
  const providerCircuit = new Map();

  return {
    order(provider, keyNames, now = Date.now()) {
      return [...keyNames].sort(
        (a, b) => (state.get(provider + ":" + a)?.nextAt || 0) - (state.get(provider + ":" + b)?.nextAt || 0)
      );
    },
    isProviderOpen(provider, now = Date.now()) {
      const circuit = providerCircuit.get(provider);
      if (!circuit) return false;
      if (circuit.openUntil > now) return true;
      return false;
    },
    success(provider, key) {
      state.delete(provider + ":" + key);
      providerCircuit.delete(provider);
    },
    failure(provider, key, { status = 0, now = Date.now() } = {}) {
      const id = provider + ":" + key;
      const old = state.get(id) || { failures: 0, nextAt: 0 };
      old.failures += 1;
      const baseCooldown =
        status === 401 || status === 403
          ? Math.max(cooldownMs * 4, 300000)
          : status === 429
            ? Math.max(cooldownMs * 2, 60000)
            : cooldownMs * Math.min(old.failures, maxFailures);
      old.nextAt = now + baseCooldown;
      old.lastStatus = status || undefined;
      state.set(id, old);

      const pState = providerCircuit.get(provider) || { consecutiveFailures: 0, openUntil: 0 };
      pState.consecutiveFailures += 1;
      if (pState.consecutiveFailures >= circuitBreakerThreshold) {
        pState.openUntil = now + circuitOpenMs;
      }
      providerCircuit.set(provider, pState);
    },
    reset(provider) {
      if (!provider) {
        state.clear();
        providerCircuit.clear();
        return;
      }
      providerCircuit.delete(provider);
      for (const k of state.keys()) {
        if (k.startsWith(provider + ":")) state.delete(k);
      }
    },
    snapshot() {
      return [...state.entries()].map(([id, value]) => ({ id, ...value }));
    },
    circuitSnapshot(now = Date.now()) {
      return [...providerCircuit.entries()].map(([provider, value]) => ({        provider,
        consecutiveFailures: value.consecutiveFailures,
        open: value.openUntil > now,
        openUntil: value.openUntil
      }));
    },
    async loadFromKv(kv, now = Date.now()) {
      if (!kv || typeof kv.get !== "function") return false;
      try {
        const raw = await kv.get("ai:rotator:state:v1");
        if (!raw) return false;
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed?.keys)) {
          for (const item of parsed.keys) {
            if (item?.id && item.nextAt > now) {
              state.set(item.id, { failures: item.failures || 1, nextAt: item.nextAt, lastStatus: item.lastStatus });
            }
          }
        }
        if (Array.isArray(parsed?.circuits)) {
          for (const item of parsed.circuits) {
            if (item?.provider && item.openUntil > now) {
              providerCircuit.set(item.provider, {
                consecutiveFailures: item.consecutiveFailures || circuitBreakerThreshold,
                openUntil: item.openUntil
              });
            }
          }
        }
        return true;
      } catch (_) {
        return false;
      }
    },
    async saveToKv(kv, now = Date.now()) {
      if (!kv || typeof kv.put !== "function") return false;
      try {
        const keys = [...state.entries()]
          .filter(([, v]) => v.nextAt > now)
          .map(([id, v]) => ({ id, failures: v.failures, nextAt: v.nextAt, lastStatus: v.lastStatus }));
        const circuits = [...providerCircuit.entries()]
          .filter(([, v]) => v.openUntil > now || v.consecutiveFailures > 0)
          .map(([provider, v]) => ({ provider, consecutiveFailures: v.consecutiveFailures, openUntil: v.openUntil }));
        await kv.put("ai:rotator:state:v1", JSON.stringify({ keys, circuits, updatedAt: now }), {
          expirationTtl: 600
        });
        return true;
      } catch (_) {
        return false;
      }
    }
  };
}