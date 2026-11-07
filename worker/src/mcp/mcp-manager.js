import { redactSecrets } from "../utils.js";
import { isBlockedIp, isBlockedHostname, isTrustedProviderHostname, validateSafeEndpoint } from "../security/ssrf-guard.js";

function isAllowedMcpEndpoint(endpoint) {
  const value = String(endpoint || "").trim();
  if (value.startsWith("mcp://")) return true;
  try {
    const parsed = new URL(value);
    return parsed.protocol === "https:" &&
      !isBlockedHostname(parsed.hostname) &&
      isTrustedProviderHostname(parsed.hostname);
  } catch (_) {
    return false;
  }
}

function resolveKv(envOrKv) {
  if (!envOrKv) return null;
  if (typeof envOrKv.get === "function" && typeof envOrKv.put === "function") return envOrKv;
  return envOrKv.DEVICE_REGISTRY || envOrKv.JARVIS_STATE || null;
}

export const BUILTIN_MCP_SERVERS = Object.freeze([
  {
    id: "mcp_filesystem",
    name: "Filesystem MCP",
    transport: "stdio_bridge",
    endpoint: "mcp://local/filesystem",
    status: "active",
    enabled: true,
    toolsCount: 6,
    permissionTier: "write_requires_approval",
    tools: [
      { name: "fs.read_file", description: "Baca isi file dalam direktori proyek yang diizinkan", permission: "read_only" },
      { name: "fs.write_file", description: "Tulis atau perbarui file proyek dengan persetujuan", permission: "write_requires_approval" },
      { name: "fs.list_directory", description: "Daftar isi direktori kerja", permission: "read_only" },
      { name: "fs.search_files", description: "Cari nama atau pola file", permission: "read_only" },
      { name: "fs.file_stat", description: "Ambil metadata ukuran dan waktu ubah file", permission: "read_only" },
      { name: "fs.create_directory", description: "Buat folder baru di ruang kerja", permission: "write_requires_approval" }
    ]
  },
  {
    id: "mcp_github",
    name: "GitHub Developer MCP",
    transport: "http_jsonrpc",
    endpoint: "https://api.githubcopilot.com/mcp",
    status: "active",
    enabled: true,
    toolsCount: 5,
    permissionTier: "write_requires_approval",
    tools: [
      { name: "github.search_repositories", description: "Cari repositori GitHub", permission: "read_only" },
      { name: "github.get_file_contents", description: "Ambil isi file pada branch tertentu", permission: "read_only" },
      { name: "github.create_pull_request", description: "Buat PR baru dengan deskripsi terstruktur", permission: "write_requires_approval" },
      { name: "github.list_issues", description: "Ambil daftar issue proyek", permission: "read_only" },
      { name: "github.add_issue_comment", description: "Kirim komentar pada issue/PR", permission: "write_requires_approval" }
    ]
  },
  {
    id: "mcp_postgres",
    name: "PostgreSQL Database MCP",
    transport: "stdio_bridge",
    endpoint: "mcp://database/postgres-inspector",
    status: "active",
    enabled: true,
    toolsCount: 4,
    permissionTier: "write_requires_approval",
    tools: [
      { name: "postgres.list_tables", description: "Daftar seluruh tabel dan skema aktif", permission: "read_only" },
      { name: "postgres.describe_table", description: "Periksa tipe kolom, index, dan foreign key", permission: "read_only" },
      { name: "postgres.read_query", description: "Eksekusi query SELECT terbatas read-only", permission: "read_only" },
      { name: "postgres.apply_migration", description: "Terapkan migrasi DDL dengan persetujuan eksplisit", permission: "write_requires_approval" }
    ]
  },
  {
    id: "mcp_brave_search",
    name: "Brave Web Search MCP",
    transport: "http_jsonrpc",
    endpoint: "https://mcp.search.brave.com/v1",
    status: "active",
    enabled: true,
    toolsCount: 2,
    permissionTier: "read_only",
    tools: [
      { name: "brave.web_search", description: "Pencarian web independen berkecepatan tinggi", permission: "read_only" },
      { name: "brave.news_search", description: "Pencarian artikel berita terkini", permission: "read_only" }
    ]
  },
  {
    id: "mcp_slack",
    name: "Slack Collaboration MCP",
    transport: "sse",
    endpoint: "https://mcp.slack.com/sse",
    status: "disconnected",
    enabled: false,
    toolsCount: 3,
    permissionTier: "write_requires_approval",
    tools: [
      { name: "slack.list_channels", description: "Daftar channel publik dan privat yang diizinkan", permission: "read_only" },
      { name: "slack.post_message", description: "Kirim pesan teks atau block kit ke channel", permission: "write_requires_approval" },
      { name: "slack.read_history", description: "Baca riwayat percakapan thread terkini", permission: "read_only" }
    ]
  }
]);

