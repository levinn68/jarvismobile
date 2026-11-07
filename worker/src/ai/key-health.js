import { redactSecrets } from "../utils.js";

export function classifyProviderError(error) {
  const message = redactSecrets(String(error?.message || error || "")).toLowerCase();
  const explicitStatus = Number(error?.status || 0);
  const matchedStatus = Number(String(message).match(/http\s+(\d{3})/)?.[1] || 0);
  const status = explicitStatus || matchedStatus || 0;

  if (status === 401 || status === 403 || /unauthorized|invalid.?api.?key|forbidden|authentication/i.test(message)) {
    return { kind: "auth", retryable: false, rotateKey: true, status: status || 401 };
  }
  if (status === 400 || status === 404 || status === 422 || /invalid.?request|bad.?request|malformed|model.?not.?found/i.test(message)) {
    return { kind: "permanent", retryable: false, rotateKey: false, status: status || 400 };
  }
  if (status === 429 || /quota|rate.?limit|resource.?exhausted|too many requests/i.test(message)) {
    return { kind: "quota", retryable: true, rotateKey: true, status: status || 429 };
  }
  if (status >= 500 || /timeout|timed out|network|fetch failed|econnreset|etimedout|abort|overloaded|unavailable/i.test(message)) {
    return { kind: "transient", retryable: true, rotateKey: true, status: status || 503 };
  }
  return { kind: "unknown", retryable: false, rotateKey: false, status };
}
