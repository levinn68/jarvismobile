
/**
 * SSRF and DNS Rebinding Protection Guard (B8.1.1 R2)
 *
 * Implements strict CIDR validation for IPv4 and IPv6, DNS resolution checking,
 * and fail-closed pre-connection guards against DNS rebinding attacks on
 * custom AI provider endpoints and MCP servers.
 */

export const PROVIDER_ERROR_CODES_DEF = Object.freeze({
  PROVIDER_CREDENTIAL_MISSING: "PROVIDER_CREDENTIAL_MISSING",
  PROVIDER_DISABLED: "PROVIDER_DISABLED",
  NETWORK_ERROR: "NETWORK_ERROR",
  PROVIDER_TIMEOUT: "PROVIDER_TIMEOUT",
  PROVIDER_RATE_LIMITED: "PROVIDER_RATE_LIMITED",
  PROVIDER_AUTH_FAILED: "PROVIDER_AUTH_FAILED",
  MODEL_UNAVAILABLE: "MODEL_UNAVAILABLE",
  PROVIDER_ERROR: "PROVIDER_ERROR"
});

// Official trusted public provider domains that are guaranteed public frontier APIs
const TRUSTED_PUBLIC_PROVIDERS = new Set([
  "api.openai.com",
  "api.anthropic.com",
  "generativelanguage.googleapis.com",
  "api.groq.com",
  "api.deepseek.com",
  "openrouter.ai",
  "api.githubcopilot.com",
  "mcp.search.brave.com",
  "mcp.slack.com",
  "api.fish.audio",
  "serpapi.com"
]);

export function isTrustedProviderHostname(hostname, env = {}) {
  const host = String(hostname || "").toLowerCase().trim().replace(/^\[|\]$/g, "");
  if (TRUSTED_PUBLIC_PROVIDERS.has(host)) return true;
  const configured = String(env?.JARVIS_OUTBOUND_HOST_ALLOWLIST || "")
    .split(",")
    .map((item) => item.trim().toLowerCase())
    .filter(Boolean);
  return configured.includes(host);
}


// Hostnames and suffixes strictly blocked
const BLOCKED_HOSTNAMES = new Set([
  "localhost",
  "0.0.0.0",
  "127.0.0.1",
  "::1",
  "metadata.google.internal",
  "metadata.internal",
  "instance-data",
  "169.254.169.254"
]);

const BLOCKED_DOMAIN_SUFFIXES = [
  ".localhost",
  ".local",
  ".internal",
  ".arpa",
  ".invalid",
  ".test",
  ".example"
];

export function isIpLiteral(str) {
  const clean = String(str || "").trim().replace(/^\[|\]$/g, "");
  if (!clean) return false;
  if (/^(\d{1,3}\.){3}\d{1,3}$/.test(clean)) {
    const parts = clean.split(".").map(Number);
    return parts.every((p) => p >= 0 && p <= 255);
  }
  if (clean.includes(":")) {
    return ipv6ToBigInt(clean) !== null;
  }
  return false;
}

export function ipv4ToInt(ip) {
  const parts = String(ip || "").trim().split(".").map(Number);
  if (parts.length !== 4 || parts.some((p) => isNaN(p) || p < 0 || p > 255)) {
    return null;
  }
  return ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
}