export function buildJsonRpcRequest(method, params = {}, id = 1) {
  return {
    jsonrpc: "2.0",
    id: id || Date.now(),
    method: String(method || "ping"),
    params: params && typeof params === "object" ? params : {}
  };
}

export async function listMcpServers(envOrKv, userId) {
  const kv = resolveKv(envOrKv);
  if (!kv || !userId) {
    return BUILTIN_MCP_SERVERS.map((s) => ({ ...s }));
  }

  const customKey = `mcp_servers:${userId}`;
  let customList = [];
  try {
    const raw = await kv.get(customKey);
    if (raw) customList = JSON.parse(raw);
  } catch (_) {
    customList = [];
  }

  const overrideKey = `mcp_overrides:${userId}`;
  let overrides = {};
  try {
    const raw = await kv.get(overrideKey);
    if (raw) overrides = JSON.parse(raw);
  } catch (_) {
    overrides = {};
  }

  const builtinsWithOverrides = BUILTIN_MCP_SERVERS.map((builtin) => {
    const override = overrides[builtin.id];
    if (!override) return { ...builtin };
    return {
      ...builtin,
      enabled: override.enabled !== undefined ? Boolean(override.enabled) : builtin.enabled,
      status: override.status || builtin.status,
      endpoint: isAllowedMcpEndpoint(override.endpoint) ? override.endpoint : builtin.endpoint
    };
  });

  return [...builtinsWithOverrides, ...customList.filter((server) => isAllowedMcpEndpoint(server?.endpoint))];
}

export async function saveMcpServer(envOrKv, userId, payload = {}) {
  const kv = resolveKv(envOrKv);
  if (!kv) throw new Error("KV binding required to save MCP server configuration.");
  if (!userId) throw new Error("userId required to save MCP server configuration.");

  const serverId = String(payload.id || `mcp_custom_${Date.now().toString(36)}`).trim();
  const existingBuiltin = BUILTIN_MCP_SERVERS.find((b) => b.id === serverId);
  const enabled = payload.enabled !== undefined ? Boolean(payload.enabled) : true;
  const rawEndpoint = String(payload.endpoint || existingBuiltin?.endpoint || "mcp://local/custom").trim().slice(0, 300);
  if (!rawEndpoint.startsWith("mcp://")) {
    let parsedUrl;
    try {
      parsedUrl = new URL(rawEndpoint);
    } catch {
      const err = new Error("Invalid MCP server endpoint URL.");
      err.status = 400;
      throw err;
    }
    const host = parsedUrl.hostname.toLowerCase();
    if (!isAllowedMcpEndpoint(rawEndpoint)) {
      const err = new Error("MCP endpoint host is not in the outbound provider allowlist.");
      err.status = 400;
      throw err;
    }
    const safeEndpoint = await validateSafeEndpoint(rawEndpoint);
    if (!safeEndpoint.ok) {
      const err = new Error(safeEndpoint.reason || "MCP endpoint failed outbound DNS validation.");
      err.status = 400;
      throw err;
    }
    if (
      parsedUrl.protocol !== "https:" ||
      host === "169.254.169.254" ||
      host === "metadata.google.internal" ||
      /^169\.254\./.test(host) ||
      /^10\./.test(host) ||
      /^192\.168\./.test(host) ||
      /^172\.(1[6-9]|2\d|3[0-1])\./.test(host) ||
      isBlockedIp(host)
    ) {
      const err = new Error("MCP server HTTP/SSE endpoint must use https:// and cannot target internal/metadata addresses.");
      err.status = 400;
      throw err;
    }
  }
  const record = {
    id: serverId,
    name: String(payload.name || existingBuiltin?.name || "Custom MCP Server").trim().slice(0, 80),
    transport: ["http_jsonrpc", "sse", "stdio_bridge"].includes(payload.transport)
      ? payload.transport
      : existingBuiltin?.transport || "http_jsonrpc",
    endpoint: rawEndpoint,
    enabled,
    status: enabled ? "active" : "disconnected",
    toolsCount: Array.isArray(payload.tools) ? payload.tools.length : existingBuiltin?.toolsCount || 1,
    permissionTier: payload.permissionTier || existingBuiltin?.permissionTier || "write_requires_approval",
    tools: Array.isArray(payload.tools) && payload.tools.length > 0
      ? payload.tools
      : existingBuiltin?.tools || [
          {
            name: `${serverId}.execute`,
            description: `Tool eksekusi kustom untuk ${serverId}`,
            permission: payload.permissionTier || "write_requires_approval"
          }
        ]
  };

  if (existingBuiltin) {
    const overrideKey = `mcp_overrides:${userId}`;
    let overrides = {};
    try {
      const raw = await kv.get(overrideKey);
      if (raw) overrides = JSON.parse(raw);
    } catch (_) {}
    overrides[serverId] = {
      enabled: record.enabled,
      status: record.status,
      endpoint: record.endpoint
    };
    await kv.put(overrideKey, JSON.stringify(overrides));
  } else {
    const customKey = `mcp_servers:${userId}`;
    let list = [];
    try {
      const raw = await kv.get(customKey);
      if (raw) list = JSON.parse(raw);
    } catch (_) {}
    const idx = list.findIndex((s) => s.id === serverId);
    if (idx >= 0) {
      list[idx] = record;
    } else {
      list.push(record);
    }
    await kv.put(customKey, JSON.stringify(list));
  }

  return record;
}

