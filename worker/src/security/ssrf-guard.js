/**
 * J.A.R.V.I.S. B8.1.1 Remediation R1 — Centralized Outbound SSRF, DNS Rebinding & Redirect Guard
 * (Remediates JARVIS-003 & JARVIS-012)
 */

export const SSRF_ERROR_CODE = "SSRF_BLOCKED_DESTINATION";

const BLOCKED_HOSTNAMES = new Set([
  "localhost",
  "localhost.localdomain",
  "ip6-localhost",
  "ip6-loopback",
  "metadata",
  "metadata.google.internal",
  "metadata.goog",
  "instance-data",
  "metadata.azure.com",
  "metadata.tencentyun.com",
  "metadata.internal",
  "localtest.me",
  "lvh.me",
  "vcap.me",
  "0.0.0.0",
  "255.255.255.255",
  "::",
  "::1",
  "0:0:0:0:0:0:0:0",
  "0:0:0:0:0:0:0:1"
]);

const BLOCKED_DOMAIN_SUFFIXES = [
  ".localhost",
  ".local",
  ".localdomain",
  ".internal",
  ".home.arpa",
  ".lan",
  ".corp",
  ".intranet",
  ".private",
  ".localtest.me",
  ".lvh.me",
  ".vcap.me",
  ".nip.io",
  ".sslip.io",
  ".xip.io"
];

const BLOCKED_INTERNAL_PORTS = new Set([
  21, 22, 23, 25, 53, 111, 135, 137, 138, 139, 389, 445,
  1433, 1521, 2049, 2375, 2376, 2379, 3306, 3389, 4369,
  5432, 5672, 5900, 5984, 6379, 6443, 7000, 7001, 8500,
  9042, 9200, 9300, 10250, 10255, 11211, 15672, 27017, 27018
]);

/**
 * Parses a single numeric IPv4 part supporting decimal, octal (0...), and hex (0x...).
 * Returns -1 if not a valid numeric part.
 */
function parseIpv4Part(part) {
  if (!part || typeof part !== "string") return -1;
  if (/^0x[0-9a-f]+$/i.test(part)) {
    const val = Number.parseInt(part, 16);
    return Number.isSafeInteger(val) && val >= 0 ? val : -1;
  }
  if (/^0[0-7]+$/.test(part)) {
    const val = Number.parseInt(part, 8);
    return Number.isSafeInteger(val) && val >= 0 ? val : -1;
  }
  if (/^(0|[1-9][0-9]*)$/.test(part)) {
    const val = Number.parseInt(part, 10);
    return Number.isSafeInteger(val) && val >= 0 ? val : -1;
  }
  return -1;
}

/**
 * Attempts to parse any IPv4 representation (standard dotted quad, 1/2/3-part dotted,
 * octal, hexadecimal, or integer IP) into an unsigned 32-bit integer.
 * Returns:
 * - { isIpCandidate: false, ipInt: null } if hostname is a normal domain name
 * - { isIpCandidate: true, valid: false, ipInt: null } if hostname looks like numeric IP but is malformed
 * - { isIpCandidate: true, valid: true, ipInt: number } if valid 32-bit IPv4
 */
export function parseIpv4Candidate(rawHost) {
  const host = String(rawHost || "").trim();
  if (!host) return { isIpCandidate: false, valid: false, ipInt: null };

  const parts = host.split(".");
  if (parts.length > 4) {
    const allNumericLike = parts.every((p) => /^[0-9a-fx]+$/i.test(p));
    return { isIpCandidate: allNumericLike, valid: false, ipInt: null };
  }

  const parsedParts = parts.map(parseIpv4Part);
  const allValidNumeric = parsedParts.every((n) => n >= 0);

  if (!allValidNumeric) {
    // Check if every label consists only of digits or hex/octal prefixes (e.g. 999.999.999.999 or 018.0.0.1)
    const looksNumericIp = parts.every((p) => /^(0x[0-9a-f]+|[0-9]+)$/i.test(p));
    return { isIpCandidate: looksNumericIp, valid: false, ipInt: null };
  }

  if (parts.length === 1) {
    const val = parsedParts[0];
    if (val > 0xffffffff) return { isIpCandidate: true, valid: false, ipInt: null };
    return { isIpCandidate: true, valid: true, ipInt: val >>> 0 };
  }

  if (parts.length === 2) {
    const [a, b] = parsedParts;
    if (a > 0xff || b > 0xffffff) return { isIpCandidate: true, valid: false, ipInt: null };
    return { isIpCandidate: true, valid: true, ipInt: (((a << 24) | b) >>> 0) };
  }

  if (parts.length === 3) {
    const [a, b, c] = parsedParts;
    if (a > 0xff || b > 0xff || c > 0xffff) return { isIpCandidate: true, valid: false, ipInt: null };
    return { isIpCandidate: true, valid: true, ipInt: (((a << 24) | (b << 16) | c) >>> 0) };
  }

  const [a, b, c, d] = parsedParts;
  if (a > 0xff || b > 0xff || c > 0xff || d > 0xff) {
    return { isIpCandidate: true, valid: false, ipInt: null };
  }
  return {
    isIpCandidate: true,
    valid: true,
    ipInt: (((a << 24) | (b << 16) | (c << 8) | d) >>> 0)
  };
}

