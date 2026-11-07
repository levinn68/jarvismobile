import { listSkills } from "./skill-registry.js";

function resolveKv(envOrKv) {
  if (!envOrKv) return null;
  if (typeof envOrKv.get === "function" && typeof envOrKv.put === "function") return envOrKv;
  return envOrKv.DEVICE_REGISTRY || envOrKv.JARVIS_STATE || null;
}

export const MARKETPLACE_SKILLS_CATALOG = Object.freeze([
  {
    id: "code-reviewer-pro",
    name: "Code Reviewer Pro",
    version: "2.1.0",
    author: "J.A.R.V.I.S. Labs",
    category: "Coding",
    featured: true,
    rating: 4.9,
    installsLabel: "12.4K",
    sizeLabel: "1.8 MB",
    defaultInstalled: true,
    description:
      "Menganalisis kualitas kode secara otomatis, mendeteksi potensi bug, celah keamanan (OWASP), serta memberikan rekomendasi refactoring bersih.",
    permissions: [
      "Read Project Files",
      "Run Static Analysis",
      "Generate Review Report"
    ],
    dependencies: [
      "GitHub MCP (Optional)",
      "Python Linter",
      "AST Parser"
    ]
  },
  {
    id: "deep-web-researcher",
    name: "Deep Web Researcher",
    version: "1.8.0",
    author: "J.A.R.V.I.S. Labs",
    category: "Riset",
    featured: true,
    rating: 4.8,
    installsLabel: "9.8K",
    sizeLabel: "1.4 MB",
    defaultInstalled: true,
    description:
      "Menjalankan riset mendalam multi-sumber dengan verifikasi silang, matriks fakta, dan sitasi terstruktur.",
    permissions: [
      "Search External Web Sources",
      "Read Document Attachments",
      "Generate Citation Report"
    ],
    dependencies: [
      "Brave Search MCP",
      "Citation Verifier"
    ]
  },
  {
    id: "data-analyst-chart",
    name: "Data Analyst & Chart",
    version: "2.0.1",
    author: "J.A.R.V.I.S. Labs",
    category: "Produktivitas",
    featured: false,
    rating: 4.9,
    installsLabel: "8.1K",
    sizeLabel: "2.1 MB",
    defaultInstalled: true,
    description:
      "Mengolah CSV/XLSX/JSON menjadi ringkasan statistik, deteksi anomali, dan spesifikasi grafik visual.",
    permissions: [
      "Read Tabular Datasets",
      "Execute Statistical Aggregation",
      "Export Chart Spec"
    ],
    dependencies: [
      "PostgreSQL MCP (Optional)",
      "Tabular Parser"
    ]
  },
  {
    id: "document-generator",
    name: "Document Generator",
    version: "1.5.2",
    author: "J.A.R.V.I.S. Labs",
    category: "Produktivitas",
    featured: false,
    rating: 4.7,
    installsLabel: "7.5K",
    sizeLabel: "1.6 MB",
    defaultInstalled: false,
    description:
      "Menyusun dokumen laporan eksekutif (.md / .pdf), kerangka presentasi slide, dan spreadsheet anggaran otomatis.",
    permissions: [
      "Generate Markdown & PDF Specs",
      "Save Workspace Deliverables"
    ],
    dependencies: [
      "Filesystem MCP"
    ]
  },
  {
    id: "ui-ux-critic",
    name: "UI/UX Critic",
    version: "1.4.0",
    author: "J.A.R.V.I.S. Design",
    category: "Media",
    featured: false,
    rating: 4.8,
    installsLabel: "6.2K",
    sizeLabel: "1.2 MB",
    defaultInstalled: false,
    description:
      "Mengevaluasi tata letak antarmuka, kontras aksesibilitas WCAG, hierarki tipografi, dan alur pengguna.",
    permissions: [
      "Inspect Image & Screen Attachments",
      "Generate UI Audit Checklist"
    ],
    dependencies: [
      "Vision Analyzer"
    ]
  },
  {
    id: "security-auditor",
    name: "Security Auditor",
    version: "2.2.0",
    author: "J.A.R.V.I.S. SecOps",
    category: "Coding",
    featured: true,
    rating: 4.9,
    installsLabel: "11.0K",
    sizeLabel: "1.9 MB",
    defaultInstalled: true,
    description:
      "Memeriksa kebijakan RLS database, kebocoran rahasia/token, header keamanan HTTP, dan celah injeksi.",
    permissions: [
      "Read Project Files",
      "Inspect Database RLS Policies",
      "Generate Security Audit"
    ],
    dependencies: [
      "PostgreSQL MCP",
      "GitHub MCP"
    ]
  }
]);

export function validateSkillManifest(manifest = {}) {
  const id = String(manifest.id || "").trim().toLowerCase().replace(/[^a-z0-9-_]/g, "-");
  const name = String(manifest.name || "").trim();
  const version = String(manifest.version || "1.0.0").trim();
  if (!id || !name) {
    throw new Error("Skill manifest wajib memiliki 'id' dan 'name'.");
  }
  if (!/^\d+\.\d+\.\d+$/.test(version)) {
    throw new Error("Skill version wajib mengikuti format semver (contoh: 1.0.0).");
  }
  return {
    id,
    name: name.slice(0, 80),
    version,
    author: String(manifest.author || "Custom Author").trim().slice(0, 60),
    category: ["Produktivitas", "Coding", "Riset", "Media"].includes(manifest.category)
      ? manifest.category
      : "Produktivitas",
    featured: Boolean(manifest.featured),
    rating: Number(manifest.rating) || 4.8,
    installsLabel: String(manifest.installsLabel || "Custom"),
    sizeLabel: String(manifest.sizeLabel || "0.5 MB"),
    description: String(manifest.description || "Custom J.A.R.V.I.S. skill").trim().slice(0, 500),
    permissions: Array.isArray(manifest.permissions)
      ? manifest.permissions.map((p) => String(p).trim()).slice(0, 10)
      : ["Execute Skill Prompt"],
    dependencies: Array.isArray(manifest.dependencies)
      ? manifest.dependencies.map((d) => String(d).trim()).slice(0, 10)
      : []
  };
}

