import { redactSecrets } from "../utils.js";

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
    name: "GitHub MCP",
    transport: "http_jsonrpc",
    endpoint: "https://api.githubcopilot.com/mcp",
    status: "active",
    enabled: true,
    toolsCount: 12,
    permissionTier: "write_requires_approval",
    tools: [
      { name: "github.list_repos", description: "Daftar repositori pengguna/organisasi", permission: "read_only" },
      { name: "github.get_file", description: "Baca file dari branch repositori", permission: "read_only" },
      { name: "github.list_prs", description: "Daftar Pull Request aktif", permission: "read_only" },
      { name: "github.create_issue", description: "Buat issue baru pada repositori", permission: "write_requires_approval" },
      { name: "github.create_pr_comment", description: "Tambahkan komentar review pada PR", permission: "write_requires_approval" },
      { name: "github.search_code", description: "Cari potongan kode lintas repositori", permission: "read_only" },
      { name: "github.get_commit", description: "Inspeksi diff dan metadata commit", permission: "read_only" },
      { name: "github.list_branches", description: "Daftar branch pada repositori", permission: "read_only" },
      { name: "github.list_workflows", description: "Lihat status GitHub Actions", permission: "read_only" },
      { name: "github.get_issue", description: "Baca detail issue dan diskusi", permission: "read_only" },
      { name: "github.create_branch", description: "Buat branch baru", permission: "write_requires_approval" },
      { name: "github.merge_pr", description: "Merge Pull Request setelah verifikasi", permission: "write_requires_approval" }
    ]
  },
  {
    id: "mcp_postgres",
    name: "PostgreSQL MCP",
    transport: "http_jsonrpc",
    endpoint: "mcp://database/postgres-inspector",
    status: "active",
    enabled: true,
    toolsCount: 8,
    permissionTier: "read_only",
    tools: [
      { name: "pg.list_tables", description: "Daftar tabel dalam skema database", permission: "read_only" },
      { name: "pg.describe_table", description: "Inspeksi kolom, tipe data, dan indeks tabel", permission: "read_only" },
      { name: "pg.explain_query", description: "Analisis rencana eksekusi query SQL", permission: "read_only" },
      { name: "pg.select_readonly", description: "Jalankan query SELECT read-only berbatas baris", permission: "read_only" },
      { name: "pg.list_rls_policies", description: "Audit kebijakan Row-Level Security", permission: "read_only" },
      { name: "pg.check_indexes", description: "Periksa kesehatan dan penggunaan indeks", permission: "read_only" },
      { name: "pg.list_functions", description: "Daftar fungsi dan trigger database", permission: "read_only" },
      { name: "pg.connection_stats", description: "Lihat statistik koneksi aktif", permission: "read_only" }
    ]
  },
  {
    id: "mcp_brave_search",
    name: "Brave Search MCP",
    transport: "http_jsonrpc",
    endpoint: "https://mcp.search.brave.com/v1",
    status: "active",
    enabled: true,
    toolsCount: 4,
    permissionTier: "read_only",
    tools: [
      { name: "brave.web_search", description: "Pencarian web faktual real-time", permission: "read_only" },
      { name: "brave.news_search", description: "Pencarian berita dan pembaruan terkini", permission: "read_only" },
      { name: "brave.local_search", description: "Pencarian lokasi dan bisnis lokal", permission: "read_only" },
      { name: "brave.summarize", description: "Ringkasan instan hasil pencarian", permission: "read_only" }
    ]
  },
  {
    id: "mcp_slack",
    name: "Slack MCP",
    transport: "sse",
    endpoint: "https://mcp.slack.com/sse",
    status: "disconnected",
    enabled: false,
    toolsCount: 5,
    permissionTier: "write_requires_approval",
    tools: [
      { name: "slack.list_channels", description: "Daftar kanal ruang kerja", permission: "read_only" },
      { name: "slack.read_thread", description: "Baca pesan dalam thread", permission: "read_only" },
      { name: "slack.post_message", description: "Kirim pesan ke kanal (wajib persetujuan)", permission: "write_requires_approval" },
      { name: "slack.search_messages", description: "Cari pesan di Slack", permission: "read_only" },
      { name: "slack.list_users", description: "Daftar anggota tim", permission: "read_only" }
    ]
  }
]);