/**
 * Checks whether a 32-bit unsigned IPv4 integer falls in any private, loopback,
 * link-local, CGNAT, metadata, benchmarking, documentation, multicast, or reserved range.
 */
export function isPrivateOrReservedIpv4Int(ipInt) {
  if (typeof ipInt !== "number" || !Number.isFinite(ipInt)) return true;
  const ip = ipInt >>> 0;
  const b0 = (ip >>> 24) & 0xff;
  const b1 = (ip >>> 16) & 0xff;
  const b2 = (ip >>> 8) & 0xff;

  // 0.0.0.0/8 ("this" network)
  if (b0 === 0) return true;
  // 10.0.0.0/8 (RFC 1918 private)
  if (b0 === 10) return true;
  // 100.64.0.0/10 (RFC 6598 Shared CGNAT space, includes 100.100.100.200 cloud metadata)
  if (b0 === 100 && b1 >= 64 && b1 <= 127) return true;
  // 127.0.0.0/8 (Loopback)
  if (b0 === 127) return true;
  // 169.254.0.0/16 (Link-local & cloud metadata 169.254.169.254)
  if (b0 === 169 && b1 === 254) return true;
  // 172.16.0.0/12 (RFC 1918 private)
  if (b0 === 172 && b1 >= 16 && b1 <= 31) return true;
  // 192.0.0.0/24 (IETF protocol assignments)
  if (b0 === 192 && b1 === 0 && b2 === 0) return true;
  // 192.0.2.0/24 (TEST-NET-1)
  if (b0 === 192 && b1 === 0 && b2 === 2) return true;
  // 192.88.99.0/24 (6to4 relay anycast)
  if (b0 === 192 && b1 === 88 && b2 === 99) return true;
  // 192.168.0.0/16 (RFC 1918 private)
  if (b0 === 192 && b1 === 168) return true;
  // 198.18.0.0/15 (RFC 2544 benchmarking)
  if (b0 === 198 && (b1 === 18 || b1 === 19)) return true;
  // 198.51.100.0/24 (TEST-NET-2)
  if (b0 === 198 && b1 === 51 && b2 === 100) return true;
  // 203.0.113.0/24 (TEST-NET-3)
  if (b0 === 203 && b1 === 0 && b2 === 113) return true;
  // 224.0.0.0/4 (Multicast) & 240.0.0.0/4 (Reserved / Broadcast 255.255.255.255)
  if (b0 >= 224) return true;

  return false;
}

/**
 * Parses an IPv6 literal into 8 16-bit unsigned integers, or null if invalid.
 */
export function parseIpv6Words(rawHost) {
  let host = String(rawHost || "").trim().replace(/^\[|\]$/g, "");
  if (!host.includes(":")) return null;
  // Reject IPv6 zone indices (%eth0, %25eth0)
  if (host.includes("%")) return null;

  // Handle embedded IPv4 in the last 32 bits (e.g. ::ffff:127.0.0.1 or ::192.168.1.1)
  const lastColon = host.lastIndexOf(":");
  const tail = host.slice(lastColon + 1);
  if (tail.includes(".")) {
    const v4 = parseIpv4Candidate(tail);
    if (!v4.valid) return null;
    const hi = (v4.ipInt >>> 16) & 0xffff;
    const lo = v4.ipInt & 0xffff;
    host = `${host.slice(0, lastColon + 1)}${hi.toString(16)}:${lo.toString(16)}`;
  }

  const doubleColonParts = host.split("::");
  if (doubleColonParts.length > 2) return null;

  let headParts = [];
  let tailParts = [];

  if (doubleColonParts.length === 2) {
    headParts = doubleColonParts[0] ? doubleColonParts[0].split(":") : [];
    tailParts = doubleColonParts[1] ? doubleColonParts[1].split(":") : [];
    const missing = 8 - (headParts.length + tailParts.length);
    if (missing < 1) return null;
    const zeros = new Array(missing).fill("0");
    headParts = [...headParts, ...zeros, ...tailParts];
  } else {
    headParts = host.split(":");
    if (headParts.length !== 8) return null;
  }

  const words = [];
  for (const part of headParts) {
    if (!/^[0-9a-f]{1,4}$/i.test(part)) return null;
    words.push(Number.parseInt(part, 16));
  }
  return words.length === 8 ? words : null;
}