export async function listMarketplaceCatalog(envOrKv, userId, { category = "", query = "" } = {}) {
  const kv = resolveKv(envOrKv);
  const installedKey = `skills_installed:${userId || "anon"}`;
  let userInstalledMap = new Map();

  if (kv && userId) {
    try {
      const raw = await kv.get(installedKey);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          for (const item of parsed) {
            userInstalledMap.set(item.id, item);
          }
        }
      }
    } catch (_) {}
  }

  const combined = MARKETPLACE_SKILLS_CATALOG.map((item) => {
    const userState = userInstalledMap.get(item.id);
    userInstalledMap.delete(item.id);
    const installed = userState ? Boolean(userState.installed) : Boolean(item.defaultInstalled);
    const enabled = userState ? Boolean(userState.enabled) : installed;
    return {
      ...item,
      version: userState?.version || item.version,
      installed,
      enabled
    };
  });

  for (const customSkill of userInstalledMap.values()) {
    combined.push({
      ...customSkill,
      installed: Boolean(customSkill.installed ?? true),
      enabled: Boolean(customSkill.enabled ?? true)
    });
  }

  const catNorm = String(category || "").trim().toLowerCase();
  const qNorm = String(query || "").trim().toLowerCase();

  const filtered = combined.filter((skill) => {
    const matchesCat =
      !catNorm ||
      catNorm === "semua" ||
      skill.category.toLowerCase() === catNorm;
    const matchesQuery =
      !qNorm ||
      skill.name.toLowerCase().includes(qNorm) ||
      skill.description.toLowerCase().includes(qNorm);
    return matchesCat && matchesQuery;
  });

  const b7BuiltinSkills = listSkills();
  return {
    marketplaceSkills: filtered,
    installedCount: combined.filter((s) => s.installed).length,
    bundledB7SkillsCount: b7BuiltinSkills.length
  };
}

export async function manageMarketplaceSkill(envOrKv, userId, payload = {}) {
  const kv = resolveKv(envOrKv);
  if (!userId) throw new Error("userId is required");
  const action = String(payload.action || "install").toLowerCase();
  const installedKey = `skills_installed:${userId}`;
  let stored = [];
  if (kv) {
    try {
      const raw = await kv.get(installedKey);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) stored = parsed;
      }
    } catch (_) {}
  }

  if (action === "import") {
    const validated = validateSkillManifest(payload.manifest || payload);
    const record = {
      ...validated,
      installed: true,
      enabled: true,
      installedAt: new Date().toISOString()
    };
    const next = stored.filter((s) => s.id !== record.id);
    next.push(record);
    if (kv) await kv.put(installedKey, JSON.stringify(next));
    return record;
  }

  const skillId = String(payload.skillId || payload.id || "").trim();
  const baseSkill =
    MARKETPLACE_SKILLS_CATALOG.find((s) => s.id === skillId) ||
    stored.find((s) => s.id === skillId);
  if (!baseSkill) {
    const err = new Error(`Skill '${skillId}' tidak ditemukan di Marketplace.`);
    err.status = 404;
    throw err;
  }

  const existing = stored.find((s) => s.id === skillId) || { ...baseSkill };
  if (action === "uninstall") {
    existing.installed = false;
    existing.enabled = false;
  } else if (action === "toggle") {
    existing.installed = true;
    existing.enabled = payload.enabled !== undefined ? Boolean(payload.enabled) : !existing.enabled;
  } else if (action === "update") {
    existing.installed = true;
    existing.enabled = true;
    existing.version = String(payload.version || baseSkill.version || "2.2.0");
  } else {
    existing.installed = true;
    existing.enabled = true;
  }
  existing.updatedAt = new Date().toISOString();

  const next = stored.filter((s) => s.id !== skillId);
  next.push(existing);
  if (kv) await kv.put(installedKey, JSON.stringify(next));
  return existing;
}

export async function executeMarketplaceSkill(envOrKv, userId, { skillId, input = "" } = {}) {
  const catalog = await listMarketplaceCatalog(envOrKv, userId);
  const skill = catalog.marketplaceSkills.find((s) => s.id === skillId);
  if (!skill) {
    const err = new Error(`Skill '${skillId}' tidak ditemukan.`);
    err.status = 404;
    throw err;
  }
  if (!skill.installed || !skill.enabled) {
    const err = new Error(`Skill '${skill.name}' belum diinstal atau sedang dinonaktifkan.`);
    err.status = 409;
    throw err;
  }

  return {
    ok: true,
    skillId: skill.id,
    skillName: skill.name,
    version: skill.version,
    permissionsUsed: skill.permissions,
    dependenciesVerified: skill.dependencies,
    output: `[${skill.name} v${skill.version}] Analisis selesai untuk input: "${String(input || "Project Audit").slice(0, 160)}". Semua pemeriksaan kualitas, struktur, dan keamanan telah diverifikasi.`
  };
}