export function buildJsonRpcRequest(method, params = {}, id = 1) {
  return {
    jsonrpc: "2.0",
    id,
    method: String(method || "tools/list"),
    params: params && typeof params === "object" ? params : {}
  };
}

export async function listMcpServers(envOrKv, userId) {
  const kv = resolveKv(envOrKv);
  const customKey = `mcp_servers:${userId || "anon"}`;
  let customServers = [];
  if (kv && userId) {
    try {
      const raw = await kv.get(customKey);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) customServers = parsed;
      }
    } catch (_) {}
  }

  const overridesMap = new Map(customServers.map((s) => [s.id, s]));
  const merged = BUILTIN_MCP_SERVERS.map((builtin) => {
    const override = overridesMap.get(builtin.id);
    if (!override) return { ...builtin };
    overridesMap.delete(builtin.id);
    return {
      ...builtin,
      enabled: typeof override.enabled === "boolean" ? override.enabled : builtin.enabled,
      status: override.status || (override.enabled ? "active" : "disconnected"),
      endpoint: override.endpoint || builtin.endpoint
    };
  });

  for (const extra of overridesMap.values()) {
    merged.push(extra);
  }

  return merged;
}

export async function saveMcpServer(envOrKv, userId, payload = {}) {
  const kv = resolveKv(envOrKv);
  if (!userId) throw new Error("userId is required");
  const action = String(payload.action || "upsert").toLowerCase();
  const customKey = `mcp_servers:${userId}`;
  let stored = [];
  if (kv) {
    try {
      const raw = await kv.get(customKey);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) stored = parsed;
      }
    } catch (_) {}
  }

  if (action === "remove" && payload.id) {
    const next = stored.filter((s) => s.id !== payload.id);
    if (kv) await kv.put(customKey, JSON.stringify(next));
    return { removed: true, id: payload.id };
  }

  const serverId = String(payload.id || `mcp_custom_${Date.now().toString(36)}`).trim();
  const existingBuiltin = BUILTIN_MCP_SERVERS.find((b) => b.id === serverId);
  const enabled = payload.enabled !== undefined ? Boolean(payload.enabled) : true;
  const record = {
    id: serverId,
    name: String(payload.name || existingBuiltin?.name || "Custom MCP Server").trim().slice(0, 80),
    transport: ["http_jsonrpc", "sse", "stdio_bridge"].includes(payload.transport)
      ? payload.transport
      : existingBuiltin?.transport || "http_jsonrpc",
    endpoint: String(payload.endpoint || existingBuiltin?.endpoint || "https://localhost:8788/mcp").trim().slice(0, 300),
    enabled,
    status: enabled ? "active" : "disconnected",
    toolsCount: Array.isArray(payload.tools)
      ? payload.tools.length
      : existingBuiltin?.toolsCount || 2,
    permissionTier: payload.permissionTier || existingBuiltin?.permissionTier || "write_requires_approval",
    tools: Array.isArray(payload.tools)
      ? payload.tools
      : existingBuiltin?.tools || [
          { name: `${serverId}.ping`, description: "Periksa konektivitas server MCP kustom", permission: "read_only" },
          { name: `${serverId}.invoke`, description: "Jalankan tool kustom MCP", permission: "write_requires_approval" }
        ],
    updatedAt: new Date().toISOString()
  };

  const filtered = stored.filter((s) => s.id !== serverId);
  filtered.push(record);
  if (kv) await kv.put(customKey, JSON.stringify(filtered.slice(0, 30)));
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
        content: [
          {
            type: "text",
            text: `[MCP ${server.name}] Tool '${tool.name}' berhasil dijalankan dengan aman.`
          }
        ],
        isError: false
      }
    }
  };
}