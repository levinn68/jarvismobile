import { isRetryableProviderError, withTimeout, computeBackoffWithJitter } from "./fallback-engine.js";
import { classifyProviderError } from "./key-health.js";
import { redactSecrets } from "../utils.js";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Provider manager with key rotation, circuit breaker check, bounded per-key
 * retry on transient failures, backoff with jitter, and per-key timeout.
 * Key names only are tracked — never values.
 */
export function createProviderManager({
  providers = {},
  rotator,
  timeoutMs = 45000,
  baseBackoffMs = 200,
  maxAttemptsPerKey = 2
} = {}) {
  return {
    names() {
      return Object.keys(providers);
    },
    configured(env, provider) {
      return (providers[provider]?.keys || []).filter(
        (key) => typeof env?.[key] === "string" && env[key].trim()
      );
    },
    async invoke(provider, env, input) {
      const config = providers[provider];
      if (!config || typeof config.invoke !== "function") {
        throw new Error("Provider adapter unavailable: " + provider);
      }
      if (rotator?.isProviderOpen?.(provider)) {
        const err = new Error("Circuit breaker open for provider: " + provider);
        err.status = 503;
        throw err;
      }
      const keys = rotator
        ? rotator.order(provider, this.configured(env, provider))
        : this.configured(env, provider);
      if (!keys.length) throw new Error("No configured key for " + provider);

      let lastError;
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i];
        const hasMoreKeys = i + 1 < keys.length;
        // If only a single key is configured, allow bounded retry on transient 5xx/network errors.
        const attemptsForThisKey = hasMoreKeys ? 1 : Math.max(1, Number(maxAttemptsPerKey) || 1);

        for (let keyAttempt = 1; keyAttempt <= attemptsForThisKey; keyAttempt++) {
          try {
            const result = await withTimeout(
              Promise.resolve(config.invoke({ env, key, input })),
              timeoutMs,
              provider
            );
            rotator?.success(provider, key);
            return { provider, keyName: key, keySlot: i + 1, attempt: keyAttempt, result };
          } catch (error) {
            const sanitized = new Error(redactSecrets(error?.message || error));
            if (error?.status) sanitized.status = error.status;
            lastError = sanitized;
            const classification = classifyProviderError(sanitized);
            rotator?.failure(provider, key, { status: classification.status });

            // Permanent request/validation errors (e.g. 400/404/422) must never retry or rotate keys.
            if (classification.kind === "permanent") {
              throw sanitized;
            }

            // Auth errors (401/403) should not retry the same key, but can rotate to the next key.
            if (classification.kind === "auth") {
              break;
            }

            const retryable = classification.retryable || isRetryableProviderError(sanitized);
            if (!retryable) {
              break;
            }

            if (keyAttempt < attemptsForThisKey) {
              if (baseBackoffMs > 0) {
                await sleep(computeBackoffWithJitter(keyAttempt, baseBackoffMs, 2000));
              }
              continue;
            }
          }
        }

        const lastClass = classifyProviderError(lastError);
        const canTryNextKey =
          hasMoreKeys &&
          !rotator?.isProviderOpen?.(provider) &&
          (lastClass.rotateKey || isRetryableProviderError(lastError));
        if (!canTryNextKey) break;
        if (baseBackoffMs > 0) {
          await sleep(computeBackoffWithJitter(i + 1, baseBackoffMs, 2000));
        }
      }
      throw lastError || new Error("All keys failed for " + provider);
    }
  };
}