export function isBlockedIpv4(ipStr) {
  const ip = ipv4ToInt(ipStr);
  if (ip === null) return false; // not an IPv4

  // 0.0.0.0/8 (Current network / "this" network)
  if ((ip >>> 24) === 0) return true;
  // 10.0.0.0/8 (RFC 1918 Private)
  if ((ip >>> 24) === 10) return true;
  // 100.64.0.0/10 (RFC 6598 Shared Address Space / CGNAT)
  if (((ip & 0xffc00000) >>> 0) === 0x64400000) return true;
  // 127.0.0.0/8 (Loopback)
  if ((ip >>> 24) === 127) return true;
  // 169.254.0.0/16 (RFC 3927 Link-Local / Cloud Metadata: AWS, GCP, Azure, OpenStack)
  if ((ip >>> 16) === ((169 << 8) | 254)) return true;
  // 172.16.0.0/12 (RFC 1918 Private: 172.16.0.0 - 172.31.255.255)
  if (((ip & 0xfff00000) >>> 0) === 0xac100000) return true;
  // 192.0.0.0/24 (IETF Protocol Assignments)
  if (((ip & 0xffffff00) >>> 0) === 0xc0000000) return true;
  // 192.0.2.0/24 (TEST-NET-1)
  if (((ip & 0xffffff00) >>> 0) === 0xc0000200) return true;
  // 192.168.0.0/16 (RFC 1918 Private)
  if (((ip & 0xffff0000) >>> 0) === 0xc0a80000) return true;
  // 198.18.0.0/15 (Network benchmark tests)
  if (((ip & 0xfffe0000) >>> 0) === 0xc6120000) return true;
  // 198.51.100.0/24 (TEST-NET-2)
  if (((ip & 0xffffff00) >>> 0) === 0xc6336400) return true;
  // 203.0.113.0/24 (TEST-NET-3)
  if (((ip & 0xffffff00) >>> 0) === 0xcb007100) return true;
  // 224.0.0.0/4 (Multicast: 224.0.0.0 - 239.255.255.255)
  if ((ip >>> 28) === 14) return true;
  // 240.0.0.0/4 (Reserved / Future use / Broadcast 255.255.255.255)
  if ((ip >>> 28) === 15) return true;

  return false;
}

export function ipv6ToBigInt(ipStr) {
  let str = String(ipStr || "").toLowerCase().trim();
  // Handle IPv4-mapped IPv6 like ::ffff:192.168.1.1
  const v4match = str.match(/^(?:.*:)?(\d+\.\d+\.\d+\.\d+)$/);
  if (v4match) {
    const v4 = v4match[1].split(".").map(Number);
    const v4hex = v4.map((n) => n.toString(16).padStart(2, "0")).join("");
    str = str.slice(0, str.lastIndexOf(v4match[1])) + v4hex.slice(0, 4) + ":" + v4hex.slice(4);
  }
  const parts = str.split("::");
  if (parts.length > 2) return null;
  let left = parts[0] ? parts[0].split(":") : [];
  let right = parts.length === 2 && parts[1] ? parts[1].split(":") : [];
  const missing = 8 - (left.length + right.length);
  if (missing < 0) return null;
  const full = [...left, ...Array(missing).fill("0"), ...right];
  if (full.length !== 8) return null;

  let big = 0n;
  for (const seg of full) {
    const val = parseInt(seg || "0", 16);
    if (isNaN(val) || val < 0 || val > 0xffff) return null;
    big = (big << 16n) | BigInt(val);
  }
  return big;
}

export function isBlockedIpv6(ipStr) {
  if (ipStr.includes(".")) {
    const match = ipStr.match(/(\d+\.\d+\.\d+\.\d+)/);
    if (match && isBlockedIpv4(match[1])) return true;
  }
  const big = ipv6ToBigInt(ipStr);
  if (big === null) return false; // not a valid IPv6

  // ::/128 (Unspecified address)
  if (big === 0n) return true;
  // ::1/128 (Loopback)
  if (big === 1n) return true;
  // ::ffff:0:0/96 (IPv4-mapped)
  if ((big >> 32n) === 0xffffn) {
    const v4 = Number(big & 0xffffffffn);
    if (isBlockedIpv4(
      `${(v4 >>> 24) & 0xff}.${(v4 >>> 16) & 0xff}.${(v4 >>> 8) & 0xff}.${v4 & 0xff}`
    )) {
      return true;
    }
  }
  // fc00::/7 (Unique Local Addresses - private: fc00::/8 and fd00::/8)
  if ((big >> 121n) === 0x7en) return true;
  // fe80::/10 (Link-Local Unicast: fe80:: - febf:...)
  if ((big >> 118n) === 0x3fan) return true;
  // ff00::/8 (Multicast)
  if ((big >> 120n) === 0xffn) return true;

  return false;
}

export function isBlockedIp(ipStr) {
  const clean = String(ipStr || "").trim().replace(/^\[|\]$/g, "");
  if (!clean) return true;
  if (!isIpLiteral(clean)) return false;
  if (clean.includes(":")) {
    return isBlockedIpv6(clean);
  }
  return isBlockedIpv4(clean);
}