/**
 * Checks whether an 8-word IPv6 address is loopback, unspecified, link-local,
 * unique-local (ULA / AWS fd00:ec2::254), multicast, documentation, 6to4/Teredo,
 * or an IPv4-mapped/compatible/NAT64 address pointing to a private/reserved IPv4.
 */
export function isPrivateOrReservedIpv6Words(words) {
  if (!Array.isArray(words) || words.length !== 8) return true;
  const [w0, w1, w2, w3, w4, w5, w6, w7] = words;

  const first5Zero = w0 === 0 && w1 === 0 && w2 === 0 && w3 === 0 && w4 === 0;
  // Unspecified :: (0:0:0:0:0:0:0:0) or Loopback ::1 (0:0:0:0:0:0:0:1)
  if (first5Zero && w5 === 0 && w6 === 0 && (w7 === 0 || w7 === 1)) {
    return true;
  }

  // IPv4-mapped IPv6 ::ffff:w6:w7 or IPv4-compatible ::w6:w7
  if (first5Zero && (w5 === 0xffff || w5 === 0x0000)) {
    const mappedIpv4 = (((w6 << 16) | w7) >>> 0);
    return isPrivateOrReservedIpv4Int(mappedIpv4);
  }

  // IPv4-translated ::ffff:0:w6:w7 (RFC 2765 SIIT)
  if (w0 === 0 && w1 === 0 && w2 === 0 && w3 === 0 && w4 === 0xffff && w5 === 0) {
    const mappedIpv4 = (((w6 << 16) | w7) >>> 0);
    return isPrivateOrReservedIpv4Int(mappedIpv4);
  }

  // NAT64 Well-Known Prefix 64:ff9b::/96 and 64:ff9b:1::/48
  if (w0 === 0x0064 && w1 === 0xff9b) {
    const mappedIpv4 = (((w6 << 16) | w7) >>> 0);
    return isPrivateOrReservedIpv4Int(mappedIpv4);
  }

  // 6to4 2002::/16 embeds IPv4 in w1:w2
  if (w0 === 0x2002) {
    const embeddedIpv4 = (((w1 << 16) | w2) >>> 0);
    if (isPrivateOrReservedIpv4Int(embeddedIpv4)) return true;
  }

  // Teredo 2001:0000::/32
  if (w0 === 0x2001 && w1 === 0x0000) return true;

  // Documentation 2001:db8::/32
  if (w0 === 0x2001 && w1 === 0x0db8) return true;

  // Unique Local Address (ULA) fc00::/7 (fc00:: - fdff::, includes fd00:ec2::254 AWS metadata)
  if ((w0 & 0xfe00) === 0xfc00) return true;

  // Link-Local fe80::/10 (fe80:: - febf::)
  if ((w0 & 0xffc0) === 0xfe80) return true;

  // Deprecated Site-Local fec0::/10
  if ((w0 & 0xffc0) === 0xfec0) return true;

  // Multicast ff00::/8
  if ((w0 & 0xff00) === 0xff00) return true;

  return false;
}

/**
 * Validates any hostname or IP literal against SSRF policies.
 */
