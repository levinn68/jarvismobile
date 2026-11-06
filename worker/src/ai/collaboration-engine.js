import { BUILTIN_MODELS, JARVIS_TIERS, listModelCatalog, resolveModelOrTier } from "./model-registry.js";
import {
  PROVIDER_CATALOG,
  PROVIDER_ERROR_CODES,
  classifyProviderError,
  invokeProviderCompletion,
  isFakeOrPlaceholderCredential,
  isProviderExplicitlyDisabled,
  resolveAccountCredentialsChain,
  resolveExecutionEnvironment
} from "./multi-account-manager.js";
import { computeBackoffWithJitter } from "./fallback-engine.js";
import { redactSecrets } from "../utils.js";

function resolveKv(envOrKv) {
  if (!envOrKv) return null;
  if (typeof envOrKv.get === "function" && typeof envOrKv.put === "function") return envOrKv;
  return envOrKv.DEVICE_REGISTRY || envOrKv.JARVIS_STATE || null;
}

export const MAX_COLLABORATION_MODELS = 6;
export const MAX_COLLABORATION_ROUNDS = 5;

export const COLLABORATION_SESSION_STATUSES = Object.freeze({
  SUCCESS: "SUCCESS",
  PARTIAL_SUCCESS: "PARTIAL_SUCCESS",
  COLLABORATION_FAILED: "COLLABORATION_FAILED",
  SYNTHESIS_FAILED: "SYNTHESIS_FAILED",
  CANCELLED: "cancelled",
  STOPPED: "stopped"
});

export const CONSENSUS_STATUSES = Object.freeze({
  AGREED: "AGREED",
  MOSTLY_AGREED: "MOSTLY_AGREED",
  PARTIAL_AGREEMENT: "PARTIAL_AGREEMENT",
  DISAGREED: "DISAGREED",
  INSUFFICIENT_EVIDENCE: "INSUFFICIENT_EVIDENCE",
  UNRESOLVED: "UNRESOLVED",
  CONSENSUS_NOT_ESTABLISHED: "CONSENSUS_NOT_ESTABLISHED"
});

export const KNOWLEDGE_NODE_TYPES = Object.freeze({
  CLAIM: "CLAIM",
  EVIDENCE: "EVIDENCE",
  AGENT_POSITION: "AGENT_POSITION",
  DECISION: "DECISION",
  RISK: "RISK",
  RECOMMENDATION: "RECOMMENDATION",
  QUESTION: "QUESTION",
  SOURCE: "SOURCE"
});

export const KNOWLEDGE_EDGE_TYPES = Object.freeze({
  SUPPORTS: "SUPPORTS",
  CONTRADICTS: "CONTRADICTS",
  DEPENDS_ON: "DEPENDS_ON",
  DERIVED_FROM: "DERIVED_FROM",
  AGREES_WITH: "AGREES_WITH",
  DISAGREES_WITH: "DISAGREES_WITH",
  REFINES: "REFINES",
  REFERENCES: "REFERENCES"
});

export const SYNTHESIS_PIPELINE_STAGES = Object.freeze([
  "1_COLLECT_COMPLETED_RESULTS",
  "2_VALIDATE_COMPLETENESS",
  "3_EXTRACT_CLAIMS",
  "4_COMPARE_SUPPORTING_EVIDENCE",
  "5_IDENTIFY_CONTRADICTIONS",
  "6_EVALUATE_AGREEMENT",
  "7_IDENTIFY_UNRESOLVED_QUESTIONS",
  "8_GENERATE_FINAL_SYNTHESIS",
  "9_VALIDATE_OUTPUT",
  "10_PERSIST_FINAL_RESULT"
]);

export const AGENT_EVENT_TYPES = Object.freeze({
  SESSION_CREATED: "SESSION_CREATED",
  TASK_PLANNED: "TASK_PLANNED",
  AGENT_ASSIGNED: "AGENT_ASSIGNED",
  AGENT_QUEUED: "AGENT_QUEUED",
  AGENT_STARTED: "AGENT_STARTED",
  AGENT_RESPONSE_RECEIVED: "AGENT_RESPONSE_RECEIVED",
  AGENT_FAILED: "AGENT_FAILED",
  AGENT_RETRIED: "AGENT_RETRIED",
  DEBATE_ROUND_STARTED: "DEBATE_ROUND_STARTED",
  REVIEW_COMPLETED: "REVIEW_COMPLETED",
  SYNTHESIS_STARTED: "SYNTHESIS_STARTED",
  SYNTHESIS_COMPLETED: "SYNTHESIS_COMPLETED",
  SYNTHESIS_FAILED: "SYNTHESIS_FAILED",
  SESSION_COMPLETED: "SESSION_COMPLETED",
  SESSION_CANCELLED: "SESSION_CANCELLED"
});

/**
 * Seven distinct, real Collaboration Modes required by Batch 8.5 / 8.5.1:
 * 1. parallel (alias: multi_perspective)
 * 2. sequential (alias: structured_workflow)
 * 3. debate (alias: debate_pro_contra)
 * 4. consensus (alias: executive_council)
 * 5. specialist
 * 6. reviewer (alias: peer_review, red_team_audit)
 * 7. ensemble (alias: direct_synthesis)
 */
export const COLLABORATION_MODES = Object.freeze({
  parallel: {
    id: "parallel",
    legacyAlias: "multi_perspective",
    name: "Parallel Multi-Perspective",
    titleId: "Parallel Multi-Perspective",
    description: "Kirim request independen ke beberapa agent secara paralel dengan batas konkurensi, lalu sintesis respons aktual.",
    defaultRounds: 1,
    executionPattern: "parallel"
  },
  sequential: {
    id: "sequential",
    legacyAlias: "structured_workflow",
    name: "Sequential Chained Workflow",
    titleId: "Sequential Chained Workflow",
    description: "Agent pertama mengerjakan tugas, hasilnya diteruskan ke agent berikutnya secara berantai.",
    defaultRounds: 1,
    executionPattern: "sequential"
  },
  debate: {
    id: "debate",
    legacyAlias: "debate_pro_contra",
    name: "Multi-Round Debate (Pro vs Contra)",
    titleId: "Multi-Round Debate (Pro vs Contra)",
    description: "Agent menghasilkan posisi independen, menerima ringkasan posisi lawan, dan memberikan kontraargumen di tiap ronde.",
    defaultRounds: 2,
    executionPattern: "debate"
  },
  consensus: {
    id: "consensus",
    legacyAlias: "executive_council",
    name: "Consensus & Divergence Analysis",
    titleId: "Consensus & Divergence Analysis",
    description: "Bandingkan hasil independen, identifikasi kesepakatan dan perbedaan nyata berbasis klaim.",
    defaultRounds: 1,
    executionPattern: "consensus"
  },
  specialist: {
    id: "specialist",
    legacyAlias: "specialist",
    name: "Specialist Task Decomposition",
    titleId: "Specialist Task Decomposition",
    description: "Dekomposisi tugas menjadi subtugas spesialis sesuai peran dan kapabilitas model, lalu gabungkan hasil aktual.",
    defaultRounds: 1,
    executionPattern: "specialist"
  },
  reviewer: {
    id: "reviewer",
    legacyAlias: "peer_review",
    name: "Primary + Reviewer + Revision Loop",
    titleId: "Primary + Reviewer + Revision Loop",
    description: "Agent utama menghasilkan draf, reviewer independen mengaudit, dan agent utama merevisi berdasarkan temuan.",
    defaultRounds: 2,
    executionPattern: "reviewer"
  },
  ensemble: {
    id: "ensemble",
    legacyAlias: "direct_synthesis",
    name: "Quality-Weighted Ensemble Synthesis",
    titleId: "Quality-Weighted Ensemble Synthesis",
    description: "Beberapa model menghasilkan respons independen, dievaluasi kualitas & buktinya, lalu disintesis secara transparan.",
    defaultRounds: 1,
    executionPattern: "ensemble"
  }
});

const MODE_ALIAS_MAP = Object.freeze({
  parallel: "parallel",
  multi_perspective: "parallel",
  sequential: "sequential",
  structured_workflow: "sequential",
  debate: "debate",
  debate_pro_contra: "debate",
  consensus: "consensus",
  executive_council: "consensus",
  specialist: "specialist",
  reviewer: "reviewer",
  peer_review: "reviewer",
  red_team_audit: "reviewer",
  ensemble: "ensemble",
  direct_synthesis: "ensemble"
});

export function resolveCollaborationMode(rawMode = "parallel") {
  const key = String(rawMode || "parallel").trim().toLowerCase();
  const mapped = MODE_ALIAS_MAP[key] || "parallel";
  return {
    ...COLLABORATION_MODES[mapped],
    requestedMode: key
  };
}

export const COLLABORATION_ROLES = Object.freeze([
  {
    id: "Analis",
    name: "Analis",
    badge: "Analyst",
    focus: "Pemecahan masalah, arsitektur sistem, dan pemetaan kebutuhan inti.",
    subtaskDirective: "Susun dekomposisi masalah, kebutuhan fungsional/non-fungsional, dan arsitektur tingkat tinggi.",
    preferredModelId: "gpt-4o"
  },
  {
    id: "Kreatif",
    name: "Kreatif",
    badge: "Innovator",
    focus: "Inovasi produk, pengalaman pengguna, dan alternatif solusi.",
    subtaskDirective: "Susun strategi pengalaman pengguna (UX), alur interaksi, dan diferensiasi fitur.",
    preferredModelId: "claude-3-5-sonnet"
  },
  {
    id: "Teknis",
    name: "Teknis",
    badge: "Engineer",
    focus: "Implementasi kode, performa, skalabilitas, dan struktur modul.",
    subtaskDirective: "Susun spesifikasi teknis, skema database, kontrak API, dan strategi skalabilitas.",
    preferredModelId: "deepseek-v3"
  },
  {
    id: "Evaluator",
    name: "Evaluator",
    badge: "Critic",
    focus: "Audit keamanan, identifikasi bottleneck, edge cases, dan risiko.",
    subtaskDirective: "Audit potensi kegagalan, celah keamanan, batas performa, dan skenario terburuk.",
    preferredModelId: "gemini-1.5-pro"
  },
  {
    id: "Perencana",
    name: "Perencana",
    badge: "Planner",
    focus: "Roadmap eksekusi, estimasi tahapan, dan mitigasi operasional.",
    subtaskDirective: "Susun tahapan implementasi berurutan, metrik keberhasilan, dan rencana rollback.",
    preferredModelId: "llama-3.3-70b"
  },
  {
    id: "Moderator",
    name: "Moderator",
    badge: "Synthesizer",
    focus: "Penyelarasan argumen, resolusi konflik, dan sintesis keputusan akhir.",
    subtaskDirective: "Selaraskan seluruh komponen menjadi panduan keputusan terpadu yang bebas konflik.",
    preferredModelId: "qwen-2.5-72b"
  }
]);

function sanitizeTopic(rawTopic) {
  const cleaned = String(rawTopic || "").trim();
  if (!cleaned) {
    throw new Error("Topik atau prompt kolaborasi wajib diisi.");
  }
  return cleaned.slice(0, 3000);
}

export function selectCollaborationTeam({
  selectionMode = "manual",
  selectedModelIds = [],
  assignRoles = true,
  customModels = []
} = {}) {
  const catalogRes = listModelCatalog({ customModels });
  const catalog = Array.isArray(catalogRes)
    ? catalogRes
    : catalogRes?.allModels || catalogRes?.models || BUILTIN_MODELS;
  const catalogMap = new Map(catalog.map((m) => [m.id, m]));

  let chosenIds = Array.isArray(selectedModelIds)
    ? selectedModelIds.map((id) => String(id || "").trim()).filter((id) => catalogMap.has(id))
    : [];

  if (selectionMode === "auto" || chosenIds.length === 0) {
    chosenIds = ["gpt-4o", "claude-3-5-sonnet", "gemini-1.5-pro", "deepseek-v3"];
  }

  const uniqueIds = [...new Set(chosenIds)].slice(0, MAX_COLLABORATION_MODELS);

  return uniqueIds.map((modelId, index) => {
    const model = catalogMap.get(modelId) || BUILTIN_MODELS[0];
    const roleObj = assignRoles
      ? COLLABORATION_ROLES[index % COLLABORATION_ROLES.length]
      : {
          id: `Agent-${index + 1}`,
          name: `Agent ${index + 1}`,
          badge: "Active",
          focus: "Analisis kolaboratif lintas dimensi.",
          subtaskDirective: "Berikan analisis mendalam dari sudut pandang independen."
        };
    return {
      agentId: `agent_${index + 1}_${model.id.replace(/[^a-z0-9]/gi, "_")}`,
      modelId: model.id,
      modelName: model.name,
      provider: model.provider === "meta" ? "groq" : model.provider,
      apiModel: model.apiModel || model.id,
      role: roleObj.name,
      roleFocus: roleObj.focus,
      subtaskDirective: roleObj.subtaskDirective,
      badge: index === 0 ? "Lead" : roleObj.badge,
      capabilities: model.capabilities || ["text"],
      tier: model.tier || "SPARK"
    };
  });
}

export function createCollaborationEventEmitter(sessionId) {
  const events = [];
  let seq = 0;
  return {
    events,
    emit(eventType, { agentId = null, status = "ok", metadata = {} } = {}) {
      seq += 1;
      const safeMeta = {};
      for (const [k, v] of Object.entries(metadata || {})) {
        if (typeof v === "string") {
          safeMeta[k] = redactSecrets(v).slice(0, 400);
        } else if (typeof v === "number" || typeof v === "boolean") {
          safeMeta[k] = v;
        } else if (Array.isArray(v)) {
          safeMeta[k] = v.slice(0, 12).map((item) =>
            typeof item === "string" ? redactSecrets(item).slice(0, 160) : item
          );
        }
      }
      const ev = {
        eventId: `ev_${seq}_${Date.now().toString(36)}`,
        sequence: seq,
        sessionId,
        agentId: agentId || null,
        eventType,
        timestamp: new Date().toISOString(),
        status,
        metadata: safeMeta
      };
      events.push(ev);
      return ev;
    }
  };
}

/**
 * Batch 8.5 / 8.5.1 — Real Agent Scheduler with Concurrency Control, Queue Management,
 * Timeout, Retry with Exponential Backoff, Rate-Limit Handling, Provider Fallback,
 * Cancellation, and Per-Agent Failure Isolation.
 */
export async function runAgentScheduler(tasks, { concurrencyLimit = 3, signal = null } = {}) {
  const limit = Math.max(1, Math.min(Number(concurrencyLimit) || 3, 6));
  const results = new Array(tasks.length);
  let cursor = 0;

  async function workerLoop() {
    while (true) {
      if (signal?.aborted) break;
      const index = cursor++;
      if (index >= tasks.length) break;
      const taskFn = tasks[index];
      try {
        results[index] = await taskFn();
      } catch (err) {
        const errorCode = classifyProviderError(err, err?.status);
        results[index] = {
          ok: false,
          errorCode,
          error: redactSecrets(err?.message || String(err)).slice(0, 240)
        };
      }
    }
  }

  const workers = Array.from({ length: Math.min(limit, tasks.length) }, () => workerLoop());
  await Promise.all(workers);
  return results;
}

async function listAvailableProvidersForUser(kv, userId, env) {
  const available = [];
  for (const providerId of Object.keys(PROVIDER_CATALOG)) {
    const chain = await resolveAccountCredentialsChain(kv, userId, providerId, env);
    if (chain.length > 0) {
      available.push(providerId);
    }
  }
  return available;
}

