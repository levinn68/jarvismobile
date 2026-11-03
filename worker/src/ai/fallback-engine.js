/**
 * Sequential provider fallback with per-attempt timeout, exponential backoff
 * on retryable failures (429/5xx/network), and structured diagnostics.
 * Never logs API keys.
 */
export function isRetryableProviderError(error) {
  const message = String(error?.message || error || "").toLowerCase();
  const status = Number(String(error?.status || message.match(/http\s+(\d{3})/)?.[1] || 0));
  if (status === 401 || status === 403) return false;
  if (status === 429 || status >= 500) return true;
  return /timeout|network|fetch failed|quota|rate.?limit|resource.?exhausted|econnreset|etimedout|abort/.test(message);
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

/**
 * @param {object} options
 * @param {string[]} options.providers ordered provider names
 * @param {(provider:string)=>Promise<any>} options.invoke
 * @param {(error:any)=>boolean} [options.isRetryable]
 * @param {(event:object)=>void} [options.onAttempt]
 * @param {number} [options.timeoutMs] per-provider timeout
 * @param {number} [options.maxAttemptsPerProvider]
 * @param {number} [options.baseBackoffMs]
 */
export async function runWithFallback({
  providers = [],
  invoke,
  isRetryable = isRetryableProviderError,
  onAttempt = () => {},
  timeoutMs = 45000,
  maxAttemptsPerProvider = 2,
  baseBackoffMs = 400
} = {}) {
  if (typeof invoke !== "function") {
    throw new Error("invoke callback is required");
  }
  const errors = [];
  const list = Array.isArray(providers) ? providers.filter(Boolean) : [];

  for (const provider of list) {
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
          error: String(error?.message || error).slice(0, 240),
          status: Number(error?.status || 0) || undefined
        };
        errors.push(item);
        onAttempt({ provider, status: "failed", attempt, error: item.error });
        const retryable = isRetryable(error);
        if (!retryable || attempt >= maxAttemptsPerProvider) break;
        const delay = Math.min(baseBackoffMs * Math.pow(2, attempt - 1), 8000);
        await sleep(delay);
      }
    }
    if (lastError && !isRetryable(lastError) && list.indexOf(provider) === list.length - 1) {
      break;
    }
  }
  return { ok: false, errors };
}