export function validateHostnameSafety(rawHostname) {
  const trimmed = String(rawHostname || "").trim();
  if (!trimmed) {
    return { ok: false, reason: "Endpoint hostname is empty." };
  }

  if (trimmed.includes("%")) {
    return { ok: false, reason: "Endpoint hostname contains disallowed percent/zone encoding." };
  }

  // Strip surrounding IPv6 brackets and trailing dot(s)
  const unbracketed = trimmed.replace(/^\[|\]$/g, "");
  const normalizedHost = unbracketed.replace(/\.+$/, "").toLowerCase();

  if (!normalizedHost) {
    return { ok: false, reason: "Endpoint hostname is empty after normalization." };
  }

  if (BLOCKED_HOSTNAMES.has(normalizedHost)) {
    return {
      ok: false,
      reason: `Endpoint host '${normalizedHost}' is blocked by SSRF security policy (loopback, internal, or metadata address).`
    };
  }

  for (const suffix of BLOCKED_DOMAIN_SUFFIXES) {
    if (normalizedHost.endsWith(suffix)) {
      return {
        ok: false,
        reason: `Endpoint host '${normalizedHost}' uses reserved/internal domain suffix '${suffix}' blocked by SSRF security policy.`
      };
    }
  }

  // Check if IPv6 literal
  if (unbracketed.includes(":")) {
    const words = parseIpv6Words(unbracketed);
    if (!words) {
      return { ok: false, reason: `Endpoint IPv6 address '${trimmed}' is malformed.` };
    }
    if (isPrivateOrReservedIpv6Words(words)) {
      return {
        ok: false,
        reason: `Endpoint IPv6 address '${trimmed}' is blocked by SSRF security policy (private, loopback, link-local, or mapped internal address).`
      };
    }
    return { ok: true, hostname: `[${unbracketed.toLowerCase()}]`, isIpLiteral: true };
  }

  // Check if IPv4 literal (including decimal, octal, hex, or shorthand)
  const v4 = parseIpv4Candidate(normalizedHost);
  if (v4.isIpCandidate) {
    if (!v4.valid) {
      return { ok: false, reason: `Endpoint IPv4 address '${normalizedHost}' is malformed.` };
    }
    if (isPrivateOrReservedIpv4Int(v4.ipInt)) {
      return {
        ok: false,
        reason: `Endpoint IPv4 address '${normalizedHost}' is blocked by SSRF security policy (private, loopback, link-local, CGNAT, or metadata address).`
      };
    }
    return { ok: true, hostname: normalizedHost, isIpLiteral: true };
  }

  // Check for DNS-rebinding style dashed IPv4 prefixes (e.g., 127-0-0-1.example.com, 169-254-169-254.example.com)
  const dashedMatch = normalizedHost.match(/^(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})\./);
  if (dashedMatch) {
    const dashedIp = `${dashedMatch[1]}.${dashedMatch[2]}.${dashedMatch[3]}.${dashedMatch[4]}`;
    const dashedCheck = parseIpv4Candidate(dashedIp);
    if (dashedCheck.valid && isPrivateOrReservedIpv4Int(dashedCheck.ipInt)) {
      return {
        ok: false,
        reason: `Endpoint host '${normalizedHost}' embeds a private/internal IPv4 address and is blocked by SSRF policy.`
      };
    }
  }

  // Validate standard DNS label characters
  const labels = normalizedHost.split(".");
  if (labels.length < 2) {
    return {
      ok: false,
      reason: `Endpoint host '${normalizedHost}' must be a fully qualified public domain name.`
    };
  }
  const tld = labels[labels.length - 1];
  if (["test", "example", "invalid", "localhost", "local", "internal", "onion"].includes(tld)) {
    return {
      ok: false,
      reason: `Endpoint host '${normalizedHost}' uses reserved TLD '.${tld}' blocked in production.`
    };
  }
  for (const label of labels) {
    if (!label || label.length > 63 || !/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label)) {
      return {
        ok: false,
        reason: `Endpoint host '${normalizedHost}' contains invalid DNS label '${label}'.`
      };
    }
  }

  return { ok: true, hostname: normalizedHost, isIpLiteral: false };
}

/**
 * Validates an outbound URL for custom AI providers, MCP servers, and external webhooks/fetches.
 */