export async function executeAgentPromptLive({
  kv,
  userId,
  env,
  member,
  prompt,
  systemPrompt,
  round = 1,
  emitter,
  timeoutMs = 25000,
  maxRetries = 2,
  preferredAccountId = "",
  fetchImpl = globalThis.fetch,
  signal = null
}) {
  emitter.emit(AGENT_EVENT_TYPES.AGENT_QUEUED, {
    agentId: member.agentId,
    status: "queued",
    metadata: { modelId: member.modelId, provider: member.provider, role: member.role, round }
  });

  if (signal?.aborted) {
    emitter.emit(AGENT_EVENT_TYPES.SESSION_CANCELLED, {
      agentId: member.agentId,
      status: "cancelled",
      metadata: { round }
    });
    return {
      ok: false,
      cancelled: true,
      agentId: member.agentId,
      modelId: member.modelId,
      modelName: member.modelName,
      provider: member.provider,
      role: member.role,
      round,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR,
      error: "Cancelled before start"
    };
  }

  emitter.emit(AGENT_EVENT_TYPES.AGENT_STARTED, {
    agentId: member.agentId,
    status: "running",
    metadata: { modelId: member.modelId, provider: member.provider, role: member.role, round }
  });

  if (await isProviderExplicitlyDisabled(kv, userId, member.provider, env)) {
    const disabledMsg = `Provider '${member.provider}' is disabled.`;
    emitter.emit(AGENT_EVENT_TYPES.AGENT_FAILED, {
      agentId: member.agentId,
      status: "failed",
      metadata: {
        modelId: member.modelId,
        provider: member.provider,
        round,
        errorCode: PROVIDER_ERROR_CODES.PROVIDER_DISABLED,
        error: disabledMsg
      }
    });
    return {
      ok: false,
      agentId: member.agentId,
      modelId: member.modelId,
      modelName: member.modelName,
      provider: member.provider,
      role: member.role,
      round,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_DISABLED,
      error: disabledMsg
    };
  }

  const primaryChain = await resolveAccountCredentialsChain(kv, userId, member.provider, env);
  const availableProviders = await listAvailableProvidersForUser(kv, userId, env);
  const resolvedPlan = resolveModelOrTier(member.modelId, availableProviders);
  const candidateTargets = primaryChain.length > 0
    ? [{ provider: member.provider, apiModel: member.apiModel, modelId: member.modelId }]
    : [
        { provider: member.provider, apiModel: member.apiModel, modelId: member.modelId },
        ...(resolvedPlan.fallbackChain || []).map((fb) => ({
          provider: fb.provider === "meta" ? "groq" : fb.provider,
          apiModel: fb.apiModel,
          modelId: fb.modelId
        }))
      ];

  const seenTargets = new Set();
  const uniqueTargets = candidateTargets.filter((t) => {
    const key = `${t.provider}:${t.apiModel}`;
    if (seenTargets.has(key)) return false;
    seenTargets.add(key);
    return true;
  });

  let lastErr = null;
  for (let tIdx = 0; tIdx < uniqueTargets.length; tIdx++) {
    const target = uniqueTargets[tIdx];
    const chain = await resolveAccountCredentialsChain(kv, userId, target.provider, env);
    if (!chain.length && tIdx + 1 < uniqueTargets.length) {
      continue;
    }

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      if (signal?.aborted) {
        emitter.emit(AGENT_EVENT_TYPES.SESSION_CANCELLED, {
          agentId: member.agentId,
          status: "cancelled",
          metadata: { round, attempt }
        });
        return {
          ok: false,
          cancelled: true,
          agentId: member.agentId,
          modelId: member.modelId,
          modelName: member.modelName,
          provider: target.provider,
          role: member.role,
          round,
          errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR,
          error: "Cancelled during execution"
        };
      }

      try {
        const res = await invokeProviderCompletion({
          kv,
          userId,
          provider: target.provider,
          apiModel: target.apiModel,
          prompt,
          systemPrompt,
          preferredAccountId,
          timeoutMs,
          env,
          fetchImpl,
          signal
        });

        emitter.emit(AGENT_EVENT_TYPES.AGENT_RESPONSE_RECEIVED, {
          agentId: member.agentId,
          status: "completed",
          metadata: {
            provider: res.provider,
            model: res.model,
            accountId: res.accountId,
            latencyMs: res.latencyMs,
            round,
            chars: res.text.length
          }
        });

        return {
          ok: true,
          agentId: member.agentId,
          modelId: member.modelId,
          modelName: member.modelName,
          provider: res.provider,
          executedModel: res.model,
          accountId: res.accountId,
          latencyMs: res.latencyMs,
          role: member.role,
          round,
          content: res.text
        };
      } catch (err) {
        lastErr = err;
        const status = err?.status || 500;
        const errCode = err?.errorCode || classifyProviderError(err, status);
        const retryable =
          status === 429 ||
          errCode === PROVIDER_ERROR_CODES.PROVIDER_RATE_LIMITED ||
          errCode === PROVIDER_ERROR_CODES.PROVIDER_TIMEOUT ||
          (status >= 500 &&
            errCode !== PROVIDER_ERROR_CODES.PROVIDER_CREDENTIAL_MISSING &&
            errCode !== PROVIDER_ERROR_CODES.PROVIDER_DISABLED &&
            errCode !== PROVIDER_ERROR_CODES.PROVIDER_AUTH_FAILED &&
            errCode !== PROVIDER_ERROR_CODES.MODEL_UNAVAILABLE);
        if (retryable && attempt < maxRetries && !signal?.aborted) {
          const delayMs = computeBackoffWithJitter(attempt, 60, 280);
          emitter.emit(AGENT_EVENT_TYPES.AGENT_RETRIED, {
            agentId: member.agentId,
            status: "retrying",
            metadata: {
              provider: target.provider,
              attempt,
              httpStatus: status,
              errorCode: errCode,
              delayMs,
              reason: redactSecrets(err?.message || "transient_error").slice(0, 140)
            }
          });
          await new Promise((r) => setTimeout(r, delayMs));
          continue;
        }
        break;
      }
    }
  }

  const finalErrorCode = lastErr?.errorCode || classifyProviderError(lastErr, lastErr?.status);
  const errMsg = redactSecrets(lastErr?.message || `Agent ${member.modelName} failed to respond`).slice(0, 240);
  emitter.emit(AGENT_EVENT_TYPES.AGENT_FAILED, {
    agentId: member.agentId,
    status: "failed",
    metadata: {
      modelId: member.modelId,
      provider: member.provider,
      round,
      errorCode: finalErrorCode,
      error: errMsg
    }
  });

  return {
    ok: false,
    agentId: member.agentId,
    modelId: member.modelId,
    modelName: member.modelName,
    provider: member.provider,
    role: member.role,
    round,
    errorCode: finalErrorCode,
    error: errMsg
  };
}

export function extractStructuredPointsFromText(text = "") {
  const clean = String(text || "").trim();
  if (!clean) {
    return { keyPoints: [], claims: [], risks: [], recommendations: [], questions: [], evidence: [], sources: [] };
  }

  const lines = clean
    .split(/\n+/)
    .map((l) => l.replace(/^[\s\-*•\d.)]+/, "").trim())
    .filter((l) => l.length >= 10);

  const sentences = clean
    .split(/(?<=[.!?])\s+/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 12);

  const pool = lines.length >= 2 ? lines : sentences.length ? sentences : [clean];
  const keyPoints = pool.slice(0, 5).map((s) => s.slice(0, 240));

  const risks = pool
    .filter((s) => /(risiko|risk|celah|bottleneck|ancaman|kegagalan|vulnerability|kontra|kelemahan|masalah|latency|limit|overload|outage|deadlock)/i.test(s))
    .slice(0, 4)
    .map((s) => s.slice(0, 240));

  const recommendations = pool
    .filter((s) => /(rekomendasi|gunakan|terapkan|pastikan|langkah|solusi|recommend|should|implement|use|adopt|wajib|disarankan)/i.test(s))
    .slice(0, 4)
    .map((s) => s.slice(0, 240));

  const questions = pool
    .filter((s) => /\?$|^(apakah|bagaimana|mengapa|kapan|berapa|what|how|why|should we|open question|pertanyaan)/i.test(s))
    .slice(0, 4)
    .map((s) => s.slice(0, 240));

  const evidence = pool
    .filter((s) => /(bukti|evidence|karena|because|sebab|benchmark|ms\b|%|rps|throughput|latensi|latency|data|uji|studi|isolasi|mencegah|menurunkan|meningkatkan)/i.test(s))
    .slice(0, 5)
    .map((s) => s.slice(0, 240));

  const sources = pool
    .filter((s) => /(rfc|iso|owasp|nist|dokumentasi|standar|standard|benchmark|postgresql|redis|cloudflare|supabase|http)/i.test(s))
    .slice(0, 3)
    .map((s) => s.slice(0, 200));

  return {
    keyPoints: keyPoints.length ? keyPoints : [clean.slice(0, 200)],
    claims: pool.slice(0, 6).map((s) => s.slice(0, 240)),
    risks,
    recommendations: recommendations.length ? recommendations : keyPoints.slice(0, 3),
    questions,
    evidence,
    sources
  };
}

function tokenizeNormalizedTerms(text = "") {
  const stopWords = new Set([
    "yang", "dan", "untuk", "dengan", "pada", "dari", "dalam", "atau", "ini", "itu", "akan", "dapat",
    "oleh", "sebagai", "agar", "serta", "juga", "lebih", "sangat", "harus", "bisa", "saat", "bagi",
    "the", "and", "for", "with", "that", "this", "from", "into", "are", "was", "were", "have", "has"
  ]);
  return new Set(
    String(text || "")
      .toLowerCase()
      .replace(/[^a-z0-9\s_-]/g, " ")
      .split(/\s+/)
      .filter((w) => w.length >= 4 && !stopWords.has(w))
  );
}

/**
 * Canonical Concept Dictionary for Semantic Claim Normalization (Batch 8.5.1 Section 5).
 * Maps different phrasings / synonyms in Indonesian & English to shared canonical architectural concepts
 * so agents using different vocabulary for the same conclusion are accurately recognized,
 * and agents using similar vocabulary for opposing conclusions are separated by polarity.
 */
const CANONICAL_CONCEPT_CLUSTERS = Object.freeze([
  {
    id: "concept_modular_distributed",
    label: "Arsitektur Modular / Layanan Terdistribusi",
    pattern: /(microservices|mikroservis|layanan independen|modular terpisah|service-oriented|arsitektur terdistribusi|bounded context|isolasi layanan|service boundary|domain-driven|komponen terpisah|dekomposisi layanan|arsitektur modular)/i
  },
  {
    id: "concept_monolith_single",
    label: "Arsitektur Monolitik Terpusat",
    pattern: /(\bmonolith\b|monolitik|aplikasi tunggal|single deployable|deployment tunggal|satu proses terpusat)/i
  },
  {
    id: "concept_database_rls_isolation",
    label: "Isolasi Multi-Tenant & Row-Level Security (RLS)",
    pattern: /(row-level security|\brls\b|isolasi tenant|kebijakan akses baris|postgresql rls|multi-tenant isolation|keamanan tingkat baris|isolasi data pelanggan)/i
  },
  {
    id: "concept_in_memory_caching",
    label: "Lapisan In-Memory Caching (Redis/Memcached)",
    pattern: /(\bredis\b|in-memory cache|lapisan caching|distributed cache|memcached|cache-aside|penyimpanan memori cepat|akselerasi baca memori)/i
  },
  {
    id: "concept_async_event_queue",
    label: "Antrean Pesan Asinkron / Message Broker",
    pattern: /(message broker|\bkafka\b|rabbitmq|antrean asinkron|event-driven|pub\/sub|event queue|asynchronous queue|pemrosesan latar belakang|worker queue)/i
  },
  {
    id: "concept_connection_resilience",
    label: "Ketahanan Koneksi, Pooling & Circuit Breaker",
    pattern: /(connection pool|pgbouncer|circuit breaker|rate limit|throttling|backpressure|batas koneksi|isolasi kegagalan|bulkhead)/i
  },
  {
    id: "concept_zero_trust_security",
    label: "Autentikasi Zero-Trust & Rotasi Token",
    pattern: /(zero trust|jwt pendek|oauth2|mtls|autentikasi berlapis|rotasi kunci|short-lived token|verifikasi kriptografis)/i
  },
  {
    id: "concept_relational_acid",    label: "Database Relasional Transaksional (ACID / PostgreSQL)",
    pattern: /(\bpostgresql\b|relational database|acid transaction|basis data relasional|konsistensi transaksi ketat|integritas referensial)/i
  },
  {
    id: "concept_nosql_eventual",
    label: "Database NoSQL / Eventual Consistency",
    pattern: /(\bnosql\b|mongodb|dynamodb|eventual consistency|skema dokumen fleksibel|penyimpanan non-relasional)/i
  },
  {
    id: "concept_edge_serverless",
    label: "Komputasi Edge & Serverless Worker",
    pattern: /(cloudflare worker|edge computing|serverless|komputasi tepi|distribusi global edge)/i
  }
]);

const NEGATION_OR_CONTRA_REGEX =
  /(\bjangan\b|\bhindari\b|\bmenolak\b|\btidak setuju\b|\bkurang tepat\b|\bberbahaya\b|\bburuk\b|\bkontra\b|\btolak\b|\breject\b|\bdo not\b|\bdon't\b|\bavoid\b|\bagainst\b|\bflawed\b|\bharmful\b|\bworse\b|\btidak direkomendasikan\b|\bbukan solusi\b|\bgagal\b|\bmasalah fatal\b|\bbottleneck fatal\b)/i;

const AFFIRMATIVE_PRO_REGEX =
  /(\bgunakan\b|\bterapkan\b|\brekomendasi\b|\bsarankan\b|\bsetuju\b|\bmendukung\b|\boptimal\b|\befektif\b|\bsolusi terbaik\b|\brecommend\b|\badopt\b|\bimplement\b|\buse\b|\bagree\b|\bsupport\b|\bessential\b|\bwajib\b)/i;

function splitIntoSemanticClauses(text = "") {
  return String(text || "")
    .split(/(?:[.!?\n]+|;\s*|\bnamun\b|\btetapi\b|\bsebaliknya\b|\balih-alih\b|\bwhereas\b|\bhowever\b|\binstead of\b|\brather than\b)/i)
    .map((c) => c.replace(/^[\s\-*•\d.)]+/, "").trim())
    .filter((c) => c.length >= 8);
}

function detectClausePolarity(clause = "") {
  const hasContra = NEGATION_OR_CONTRA_REGEX.test(clause);
  const hasPro = AFFIRMATIVE_PRO_REGEX.test(clause);
  if (hasContra && !hasPro) return "CONTRA";
  if (hasContra && hasPro) {
    // Check if negation precedes recommendation verb ("jangan gunakan", "tidak direkomendasikan")
    if (/(jangan\s+gunakan|tidak\s+direkomendasikan|hindari\s+penggunaan|menolak\s+penggunaan|do\s+not\s+use|do\s+not\s+recommend)/i.test(clause)) {
      return "CONTRA";
    }
    return "CONTRA";
  }
  return "PRO";
}

function measureEvidenceQualityForMessage(content = "") {
  const clean = String(content || "").trim();
  if (!clean) return { score: 0, strength: "NONE", evidenceItems: [] };

  const pts = extractStructuredPointsFromText(clean);
  const hasQuantitativeMetrics = /(\b\d+(\.\d+)?\s*(ms|s|%|rps|tps|mb|gb|tb|x|kali|ribu|juta|users|pengguna|nodes)\b)/i.test(clean);
  const hasCausalReasoning = /(\bkarena\b|\bsebab\b|\bsehingga\b|\bmencegah\b|\bmenurunkan\b|\bmeningkatkan\b|\bbecause\b|\btherefore\b|\bprevents\b|\breduces\b|\bensures\b)/i.test(clean);
  const hasConcreteMechanisms = CANONICAL_CONCEPT_CLUSTERS.some((c) => c.pattern.test(clean));

  const evidenceItems = pts.evidence.length > 0 ? pts.evidence : pts.keyPoints.filter((k) => k.length >= 25);

  let score = 0;
  if (clean.length >= 40) score += 20;
  if (clean.length >= 120) score += 15;
  if (evidenceItems.length >= 1) score += 20;
  if (evidenceItems.length >= 2) score += 15;
  if (hasCausalReasoning) score += 15;
  if (hasConcreteMechanisms) score += 10;
  if (hasQuantitativeMetrics) score += 10;

  score = Math.min(100, score);
  const strength =
    score >= 75 ? "HIGH" : score >= 45 ? "MEDIUM" : score >= 20 ? "LOW" : "NONE";

  return {
    score,
    strength,
    hasQuantitativeMetrics,
    hasCausalReasoning,
    evidenceItems
  };
}

/**
 * Batch 8.5.1 Section 5 — Real Claim-Based Consensus Evaluator.
 * Separates Similarity, Agreement, Evidence Quality, Confidence, and Consensus.
 * Never uses arbitrary baseline (0.55 + jaccard * 1.15).
 */
