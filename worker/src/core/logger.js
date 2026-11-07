import { redactSecrets } from "../utils.js";

/**
 * Structured, secret-redacting logger for Cloudflare Worker observability.
 * Ensures correlation IDs, latency, and sanitized error details are logged
 * consistently without ever exposing tokens or API keys.
 */
export function createLogger({ service = "jarvis-api", sink = console } = {}) {
  function emit(level, event, fields = {}) {
    const sanitized = {};
    for (const [k, v] of Object.entries(fields || {})) {
      if (v === undefined) continue;
      if (typeof v === "string") {
        sanitized[k] = redactSecrets(v).slice(0, 500);
      } else if (v instanceof Error) {
        sanitized[k] = redactSecrets(v.message || String(v)).slice(0, 500);
      } else {
        try {
          sanitized[k] = JSON.parse(redactSecrets(JSON.stringify(v)));
        } catch (_) {
          sanitized[k] = String(v).slice(0, 200);
        }
      }
    }
    const payload = {
      timestamp: new Date().toISOString(),
      level,
      service,
      event: String(event || "log"),
      ...sanitized
    };
    const fn = level === "error" ? sink.error : level === "warn" ? sink.warn : sink.log;
    if (typeof fn === "function") {
      fn.call(sink, JSON.stringify(payload));
    }
    return payload;
  }

  return {
    info: (event, fields) => emit("info", event, fields),
    warn: (event, fields) => emit("warn", event, fields),
    error: (event, fields) => emit("error", event, fields)
  };
}

export const logger = createLogger();
