import { isRetryableProviderError, withTimeout } from "./fallback-engine.js";

/**
 * Provider manager with key rotation and per-key timeout.
 * Key names only are tracked — never values.
 */
export function createProviderManager({ providers = {}, rotator, timeoutMs = 45000 } = {}) {
  return {
    names() {
      return Object.keys(providers);
    },
    configured(env, provider) {
      return (providers[provider]?.keys || []).filter(
        (key) => typeof env[key] === "string" && env[key].trim()
      );
    },
    async invoke(provider, env, input) {
      const config = providers[provider];
      if (!config || typeof config.invoke !== "function") {
        throw new Error("Provider adapter unavailable: " + provider);
      }
      const keys = rotator
        ? rotator.order(provider, this.configured(env, provider))
        : this.configured(env, provider);
      if (!keys.length) throw new Error("No configured key for " + provider);

      let lastError;
      for (const key of keys) {
        try {
          const result = await withTimeout(
            Promise.resolve(config.invoke({ env, key, input })),
            timeoutMs,
            provider
          );
          rotator?.success(provider, key);
          return { provider, keyName: key, result };
        } catch (error) {
          lastError = error;
          const status = Number(
            String(error?.message || "").match(/HTTP (\d+)/)?.[1] || error?.status || 0
          );
          rotator?.failure(provider, key, { status });
          if (!isRetryableProviderError(error) && status !== 429 && status < 500) {
            // Auth failures on one key may still allow other keys; continue.
          }
        }
      }
      throw lastError || new Error("All keys failed for " + provider);
    }
  };
}