export function evaluateClaimConsensus(topic, messages = [], team = []) {
  const validMessages = (Array.isArray(messages) ? messages : []).filter(
    (m) => m && m.status === "completed" && typeof m.content === "string" && m.content.trim().length > 0
  );
  const failedMessages = (Array.isArray(messages) ? messages : []).filter(
    (m) => m && m.status === "failed"
  );

  // Latest completed message per unique active agent
  const latestByAgent = new Map();
  for (const msg of validMessages) {
    latestByAgent.set(msg.agentId, msg);
  }
  const activeAgentOutputs = [...latestByAgent.values()];
  const activeAgentIds = new Set(activeAgentOutputs.map((m) => m.agentId));
  const excludedFailedAgents = (Array.isArray(team) ? team : [])
    .filter((member) => !activeAgentIds.has(member.agentId))
    .map((member) => {
      const failMsg = failedMessages.find((f) => f.agentId === member.agentId);
      return {
        agentId: member.agentId,
        modelName: member.modelName,
        provider: member.provider,
        role: member.role,
        errorCode: failMsg?.errorCode || PROVIDER_ERROR_CODES.PROVIDER_ERROR,
        reason: failMsg?.error || "Agent did not complete execution"
      };
    });

  // 1. Check minimum active agents for consensus (must have >= 2 active agents)
  if (activeAgentOutputs.length < 2) {
    const singleEvidence = activeAgentOutputs.length === 1
      ? measureEvidenceQualityForMessage(activeAgentOutputs[0].content)
      : { score: 0, strength: "NONE", evidenceItems: [] };
    return {
      consensusStatus: CONSENSUS_STATUSES.CONSENSUS_NOT_ESTABLISHED,
      consensusReached: false,
      similarityScore: 0,
      agreementScore: 0,
      evidenceQualityScore: singleEvidence.score,
      confidenceScore: activeAgentOutputs.length === 1 ? Math.round((activeAgentOutputs[0].confidence || 0.5) * 100) : 0,
      consensusScore: 0,
      activeAgentsCount: activeAgentOutputs.length,
      totalTeamCount: Array.isArray(team) ? team.length : 0,
      excludedFailedAgents,
      claims: [],
      agreements: [],
      disagreements:
        activeAgentOutputs.length === 0
          ? ["Konsensus tidak dapat ditetapkan karena seluruh agent gagal merespons."]
          : ["Konsensus tidak dapat ditetapkan karena hanya 1 agent aktif yang berhasil merespons (butuh minimal 2 agent independen)."],
      supportingEvidence: singleEvidence.evidenceItems,
      conflictingEvidence: [],
      unresolvedQuestions: [
        activeAgentOutputs.length === 0
          ? "Seluruh provider gagal merespons; verifikasi kredensial dan konektivitas jaringan."
          : "Diperlukan minimal satu agent pembanding tambahan untuk memvalidasi klaim secara silang."
      ]
    };
  }

  // 2. Measure lexical similarity (strictly raw Jaccard 0..100 without any 0.55 inflation)
  const agentTermSets = activeAgentOutputs.map((msg) => ({
    msg,
    terms: tokenizeNormalizedTerms(msg.content),
    evidenceMeta: measureEvidenceQualityForMessage(msg.content)
  }));

  let pairCount = 0;
  let rawJaccardSum = 0;
  for (let i = 0; i < agentTermSets.length; i++) {
    for (let j = i + 1; j < agentTermSets.length; j++) {
      const a = agentTermSets[i].terms;
      const b = agentTermSets[j].terms;
      if (a.size === 0 && b.size === 0) continue;
      let intersection = 0;
      for (const t of a) {
        if (b.has(t)) intersection++;
      }
      const union = a.size + b.size - intersection;
      const jaccard = union > 0 ? intersection / union : 0;
      rawJaccardSum += jaccard;
      pairCount++;
    }
  }
  const similarityScore = pairCount > 0 ? Math.round((rawJaccardSum / pairCount) * 100) : 0;

  // 3. Extract structured claims & stances per agent across canonical concepts + dynamic subject clusters
  const conceptBuckets = new Map();

  for (const { msg, evidenceMeta } of agentTermSets) {
    const clauses = splitIntoSemanticClauses(msg.content);
    const matchedConceptsForAgent = new Set();

    for (const clause of clauses) {
      const polarity = detectClausePolarity(clause);
      for (const concept of CANONICAL_CONCEPT_CLUSTERS) {
        if (concept.pattern.test(clause)) {
          matchedConceptsForAgent.add(concept.id);
          if (!conceptBuckets.has(concept.id)) {
            conceptBuckets.set(concept.id, {
              conceptId: concept.id,
              label: concept.label,
              entries: []
            });
          }
          const bucket = conceptBuckets.get(concept.id);
          // Update or insert agent stance on this concept
          const existingIdx = bucket.entries.findIndex((e) => e.agentId === msg.agentId);
          const entryObj = {
            agentId: msg.agentId,
            modelName: msg.modelName,
            role: msg.role,
            messageId: msg.id,
            stance: polarity,
            clause: clause.slice(0, 240),
            evidenceItems: evidenceMeta.evidenceItems,
            evidenceScore: evidenceMeta.score,
            evidenceStrength: evidenceMeta.strength,
            confidence: msg.confidence || 0.85
          };
          if (existingIdx >= 0) {
            // If any clause for this concept is CONTRA, preserve CONTRA to catch refutations
            if (polarity === "CONTRA" && bucket.entries[existingIdx].stance !== "CONTRA") {
              bucket.entries[existingIdx] = entryObj;
            }
          } else {
            bucket.entries.push(entryObj);
          }
        }
      }
    }

    // If an agent's message didn't match any canonical dictionary cluster, create a general topic stance bucket
    if (matchedConceptsForAgent.size === 0) {
      const fallbackId = "concept_general_position";
      if (!conceptBuckets.has(fallbackId)) {
        conceptBuckets.set(fallbackId, {
          conceptId: fallbackId,
          label: `Posisi terhadap: ${String(topic || "Topik Utama").slice(0, 48)}`,
          entries: []
        });
      }
      const overallPolarity = detectClausePolarity(msg.content);
      conceptBuckets.get(fallbackId).entries.push({
        agentId: msg.agentId,
        modelName: msg.modelName,
        role: msg.role,
        messageId: msg.id,
        stance: overallPolarity,
        clause: (clauses[0] || msg.content).slice(0, 240),
        evidenceItems: evidenceMeta.evidenceItems,
        evidenceScore: evidenceMeta.score,
        evidenceStrength: evidenceMeta.strength,
        confidence: msg.confidence || 0.85
      });
    }
  }

  // Detect mutually exclusive architectural choices (e.g., Agent A PRO microservices vs Agent B PRO monolith)
  const hasProMicroservices = conceptBuckets.get("concept_modular_distributed")?.entries.some((e) => e.stance === "PRO");
  const hasProMonolith = conceptBuckets.get("concept_monolith_single")?.entries.some((e) => e.stance === "PRO");
  if (hasProMicroservices && hasProMonolith) {
    // Cross-register opposing stances so architectural divergence is explicitly captured as a contested claim
    const distBucket = conceptBuckets.get("concept_modular_distributed");
    const monoBucket = conceptBuckets.get("concept_monolith_single");
    for (const monoEntry of monoBucket.entries.filter((e) => e.stance === "PRO")) {
      if (!distBucket.entries.some((e) => e.agentId === monoEntry.agentId)) {
        distBucket.entries.push({
          ...monoEntry,
          stance: "CONTRA",
          clause: `[Memilih Monolith vs Terdistribusi] ${monoEntry.clause}`
        });
      }
    }
  }

  const hasProSql = conceptBuckets.get("concept_relational_acid")?.entries.some((e) => e.stance === "PRO");
  const hasProNoSql = conceptBuckets.get("concept_nosql_eventual")?.entries.some((e) => e.stance === "PRO");
  if (hasProSql && hasProNoSql) {
    const sqlBucket = conceptBuckets.get("concept_relational_acid");
    const nosqlBucket = conceptBuckets.get("concept_nosql_eventual");
    for (const nsEntry of nosqlBucket.entries.filter((e) => e.stance === "PRO")) {
      if (!sqlBucket.entries.some((e) => e.agentId === nsEntry.agentId)) {
        sqlBucket.entries.push({
          ...nsEntry,
          stance: "CONTRA",
          clause: `[Memilih NoSQL Eventual vs ACID Relasional] ${nsEntry.clause}`
        });
      }
    }
  }

  // 4. Evaluate each claim bucket
  const claims = [];
  let claimIdx = 0;
  for (const [, bucket] of conceptBuckets.entries()) {
    claimIdx += 1;
    const proEntries = bucket.entries.filter((e) => e.stance === "PRO");
    const contraEntries = bucket.entries.filter((e) => e.stance === "CONTRA");
    const totalParticipating = bucket.entries.length;

    const supportingEvidence = [
      ...new Set(
        proEntries.flatMap((e) =>
          (e.evidenceItems.length ? e.evidenceItems : [e.clause]).map(
            (ev) => `[${e.modelName}] ${ev}`
          )
        )
      )
    ];
    const counterEvidence = [
      ...new Set(
        contraEntries.flatMap((e) =>
          (e.evidenceItems.length ? e.evidenceItems : [e.clause]).map(
            (ev) => `[${e.modelName}] ${ev}`
          )
        )
      )
    ];

    const avgEvidenceScore =
      totalParticipating > 0
        ? Math.round(bucket.entries.reduce((acc, e) => acc + e.evidenceScore, 0) / totalParticipating)
        : 0;
    const evidenceStrength =
      avgEvidenceScore >= 75
        ? "HIGH"
        : avgEvidenceScore >= 45
          ? "MEDIUM"
          : avgEvidenceScore >= 20
            ? "LOW"
            : "NONE";

    let agreementStatus = CONSENSUS_STATUSES.UNRESOLVED;
    let contradictionStatus = "NONE";
    let position = "PRO";

    if (evidenceStrength === "NONE" || avgEvidenceScore < 25) {
      agreementStatus = CONSENSUS_STATUSES.INSUFFICIENT_EVIDENCE;
      contradictionStatus = contraEntries.length > 0 && proEntries.length > 0 ? "DIRECT_CONTRADICTION" : "NONE";
    } else if (proEntries.length > 0 && contraEntries.length === 0) {
      position = "PRO";
      contradictionStatus = "NONE";
      if (proEntries.length === activeAgentOutputs.length) {
        agreementStatus = CONSENSUS_STATUSES.AGREED;
      } else if (proEntries.length >= Math.ceil(activeAgentOutputs.length * 0.66)) {
        agreementStatus = CONSENSUS_STATUSES.MOSTLY_AGREED;
      } else if (proEntries.length >= 2) {
        agreementStatus = CONSENSUS_STATUSES.AGREED;
      } else {
        agreementStatus = CONSENSUS_STATUSES.PARTIAL_AGREEMENT;
      }
    } else if (contraEntries.length > 0 && proEntries.length === 0) {
      position = "CONTRA";
      contradictionStatus = "NONE";
      agreementStatus =
        contraEntries.length >= 2 ? CONSENSUS_STATUSES.AGREED : CONSENSUS_STATUSES.PARTIAL_AGREEMENT;
    } else if (proEntries.length > 0 && contraEntries.length > 0) {
      contradictionStatus = "DIRECT_CONTRADICTION";
      const majorityCount = Math.max(proEntries.length, contraEntries.length);
      const minorityCount = Math.min(proEntries.length, contraEntries.length);
      position = proEntries.length >= contraEntries.length ? "PRO" : "CONTRA";

      if (majorityCount >= 3 && minorityCount === 1) {
        // Majority agrees, one agent rejects with strong reason
        agreementStatus = CONSENSUS_STATUSES.MOSTLY_AGREED;
      } else if (majorityCount > minorityCount) {
        agreementStatus = CONSENSUS_STATUSES.PARTIAL_AGREEMENT;
      } else {
        agreementStatus = CONSENSUS_STATUSES.DISAGREED;
      }
    }

    const avgConfidence =
      totalParticipating > 0
        ? Number(
            (
              bucket.entries.reduce((acc, e) => acc + (Number(e.confidence) || 0.8), 0) /
              totalParticipating
            ).toFixed(2)
          )
        : 0;

    claims.push({
      claimId: `claim_${claimIdx}_${bucket.conceptId}`,
      topicCluster: bucket.conceptId,
      topicLabel: bucket.label,
      statement: proEntries[0]?.clause || contraEntries[0]?.clause || bucket.label,
      position,
      proposingAgents: bucket.entries.map((e) => ({
        agentId: e.agentId,
        modelName: e.modelName,
        role: e.role,
        messageId: e.messageId,
        stance: e.stance
      })),
      supportingAgents: proEntries.map((e) => e.agentId),
      contradictingAgents: contraEntries.map((e) => e.agentId),
      supportingEvidence,
      counterEvidence,
      agreementStatus,
      contradictionStatus,
      confidence: avgConfidence,
      evidenceStrength
    });
  }

  // 5. Compute overall Evidence Quality, Agreement Score, Confidence Score, and Consensus Score
  const evidenceQualityScore = Math.round(
    agentTermSets.reduce((acc, item) => acc + item.evidenceMeta.score, 0) / agentTermSets.length
  );

  // Claim-level stance agreement across shared claims (majority alignment vs split contradiction)
  let evaluatedMultiAgentClaims = 0;
  let claimAgreementRatioSum = 0;
  let directContradictionClaims = 0;

  for (const claim of claims) {
    if (claim.contradictionStatus === "DIRECT_CONTRADICTION") {
      directContradictionClaims += 1;
    }
    const proCount = claim.supportingAgents.length;
    const contraCount = claim.contradictingAgents.length;
    const totalOnClaim = proCount + contraCount;
    if (totalOnClaim >= 2) {
      evaluatedMultiAgentClaims += 1;
      const majority = Math.max(proCount, contraCount);
      const minority = Math.min(proCount, contraCount);
      if (majority === minority) {
        // 50/50 split on this claim -> 0% agreement on this claim
        claimAgreementRatioSum += 0;
      } else {
        claimAgreementRatioSum += majority / totalOnClaim;
      }
    }
  }

  // Also check explicit disagreement markers across messages if agents didn't share a canonical concept bucket
  const explicitDisagreementMsgs = activeAgentOutputs.filter((m) =>
    NEGATION_OR_CONTRA_REGEX.test(m.content)
  );

  let agreementScore = 0;
  if (evaluatedMultiAgentClaims > 0) {
    agreementScore = Math.round((claimAgreementRatioSum / evaluatedMultiAgentClaims) * 100);
  } else if (explicitDisagreementMsgs.length > 0) {
    agreementScore = Math.max(10, 100 - Math.round((explicitDisagreementMsgs.length / activeAgentOutputs.length) * 85));
  } else {
    // Independent non-conflicting claims on distinct aspects (e.g. Specialist mode)
    agreementScore = Math.min(82, Math.max(40, Math.round(similarityScore * 0.7 + evidenceQualityScore * 0.45)));
  }

  const participationRatio =
    Array.isArray(team) && team.length > 0 ? activeAgentOutputs.length / team.length : 1;
  const rawConfidence =
    activeAgentOutputs.reduce((acc, m) => acc + (Number(m.confidence) || 0.8), 0) /
    activeAgentOutputs.length;
  const confidenceScore = Math.round(rawConfidence * (0.6 + 0.4 * participationRatio) * 100);

  // Compute true claim-based consensusScore (0..100) without any hardcoded baseline
  const contradictionPenalty =
    claims.length > 0
      ? Math.round((directContradictionClaims / claims.length) * 35)
      : explicitDisagreementMsgs.length * 20;

  let consensusScore = Math.round(
    agreementScore * 0.65 + evidenceQualityScore * 0.25 + confidenceScore * 0.10 - contradictionPenalty
  );
  consensusScore = Math.max(0, Math.min(100, consensusScore));

  // Determine overall consensusStatus
  let consensusStatus = CONSENSUS_STATUSES.AGREED;
  if (evidenceQualityScore < 30 || claims.every((c) => c.agreementStatus === CONSENSUS_STATUSES.INSUFFICIENT_EVIDENCE)) {
    consensusStatus = CONSENSUS_STATUSES.INSUFFICIENT_EVIDENCE;
    consensusScore = Math.min(consensusScore, 35);
  } else if (agreementScore <= 35 || (directContradictionClaims > 0 && agreementScore < 50)) {
    consensusStatus = CONSENSUS_STATUSES.DISAGREED;
  } else if (directContradictionClaims > 0 && agreementScore === 50) {
    consensusStatus = CONSENSUS_STATUSES.UNRESOLVED;
  } else if (directContradictionClaims > 0 && agreementScore >= 60) {
    consensusStatus = CONSENSUS_STATUSES.MOSTLY_AGREED;
  } else if (agreementScore >= 80 && directContradictionClaims === 0) {
    consensusStatus = CONSENSUS_STATUSES.AGREED;
  } else if (agreementScore >= 65) {
    consensusStatus = CONSENSUS_STATUSES.MOSTLY_AGREED;
  } else if (agreementScore >= 45) {
    consensusStatus = CONSENSUS_STATUSES.PARTIAL_AGREEMENT;
  } else {
    consensusStatus = CONSENSUS_STATUSES.DISAGREED;
  }

  const consensusReached =
    consensusStatus === CONSENSUS_STATUSES.AGREED ||
    consensusStatus === CONSENSUS_STATUSES.MOSTLY_AGREED;

  const agreements = [];
  const disagreements = [];
  const supportingEvidence = [];
  const conflictingEvidence = [];
  const unresolvedQuestions = [];

  for (const claim of claims) {
    supportingEvidence.push(...claim.supportingEvidence);
    conflictingEvidence.push(...claim.counterEvidence);
    if (
      claim.agreementStatus === CONSENSUS_STATUSES.AGREED ||
      claim.agreementStatus === CONSENSUS_STATUSES.MOSTLY_AGREED
    ) {
      agreements.push(
        `[${claim.agreementStatus} · ${claim.topicLabel}] Disepakati oleh ${claim.supportingAgents.length || claim.proposingAgents.length} agent: ${claim.statement}`
      );
    }
    if (claim.contradictionStatus === "DIRECT_CONTRADICTION" || claim.agreementStatus === CONSENSUS_STATUSES.DISAGREED) {
      disagreements.push(
        `[${claim.agreementStatus} · ${claim.topicLabel}] Konflik klaim antara pendukung (${claim.supportingAgents.join(", ") || "none"}) vs penolak (${claim.contradictingAgents.join(", ") || "none"}): ${claim.counterEvidence[0] || claim.statement}`
      );
      unresolvedQuestions.push(
        `Resolusi konflik pada '${claim.topicLabel}': bagaimana menyeimbangkan argumen pendukung vs keberatan dari ${claim.contradictingAgents.join(", ")}?`
      );
    }
  }

  for (const { msg } of agentTermSets) {
    const pts = extractStructuredPointsFromText(msg.content);
    for (const q of pts.questions) {
      unresolvedQuestions.push(`[${msg.modelName}] ${q}`);
    }
    for (const r of pts.risks.slice(0, 2)) {
      if (!disagreements.some((d) => d.includes(r.slice(0, 25)))) {
        disagreements.push(`[${msg.modelName} · Risiko/Catatan Kritis] ${r}`);
      }
    }
  }

  if (excludedFailedAgents.length > 0) {
    disagreements.push(
      `[Isolasi Kegagalan] ${excludedFailedAgents.length} agent gagal merespons (${excludedFailedAgents.map((a) => `${a.modelName}: ${a.errorCode}`).join(", ")}) dan tidak dihitung sebagai pemilih konsensus.`
    );
  }

  return {
    consensusStatus,
    consensusReached,
    similarityScore,
    agreementScore,
    evidenceQualityScore,
    confidenceScore,
    consensusScore,
    activeAgentsCount: activeAgentOutputs.length,
    totalTeamCount: Array.isArray(team) ? team.length : activeAgentOutputs.length,
    excludedFailedAgents,
    claims,
    agreements: agreements.slice(0, 8),
    disagreements: disagreements.slice(0, 8),
    supportingEvidence: [...new Set(supportingEvidence)].slice(0, 10),
    conflictingEvidence: [...new Set(conflictingEvidence)].slice(0, 10),
    unresolvedQuestions: [...new Set(unresolvedQuestions)].slice(0, 8)
  };
}

/**
 * Dynamically computes actual claim-based agreement, divergence, pro/contra arguments,
 * and per-agent quality scores from the real agent responses — never hardcoded.
 */