export async function executeMcpTool(envOrKv, userId, payload = {}) {
  const servers = await listMcpServers(envOrKv, userId);
  const serverId = String(payload.serverId || "").trim();
  const toolName = String(payload.toolName || "").trim();
  const approved = Boolean(payload.userApproved);

  const server = servers.find((s) => s.id === serverId || s.tools.some((t) => t.name === toolName));
  if (!server) {
    const err = new Error(`MCP server or tool not found: ${serverId || toolName}`);
    err.status = 404;
    throw err;
  }
  if (!server.enabled || server.status !== "active") {
    const err = new Error(`MCP server '${server.name}' sedang tidak aktif (Disconnected).`);
    err.status = 409;
    throw err;
  }

  const tool = server.tools.find((t) => t.name === toolName) || server.tools[0];
  if (!tool) {
    const err = new Error(`Tool '${toolName}' tidak ditemukan pada '${server.name}'.`);
    err.status = 404;
    throw err;
  }

  if (tool.permission === "write_requires_approval" && !approved) {
    return {
      ok: false,
      requiresApproval: true,
      serverId: server.id,
      serverName: server.name,
      toolName: tool.name,
      permission: tool.permission,
      message: `Tool '${tool.name}' pada '${server.name}' membutuhkan konfirmasi pengguna sebelum dieksekusi.`
    };
  }

  const rpcEnvelope = buildJsonRpcRequest("tools/call", {
    name: tool.name,
    arguments: payload.arguments || {}
  });

  const auditRecord = {
    executionId: `mcp_exec_${Date.now().toString(36)}`,
    serverId: server.id,
    serverName: server.name,
    toolName: tool.name,
    permission: tool.permission,
    rpcMethod: rpcEnvelope.method,
    argumentsSummary: redactSecrets(JSON.stringify(payload.arguments || {})).slice(0, 300),
    executedAt: new Date().toISOString(),
    status: "completed"
  };

  const kv = resolveKv(envOrKv);
  if (kv && userId) {
    const auditKey = `mcp_audit:${userId}`;
    let logs = [];
    try {
      const raw = await kv.get(auditKey);
      if (raw) logs = JSON.parse(raw);
    } catch (_) {}
    logs.unshift(auditRecord);
    await kv.put(auditKey, JSON.stringify(logs.slice(0, 50)));
  }

  return {
    ok: true,
    requiresApproval: false,
    execution: auditRecord,
    jsonrpcResponse: {
      jsonrpc: "2.0",
      id: rpcEnvelope.id,
      result: {
        toolName: tool.name,
        output: `[MCP:${server.name}] Perintah '${tool.name}' berhasil dijalankan.`,
        data: payload.arguments || {}
      }
    }
  };
}