export function isBlockedHostname(rawHost) {
  const host = String(rawHost || "").toLowerCase().trim().replace(/^\[|\]$/g, "");
  if (!host) return true;
  if (BLOCKED_HOSTNAMES.has(host)) return true;
  for (const suffix of BLOCKED_DOMAIN_SUFFIXES) {
    if (host.endsWith(suffix)) return true;
  }
  if (/^127\./.test(host) || /^10\./.test(host) || /^169\.254\./.test(host) || /^192\.168\./.test(host)) {
    return true;
  }
  return false;
}

/**
 * Resolves a hostname to a list of IP addresses.
 * Uses node:dns when available (Node.js/testing), DoH fallback in Cloudflare Workers,
 * or options.dnsResolver if injected.
 */
export async function resolveHostAddresses(hostname, options = {}) {
  const cleanHost = String(hostname || "").toLowerCase().trim().replace(/^\[|\]$/g, "");
  if (!cleanHost) return [];

  if (typeof options.dnsResolver === "function") {
    return await options.dnsResolver(cleanHost);
  }
  if (isIpLiteral(cleanHost)) return [cleanHost];

  // Cloudflare DoH is used in Workers; never substitute a made-up placeholder IP.
  const fetchImpl = options.dohFetchImpl || globalThis.fetch;
  if (typeof fetchImpl !== "function") return [];
  const addresses = [];
  for (const type of ["A", "AAAA"]) {
    try {
      const dohUrl = `https://1.1.1.1/dns-query?name=${encodeURIComponent(cleanHost)}&type=${type}`;
      const response = await fetchImpl(dohUrl, {
        headers: { Accept: "application/dns-json" }
      });
      if (!response.ok) continue;
      const data = await response.json();
      const answers = Array.isArray(data?.Answer) ? data.Answer : [];
      for (const answer of answers) {
        if ((type === "A" && answer.type === 1) || (type === "AAAA" && answer.type === 28)) {
          if (typeof answer.data === "string" && answer.data.trim()) addresses.push(answer.data.trim());
        }
      }
    } catch (_) {
      // Fail closed if neither address family can be resolved.
    }
  }
  return [...new Set(addresses)];
}

/**
 * Validates that an endpoint URL is an external, safe HTTPS URL and does not
 * resolve to any internal, private, loopback, or cloud metadata IP address.
 */
export async function validateSafeEndpoint(rawUrl, options = {}) {
  const clean = String(rawUrl || "").trim();
  if (!clean) {
    return { ok: true, valid: true, url: "" };
  }

  let parsed;
  try {
    parsed = new URL(clean);
  } catch (_) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: "Custom endpoint URL is malformed."
    };
  }

  if (parsed.protocol !== "https:") {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: "Custom endpoint must use HTTPS (https://)."
    };
  }

  const host = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, "");

  // 1. Static hostname / suffix checks
  if (isBlockedHostname(host)) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: `Custom endpoint host '${host}' is blocked by SSRF security policy (private, loopback, or metadata address).`
    };
  }

  // 2. Direct IP literal checks
  if (isIpLiteral(host)) {
    if (isBlockedIp(host)) {
      return {
        ok: false,
        valid: false,
        errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
        reason: `Custom endpoint host '${host}' is blocked by SSRF security policy (private, loopback, or metadata address).`
      };
    }
    return { ok: true, valid: true, url: parsed.toString(), hostname: host, resolvedIps: [host] };
  }


  // Official fixed provider hosts are allowlisted. This is a hostname policy, not socket-IP pinning.
  if (isTrustedProviderHostname(host)) {
    return { ok: true, valid: true, url: parsed.toString(), hostname: host, resolvedIps: [] };
  }

  // 3. DNS Resolution validation for non-allowlisted hosts (validation/test path only)
  const resolvedIps = await resolveHostAddresses(host, options);
  if (!Array.isArray(resolvedIps) || resolvedIps.length === 0) {
    // In air-gapped / offline test environments, if host is not an IP and not blocked, allow mock hostname
    const isTestEnv = options.allowUnresolvedMock || options.env?.JARVIS_ALLOW_MOCK_PROVIDERS === "true";
    if (isTestEnv) {
      return { ok: true, valid: true, url: parsed.toString(), hostname: host, resolvedIps: ["93.184.216.34"] };
    }
    // Fail-closed policy: if host cannot be resolved, reject request
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: `Custom endpoint host '${host}' could not be safely resolved via DNS (fail-closed policy).`
    };
  }

  for (const ip of resolvedIps) {
    if (isBlockedIp(ip)) {
      return {
        ok: false,
        valid: false,
        errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
        reason: `Custom endpoint host '${host}' resolves to blocked internal/private IP '${ip}'.`
      };
    }
  }

  return {
    ok: true,
    valid: true,
    url: parsed.toString(),
    hostname: host,
    resolvedIps
  };
}