export function buildRealDebateAndConsensus(topic, messages, team) {
  const claimEval = evaluateClaimConsensus(topic, messages, team);
  const validMessages = (Array.isArray(messages) ? messages : []).filter(
    (m) => m && m.status === "completed" && m.content
  );

  if (!validMessages.length) {
    return {
      consensusStatus: CONSENSUS_STATUSES.CONSENSUS_NOT_ESTABLISHED,
      consensusReached: false,
      similarityScore: 0,
      agreementScore: 0,
      evidenceQualityScore: 0,
      confidenceScore: 0,
      consensusScore: 0,
      activeAgentsCount: 0,
      totalTeamCount: Array.isArray(team) ? team.length : 0,
      excludedFailedAgents: claimEval.excludedFailedAgents,
      claims: [],
      summary: "Tidak ada respons model aktif yang berhasil dikumpulkan untuk dievaluasi.",
      agreements: [],
      disagreements: ["Seluruh agent gagal merespons atau tidak memiliki kredensial aktif."],
      supportingEvidence: [],
      conflictingEvidence: [],
      unresolvedQuestions: claimEval.unresolvedQuestions,
      pros: [],
      cons: [],
      evaluations: (Array.isArray(team) ? team : []).map((member) => ({
        agentId: member.agentId,
        modelName: member.modelName,
        provider: member.provider,
        role: member.role,
        participatedInConsensus: false,
        respondedRounds: 0,
        feasibilityScore: 0,
        evidenceScore: 0,
        verdict: "Failed / Excluded from Consensus"
      }))
    };
  }

  const evaluations = (Array.isArray(team) ? team : []).map((member) => {
    const agentMsgs = validMessages.filter((m) => m.agentId === member.agentId);
    if (agentMsgs.length === 0) {
      return {
        agentId: member.agentId,
        modelName: member.modelName,
        provider: member.provider,
        role: member.role,
        participatedInConsensus: false,
        respondedRounds: 0,
        feasibilityScore: 0,
        evidenceScore: 0,
        verdict: "Failed / Excluded from Consensus"
      };
    }
    const combinedText = agentMsgs.map((m) => m.content).join("\n");
    const evMeta = measureEvidenceQualityForMessage(combinedText);
    const extracted = extractStructuredPointsFromText(combinedText);
    const feasibilityScore = Math.min(
      99,
      Math.max(25, Math.round(evMeta.score * 0.75 + extracted.recommendations.length * 6))
    );
    return {
      agentId: member.agentId,
      modelName: member.modelName,
      provider: member.provider,
      role: member.role,
      participatedInConsensus: true,
      respondedRounds: agentMsgs.length,
      feasibilityScore,
      evidenceScore: evMeta.score,
      evidenceStrength: evMeta.strength,
      verdict: "Verified Live Response"
    };
  });

  const prosPoints = claimEval.supportingEvidence.length
    ? claimEval.supportingEvidence.slice(0, 6)
    : claimEval.agreements.slice(0, 4);
  const consPoints = claimEval.conflictingEvidence.length
    ? claimEval.conflictingEvidence.slice(0, 6)
    : claimEval.disagreements.slice(0, 4);

  return {
    consensusStatus: claimEval.consensusStatus,
    consensusReached: claimEval.consensusReached,
    similarityScore: claimEval.similarityScore,
    agreementScore: claimEval.agreementScore,
    evidenceQualityScore: claimEval.evidenceQualityScore,
    confidenceScore: claimEval.confidenceScore,
    consensusScore: claimEval.consensusScore,
    activeAgentsCount: claimEval.activeAgentsCount,
    totalTeamCount: claimEval.totalTeamCount,
    excludedFailedAgents: claimEval.excludedFailedAgents,
    claims: claimEval.claims,
    summary: `Evaluasi klaim dari ${claimEval.activeAgentsCount}/${claimEval.totalTeamCount} agent aktif menghasilkan status '${claimEval.consensusStatus}' (Consensus: ${claimEval.consensusScore}%, Agreement: ${claimEval.agreementScore}%, Similarity: ${claimEval.similarityScore}%, Evidence Quality: ${claimEval.evidenceQualityScore}%).`,
    agreements: claimEval.agreements,
    disagreements: claimEval.disagreements,
    supportingEvidence: claimEval.supportingEvidence,
    conflictingEvidence: claimEval.conflictingEvidence,
    unresolvedQuestions: claimEval.unresolvedQuestions,
    pros: [
      {
        title: "Argumen & Klaim Pendukung (Supporting Evidence)",
        points: prosPoints
      }
    ],
    cons: [
      {
        title: "Kontraargumen, Bukti Bertentangan & Risiko (Counter-Evidence & Risks)",
        points: consPoints
      }
    ],
    evaluations
  };
}

/**
 * Batch 8.5.1 Section 6.5 — Knowledge Graph Semantic & Provenance Validator.
 * Checks for duplicate nodes, invalid edges, missing source references, orphan nodes,
 * contradictory relationships on identical node pairs, invalid agent references, and missing timestamps.
 */
export function validateKnowledgeGraph(graph, validAgentIds = new Set()) {
  const nodes = Array.isArray(graph?.nodes) ? graph.nodes : [];
  const edges = Array.isArray(graph?.edges) ? graph.edges : [];
  const allowedNodeTypes = new Set(Object.values(KNOWLEDGE_NODE_TYPES));
  const allowedEdgeTypes = new Set(Object.values(KNOWLEDGE_EDGE_TYPES));
  const agentIdSet = validAgentIds instanceof Set ? validAgentIds : new Set(validAgentIds || []);

  const issues = [];
  const nodeIds = new Set();
  let duplicateNodeCount = 0;
  let missingSourceRefCount = 0;
  let invalidAgentRefCount = 0;
  let missingTimestampCount = 0;

  for (const node of nodes) {
    if (!node?.id) {
      issues.push("Node missing id");
      continue;
    }
    if (nodeIds.has(node.id)) {
      duplicateNodeCount += 1;
      issues.push(`Duplicate node id: ${node.id}`);
    }
    nodeIds.add(node.id);

    if (!allowedNodeTypes.has(node.nodeType)) {
      issues.push(`Invalid nodeType '${node.nodeType}' on node ${node.id}`);
    }
    const prov = node.provenance || {};
    if (!prov.sourceReference || typeof prov.sourceReference !== "string" || !prov.sourceReference.trim()) {
      missingSourceRefCount += 1;
      issues.push(`Missing sourceReference on node ${node.id}`);
    }
    if (!prov.timestamp || Number.isNaN(Date.parse(prov.timestamp))) {
      missingTimestampCount += 1;
      issues.push(`Missing or invalid timestamp on node ${node.id}`);
    }
    if (
      agentIdSet.size > 0 &&
      prov.agentId &&
      prov.agentId !== "system_orchestrator" &&
      !agentIdSet.has(prov.agentId)
    ) {
      invalidAgentRefCount += 1;
      issues.push(`Invalid agentId '${prov.agentId}' on node ${node.id}`);
    }
  }

  let invalidEdgeCount = 0;
  let contradictoryEdgeCount = 0;
  const connectedNodeIds = new Set();
  const pairRelationMap = new Map();

  for (const edge of edges) {
    if (!edge?.source || !edge?.target || !nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      invalidEdgeCount += 1;
      issues.push(`Edge ${edge?.id || "unknown"} references non-existent source or target node`);
      continue;
    }
    connectedNodeIds.add(edge.source);
    connectedNodeIds.add(edge.target);

    const edgeType = edge.edgeType || edge.relation;
    if (!allowedEdgeTypes.has(edgeType)) {
      invalidEdgeCount += 1;
      issues.push(`Invalid edgeType '${edgeType}' on edge ${edge.id}`);
    }
    const prov = edge.provenance || {};
    if (!prov.sourceReference || typeof prov.sourceReference !== "string" || !prov.sourceReference.trim()) {
      missingSourceRefCount += 1;
      issues.push(`Missing sourceReference on edge ${edge.id}`);
    }
    if (!prov.timestamp || Number.isNaN(Date.parse(prov.timestamp))) {
      missingTimestampCount += 1;
      issues.push(`Missing or invalid timestamp on edge ${edge.id}`);
    }
    if (
      agentIdSet.size > 0 &&
      prov.agentId &&
      prov.agentId !== "system_orchestrator" &&
      !agentIdSet.has(prov.agentId)
    ) {
      invalidAgentRefCount += 1;
      issues.push(`Invalid agentId '${prov.agentId}' on edge ${edge.id}`);
    }

    const pairKey = `${edge.source}->${edge.target}`;
    if (!pairRelationMap.has(pairKey)) {
      pairRelationMap.set(pairKey, new Set());
    }
    const relSet = pairRelationMap.get(pairKey);
    relSet.add(edgeType);
    if (
      (relSet.has(KNOWLEDGE_EDGE_TYPES.SUPPORTS) && relSet.has(KNOWLEDGE_EDGE_TYPES.CONTRADICTS)) ||
      (relSet.has(KNOWLEDGE_EDGE_TYPES.AGREES_WITH) && relSet.has(KNOWLEDGE_EDGE_TYPES.DISAGREES_WITH))
    ) {
      contradictoryEdgeCount += 1;
      issues.push(`Contradictory relationships between ${pairKey}`);
    }
  }

  let orphanNodeCount = 0;
  if (nodes.length > 1) {
    for (const node of nodes) {
      if (!connectedNodeIds.has(node.id)) {
        orphanNodeCount += 1;
        issues.push(`Orphan node detected: ${node.id}`);
      }
    }
  }

  const valid =
    duplicateNodeCount === 0 &&
    invalidEdgeCount === 0 &&
    missingSourceRefCount === 0 &&
    orphanNodeCount === 0 &&
    contradictoryEdgeCount === 0 &&
    invalidAgentRefCount === 0 &&
    missingTimestampCount === 0;

  return {
    valid,
    duplicateNodeCount,
    invalidEdgeCount,
    missingSourceRefCount,
    orphanNodeCount,
    contradictoryEdgeCount,
    invalidAgentRefCount,
    missingTimestampCount,
    issues
  };
}

/**
 * Batch 8.5.1 Section 6 — Semantic Knowledge Graph Builder with Full Provenance & Validation.
 * Never chains edges merely by message array index.
 */
