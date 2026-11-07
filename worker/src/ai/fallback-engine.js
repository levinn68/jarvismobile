import { redactSecrets } from "../utils.js";

/**
 * Sequential provider fallback with per-attempt timeout, exponential backoff
 * with bounded jitter on retryable failures (429/5xx/network), circuit-breaker
 * awareness, and structured diagnostics. Never logs API keys.
 */
export function isRetryableProviderError(error) {
  const message = String(error?.message || error || "").toLowerCase();
  const status = Number(String(error?.status || message.match(/http\s+(\d{3})/)?.[1] || 0));
  if (status === 400 || status === 401 || status === 403 || status === 404 || status === 422) return false;
  if (status === 429 || status >= 500) return true;
  return /timeout|timed out|network|fetch failed|quota|rate.?limit|resource.?exhausted|econnreset|etimedout|abort/.test(message);
}

export function computeBackoffWithJitter(attempt, baseBackoffMs = 400, maxBackoffMs = 8000, randomFn = Math.random) {
  const exp = Math.min(maxBackoffMs, baseBackoffMs * Math.pow(2, Math.max(0, attempt - 1)));
  const jitter = Math.floor(Math.max(0, Math.min(1, randomFn())) * Math.max(1, Math.floor(exp * 0.25)));
  return Math.min(maxBackoffMs, exp + jitter);
}

export async function withTimeout(promise, ms, label = "operation") {
  const timeoutMs = Math.max(1000, Number(ms) || 30000);
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => {
          const err = new Error(label + " timed out after " + timeoutMs + "ms");
          err.status = 504;
          reject(err);
        }, timeoutMs);
      })
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function runWithFallback({
  providers = [],
  invoke,
  isRetryable = isRetryableProviderError,
  isCircuitOpen = () => false,
  onAttempt = () => {},
  timeoutMs = 45000,
  maxAttemptsPerProvider = 2,
  baseBackoffMs = 400,
  jitterFn = Math.random
} = {}) {
  if (typeof invoke !== "function") {
    throw new Error("invoke callback is required");
  }
  const errors = [];
  const list = Array.isArray(providers) ? providers.filter(Boolean) : [];

  for (const provider of list) {
    if (isCircuitOpen(provider)) {
      const skipped = {
        provider,
        attempt: 0,
        error: "Circuit breaker open for provider " + provider,
        circuitOpen: true
      };
      errors.push(skipped);
      onAttempt({ provider, status: "circuit_open", attempt: 0, error: skipped.error });
      continue;
    }
    let lastError = null;
    for (let attempt = 1; attempt <= maxAttemptsPerProvider; attempt++) {
      onAttempt({ provider, status: "started", attempt });
      try {
        const result = await withTimeout(Promise.resolve(invoke(provider)), timeoutMs, provider);
        onAttempt({ provider, status: "success", attempt });
        return { ok: true, provider, result, errors, attempts: errors.length + 1 };
      } catch (error) {
        lastError = error;
        const item = {
          provider,
          attempt,
          error: redactSecrets(String(error?.message || error)).slice(0, 240),
          status: Number(error?.status || 0) || undefined
        };
        errors.push(item);
        onAttempt({ provider, status: "failed", attempt, error: item.error });
        const retryable = isRetryable(error);
        if (!retryable || attempt >= maxAttemptsPerProvider) break;
        const delay = computeBackoffWithJitter(attempt, baseBackoffMs, 8000, jitterFn);
        await sleep(delay);
      }
    }
    if (lastError && !isRetryable(lastError) && list.indexOf(provider) === list.length - 1) {
      break;
    }
  }
  return { ok: false, errors };
}