/**
 * Synchronous fast check for initial syntax / static IP validation
 * Preserves backwards-compatibility with synchronous callers.
 */
export function validateCustomProviderEndpoint(rawUrl) {
  const clean = String(rawUrl || "").trim();
  if (!clean) return { ok: true, valid: true, url: "" };
  let parsed;
  try {
    parsed = new URL(clean);
  } catch (_) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: "Custom endpoint URL is malformed."
    };
  }
  if (parsed.protocol !== "https:") {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: "Custom endpoint must use HTTPS (https://)."
    };
  }
  const host = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (isBlockedHostname(host) || (isIpLiteral(host) && isBlockedIp(host))) {
    return {
      ok: false,
      valid: false,
      errorCode: PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR,
      reason: `Custom endpoint host '${host}' is blocked by SSRF security policy (private, loopback, or metadata address).`
    };
  }
  return { ok: true, valid: true, url: parsed.toString() };
}

/**
 * Executes a fetch with live pre-connection DNS rebinding protection.
 * Validates that immediately before the connection is made, the hostname does not
 * resolve to an internal/private address. Fails closed on any ambiguity.
 */
export async function safeFetchWithDnsRebindingGuard(rawUrl, init = {}, options = {}) {
  const endpointValidation = await validateSafeEndpoint(rawUrl, options);
  if (!endpointValidation.ok) {
    const error = new Error(endpointValidation.reason);
    error.status = 400;
    error.code = endpointValidation.errorCode;
    throw error;
  }

  const hostname = endpointValidation.hostname;
  if (!hostname || (!isTrustedProviderHostname(hostname, options.env) && options.allowUntrustedHost !== true)) {
    const error = new Error("Outbound host is not in the configured provider allowlist; custom endpoints are disabled until connection-pinned egress is available.");
    error.status = 403;
    error.code = PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR;
    throw error;
  }
  if (isIpLiteral(hostname)) {
    const error = new Error("Outbound IP-literal endpoints are not permitted.");
    error.status = 403;
    error.code = PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR;
    throw error;
  }

  const fetchImpl = options.fetchImpl || globalThis.fetch;
  if (typeof fetchImpl !== "function") throw new Error("No valid fetch implementation available");

  // Worker fetch resolves its own socket destination. Built-in provider hostnames
  // are trusted by fixed allowlist. Admin-allowlisted custom hosts are DNS-checked,
  // but remain an explicit trust decision because Worker fetch does not pin sockets.
  if (!TRUSTED_PUBLIC_PROVIDERS.has(hostname)) {
    const liveCheckIps = await resolveHostAddresses(hostname, options);
    const allowMockDns = options.allowUnresolvedMock === true ||
      options.env?.JARVIS_ALLOW_MOCK_PROVIDERS === "true";
    if ((!Array.isArray(liveCheckIps) || liveCheckIps.length === 0) && !allowMockDns) {
      const error = new Error(`Outbound DNS validation failed for '${hostname}' (fail closed).`);
      error.status = 403;
      error.code = PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR;
      throw error;
    }
    if (Array.isArray(liveCheckIps) && liveCheckIps.some((ip) => isBlockedIp(ip))) {
      const error = new Error(`DNS Rebinding detected! Host '${hostname}' resolved to blocked IP during connection attempt.`);
      error.status = 403;
      error.code = PROVIDER_ERROR_CODES_DEF.PROVIDER_ERROR;
      throw error;
    }
  }

  return await fetchImpl(rawUrl, init);
}

export const PROVIDER_ERROR_CODES = PROVIDER_ERROR_CODES_DEF;