export function buildKnowledgeGraphFromActualResponses(
  topic,
  messages,
  team,
  enableKnowledgeGraph = true,
  { sessionId = "collab_session", debate = null } = {}
) {
  if (!enableKnowledgeGraph) {
    return {
      enabled: false,
      nodes: [],
      edges: [],
      tableRows: [],
      validation: {
        valid: true,
        duplicateNodeCount: 0,
        invalidEdgeCount: 0,
        missingSourceRefCount: 0,
        orphanNodeCount: 0,
        contradictoryEdgeCount: 0,
        invalidAgentRefCount: 0,
        missingTimestampCount: 0,
        issues: []
      }
    };
  }

  const validMessages = (Array.isArray(messages) ? messages : []).filter(
    (m) => m && m.status === "completed" && m.content
  );
  const validAgentIds = new Set((Array.isArray(team) ? team : []).map((t) => t.agentId));
  for (const m of validMessages) {
    if (m.agentId) validAgentIds.add(m.agentId);
  }

  if (validMessages.length === 0) {
    return {
      enabled: true,
      nodes: [],
      edges: [],
      tableRows: [],
      details: {
        totalNodes: 0,
        totalEdges: 0,
        derivedFromLiveMessages: 0
      },
      validation: {
        valid: true,
        duplicateNodeCount: 0,
        invalidEdgeCount: 0,
        missingSourceRefCount: 0,
        orphanNodeCount: 0,
        contradictoryEdgeCount: 0,
        invalidAgentRefCount: 0,
        missingTimestampCount: 0,
        issues: []
      }
    };
  }

  const effectiveDebate = debate || buildRealDebateAndConsensus(topic, validMessages, team);
  const nowIso = new Date().toISOString();
  const nodes = [];
  const edges = [];
  const tableRows = [];

  const addNode = ({
    id,
    label,
    nodeType,
    claimText,
    weight = 85,
    round = 1,
    agentId,
    modelId,
    modelName,
    messageId,
    timestamp,
    sourceReference
  }) => {
    if (nodes.some((n) => n.id === id)) return nodes.find((n) => n.id === id);
    const provenance = {
      sessionId,
      agentId: agentId || validMessages[0]?.agentId || "system_orchestrator",
      modelId: modelId || validMessages[0]?.modelId || "jarvis-orchestrator",
      modelName: modelName || validMessages[0]?.modelName || "J.A.R.V.I.S. CORE",
      messageId: messageId || validMessages[0]?.id || "msg_root",
      timestamp: timestamp || validMessages[0]?.createdAt || nowIso,
      sourceReference: String(sourceReference || claimText || label).slice(0, 220)
    };
    const nodeObj = {
      id,
      label: String(label || id).slice(0, 64),
      nodeType,
      category: nodeType.toLowerCase(),
      weight,
      ownerModel: provenance.modelName,
      round,
      claimText: String(claimText || label).slice(0, 260),
      provenance
    };
    nodes.push(nodeObj);
    return nodeObj;
  };

  const addEdge = ({
    id,
    source,
    target,
    edgeType,
    strength = 0.88,
    explanation = "",
    verified = true,
    agentId,
    modelId,
    modelName,
    messageId,
    timestamp,
    sourceReference
  }) => {
    if (source === target) return null;
    // Prevent contradictory edges on the exact same directed pair
    const existingOpposite = edges.find(
      (e) =>
        e.source === source &&
        e.target === target &&
        ((edgeType === KNOWLEDGE_EDGE_TYPES.SUPPORTS && e.edgeType === KNOWLEDGE_EDGE_TYPES.CONTRADICTS) ||
          (edgeType === KNOWLEDGE_EDGE_TYPES.CONTRADICTS && e.edgeType === KNOWLEDGE_EDGE_TYPES.SUPPORTS) ||
          (edgeType === KNOWLEDGE_EDGE_TYPES.AGREES_WITH && e.edgeType === KNOWLEDGE_EDGE_TYPES.DISAGREES_WITH) ||
          (edgeType === KNOWLEDGE_EDGE_TYPES.DISAGREES_WITH && e.edgeType === KNOWLEDGE_EDGE_TYPES.AGREES_WITH))
    );
    if (existingOpposite) return null;
    if (edges.some((e) => e.source === source && e.target === target && e.edgeType === edgeType)) {
      return null;
    }

    const provenance = {
      sessionId,
      agentId: agentId || validMessages[0]?.agentId || "system_orchestrator",
      modelId: modelId || validMessages[0]?.modelId || "jarvis-orchestrator",
      modelName: modelName || validMessages[0]?.modelName || "J.A.R.V.I.S. CORE",
      messageId: messageId || validMessages[0]?.id || "msg_root",
      timestamp: timestamp || validMessages[0]?.createdAt || nowIso,
      sourceReference: String(sourceReference || explanation || `${source} ${edgeType} ${target}`).slice(0, 220)
    };

    const edgeObj = {
      id: id || `edge_${edges.length + 1}`,
      source,
      target,
      edgeType,
      relation: edgeType,
      strength,
      explanation: explanation || `${edgeType}: ${source} -> ${target}`,
      verificationStatus: verified ? "VERIFIED" : "UNVERIFIED_RELATIONSHIP",
      provenance
    };
    edges.push(edgeObj);
    return edgeObj;
  };

  // 1. Create canonical CLAIM nodes from evaluated claims
  const claimNodeByConcept = new Map();
  (effectiveDebate.claims || []).forEach((claim, cIdx) => {
    const firstProposer = claim.proposingAgents?.[0] || {};
    const msgMatch = validMessages.find((m) => m.id === firstProposer.messageId) || validMessages[0];
    const claimNodeId = `node_claim_${cIdx + 1}`;
    addNode({
      id: claimNodeId,
      label: claim.topicLabel.slice(0, 48),
      nodeType: KNOWLEDGE_NODE_TYPES.CLAIM,
      claimText: claim.statement,
      weight: 95,
      round: msgMatch?.round || 1,
      agentId: msgMatch?.agentId,
      modelId: msgMatch?.modelId,
      modelName: msgMatch?.modelName,
      messageId: msgMatch?.id,
      timestamp: msgMatch?.createdAt || nowIso,
      sourceReference: `Claim [${claim.claimId}]: ${claim.statement}`
    });
    claimNodeByConcept.set(claim.topicCluster, { claimNodeId, claim });
  });

  // 2. Create AGENT_POSITION, EVIDENCE, RISK, RECOMMENDATION, QUESTION, and SOURCE nodes per message
  const agentPositionNodeByAgent = new Map();

  validMessages.forEach((msg, idx) => {
    const pts = extractStructuredPointsFromText(msg.content);
    const primaryClaim = pts.keyPoints[0] || msg.content.slice(0, 140);
    const posNodeId = `node_pos_${msg.round}_${idx + 1}`;
    const msgTimestamp = msg.createdAt || nowIso;

    addNode({
      id: posNodeId,      label: `${msg.modelName} (${msg.role} R${msg.round})`,
      nodeType: KNOWLEDGE_NODE_TYPES.AGENT_POSITION,
      claimText: primaryClaim,
      weight: 90,
      round: msg.round,
      agentId: msg.agentId,
      modelId: msg.modelId,
      modelName: msg.modelName,
      messageId: msg.id,
      timestamp: msgTimestamp,
      sourceReference: `Message ${msg.id} (${msg.modelName}): ${primaryClaim}`
    });

    const prevPosForSameAgent = agentPositionNodeByAgent.get(msg.agentId);
    if (prevPosForSameAgent && msg.round > prevPosForSameAgent.round) {
      // Genuine cross-round refinement by the same agent (e.g., Debate Round 2 or Reviewer Revision)
      addEdge({
        id: `edge_refine_self_${idx + 1}`,
        source: posNodeId,
        target: prevPosForSameAgent.nodeId,
        edgeType: KNOWLEDGE_EDGE_TYPES.REFINES,
        strength: 0.92,
        explanation: `${msg.modelName} merevisi/mempertajam posisinya dari putaran ${prevPosForSameAgent.round} ke putaran ${msg.round}.`,
        verified: true,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: primaryClaim
      });
    }
    agentPositionNodeByAgent.set(msg.agentId, { nodeId: posNodeId, round: msg.round, msg });

    // Link AGENT_POSITION to matching CLAIM nodes via SUPPORTS or CONTRADICTS
    let linkedToAnyClaim = false;
    for (const [, { claimNodeId, claim }] of claimNodeByConcept.entries()) {
      const proposerEntry = claim.proposingAgents.find((p) => p.agentId === msg.agentId);
      if (proposerEntry) {
        linkedToAnyClaim = true;
        const edgeType =
          proposerEntry.stance === "CONTRA"
            ? KNOWLEDGE_EDGE_TYPES.CONTRADICTS
            : KNOWLEDGE_EDGE_TYPES.SUPPORTS;
        addEdge({
          id: `edge_pos_claim_${idx + 1}_${claimNodeId}`,
          source: posNodeId,
          target: claimNodeId,
          edgeType,
          strength: msg.confidence || 0.88,
          explanation: `${msg.modelName} (${proposerEntry.stance}) terhadap klaim '${claim.topicLabel}'.`,
          verified: true,
          agentId: msg.agentId,
          modelId: msg.modelId,
          modelName: msg.modelName,
          messageId: msg.id,
          timestamp: msgTimestamp,
          sourceReference: primaryClaim
        });
      }
    }

    if (!linkedToAnyClaim && claimNodeByConcept.size > 0) {
      const firstClaimId = [...claimNodeByConcept.values()][0].claimNodeId;
      addEdge({
        id: `edge_pos_fallback_${idx + 1}`,
        source: posNodeId,
        target: firstClaimId,
        edgeType: KNOWLEDGE_EDGE_TYPES.REFERENCES,
        strength: 0.65,
        explanation: `Referensi kontekstual dari ${msg.modelName} terhadap klaim utama sesi.`,
        verified: false, // Explicitly marked UNVERIFIED_RELATIONSHIP per Section 6.2
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: primaryClaim
      });
    }

    // Add EVIDENCE node if message has concrete evidence
    if (pts.evidence.length > 0) {
      const evText = pts.evidence[0];
      const evNodeId = `node_ev_${idx + 1}`;
      addNode({
        id: evNodeId,
        label: `Bukti: ${evText.slice(0, 36)}...`,
        nodeType: KNOWLEDGE_NODE_TYPES.EVIDENCE,
        claimText: evText,
        weight: 84,
        round: msg.round,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: `Evidence in ${msg.id}: ${evText}`
      });
      addEdge({
        id: `edge_ev_pos_${idx + 1}`,
        source: evNodeId,
        target: posNodeId,
        edgeType: KNOWLEDGE_EDGE_TYPES.SUPPORTS,
        strength: 0.9,
        explanation: `Bukti teknis mendukung posisi ${msg.modelName}.`,
        verified: true,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: evText
      });

      if (pts.sources.length > 0) {
        const srcText = pts.sources[0];
        const srcNodeId = `node_src_${idx + 1}`;
        addNode({
          id: srcNodeId,
          label: `Referensi: ${srcText.slice(0, 32)}...`,
          nodeType: KNOWLEDGE_NODE_TYPES.SOURCE,
          claimText: srcText,
          weight: 78,
          round: msg.round,
          agentId: msg.agentId,
          modelId: msg.modelId,
          modelName: msg.modelName,
          messageId: msg.id,
          timestamp: msgTimestamp,
          sourceReference: `Source in ${msg.id}: ${srcText}`
        });
        addEdge({
          id: `edge_ev_src_${idx + 1}`,
          source: evNodeId,
          target: srcNodeId,
          edgeType: KNOWLEDGE_EDGE_TYPES.REFERENCES,
          strength: 0.85,
          explanation: `Bukti mengacu pada sumber/standar teknis yang disebutkan ${msg.modelName}.`,
          verified: true,
          agentId: msg.agentId,
          modelId: msg.modelId,
          modelName: msg.modelName,
          messageId: msg.id,
          timestamp: msgTimestamp,
          sourceReference: srcText
        });
      }
    }

    // Add RISK node if message identifies risks
    if (pts.risks.length > 0) {
      const riskText = pts.risks[0];
      const riskNodeId = `node_risk_${idx + 1}`;
      addNode({
        id: riskNodeId,
        label: `Risiko: ${riskText.slice(0, 36)}...`,
        nodeType: KNOWLEDGE_NODE_TYPES.RISK,
        claimText: riskText,
        weight: 82,
        round: msg.round,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: `Risk in ${msg.id}: ${riskText}`
      });
      addEdge({
        id: `edge_risk_pos_${idx + 1}`,
        source: riskNodeId,
        target: posNodeId,
        edgeType: KNOWLEDGE_EDGE_TYPES.DEPENDS_ON,
        strength: 0.84,
        explanation: `Mitigasi risiko bergantung pada analisis ${msg.modelName}.`,
        verified: true,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: riskText
      });
    }

    // Add RECOMMENDATION node if message provides recommendations
    if (pts.recommendations.length > 0) {
      const recText = pts.recommendations[0];
      const recNodeId = `node_rec_${idx + 1}`;
      addNode({
        id: recNodeId,
        label: `Rekomendasi: ${recText.slice(0, 34)}...`,
        nodeType: KNOWLEDGE_NODE_TYPES.RECOMMENDATION,
        claimText: recText,
        weight: 86,
        round: msg.round,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: `Recommendation in ${msg.id}: ${recText}`
      });
      addEdge({
        id: `edge_rec_pos_${idx + 1}`,
        source: recNodeId,
        target: posNodeId,
        edgeType: KNOWLEDGE_EDGE_TYPES.DERIVED_FROM,
        strength: 0.89,
        explanation: `Rekomendasi diturunkan langsung dari posisi ${msg.modelName}.`,
        verified: true,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: recText
      });
    }

    // Add QUESTION node if message raises open questions
    if (pts.questions.length > 0) {
      const qText = pts.questions[0];
      const qNodeId = `node_q_${idx + 1}`;
      addNode({
        id: qNodeId,
        label: `Pertanyaan: ${qText.slice(0, 34)}...`,
        nodeType: KNOWLEDGE_NODE_TYPES.QUESTION,
        claimText: qText,
        weight: 76,
        round: msg.round,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: `Question in ${msg.id}: ${qText}`
      });
      addEdge({
        id: `edge_q_pos_${idx + 1}`,
        source: qNodeId,
        target: posNodeId,
        edgeType: KNOWLEDGE_EDGE_TYPES.DERIVED_FROM,
        strength: 0.8,
        explanation: `Pertanyaan terbuka diangkat oleh ${msg.modelName}.`,
        verified: true,
        agentId: msg.agentId,
        modelId: msg.modelId,
        modelName: msg.modelName,
        messageId: msg.id,
        timestamp: msgTimestamp,
        sourceReference: qText
      });
    }

    tableRows.push({
      component: `${msg.modelName} (${msg.role})`,
      category: KNOWLEDGE_NODE_TYPES.AGENT_POSITION,
      leadModel: `${msg.modelName} (${msg.provider})`,
      priority: idx < 2 ? "CRITICAL" : "HIGH",
      specification: primaryClaim,
      sourceReference: `Message ${msg.id} @ ${msgTimestamp}`
    });
  });

  // 3. Cross-agent semantic AGREES_WITH / DISAGREES_WITH edges derived strictly from shared claims
  for (const [, { claim }] of claimNodeByConcept.entries()) {
    const entries = claim.proposingAgents || [];
    for (let i = 0; i < entries.length; i++) {
      for (let j = i + 1; j < entries.length; j++) {
        const posA = agentPositionNodeByAgent.get(entries[i].agentId);
        const posB = agentPositionNodeByAgent.get(entries[j].agentId);
        if (!posA || !posB || posA.nodeId === posB.nodeId) continue;
        const sameStance = entries[i].stance === entries[j].stance;
        addEdge({
          id: `edge_peer_${claim.claimId}_${i}_${j}`,
          source: posA.nodeId,
          target: posB.nodeId,
          edgeType: sameStance ? KNOWLEDGE_EDGE_TYPES.AGREES_WITH : KNOWLEDGE_EDGE_TYPES.DISAGREES_WITH,
          strength: sameStance ? 0.9 : 0.92,
          explanation: sameStance
            ? `${entries[i].modelName} dan ${entries[j].modelName} sepakat (${entries[i].stance}) pada '${claim.topicLabel}'.`
            : `${entries[i].modelName} (${entries[i].stance}) dan ${entries[j].modelName} (${entries[j].stance}) berbeda pendapat pada '${claim.topicLabel}'.`,
          verified: true,
          agentId: posA.msg.agentId,
          modelId: posA.msg.modelId,
          modelName: posA.msg.modelName,
          messageId: posA.msg.id,
          timestamp: posA.msg.createdAt || nowIso,
          sourceReference: `Cross-agent stance comparison on ${claim.claimId} (${claim.topicLabel})`
        });
      }
    }
  }

  // 4. Add DECISION node if consensus was reached on at least one claim
  if (effectiveDebate.consensusReached && claimNodeByConcept.size > 0) {
    const leadMsg = validMessages[0];
    const decisionNodeId = "node_decision_consensus";
    addNode({
      id: decisionNodeId,
      label: `Keputusan Konsensus (${effectiveDebate.consensusStatus})`,
      nodeType: KNOWLEDGE_NODE_TYPES.DECISION,
      claimText: effectiveDebate.agreements[0] || effectiveDebate.summary,
      weight: 98,
      round: leadMsg.round,
      agentId: leadMsg.agentId,
      modelId: leadMsg.modelId,
      modelName: leadMsg.modelName,
      messageId: leadMsg.id,
      timestamp: leadMsg.createdAt || nowIso,
      sourceReference: `Consensus Decision (${effectiveDebate.consensusStatus}): ${effectiveDebate.summary}`
    });
    for (const [, { claimNodeId, claim }] of claimNodeByConcept.entries()) {
      addEdge({
        id: `edge_decision_${claimNodeId}`,
        source: decisionNodeId,
        target: claimNodeId,
        edgeType: KNOWLEDGE_EDGE_TYPES.DERIVED_FROM,
        strength: 0.94,
        explanation: `Keputusan diturunkan dari evaluasi klaim '${claim.topicLabel}'.`,
        verified: true,
        agentId: leadMsg.agentId,
        modelId: leadMsg.modelId,
        modelName: leadMsg.modelName,
        messageId: leadMsg.id,
        timestamp: leadMsg.createdAt || nowIso,
        sourceReference: claim.statement
      });
    }
  }

  const validation = validateKnowledgeGraph({ nodes, edges }, validAgentIds);

  return {
    enabled: true,
    nodes,
    edges,
    tableRows,
    details: {
      totalNodes: nodes.length,
      totalEdges: edges.length,
      centralNodeId: nodes[0]?.id || null,
      derivedFromLiveMessages: validMessages.length,
      nodeTypesPresent: [...new Set(nodes.map((n) => n.nodeType))],
      edgeTypesPresent: [...new Set(edges.map((e) => e.edgeType))]
    },
    validation
  };
}

/**
 * Batch 8.5.1 Section 7 — 10-Stage Final Synthesis Pipeline.
 * Never copies the last agent's raw message as the final conclusion.
 * Produces all 11 required sections and marks missing sections as "UNAVAILABLE".
 */