export function validateOutboundUrl(rawUrl, options = {}) {
  const clean = String(rawUrl || "").trim();
  if (!clean) {
    if (options.allowEmpty) return { ok: true, valid: true, url: "" };
    return {
      ok: false,
      valid: false,
      errorCode: options.errorCode || SSRF_ERROR_CODE,
      reason: "Endpoint URL is required."
    };
  }

  if (options.allowMcpScheme && clean.startsWith("mcp://")) {
    const mcpTarget = clean.slice("mcp://".length);
    if (!/^[a-zA-Z0-9._-]+(\/[a-zA-Z0-9._/-]*)?$/.test(mcpTarget) || mcpTarget.includes("..")) {
      return {
        ok: false,
        valid: false,
        errorCode: options.errorCode || SSRF_ERROR_CODE,
        reason: "Invalid internal mcp:// URI."
      };
    }
    return { ok: true, valid: true, url: clean, scheme: "mcp:" };
  }

  let parsed;
  try {
    parsed = new URL(clean);
  } catch (_) {
    return {
      ok: false,
      valid: false,
      errorCode: options.errorCode || SSRF_ERROR_CODE,
      reason: "Endpoint URL is malformed."
    };
  }

  if (parsed.protocol !== "https:") {
    return {
      ok: false,
      valid: false,
      errorCode: options.errorCode || SSRF_ERROR_CODE,
      reason: "Outbound endpoint must use HTTPS (https://)."
    };
  }

  if (parsed.username || parsed.password) {
    return {
      ok: false,
      valid: false,
      errorCode: options.errorCode || SSRF_ERROR_CODE,
      reason: "Outbound endpoint URL must not contain embedded user credentials."
    };
  }

  // Raw URL check for octal/hex/short IPv4 before URL constructor normalizes it to dotted-quad
  // (Even after URL normalization, parsed.hostname will be the normalized IPv4 or IPv6 which validateHostnameSafety checks!)
  const hostSafety = validateHostnameSafety(parsed.hostname);
  if (!hostSafety.ok) {
    return {
      ok: false,
      valid: false,
      errorCode: options.errorCode || SSRF_ERROR_CODE,
      reason: hostSafety.reason
    };
  }

  if (parsed.port) {
    const portNum = Number.parseInt(parsed.port, 10);
    if (!Number.isInteger(portNum) || portNum < 1 || portNum > 65535) {
      return {
        ok: false,
        valid: false,
        errorCode: options.errorCode || SSRF_ERROR_CODE,
        reason: "Outbound endpoint port is invalid."
      };
    }
    if (BLOCKED_INTERNAL_PORTS.has(portNum)) {
      return {
        ok: false,
        valid: false,
        errorCode: options.errorCode || SSRF_ERROR_CODE,
        reason: `Outbound endpoint port ${portNum} is blocked by SSRF security policy.`
      };
    }
    if (options.disallowCustomPorts && portNum !== 443) {
      return {
        ok: false,
        valid: false,
        errorCode: options.errorCode || SSRF_ERROR_CODE,
        reason: `Outbound endpoint custom port ${portNum} is not permitted; use standard HTTPS port 443.`
      };
    }
  }

  // Strip trailing dot on hostname if present
  if (parsed.hostname.endsWith(".")) {
    parsed.hostname = parsed.hostname.replace(/\.+$/, "");
  }

  return {
    ok: true,
    valid: true,
    url: parsed.toString(),
    hostname: hostSafety.hostname,
    isIpLiteral: hostSafety.isIpLiteral,
    scheme: "https:"
  };
}

/**
 * Optional DNS-aware validation that verifies resolved A/AAAA records do not point to private/internal IPs
 * (protects against DNS rebinding when a dnsResolver or DoH resolver is supplied).
 */
export async function validateOutboundUrlWithDns(rawUrl, options = {}) {
  const baseCheck = validateOutboundUrl(rawUrl, options);
  if (!baseCheck.ok || baseCheck.scheme === "mcp:" || !baseCheck.url) {
    return baseCheck;
  }

  const dnsResolver = options.dnsResolver;
  if (typeof dnsResolver === "function" && !baseCheck.isIpLiteral) {
    let resolvedIps = [];
    try {
      const res = await dnsResolver(baseCheck.hostname);
      resolvedIps = Array.isArray(res) ? res : typeof res === "string" ? [res] : [];
    } catch (err) {
      return {
        ok: false,
        valid: false,
        errorCode: options.errorCode || SSRF_ERROR_CODE,
        reason: `DNS resolution failed for '${baseCheck.hostname}': ${err?.message || String(err)}`
      };
    }
    if (resolvedIps.length === 0) {
      return {
        ok: false,
        valid: false,
        errorCode: options.errorCode || SSRF_ERROR_CODE,
        reason: `DNS resolution returned no records for '${baseCheck.hostname}'.`
      };
    }
    for (const ip of resolvedIps) {
      const ipSafety = validateHostnameSafety(String(ip));
      if (!ipSafety.ok) {
        return {
          ok: false,
          valid: false,
          errorCode: options.errorCode || SSRF_ERROR_CODE,
          reason: `DNS rebinding blocked: '${baseCheck.hostname}' resolved to forbidden address '${ip}' (${ipSafety.reason})`
        };
      }
    }
  }

  return baseCheck;
}