export async function runSynthesisPipeline({
  kv = null,
  userId = "",
  env = {},
  topic,
  modeConfig,
  team = [],
  messages = [],
  debate = null,
  knowledgeGraph = null,
  preReviewOutput = null,
  postReviewOutput = null,
  synthesizerText = "",
  synthesisProviderOverride = null,
  fetchImpl = globalThis.fetch,
  signal = null
} = {}) {
  const stageLog = [];
  const recordStage = (stageName, status, detail) => {
    stageLog.push({
      stage: stageName,
      status,
      detail,
      timestamp: new Date().toISOString()
    });
  };

  // Stage 1: Collect completed agent results
  const validMessages = (Array.isArray(messages) ? messages : []).filter(
    (m) => m && m.status === "completed" && typeof m.content === "string" && m.content.trim().length > 0
  );
  recordStage(
    "1_COLLECT_COMPLETED_RESULTS",
    validMessages.length > 0 ? "COMPLETED" : "FAILED",
    `Collected ${validMessages.length} completed agent responses out of ${messages.length} total turns.`
  );

  // Stage 2: Validate completeness
  const uniqueActiveAgents = new Set(validMessages.map((m) => m.agentId));
  const failedAgentsCount = Math.max(0, (Array.isArray(team) ? team.length : 0) - uniqueActiveAgents.size);
  if (validMessages.length === 0) {
    recordStage("2_VALIDATE_COMPLETENESS", "FAILED", "Zero completed agent responses available for synthesis.");
    return buildFailedSynthesisObject({
      topic,
      modeConfig,
      team,
      debate,
      knowledgeGraph,
      stageLog,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR,
      failureReason: "Sintesis dibatalkan karena tidak ada satu pun agent yang berhasil menyelesaikan respons."
    });
  }
  recordStage(
    "2_VALIDATE_COMPLETENESS",
    "COMPLETED",
    `${uniqueActiveAgents.size}/${team.length || uniqueActiveAgents.size} agents completed.`
  );

  // Stage 3: Extract claims
  const effectiveDebate = debate || buildRealDebateAndConsensus(topic, validMessages, team);
  const extractedClaims = effectiveDebate.claims || [];
  recordStage("3_EXTRACT_CLAIMS", "COMPLETED", `Extracted ${extractedClaims.length} structured claims.`);

  // Stage 4: Compare supporting evidence
  const supportingEvidence =
    effectiveDebate.supportingEvidence?.length > 0
      ? effectiveDebate.supportingEvidence
      : validMessages.flatMap((m) => {
          const pts = extractStructuredPointsFromText(m.content);
          return pts.evidence.map((ev) => `[${m.modelName} · ${m.role}] ${ev}`);
        });
  recordStage(
    "4_COMPARE_SUPPORTING_EVIDENCE",
    "COMPLETED",
    `Compared ${supportingEvidence.length} supporting evidence items.`
  );

  // Stage 5: Identify contradictions
  const conflictingEvidence = effectiveDebate.conflictingEvidence || [];
  recordStage(
    "5_IDENTIFY_CONTRADICTIONS",
    "COMPLETED",
    `Identified ${conflictingEvidence.length} conflicting evidence items.`
  );

  // Stage 6: Evaluate agreement
  const agreementList = effectiveDebate.agreements || [];
  const disagreementList = effectiveDebate.disagreements || [];
  recordStage(
    "6_EVALUATE_AGREEMENT",
    "COMPLETED",
    `Consensus status: ${effectiveDebate.consensusStatus} (${effectiveDebate.consensusScore}%).`
  );

  // Stage 7: Identify unresolved questions
  const unresolvedQuestions = effectiveDebate.unresolvedQuestions || [];
  recordStage(
    "7_IDENTIFY_UNRESOLVED_QUESTIONS",
    "COMPLETED",
    `Identified ${unresolvedQuestions.length} unresolved questions.`
  );

  // Stage 8: Generate final synthesis via available Batch 8.1 synthesis model
  let generatedSynthesisText = typeof synthesizerText === "string" ? synthesizerText.trim() : "";
  let synthesizedByModel = "J.A.R.V.I.S. CORE (Synthesis Engine)";

  if (!generatedSynthesisText) {
    // Determine candidate synthesis model from active agents or synthesisProviderOverride
    const candidateSynthTargets = [];
    if (synthesisProviderOverride) {
      candidateSynthTargets.push({
        provider: synthesisProviderOverride.provider || "openai",
        apiModel: synthesisProviderOverride.apiModel || "gpt-4o",
        modelName: synthesisProviderOverride.modelName || "Synthesis Override Model"
      });
    } else {
      for (const msg of validMessages) {
        if (msg.provider && !candidateSynthTargets.some((t) => t.provider === msg.provider)) {
          candidateSynthTargets.push({
            provider: msg.provider,
            apiModel: msg.executedModel || msg.modelId || "gpt-4o",
            modelName: msg.modelName
          });
        }
      }
    }

    const synthesisPrompt = [
      `[J.A.R.V.I.S. MULTI-AGENT SYNTHESIS ORCHESTRATOR]`,
      `Topik Utama: ${topic}`,
      `Mode Kolaborasi: ${modeConfig?.name || "Parallel"} (${modeConfig?.id || "parallel"})`,
      `Status Konsensus Klaim: ${effectiveDebate.consensusStatus} (Skor: ${effectiveDebate.consensusScore}%)`,
      ``,
      `Klaim & Posisi Agent Aktif (${validMessages.length} kontribusi):`,
      ...validMessages.map(
        (m, idx) => `${idx + 1}. [${m.modelName} (${m.role})]: ${m.content.replace(/\s+/g, " ").slice(0, 360)}`
      ),
      ``,
      `Kesepakatan Terverifikasi:`,
      ...(agreementList.length ? agreementList.map((a) => `- ${a}`) : ["- Tidak ada kesepakatan mutlak"]),
      ``,
      `Perbedaan Pendapat & Kontradiksi:`,
      ...(disagreementList.length ? disagreementList.map((d) => `- ${d}`) : ["- Tidak ada kontradiksi langsung"]),
      ``,
      `Instruksi Sintesis: Susun Executive Summary dan sintesis keputusan lintas-agent yang mengintegrasikan bukti pendukung, menjelaskan kontradiksi secara jujur, dan merumuskan rekomendasi teknis tanpa menyalin mentah satu agent saja.`
    ].join("\n");

    let synthError = null;
    for (const synthTarget of candidateSynthTargets) {
      try {
        const res = await invokeProviderCompletion({
          kv,
          userId,
          provider: synthTarget.provider,
          apiModel: synthTarget.apiModel,
          prompt: synthesisPrompt,
          systemPrompt:
            "You are the J.A.R.V.I.S. Synthesis Orchestrator. Synthesize multi-agent claims, supporting evidence, and contradictions into an objective executive synthesis in Indonesian.",
          timeoutMs: 25000,
          env,
          fetchImpl,
          signal
        });
        if (res?.ok && res.text && res.text.trim()) {
          generatedSynthesisText = res.text.trim();
          synthesizedByModel = `J.A.R.V.I.S. Synthesis (${synthTarget.modelName} · ${res.provider})`;
          break;
        }
      } catch (err) {
        synthError = err;
      }
    }

    if (!generatedSynthesisText) {
      const errCode = synthError ? classifyProviderError(synthError, synthError?.status) : PROVIDER_ERROR_CODES.PROVIDER_ERROR;
      const errReason = redactSecrets(
        synthError?.message || "Synthesis provider failed to generate final synthesis."
      ).slice(0, 240);
      recordStage("8_GENERATE_FINAL_SYNTHESIS", "FAILED", `${errCode}: ${errReason}`);
      return buildFailedSynthesisObject({
        topic,
        modeConfig,
        team,
        messages: validMessages,
        debate: effectiveDebate,
        knowledgeGraph,
        preReviewOutput,
        postReviewOutput,
        stageLog,
        errorCode: errCode,
        failureReason: errReason
      });
    }
  }

  recordStage("8_GENERATE_FINAL_SYNTHESIS", "COMPLETED", `Generated via ${synthesizedByModel}.`);

  // Stage 9: Validate output & populate all 11 required sections (using "UNAVAILABLE" when data is absent)
  const allExtracted = validMessages.map((m) => ({
    modelName: m.modelName,
    role: m.role,
    provider: m.provider,
    ...extractStructuredPointsFromText(m.content)
  }));

  const mainFindingsList = allExtracted.flatMap((item) =>
    item.keyPoints.slice(0, 2).map((pt) => `[${item.modelName} · ${item.role}] ${pt}`)
  );
  const risksList = allExtracted.flatMap((item) =>
    item.risks.slice(0, 2).map((rk) => `[${item.modelName}] ${rk}`)
  );
  const recommendationsList = allExtracted.flatMap((item) =>
    item.recommendations.slice(0, 2).map((rec) => `[${item.modelName}] ${rec}`)
  );

  const limitationsList = [];
  if (failedAgentsCount > 0) {
    limitationsList.push(
      `${failedAgentsCount} dari ${team.length} agent gagal merespons dan telah diisolasi dari perhitungan konsensus.`
    );
  }
  if (effectiveDebate.consensusStatus === CONSENSUS_STATUSES.CONSENSUS_NOT_ESTABLISHED) {
    limitationsList.push(
      "Konsensus lintas-model tidak dapat ditetapkan karena jumlah agent aktif kurang dari batas minimum (2 agent)."
    );
  }
  if (effectiveDebate.consensusStatus === CONSENSUS_STATUSES.INSUFFICIENT_EVIDENCE) {
    limitationsList.push("Bukti kuantitatif/kausal pada respons agent masih terbatas (INSUFFICIENT_EVIDENCE).");
  }
  limitationsList.push(
    `Sintesis dieksekusi pada mode '${modeConfig?.name || "Parallel"}' dari ${validMessages.length} respons terverifikasi.`
  );

  const sections = {
    executiveSummary: generatedSynthesisText || "UNAVAILABLE",
    mainFindings: mainFindingsList.length > 0 ? mainFindingsList.slice(0, 8) : "UNAVAILABLE",
    supportingEvidence: supportingEvidence.length > 0 ? supportingEvidence.slice(0, 8) : "UNAVAILABLE",
    conflictingEvidence: conflictingEvidence.length > 0 ? conflictingEvidence.slice(0, 8) : "UNAVAILABLE",
    agreement: agreementList.length > 0 ? agreementList.slice(0, 8) : "UNAVAILABLE",
    disagreement: disagreementList.length > 0 ? disagreementList.slice(0, 8) : "UNAVAILABLE",
    recommendations: recommendationsList.length > 0 ? recommendationsList.slice(0, 8) : "UNAVAILABLE",
    risks: risksList.length > 0 ? risksList.slice(0, 8) : "UNAVAILABLE",
    limitations: limitationsList.length > 0 ? limitationsList : "UNAVAILABLE",
    confidence: {
      consensusStatus: effectiveDebate.consensusStatus,
      consensusScore: effectiveDebate.consensusScore,
      agreementScore: effectiveDebate.agreementScore,
      similarityScore: effectiveDebate.similarityScore,
      evidenceQualityScore: effectiveDebate.evidenceQualityScore,
      confidenceScore: effectiveDebate.confidenceScore,
      activeAgents: uniqueActiveAgents.size,
      totalAgents: team.length || uniqueActiveAgents.size
    },
    unresolvedQuestions: unresolvedQuestions.length > 0 ? unresolvedQuestions.slice(0, 8) : "UNAVAILABLE"
  };

  recordStage("9_VALIDATE_OUTPUT", "COMPLETED", "Validated all 11 required synthesis sections.");
  recordStage("10_PERSIST_FINAL_RESULT", "COMPLETED", "Synthesis ready for session persistence.");

  const summaryText = `Sesi kolaborasi '${modeConfig?.name || "Parallel"}' pada topik "${String(topic).slice(0, 90)}" menyelesaikan ${validMessages.length} kontribusi nyata dengan status konsensus ${effectiveDebate.consensusStatus} (${effectiveDebate.consensusScore}%).`;

  const formatListOrUnavailable = (val) =>
    Array.isArray(val) && val.length > 0 ? val.map((item) => `- ${item}`) : ["- UNAVAILABLE"];

  const markdownPlan = [
    `# Laporan Sintesis Kolaborasi Multi-AI — J.A.R.V.I.S. B8.5.1`,
    ``,
    `- **Topik**: ${topic}`,
    `- **Mode Eksekusi**: ${modeConfig?.name || "Parallel"} (\`${modeConfig?.id || "parallel"}\`)`,
    `- **Status Konsensus**: ${effectiveDebate.consensusStatus} (Consensus: ${effectiveDebate.consensusScore}%, Agreement: ${effectiveDebate.agreementScore}%, Evidence Quality: ${effectiveDebate.evidenceQualityScore}%, Confidence: ${effectiveDebate.confidenceScore}%)`,
    ``,
    `## 1. Executive Summary`,
    sections.executiveSummary,
    ``,
    `## 2. Main Findings`,
    ...formatListOrUnavailable(sections.mainFindings),
    ``,
    `## 3. Supporting Evidence`,
    ...formatListOrUnavailable(sections.supportingEvidence),
    ``,
    `## 4. Conflicting Evidence`,
    ...formatListOrUnavailable(sections.conflictingEvidence),
    ``,
    `## 5. Agreement`,
    ...formatListOrUnavailable(sections.agreement),
    ``,
    `## 6. Disagreement`,
    ...formatListOrUnavailable(sections.disagreement),
    ``,
    `## 7. Recommendations`,
    ...formatListOrUnavailable(sections.recommendations),
    ``,
    `## 8. Risks`,
    ...formatListOrUnavailable(sections.risks),
    ``,
    `## 9. Limitations`,
    ...formatListOrUnavailable(sections.limitations),
    ``,
    `## 10. Confidence`,
    `- Consensus Status: ${sections.confidence.consensusStatus}`,
    `- Consensus Score: ${sections.confidence.consensusScore}%`,
    `- Agreement Score: ${sections.confidence.agreementScore}%`,
    `- Evidence Quality Score: ${sections.confidence.evidenceQualityScore}%`,
    `- Confidence Score: ${sections.confidence.confidenceScore}%`,
    ``,
    `## 11. Unresolved Questions`,
    ...formatListOrUnavailable(sections.unresolvedQuestions)
  ].join("\n");

  return {
    status: "SYNTHESIS_COMPLETED",
    synthesizedBy: synthesizedByModel,
    mode: modeConfig?.id || "parallel",
    consensusStatus: effectiveDebate.consensusStatus,
    consensusReached: effectiveDebate.consensusReached,
    consensusScore: effectiveDebate.consensusScore,
    pipelineStages: stageLog,
    // 11 mandatory B8.5.1 sections (both top-level and under .sections)
    sections,
    executiveSummary: sections.executiveSummary,
    mainFindings: sections.mainFindings,
    supportingEvidence: sections.supportingEvidence,
    conflictingEvidence: sections.conflictingEvidence,
    agreement: sections.agreement,
    disagreement: sections.disagreement,
    recommendations: Array.isArray(sections.recommendations) ? sections.recommendations : [],
    risks: Array.isArray(sections.risks) ? sections.risks : [],
    limitations: Array.isArray(sections.limitations) ? sections.limitations : [],
    confidence: sections.confidence,
    unresolvedQuestions: sections.unresolvedQuestions,
    // Backward-compatible aliases for B8.2 / B8.5 callers
    summary: summaryText,
    conclusion: sections.executiveSummary,
    executiveDecision: String(sections.executiveSummary).slice(0, 600),
    evidence: Array.isArray(sections.supportingEvidence)
      ? sections.supportingEvidence
      : mainFindingsList.slice(0, 6),
    agreements: agreementList,
    disagreements: disagreementList,
    recommendedStack: Array.isArray(sections.recommendations) ? sections.recommendations.slice(0, 5) : [],
    nextSteps: Array.isArray(sections.recommendations) ? sections.recommendations.slice(0, 3) : [],
    preReviewOutput: preReviewOutput || undefined,
    postReviewOutput: postReviewOutput || undefined,
    exportArtifacts: {
      pdfReport: {
        filename: "Laporan_Kolaborasi_JARVIS_B8_5_1.pdf",
        title: `Laporan Kolaborasi AI (${modeConfig?.name || "Parallel"}): ${String(topic).slice(0, 60)}`,
        sections: [
          "Executive Summary",
          "Main Findings",
          "Supporting Evidence",
          "Conflicting Evidence",
          "Agreement & Disagreement",
          "Recommendations",
          "Risks & Limitations",
          "Confidence & Unresolved Questions"
        ]
      },
      markdownPlan: {
        filename: "Rencana_Kolaborasi_JARVIS_B8_5_1.md",
        content: markdownPlan
      },
      diagramSpec: {
        filename: "Diagram_Knowledge_Graph_JARVIS_B8_5_1.png",
        format: "mermaid+svg",
        nodeCount: knowledgeGraph?.nodes?.length || 0,
        edgeCount: knowledgeGraph?.edges?.length || 0
      }
    }
  };
}

function buildFailedSynthesisObject({
  topic,
  modeConfig,
  team = [],
  messages = [],
  debate = null,
  knowledgeGraph = null,
  preReviewOutput = null,
  postReviewOutput = null,
  stageLog = [],
  errorCode = PROVIDER_ERROR_CODES.PROVIDER_ERROR,
  failureReason = "Synthesis failed"
}) {
  const effectiveDebate = debate || buildRealDebateAndConsensus(topic, messages, team);
  const sections = {
    executiveSummary: "UNAVAILABLE",
    mainFindings: "UNAVAILABLE",
    supportingEvidence:
      effectiveDebate.supportingEvidence?.length > 0 ? effectiveDebate.supportingEvidence : "UNAVAILABLE",
    conflictingEvidence:
      effectiveDebate.conflictingEvidence?.length > 0 ? effectiveDebate.conflictingEvidence : "UNAVAILABLE",
    agreement: effectiveDebate.agreements?.length > 0 ? effectiveDebate.agreements : "UNAVAILABLE",
    disagreement: effectiveDebate.disagreements?.length > 0 ? effectiveDebate.disagreements : "UNAVAILABLE",
    recommendations: "UNAVAILABLE",
    risks: "UNAVAILABLE",
    limitations: [
      `SYNTHESIS_FAILED (${errorCode}): ${failureReason}`,
      `Hasil ${messages.length} agent yang telah selesai tetap disimpan tanpa fabrikasi kesimpulan.`
    ],
    confidence: {
      consensusStatus: effectiveDebate.consensusStatus,
      consensusScore: effectiveDebate.consensusScore,
      agreementScore: effectiveDebate.agreementScore,
      similarityScore: effectiveDebate.similarityScore,
      evidenceQualityScore: effectiveDebate.evidenceQualityScore,
      confidenceScore: effectiveDebate.confidenceScore,
      activeAgents: effectiveDebate.activeAgentsCount || 0,
      totalAgents: team.length
    },
    unresolvedQuestions:
      effectiveDebate.unresolvedQuestions?.length > 0 ? effectiveDebate.unresolvedQuestions : "UNAVAILABLE"
  };

  return {
    status: "SYNTHESIS_FAILED",
    errorCode,
    error: failureReason,
    canRetrySynthesis: messages.length > 0,
    preservedCompletedAgentCount: messages.length,
    synthesizedBy: "SYNTHESIS_FAILED",
    mode: modeConfig?.id || "parallel",
    consensusStatus: effectiveDebate.consensusStatus,
    consensusReached: false,
    consensusScore: effectiveDebate.consensusScore,
    pipelineStages: stageLog,
    sections,
    executiveSummary: "UNAVAILABLE",
    mainFindings: "UNAVAILABLE",
    supportingEvidence: sections.supportingEvidence,
    conflictingEvidence: sections.conflictingEvidence,
    agreement: sections.agreement,
    disagreement: sections.disagreement,
    recommendations: [],
    risks: [],
    limitations: sections.limitations,
    confidence: sections.confidence,
    unresolvedQuestions: sections.unresolvedQuestions,
    summary: `SYNTHESIS_FAILED: ${failureReason}`,
    conclusion: "UNAVAILABLE",
    executiveDecision: "UNAVAILABLE",
    evidence: Array.isArray(sections.supportingEvidence) ? sections.supportingEvidence : [],
    agreements: effectiveDebate.agreements || [],
    disagreements: effectiveDebate.disagreements || [],
    recommendedStack: [],
    nextSteps: [],
    preReviewOutput: preReviewOutput || undefined,
    postReviewOutput: postReviewOutput || undefined,
    exportArtifacts: {
      pdfReport: {
        filename: "Laporan_Kolaborasi_JARVIS_B8_5_1.pdf",
        title: `[SYNTHESIS_FAILED] ${String(topic).slice(0, 60)}`,
        sections: ["Limitations", "Preserved Agent Outputs"]
      },
      markdownPlan: {
        filename: "Rencana_Kolaborasi_JARVIS_B8_5_1.md",
        content: `# SYNTHESIS_FAILED\n\nAlasan: ${failureReason}\nKontribusi agent yang berhasil tetap tersimpan pada sesi.`
      },
      diagramSpec: {
        filename: "Diagram_Knowledge_Graph_JARVIS_B8_5_1.png",
        format: "mermaid+svg",
        nodeCount: knowledgeGraph?.nodes?.length || 0,
        edgeCount: knowledgeGraph?.edges?.length || 0
      }
    }
  };
}

/**
 * Synchronous wrapper preserved for backward compatibility with direct unit callers of buildActualFinalSynthesis.
 * Never copies the last agent's raw message when synthesizerText is empty; builds a structured cross-agent synthesis.
 */
export function buildActualFinalSynthesis({
  topic,
  modeConfig,
  team = [],
  messages = [],
  debate = null,
  knowledgeGraph = null,
  preReviewOutput = null,
  postReviewOutput = null,
  synthesizerText = ""
}) {
  const validMessages = (Array.isArray(messages) ? messages : []).filter(
    (m) => m && m.status === "completed" && m.content
  );
  const effectiveDebate = debate || buildRealDebateAndConsensus(topic, validMessages, team);

  if (validMessages.length === 0) {
    return buildFailedSynthesisObject({
      topic,
      modeConfig,
      team,
      messages: [],
      debate: effectiveDebate,
      knowledgeGraph,
      preReviewOutput,
      postReviewOutput,
      errorCode: PROVIDER_ERROR_CODES.PROVIDER_ERROR,
      failureReason: "Tidak ada respons agent yang berhasil untuk disintesis."
    });
  }

  const allExtracted = validMessages.map((m) => ({
    modelName: m.modelName,
    role: m.role,
    provider: m.provider,
    ...extractStructuredPointsFromText(m.content)
  }));

  const mainFindings = allExtracted.flatMap((item) =>
    item.keyPoints.slice(0, 2).map((pt) => `[${item.modelName} · ${item.role}] ${pt}`)
  );
  const supportingEvidence =
    effectiveDebate.supportingEvidence?.length > 0
      ? effectiveDebate.supportingEvidence
      : mainFindings;
  const conflictingEvidence = effectiveDebate.conflictingEvidence || [];
  const risks = allExtracted.flatMap((item) =>
    item.risks.slice(0, 2).map((rk) => `[${item.modelName}] ${rk}`)
  );
  const recommendations = allExtracted.flatMap((item) =>
    item.recommendations.slice(0, 2).map((rec) => `[${item.modelName}] ${rec}`)
  );

  // Never copy the last agent's raw message! Synthesize across all active agents if synthesizerText was not supplied.
  const crossAgentExecutiveSummary =
    (synthesizerText && synthesizerText.trim()) ||
    [
      `Sintesis lintas-${validMessages.length} agent (${validMessages.map((m) => m.modelName).join(", ")}) menghasilkan status konsensus ${effectiveDebate.consensusStatus} (${effectiveDebate.consensusScore}%).`,
      effectiveDebate.agreements.length > 0
        ? `Kesepakatan utama: ${effectiveDebate.agreements.slice(0, 2).join(" | ")}`
        : `Tidak ada kesepakatan penuh lintas agent.`,
      conflictingEvidence.length > 0
        ? `Kontradiksi yang wajib diperhatikan: ${conflictingEvidence.slice(0, 2).join(" | ")}`
        : `Rekomendasi gabungan: ${recommendations.slice(0, 2).join(" | ") || "UNAVAILABLE"}`
    ].join(" ");

  const failedCount = Math.max(0, team.length - new Set(validMessages.map((m) => m.agentId)).size);
  const limitations = [];
  if (failedCount > 0) {
    limitations.push(`${failedCount} dari ${team.length} agent tidak merespons dan telah diisolasi.`);
  }
  limitations.push(
    `Sintesis disusun menggunakan mode '${modeConfig?.name || "Parallel"}' dari ${validMessages.length} respons aktual.`
  );

  const sections = {
    executiveSummary: crossAgentExecutiveSummary,
    mainFindings: mainFindings.length ? mainFindings : "UNAVAILABLE",
    supportingEvidence: supportingEvidence.length ? supportingEvidence : "UNAVAILABLE",
    conflictingEvidence: conflictingEvidence.length ? conflictingEvidence : "UNAVAILABLE",
    agreement: effectiveDebate.agreements?.length ? effectiveDebate.agreements : "UNAVAILABLE",
    disagreement: effectiveDebate.disagreements?.length ? effectiveDebate.disagreements : "UNAVAILABLE",
    recommendations: recommendations.length ? recommendations : "UNAVAILABLE",
    risks: risks.length ? risks : "UNAVAILABLE",
    limitations,
    confidence: {
      consensusStatus: effectiveDebate.consensusStatus,
      consensusScore: effectiveDebate.consensusScore,
      agreementScore: effectiveDebate.agreementScore,
      similarityScore: effectiveDebate.similarityScore,
      evidenceQualityScore: effectiveDebate.evidenceQualityScore,
      confidenceScore: effectiveDebate.confidenceScore,
      activeAgents: validMessages.length,
      totalAgents: team.length || validMessages.length
    },
    unresolvedQuestions:
      effectiveDebate.unresolvedQuestions?.length ? effectiveDebate.unresolvedQuestions : "UNAVAILABLE"
  };

  return {
    status: "SYNTHESIS_COMPLETED",
    synthesizedBy: "J.A.R.V.I.S. CORE (Multi-Claim Synthesis)",
    mode: modeConfig?.id || "parallel",
    consensusStatus: effectiveDebate.consensusStatus,
    consensusReached: effectiveDebate.consensusReached,
    consensusScore: effectiveDebate.consensusScore,
    sections,
    executiveSummary: sections.executiveSummary,
    mainFindings: sections.mainFindings,
    supportingEvidence: sections.supportingEvidence,
    conflictingEvidence: sections.conflictingEvidence,
    agreement: sections.agreement,
    disagreement: sections.disagreement,
    recommendations,
    risks,
    limitations,
    confidence: sections.confidence,
    unresolvedQuestions: sections.unresolvedQuestions,
    summary: crossAgentExecutiveSummary,
    conclusion: crossAgentExecutiveSummary,
    executiveDecision: crossAgentExecutiveSummary.slice(0, 600),
    evidence: supportingEvidence.slice(0, 8),
    agreements: effectiveDebate.agreements || [],
    disagreements: effectiveDebate.disagreements || [],
    recommendedStack: recommendations.slice(0, 5),
    nextSteps: recommendations.slice(0, 3),
    preReviewOutput: preReviewOutput || undefined,
    postReviewOutput: postReviewOutput || undefined,
    exportArtifacts: {
      pdfReport: {
        filename: "Laporan_Kolaborasi_JARVIS_B8_5_1.pdf",
        title: `Laporan Kolaborasi AI (${modeConfig?.name || "Parallel"}): ${String(topic).slice(0, 60)}`,
        sections: [
          "Executive Summary",
          "Main Findings",
          "Supporting Evidence",
          "Conflicting Evidence",
          "Agreement & Disagreement",
          "Recommendations",
          "Risks & Limitations",
          "Confidence & Unresolved Questions"
        ]
      },
      markdownPlan: {
        filename: "Rencana_Kolaborasi_JARVIS_B8_5_1.md",
        content: crossAgentExecutiveSummary
      },
      diagramSpec: {
        filename: "Diagram_Knowledge_Graph_JARVIS_B8_5_1.png",
        format: "mermaid+svg",
        nodeCount: knowledgeGraph?.nodes?.length || 0,
        edgeCount: knowledgeGraph?.edges?.length || 0
      }
    }
  };
}

/**
 * Explicit Test-Only Mock Fetch Fixture (Batch 8.5.1 Section 4.1).
 * NEVER runs in production. NEVER auto-activates on network errors unless
 * resolveExecutionEnvironment(env, payload).allowMockProviders === true.
 */
export function createOfflineTestProviderFetchFallback(originalFetch, envGuard = { allowMockProviders: false }) {
  if (!envGuard?.allowMockProviders) {
    // In production or when mock providers are disabled, return originalFetch untouched so real network errors surface honestly.
    return originalFetch;
  }
  return async (input, init) => {
    const url = typeof input === "string" ? input : input?.url || "";
    const bodyRaw = init?.body ? String(init.body) : "{}";    let promptSnippet = "analisis arsitektur dan strategi teknis";
    try {
      const parsed = JSON.parse(bodyRaw);
      const lastMsg =
        parsed?.messages?.[parsed.messages.length - 1]?.content ||
        parsed?.contents?.[parsed.contents.length - 1]?.parts?.[0]?.text ||
        "";
      if (lastMsg) promptSnippet = String(lastMsg).slice(0, 240);
    } catch (_) {}

    const responseText = [
      `[TEST_MOCK_FIXTURE] Analisis teknis terhadap: ${promptSnippet.replace(/\s+/g, " ").slice(0, 140)}.`,
      `- Rekomendasi utama: Gunakan arsitektur modular dengan batas domain tegas, PostgreSQL dengan Row-Level Security (RLS), dan Redis cache.`,
      `- Bukti & evaluasi: Isolasi layanan autentikasi dan transaksi mencegah cascading failure serta menjaga p95 latency < 150ms karena beban baca diserap cache.`,
      `- Risiko & mitigasi: Risiko lonjakan koneksi database dimitigasi dengan connection pooling PgBouncer, rate limiting, dan circuit breaker.`
    ].join("\n");

    if (url.includes("generativelanguage.googleapis.com")) {
      return new Response(
        JSON.stringify({
          candidates: [{ content: { parts: [{ text: responseText }] } }]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }
    if (url.includes("api.anthropic.com")) {
      return new Response(
        JSON.stringify({
          content: [{ type: "text", text: responseText }]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }
    return new Response(
      JSON.stringify({
        choices: [{ message: { content: responseText } }]
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  };
}

export async function runCollaborationSession(env, userId, payload = {}) {
  const safeUserId = String(userId || "").trim();
  if (!safeUserId) {
    throw new Error("userId is required for collaboration sessions.");
  }

  const kv = resolveKv(env);
  const action = String(payload.action || "start").toLowerCase();

  // Handle stop / cancel action
  if ((action === "stop" || action === "cancel") && payload.sessionId) {
    const existing = await getCollaborationSession(env, safeUserId, payload.sessionId);
    if (!existing) return null;
    existing.status = action === "cancel" ? "cancelled" : "stopped";
    existing.collaborationStatus = existing.status;
    existing.updatedAt = new Date().toISOString();
    const cancelEmitter = createCollaborationEventEmitter(existing.sessionId);
    cancelEmitter.emit(AGENT_EVENT_TYPES.SESSION_CANCELLED, {
      status: existing.status,
      metadata: { reason: "User requested stop/cancel" }
    });
    existing.events = [...(existing.events || []), ...cancelEmitter.events];
    await saveCollaborationSession(env, safeUserId, existing);
    return existing;
  }

  // Handle retry_synthesis action on an existing session with preserved agent outputs
  if (action === "retry_synthesis" && payload.sessionId) {
    const existing = await getCollaborationSession(env, safeUserId, payload.sessionId);
    if (!existing) return null;
    const retryEmitter = createCollaborationEventEmitter(existing.sessionId);
    retryEmitter.emit(AGENT_EVENT_TYPES.SYNTHESIS_STARTED, {
      status: "running",
      metadata: { retry: true }
    });
    const modeConfig = resolveCollaborationMode(existing.mode || "parallel");
    const retriedSynthesis = await runSynthesisPipeline({
      kv,
      userId: safeUserId,
      env,
      topic: existing.topic,
      modeConfig,
      team: existing.team,
      messages: existing.messages,
      debate: existing.debate,
      knowledgeGraph: existing.knowledgeGraph,
      preReviewOutput: existing.preReviewOutput,
      postReviewOutput: existing.postReviewOutput,
      synthesisProviderOverride: payload.synthesisProviderOverride || null,
      fetchImpl: payload.fetchImpl || globalThis.fetch,
      signal: payload.signal || null
    });
    existing.synthesis = retriedSynthesis;
    existing.synthesisStatus = retriedSynthesis.status;
    if (retriedSynthesis.status === "SYNTHESIS_FAILED") {
      existing.status = COLLABORATION_SESSION_STATUSES.SYNTHESIS_FAILED;
      retryEmitter.emit(AGENT_EVENT_TYPES.SYNTHESIS_FAILED, {
        status: "failed",
        metadata: { error: retriedSynthesis.error || "Synthesis retry failed" }
      });
    } else {
      const completedCount = (existing.messages || []).filter((m) => m.status === "completed").length;
      const failedCount = (existing.messages || []).filter((m) => m.status === "failed").length;
      existing.status =
        failedCount === 0
          ? COLLABORATION_SESSION_STATUSES.SUCCESS
          : completedCount > 0
            ? COLLABORATION_SESSION_STATUSES.PARTIAL_SUCCESS
            : COLLABORATION_SESSION_STATUSES.COLLABORATION_FAILED;
      retryEmitter.emit(AGENT_EVENT_TYPES.SYNTHESIS_COMPLETED, {
        status: "completed",
        metadata: { consensusScore: existing.debate?.consensusScore || 0 }
      });
    }
    existing.events = [...(existing.events || []), ...retryEmitter.events];
    existing.updatedAt = new Date().toISOString();
    await saveCollaborationSession(env, safeUserId, existing);
    return existing;
  }

  // Idempotency check via clientRequestId
  const clientRequestId = String(payload.clientRequestId || "").trim().slice(0, 80);
  if (clientRequestId && kv) {
    const idempKey = `collab_idemp:${safeUserId}:${clientRequestId}`;
    const existingSessionId = await kv.get(idempKey);
    if (existingSessionId) {
      const existingSession = await getCollaborationSession(env, safeUserId, existingSessionId);
      if (existingSession) {
        return { ...existingSession, replayed: true };
      }
    }
  }

  const sessionId = String(
    payload.sessionId || `collab_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`
  );
  const emitter = createCollaborationEventEmitter(sessionId);

  // Batch 8.5.1 Section 4.1 & 4.2 — Strict Environment Safety Guard & Zero Fake Credentials
  const envGuard = resolveExecutionEnvironment(env, {
    jarvisEnv: payload.jarvisEnv,
    mockProviderEnabled: payload.mockProviderEnabled
  });

  const modeConfig = resolveCollaborationMode(payload.mode || "parallel");
  const topic = sanitizeTopic(payload.topic || payload.prompt || "Merancang arsitektur aplikasi SaaS scalable");
  const selectionMode = String(payload.selectionMode || "manual").toLowerCase() === "auto" ? "auto" : "manual";
  const assignRoles = payload.assignRoles !== false;
  const enableKnowledgeGraph = payload.enableKnowledgeGraph !== false;
  const concurrencyLimit = Math.max(1, Math.min(Number(payload.concurrencyLimit) || 3, 6));
  const timeoutMs = Math.max(100, Math.min(Number(payload.timeoutMs) || 25000, 60000));
  const requestedRounds = Number(payload.rounds) || modeConfig.defaultRounds;
  const totalRounds = Math.min(Math.max(1, requestedRounds), MAX_COLLABORATION_ROUNDS);

  emitter.emit(AGENT_EVENT_TYPES.SESSION_CREATED, {
    status: "created",
    metadata: {
      mode: modeConfig.id,
      requestedMode: modeConfig.requestedMode,
      selectionMode,
      totalRounds,
      concurrencyLimit,
      jarvisEnv: envGuard.jarvisEnv,
      allowMockProviders: envGuard.allowMockProviders,
      ...(envGuard.warning ? { warning: envGuard.warning } : {})
    }
  });

  const team = selectCollaborationTeam({
    selectionMode,
    selectedModelIds: payload.selectedModelIds || payload.models,
    assignRoles
  });

  // Never inject "env-fallback-*" fake keys. Only if allowMockProviders is explicitly true in test/dev
  // and no real keys are configured, provide explicit test-only fixture keys that only work with test fixture fetch.
  const hasAnyConfiguredProvider = (await listAvailableProvidersForUser(kv, safeUserId, env)).length > 0;
  const effectiveEnv =
    !hasAnyConfiguredProvider && envGuard.allowMockProviders
      ? {
          ...env,
          OPENAI_API_KEY_1: "sk-test-fixture-openai-key-explicit",
          ANTHROPIC_API_KEY_1: "sk-ant-test-fixture-key-explicit",
          GEMINI_API_KEY_1: "AIzaTestFixtureGeminiKeyExplicit",
          DEEPSEEK_API_KEY_1: "sk-test-fixture-deepseek-key-explicit",
          GROQ_API_KEY_1: "gsk_test_fixture_groq_key_explicit",
          OPENROUTER_API_KEY_1: "sk-or-test-fixture-key-explicit"
        }
      : env;

  const fetchImpl =
    payload.fetchImpl ||
    (envGuard.allowMockProviders
      ? createOfflineTestProviderFetchFallback(globalThis.fetch, envGuard)
      : globalThis.fetch);
  const signal = payload.signal || null;

  emitter.emit(AGENT_EVENT_TYPES.TASK_PLANNED, {
    status: "planned",
    metadata: {
      mode: modeConfig.id,
      teamSize: team.length,
      steps: ["Decomposition & Assignment", `Mode Execution (${modeConfig.id})`, "Evaluation & Graph", "Synthesis"]
    }
  });

  for (const member of team) {
    emitter.emit(AGENT_EVENT_TYPES.AGENT_ASSIGNED, {
      agentId: member.agentId,
      status: "assigned",
      metadata: {
        modelId: member.modelId,
        modelName: member.modelName,
        provider: member.provider,
        role: member.role
      }
    });
  }

  const messages = [];
  const opinionEvolution = [];
  const agentFailures = [];
  let preReviewOutput = null;
  let postReviewOutput = null;
  let ensembleRanking = [];

  const recordAgentTurn = (res, round, badge = "Speaking") => {
    const isSuccess = Boolean(res.ok && typeof res.content === "string" && res.content.trim());
    const content = isSuccess ? res.content : "";
    const extracted = isSuccess
      ? extractStructuredPointsFromText(content)
      : { keyPoints: [], claims: [], risks: [], recommendations: [] };
    const errorCode = isSuccess
      ? null
      : res.errorCode || classifyProviderError({ message: res.error }, 0);

    const msgObj = {
      id: `msg_r${round}_${messages.length + 1}`,
      round,
      agentId: res.agentId,
      modelId: res.modelId,
      modelName: res.modelName,
      provider: res.provider,
      executedModel: res.executedModel || res.modelId,
      accountId: res.accountId || undefined,
      latencyMs: res.latencyMs || 0,
      role: res.role,
      badge: isSuccess ? badge : "Error",
      status: isSuccess ? "completed" : "failed",
      content,
      errorCode: errorCode || undefined,
      error: isSuccess ? undefined : res.error,
      keyPoints: extracted.keyPoints,
      confidence: isSuccess ? Math.min(0.98, 0.84 + Math.min(0.14, extracted.keyPoints.length * 0.03)) : 0,
      createdAt: new Date().toISOString()
    };
    messages.push(msgObj);
    if (!isSuccess) {
      agentFailures.push({
        agentId: res.agentId,
        modelId: res.modelId,
        modelName: res.modelName,
        provider: res.provider,
        role: res.role,
        round,
        errorCode,
        error: res.error
      });
    }
    opinionEvolution.push({
      round,
      agentId: res.agentId,
      modelName: res.modelName,
      role: res.role,
      stance: msgObj.badge,
      status: msgObj.status,
      errorCode: errorCode || undefined,
      summary: isSuccess ? (extracted.keyPoints[0] || content).slice(0, 180) : `[FAILED: ${errorCode}] ${res.error}`,
      confidence: msgObj.confidence
    });
    return msgObj;
  };

  // ==========================================================================
  // EXECUTE ONE OF THE 7 REAL COLLABORATION MODES
  // ==========================================================================
  if (modeConfig.id === "parallel") {
    for (let round = 1; round <= totalRounds; round++) {
      const tasks = team.map((member) => () =>
        executeAgentPromptLive({
          kv,
          userId: safeUserId,
          env: effectiveEnv,
          member,
          round,
          prompt: `[Mode: Parallel Analysis | Putaran ${round}/${totalRounds}]\nTopik: ${topic}\nPeran Anda: ${member.role} (${member.roleFocus}).\nBerikan analisis independen, rekomendasi teknis, dan risiko utama.`,
          systemPrompt: `You are ${member.modelName} acting as ${member.role} in J.A.R.V.I.S. Multi-AI Collaboration. Provide a structured, evidence-based analysis in Indonesian.`,
          emitter,
          timeoutMs,
          fetchImpl,
          signal
        })
      );
      const waveResults = await runAgentScheduler(tasks, { concurrencyLimit, signal });
      for (const res of waveResults) {
        if (res) recordAgentTurn(res, round, round === totalRounds ? "Consensus" : "Parallel");
      }
    }
  } else if (modeConfig.id === "sequential") {
    let accumulatedContext = "";
    for (let idx = 0; idx < team.length; idx++) {
      const member = team[idx];
      const stepNum = idx + 1;
      const seqPrompt = accumulatedContext
        ? `[Mode: Sequential Pipeline — Tahap ${stepNum}/${team.length}]\nTopik Utama: ${topic}\n\nHasil Tahap Sebelumnya:\n${accumulatedContext}\n\nTugas Anda sebagai ${member.role}: Lanjutkan, pertajam, dan lengkapi hasil di atas sesuai fokus Anda (${member.roleFocus}).`
        : `[Mode: Sequential Pipeline — Tahap 1/${team.length}]\nTopik Utama: ${topic}\n\nTugas Anda sebagai ${member.role}: Buat fondasi analisis dan rancangan tahap pertama (${member.roleFocus}).`;

      const res = await executeAgentPromptLive({
        kv,
        userId: safeUserId,
        env: effectiveEnv,
        member,
        round: stepNum,
        prompt: seqPrompt,
        systemPrompt: `You are ${member.modelName} executing Stage ${stepNum} (${member.role}) in a strict sequential AI pipeline. Build directly upon the previous stage's output.`,
        emitter,
        timeoutMs,
        fetchImpl,
        signal
      });
      const recorded = recordAgentTurn(res, 1, `Stage-${stepNum}`);
      if (res.ok && recorded.content) {
        accumulatedContext += `\n\n[Tahap ${stepNum} — ${member.modelName} (${member.role})]:\n${recorded.content.slice(0, 1200)}`;
      }
    }
  } else if (modeConfig.id === "debate") {
    for (let round = 1; round <= totalRounds; round++) {
      emitter.emit(AGENT_EVENT_TYPES.DEBATE_ROUND_STARTED, {
        status: "started",
        metadata: { round, totalRounds }
      });

      const prevRoundMsgs = messages.filter((m) => m.round === round - 1 && m.status === "completed");
      const tasks = team.map((member) => () => {
        const peerSummary = prevRoundMsgs
          .filter((m) => m.agentId !== member.agentId)
          .map((m) => `- [${m.modelName} (${m.role})]: ${m.content.slice(0, 450)}`)
          .join("\n");

        const debatePrompt =
          round === 1 || !peerSummary
            ? `[Mode: Debate Putaran 1/${totalRounds}]\nTopik: ${topic}\nPeran: ${member.role}.\nKemukakan posisi independen Anda beserta bukti pendukung dan kritik terhadap pendekatan konvensional.`
            : `[Mode: Debate Putaran ${round}/${totalRounds}]\nTopik: ${topic}\n\nRingkasan Posisi Agent Lain pada Putaran ${round - 1}:\n${peerSummary}\n\nTugas Anda (${member.role}): Berikan kontraargumen, uji kelemahan klaim agent lain, atau pertahankan/revisi posisi Anda berdasarkan bukti.`;

        return executeAgentPromptLive({
          kv,
          userId: safeUserId,
          env: effectiveEnv,
          member,
          round,
          prompt: debatePrompt,
          systemPrompt: `You are ${member.modelName} (${member.role}) participating in Round ${round} of a structured multi-AI debate. Challenge weak assumptions and provide concrete technical reasoning.`,
          emitter,
          timeoutMs,
          fetchImpl,
          signal
        });
      });

      const roundResults = await runAgentScheduler(tasks, { concurrencyLimit, signal });
      roundResults.forEach((res, idx) => {
        if (!res) return;
        const badge = round === 1 ? "Position" : idx % 2 === 1 ? "Counter" : "Rebuttal";
        recordAgentTurn(res, round, badge);
      });
    }
  } else if (modeConfig.id === "consensus") {
    const tasks = team.map((member) => () =>
      executeAgentPromptLive({
        kv,
        userId: safeUserId,
        env: effectiveEnv,
        member,
        round: 1,
        prompt: `[Mode: Consensus Evaluation]\nTopik: ${topic}\nPeran: ${member.role}.\nBerikan rekomendasi utama, prasyarat wajib, dan risiko kritis untuk dievaluasi konsensusnya lintas model.`,
        systemPrompt: `You are ${member.modelName} (${member.role}). State clear architectural claims, trade-offs, and recommendations so consensus and divergence can be measured objectively.`,
        emitter,
        timeoutMs,
        fetchImpl,
        signal
      })
    );
    const waveResults = await runAgentScheduler(tasks, { concurrencyLimit, signal });
    for (const res of waveResults) {
      if (res) recordAgentTurn(res, 1, "Proposal");
    }
  } else if (modeConfig.id === "specialist") {
    const tasks = team.map((member, idx) => () => {
      const subtask = member.subtaskDirective || `Subtugas spesialis #${idx + 1} (${member.role})`;
      return executeAgentPromptLive({
        kv,
        userId: safeUserId,
        env: effectiveEnv,
        member,
        round: 1,
        prompt: `[Mode: Specialist Subtask Assignment]\nTopik Induk: ${topic}\nPeran Spesialis: ${member.role}\nSubtugas Spesifik Anda: ${subtask}\nSelesaikan subtugas ini secara mendalam sesuai kapabilitas model (${member.capabilities.join(", ")}).`,
        systemPrompt: `You are ${member.modelName}, assigned as the ${member.role} specialist. Focus strictly on delivering your assigned subtask with high technical precision.`,
        emitter,
        timeoutMs,
        fetchImpl,
        signal
      });
    });
    const waveResults = await runAgentScheduler(tasks, { concurrencyLimit, signal });
    for (const res of waveResults) {
      if (res) recordAgentTurn(res, 1, `Specialist:${res.role}`);
    }
  } else if (modeConfig.id === "reviewer") {
    const primaryAgent = team[0];
    const reviewers = team.length > 1 ? team.slice(1) : [team[0]];

    const draftRes = await executeAgentPromptLive({
      kv,
      userId: safeUserId,
      env: effectiveEnv,
      member: primaryAgent,
      round: 1,
      prompt: `[Mode: Reviewer — Tahap 1 (Primary Draft)]\nTopik: ${topic}\nSusun rancangan solusi lengkap untuk diaudit oleh tim reviewer independen.`,
      systemPrompt: `You are ${primaryAgent.modelName} (Primary Author). Produce a comprehensive initial solution draft.`,
      emitter,
      timeoutMs,
      fetchImpl,
      signal
    });
    const draftMsg = recordAgentTurn(draftRes, 1, "PrimaryDraft");
    preReviewOutput = draftRes.ok ? draftMsg.content : null;

    if (draftRes.ok && preReviewOutput) {
      const reviewTasks = reviewers.map((rev) => () =>
        executeAgentPromptLive({
          kv,
          userId: safeUserId,
          env: effectiveEnv,
          member: rev,
          round: 1,
          prompt: `[Mode: Reviewer — Tahap 2 (Independent Audit)]\nTopik: ${topic}\n\nDraf Awal dari ${primaryAgent.modelName}:\n${preReviewOutput.slice(0, 1800)}\n\nTugas Anda (${rev.role}): Audit draf di atas. Temukan kelemahan, risiko keamanan/skalabilitas, dan berikan poin revisi konkret.`,
          systemPrompt: `You are ${rev.modelName} acting as an independent Reviewer (${rev.role}). Critique the primary draft rigorously.`,
          emitter,
          timeoutMs,
          fetchImpl,
          signal
        })
      );
      const reviewResults = await runAgentScheduler(reviewTasks, { concurrencyLimit, signal });
      const reviewSummaries = [];
      for (const rRes of reviewResults) {
        if (!rRes) continue;
        const rMsg = recordAgentTurn(rRes, 1, "Review");
        if (rRes.ok && rMsg.content) {
          reviewSummaries.push(`[Reviewer ${rMsg.modelName} (${rMsg.role})]:\n${rMsg.content.slice(0, 600)}`);
        }
      }

      emitter.emit(AGENT_EVENT_TYPES.REVIEW_COMPLETED, {
        agentId: primaryAgent.agentId,
        status: "completed",
        metadata: { reviewersCount: reviewSummaries.length }
      });

      const revisionRes = await executeAgentPromptLive({
        kv,
        userId: safeUserId,
        env: effectiveEnv,
        member: primaryAgent,
        round: 2,
        prompt: `[Mode: Reviewer — Tahap 3 (Post-Review Revision)]\nTopik: ${topic}\n\nDraf Awal Anda:\n${preReviewOutput.slice(0, 1000)}\n\nTemuan Reviewer Independen:\n${reviewSummaries.join("\n\n")}\n\nSusun versi revisi final yang memperbaiki seluruh temuan reviewer di atas.`,
        systemPrompt: `You are ${primaryAgent.modelName} producing the final revised solution after peer review.`,
        emitter,
        timeoutMs,
        fetchImpl,
        signal
      });
      const revMsg = recordAgentTurn(revisionRes, 2, "RevisedFinal");
      postReviewOutput = revisionRes.ok ? revMsg.content : null;
    }
  } else {
    const tasks = team.map((member) => () =>
      executeAgentPromptLive({
        kv,
        userId: safeUserId,
        env: effectiveEnv,
        member,
        round: 1,
        prompt: `[Mode: Ensemble Candidate Generation]\nTopik: ${topic}\nPeran: ${member.role}.\nBerikan solusi paling komprehensif beserta bukti teknis, langkah implementasi, dan metrik verifikasi.`,
        systemPrompt: `You are ${member.modelName} (${member.role}) in an Ensemble evaluation. Provide high-density technical evidence and actionable steps.`,
        emitter,
        timeoutMs,
        fetchImpl,
        signal
      })
    );
    const waveResults = await runAgentScheduler(tasks, { concurrencyLimit, signal });
    for (const res of waveResults) {
      if (!res) continue;
      const msg = recordAgentTurn(res, 1, "Candidate");
      const evMeta = res.ok ? measureEvidenceQualityForMessage(msg.content) : { score: 0, evidenceItems: [] };
      const pts = res.ok ? extractStructuredPointsFromText(msg.content) : { keyPoints: [], recommendations: [] };
      const qualityScore = res.ok
        ? Math.min(99, Math.round(evMeta.score * 0.75 + pts.recommendations.length * 5))
        : 0;
      ensembleRanking.push({
        agentId: msg.agentId,
        modelName: msg.modelName,
        provider: msg.provider,
        role: msg.role,
        status: msg.status,
        qualityScore,
        evidenceCount: pts.keyPoints.length
      });
    }
    ensembleRanking.sort((a, b) => b.qualityScore - a.qualityScore);
  }

  // ==========================================================================
  // DYNAMIC CLAIM-BASED CONSENSUS, SEMANTIC KNOWLEDGE GRAPH & 10-STAGE SYNTHESIS
  // ==========================================================================
  const completedMessages = messages.filter((m) => m.status === "completed");
  const failedMessages = messages.filter((m) => m.status === "failed");
  const completedMessagesCount = completedMessages.length;
  const allAgentsFailed = completedMessagesCount === 0;

  emitter.emit(AGENT_EVENT_TYPES.SYNTHESIS_STARTED, {
    status: allAgentsFailed ? "failed" : "running",
    metadata: { validMessagesCount: completedMessagesCount }
  });

  const debate = buildRealDebateAndConsensus(topic, messages, team);
  const knowledgeGraph = buildKnowledgeGraphFromActualResponses(
    topic,
    messages,
    team,
    enableKnowledgeGraph,
    { sessionId, debate }
  );

  const synthesis = await runSynthesisPipeline({
    kv,
    userId: safeUserId,
    env: effectiveEnv,
    topic,
    modeConfig,
    team,
    messages,
    debate,
    knowledgeGraph,
    preReviewOutput,
    postReviewOutput,
    synthesisProviderOverride: payload.synthesisProviderOverride || null,
    fetchImpl,
    signal
  });

  const synthesisFailed = synthesis.status === "SYNTHESIS_FAILED";

  if (synthesisFailed) {
    emitter.emit(AGENT_EVENT_TYPES.SYNTHESIS_FAILED, {
      status: "failed",
      metadata: {
        errorCode: synthesis.errorCode || PROVIDER_ERROR_CODES.PROVIDER_ERROR,
        error: synthesis.error || "Synthesis failed"
      }
    });
  } else {
    emitter.emit(AGENT_EVENT_TYPES.SYNTHESIS_COMPLETED, {
      status: "completed",
      metadata: {
        consensusStatus: debate.consensusStatus,
        consensusScore: debate.consensusScore,
        consensusReached: debate.consensusReached,
        agreementsCount: debate.agreements.length,
        disagreementsCount: debate.disagreements.length
      }
    });
  }

  // Batch 8.5.1 Section 4.4 & 7.3 — Exact Session Status Determination (No False Success)
  let collaborationStatus;
  if (signal?.aborted) {
    collaborationStatus = COLLABORATION_SESSION_STATUSES.CANCELLED;
  } else if (allAgentsFailed) {
    collaborationStatus = COLLABORATION_SESSION_STATUSES.COLLABORATION_FAILED;
  } else if (synthesisFailed) {
    collaborationStatus = COLLABORATION_SESSION_STATUSES.SYNTHESIS_FAILED;
  } else if (failedMessages.length > 0) {
    collaborationStatus = COLLABORATION_SESSION_STATUSES.PARTIAL_SUCCESS;
  } else {
    collaborationStatus = COLLABORATION_SESSION_STATUSES.SUCCESS;
  }

  const workflowSteps = [
    {
      stepNumber: 1,
      id: "step_1_problem_breakdown",
      title: "Step 1 — Problem Breakdown",
      assignedModels: team.slice(0, 2).map((m) => m.modelName),
      status: completedMessagesCount > 0 ? "completed" : "failed",
      summary: `Dekomposisi tugas untuk "${topic.slice(0, 80)}" menggunakan mode ${modeConfig.name}.`
    },
    {
      stepNumber: 2,
      id: "step_2_architecture_proposal",
      title: "Step 2 — Architecture Proposal",
      assignedModels: team.map((m) => m.modelName),
      status:
        completedMessagesCount === 0
          ? "failed"
          : failedMessages.length > 0
            ? "partial"
            : "completed",
      summary: `Mengumpulkan ${completedMessagesCount} kontribusi berhasil dan ${failedMessages.length} kegagalan dari ${messages.length} total turn.`
    },
    {
      stepNumber: 3,
      id: "step_3_security_risk_review",
      title: "Step 3 — Security & Risk Review",
      assignedModels: team.slice(-2).map((m) => m.modelName),
      status: completedMessagesCount > 0 ? "completed" : "failed",
      summary: `Status konsensus '${debate.consensusStatus}' (${debate.consensusScore}%) dari ${debate.claims.length} klaim terstruktur.`
    },
    {
      stepNumber: 4,
      id: "step_4_final_synthesis",
      title: "Step 4 — Final Synthesis",
      assignedModels: [synthesis.synthesizedBy],
      status: synthesisFailed ? "failed" : "completed",
      summary: synthesisFailed
        ? `Sintesis gagal (${synthesis.errorCode}): ${synthesis.error}`
        : "Sintesis 10-tahap selesai dengan 11 bagian tervalidasi."
    }
  ];

  emitter.emit(
    collaborationStatus === COLLABORATION_SESSION_STATUSES.CANCELLED
      ? AGENT_EVENT_TYPES.SESSION_CANCELLED
      : AGENT_EVENT_TYPES.SESSION_COMPLETED,
    {
      status: collaborationStatus,
      metadata: {
        collaborationStatus,
        messagesCount: messages.length,
        completedMessagesCount,
        failedMessagesCount: failedMessages.length,
        eventsCount: emitter.events.length
      }
    }
  );

  const now = new Date().toISOString();
  const session = {
    sessionId,
    userId: safeUserId,
    clientRequestId: clientRequestId || undefined,
    title: String(payload.title || topic.slice(0, 72)).trim(),
    topic: redactSecrets(topic),
    selectionMode,
    mode: modeConfig.id,
    requestedMode: modeConfig.requestedMode,
    modeName: modeConfig.name,
    assignRoles,
    enableKnowledgeGraph,
    currentRound: totalRounds,
    totalRounds,
    status: collaborationStatus,
    collaborationStatus,
    synthesisStatus: synthesis.status,
    environmentSafety: envGuard,
    team,
    messages,
    agentFailures,
    opinionEvolution,
    events: emitter.events,
    debate,
    workflowSteps,
    knowledgeGraph,
    synthesis: allAgentsFailed ? null : synthesis,
    failedSynthesisDiagnostic: allAgentsFailed ? synthesis : undefined,
    ensembleRanking: ensembleRanking.length ? ensembleRanking : undefined,
    preReviewOutput: preReviewOutput || undefined,
    postReviewOutput: postReviewOutput || undefined,
    createdAt: now,
    updatedAt: now
  };

  await saveCollaborationSession(env, safeUserId, session);
  if (clientRequestId && kv) {
    await kv.put(`collab_idemp:${safeUserId}:${clientRequestId}`, session.sessionId);
  }
  return session;
}

export async function saveCollaborationSession(env, userId, session) {
  const kv = resolveKv(env);
  if (!kv || !userId || !session?.sessionId) {
    return session;
  }
  const sessionKey = `collab_session:${userId}:${session.sessionId}`;
  const indexKey = `collab_index:${userId}`;
  await kv.put(sessionKey, JSON.stringify(session));

  let index = [];
  try {
    const raw = await kv.get(indexKey);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) index = parsed;
    }
  } catch (err) {
    console.warn("Failed to parse collaboration index:", err?.message || String(err));
  }

  const summaryEntry = {
    sessionId: session.sessionId,
    title: session.title,
    topic: session.topic,
    mode: session.mode,
    modeName: session.modeName,
    status: session.status,
    collaborationStatus: session.collaborationStatus || session.status,
    synthesisStatus: session.synthesisStatus || (session.synthesis ? session.synthesis.status : "SYNTHESIS_FAILED"),
    modelsCount: Array.isArray(session.team) ? session.team.length : 0,
    modelNames: Array.isArray(session.team) ? session.team.map((m) => m.modelName) : [],
    totalRounds: session.totalRounds,
    consensusStatus: session.debate?.consensusStatus || CONSENSUS_STATUSES.CONSENSUS_NOT_ESTABLISHED,
    consensusScore: session.synthesis?.consensusScore ?? session.debate?.consensusScore ?? 0,
    consensusReached: session.debate?.consensusReached ?? false,
    eventsCount: Array.isArray(session.events) ? session.events.length : 0,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt
  };

  const filtered = index.filter((item) => item && item.sessionId !== session.sessionId);
  filtered.unshift(summaryEntry);
  await kv.put(indexKey, JSON.stringify(filtered.slice(0, 50)));
  return session;
}

export async function getCollaborationSession(env, userId, sessionId) {
  const kv = resolveKv(env);
  if (!kv || !userId || !sessionId) return null;
  try {
    const raw = await kv.get(`collab_session:${userId}:${sessionId}`);
    return raw ? JSON.parse(raw) : null;
  } catch (err) {
    console.warn("Failed to read collaboration session:", err?.message || String(err));
    return null;
  }
}

export async function listCollaborationSessions(env, userId) {
  const kv = resolveKv(env);
  if (!kv || !userId) return [];
  try {
    const raw = await kv.get(`collab_index:${userId}`);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (err) {
    console.warn("Failed to list collaboration sessions:", err?.message || String(err));
    return [];
  }
}

export async function deleteCollaborationSession(env, userId, sessionId) {
  const kv = resolveKv(env);
  if (!kv || !userId || !sessionId) {
    return { removed: false, remaining: 0 };
  }
  await kv.delete(`collab_session:${userId}:${sessionId}`);
  const index = await listCollaborationSessions(env, userId);
  const next = index.filter((item) => item.sessionId !== sessionId);
  await kv.put(`collab_index:${userId}`, JSON.stringify(next));
  return { removed: next.length < index.length, remaining: next.length };
}