/**
 * Executes an outbound HTTP request with strict SSRF validation and redirect hop protection.
 * By default (`followRedirects: false`), any 3xx redirect is rejected so credential headers
 * (`Authorization`, `x-api-key`) are never leaked or redirected to internal destinations.
 * When `followRedirects: true`, every redirect hop is validated against `validateOutboundUrlWithDns`
 * and sensitive headers are stripped on cross-origin hops.
 */
export async function safeOutboundFetch(rawUrl, init = {}, options = {}) {
  const fetchImpl = options.fetchImpl || globalThis.fetch;
  const maxRedirects = typeof options.maxRedirects === "number" ? options.maxRedirects : 3;
  const followRedirects = Boolean(options.followRedirects);

  let currentUrl = String(rawUrl || "").trim();
  let currentInit = {
    ...init,
    redirect: "manual"
  };

  for (let hop = 0; hop <= maxRedirects; hop++) {
    const check = await validateOutboundUrlWithDns(currentUrl, options);
    if (!check.ok) {
      const err = new Error(check.reason);
      err.status = 400;
      err.code = check.errorCode || SSRF_ERROR_CODE;
      err.errorCode = check.errorCode || SSRF_ERROR_CODE;
      throw err;
    }

    const response = await fetchImpl(check.url, currentInit);
    const status = Number(response?.status || 0);

    if (status >= 300 && status < 400) {
      const location = response?.headers?.get?.("Location") || response?.headers?.get?.("location") || "";
      if (!followRedirects) {
        const redirectErr = new Error(
          `Outbound redirect (HTTP ${status}${location ? ` -> ${location}` : ""}) is blocked by SSRF security policy.`
        );
        redirectErr.status = 502;
        redirectErr.code = SSRF_ERROR_CODE;
        redirectErr.errorCode = SSRF_ERROR_CODE;
        throw redirectErr;
      }

      if (!location) {
        const missingLocErr = new Error(`Outbound redirect HTTP ${status} is missing Location header.`);
        missingLocErr.status = 502;
        missingLocErr.code = SSRF_ERROR_CODE;
        missingLocErr.errorCode = SSRF_ERROR_CODE;
        throw missingLocErr;
      }

      if (hop >= maxRedirects) {
        const maxHopErr = new Error(`Outbound request exceeded maximum safe redirect hops (${maxRedirects}).`);
        maxHopErr.status = 502;
        maxHopErr.code = SSRF_ERROR_CODE;
        maxHopErr.errorCode = SSRF_ERROR_CODE;
        throw maxHopErr;
      }

      let nextUrlObj;
      try {
        nextUrlObj = new URL(location, check.url);
      } catch (_) {
        const badLocErr = new Error(`Redirect Location '${location}' is malformed.`);
        badLocErr.status = 502;
        badLocErr.code = SSRF_ERROR_CODE;
        badLocErr.errorCode = SSRF_ERROR_CODE;
        throw badLocErr;
      }

      const nextCheck = await validateOutboundUrlWithDns(nextUrlObj.toString(), options);
      if (!nextCheck.ok) {
        const hopBlockedErr = new Error(`Redirect hop blocked by SSRF guard: ${nextCheck.reason}`);
        hopBlockedErr.status = 400;
        hopBlockedErr.code = nextCheck.errorCode || SSRF_ERROR_CODE;
        hopBlockedErr.errorCode = nextCheck.errorCode || SSRF_ERROR_CODE;
        throw hopBlockedErr;
      }

      const prevOrigin = new URL(check.url).origin;
      const nextOrigin = new URL(nextCheck.url).origin;
      if (prevOrigin !== nextOrigin && currentInit.headers) {
        const sanitizedHeaders = new Headers(currentInit.headers);
        sanitizedHeaders.delete("authorization");
        sanitizedHeaders.delete("x-api-key");
        sanitizedHeaders.delete("cookie");
        currentInit = { ...currentInit, headers: sanitizedHeaders };
      }
      if (status === 303 || ((status === 301 || status === 302) && currentInit.method === "POST")) {
        currentInit = { ...currentInit, method: "GET", body: undefined };
      }

      currentUrl = nextCheck.url;
      continue;
    }

    return response;
  }

  const fallbackErr = new Error("Outbound request failed redirect validation.");
  fallbackErr.status = 502;
  fallbackErr.code = SSRF_ERROR_CODE;
  throw fallbackErr;
}
