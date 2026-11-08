import { boundedString } from "../utils.js";

/**
 * J.A.R.V.I.S. B8.1 / B8.5.2 / B8.1.1 Model Registry — CORE, PULSE, SPARK, APEX tiers + Provider-Scoped Model Catalog.
 * Strictly binds models to their respective provider, preserves exact requested apiModel IDs without
 * silent downgrade, supports dynamic per-provider model discovery, and filters active models based on provider availability.
 */
export const JARVIS_TIERS = Object.freeze({
  CORE: {
    id: "CORE",
    code: "JARVIS 1 — CORE",
    label: "CORE",
    tagline: "Cepat, ringan, dan efisien untuk percakapan harian & perintah perangkat",
    defaultModelId: "llama-3.3-70b",
    routingOrder: [
      { provider: "groq", modelId: "llama-3.3-70b", apiModel: "llama-3.3-70b-versatile" },
      { provider: "gemini", modelId: "gemini-2.5-flash", apiModel: "gemini-2.5-flash" },
      { provider: "deepseek", modelId: "deepseek-v3", apiModel: "deepseek-chat" }
    ],
    capabilities: ["text", "code", "fast"]
  },
  PULSE: {
    id: "PULSE",
    code: "JARVIS 2 — PULSE",
    label: "PULSE",
    tagline: "Seimbang untuk riset cepat, multimodal, dan produktivitas harian",
    defaultModelId: "gemini-1.5-pro",
    routingOrder: [
      { provider: "gemini", modelId: "gemini-1.5-pro", apiModel: "gemini-1.5-pro" },
      { provider: "openai", modelId: "gpt-4o", apiModel: "gpt-4o" },
      { provider: "groq", modelId: "llama-3.3-70b", apiModel: "llama-3.3-70b-versatile" }
    ],
    capabilities: ["text", "vision", "audio", "long_context"]
  },
  SPARK: {
    id: "SPARK",
    code: "JARVIS 3 — SPARK",
    label: "SPARK",
    tagline: "Penalaran mendalam, arsitektur perangkat lunak, dan analisis teknis",
    defaultModelId: "deepseek-v3",
    routingOrder: [
      { provider: "deepseek", modelId: "deepseek-v3", apiModel: "deepseek-chat" },
      { provider: "openai", modelId: "gpt-4o", apiModel: "gpt-4o" },
      { provider: "anthropic", modelId: "claude-3-5-sonnet", apiModel: "claude-3-5-sonnet-latest" },
      { provider: "gemini", modelId: "gemini-1.5-pro", apiModel: "gemini-1.5-pro" }
    ],
    capabilities: ["text", "code", "reasoning", "vision"]
  },
  APEX: {
    id: "APEX",
    code: "JARVIS 4 — APEX",
    label: "APEX",
    tagline: "Orkestrasi flagship multi-provider, sintesis eksekutif, dan kolaborasi penuh",
    defaultModelId: "gpt-4o",
    routingOrder: [
      { provider: "openai", modelId: "gpt-4o", apiModel: "gpt-4o" },
      { provider: "anthropic", modelId: "claude-3-5-sonnet", apiModel: "claude-3-5-sonnet-latest" },
      { provider: "gemini", modelId: "gemini-1.5-pro", apiModel: "gemini-1.5-pro" },
      { provider: "deepseek", modelId: "deepseek-v3", apiModel: "deepseek-chat" },
      { provider: "openrouter", modelId: "qwen-2.5-72b", apiModel: "qwen/qwen-2.5-72b-instruct" }
    ],
    capabilities: ["text", "vision", "code", "audio", "reasoning", "long_context", "multi_agent"]
  }
});

export const BUILTIN_MODELS = Object.freeze([
  {
    id: "gpt-4o",
    name: "GPT-4o",
    provider: "openai",
    providerLabel: "OpenAI",
    apiModel: "gpt-4o",
    tier: "APEX",
    summary: "Multimodal · Analisis, reasoning, pemecahan masalah",
    categories: ["Semua", "Teks", "Vision", "Kode"],
    capabilities: ["text", "vision", "code", "reasoning"],
    contextWindow: 128000,
    defaultSelected: true
  },
  {
    id: "claude-3-5-sonnet",
    name: "Claude 3.5 Sonnet",
    provider: "anthropic",
    providerLabel: "Anthropic",
    apiModel: "claude-3-5-sonnet-latest",
    tier: "SPARK",
    summary: "Analisis & Riset · Penulisan, analisis mendalam",
    categories: ["Semua", "Teks", "Vision", "Kode"],
    capabilities: ["text", "vision", "code", "reasoning"],
    contextWindow: 200000,
    defaultSelected: false
  },
  {
    id: "gemini-1.5-pro",
    name: "Gemini 1.5 Pro",
    provider: "gemini",
    providerLabel: "Google",
    apiModel: "gemini-1.5-pro",
    tier: "PULSE",
    summary: "Multimodal & Panjang · Riset, multimodal, konteks panjang",
    categories: ["Semua", "Teks", "Vision", "Kode"],
    capabilities: ["text", "vision", "audio", "code", "long_context"],
    contextWindow: 1000000,
    defaultSelected: false
  },
  {
    id: "llama-3.3-70b",
    name: "Llama 3.3 70B",
    provider: "meta",
    providerLabel: "Meta",
    apiModel: "llama-3.3-70b-versatile",
    tier: "CORE",
    summary: "Efisien & Open · Efisien, open model, coding",
    categories: ["Semua", "Teks", "Kode"],
    capabilities: ["text", "code", "fast"],
    contextWindow: 128000,
    defaultSelected: false
  },
  {
    id: "deepseek-v3",
    name: "DeepSeek-V3",
    provider: "deepseek",
    providerLabel: "DeepSeek",
    apiModel: "deepseek-chat",
    tier: "SPARK",
    summary: "Penalaran & Kode · Logika, matematika, analisis teknis",
    categories: ["Semua", "Teks", "Kode"],
    capabilities: ["text", "code", "reasoning"],
    contextWindow: 64000,
    defaultSelected: false
  },
  {
    id: "qwen-2.5-72b",
    name: "Qwen 2.5",
    provider: "openrouter",
    providerLabel: "Qwen / OpenRouter",
    apiModel: "qwen/qwen-2.5-72b-instruct",
    tier: "APEX",
    summary: "Multibahasa, general purpose",
    categories: ["Semua", "Teks", "Kode"],
    capabilities: ["text", "code", "multilingual"],
    contextWindow: 128000,
    defaultSelected: false
  }
]);

/**
 * Extended provider-specific model catalog so each provider only exposes its own models.
 */
export const PROVIDER_MODEL_CATALOG = Object.freeze({
  openai: [
    { id: "gpt-4o", name: "GPT-4o", provider: "openai", providerLabel: "OpenAI", apiModel: "gpt-4o", tier: "APEX", capabilities: ["text", "vision", "code", "reasoning"], categories: ["Semua", "Teks", "Vision", "Kode"], summary: "Flagship multimodal OpenAI" },
    { id: "gpt-4.1-mini", name: "GPT-4.1 Mini", provider: "openai", providerLabel: "OpenAI", apiModel: "gpt-4.1-mini", tier: "PULSE", capabilities: ["text", "vision", "code"], categories: ["Semua", "Teks", "Vision", "Kode"], summary: "Model cepat & hemat biaya OpenAI" },
    { id: "o3-mini", name: "OpenAI o3-mini", provider: "openai", providerLabel: "OpenAI", apiModel: "o3-mini", tier: "SPARK", capabilities: ["text", "code", "reasoning"], categories: ["Semua", "Teks", "Kode"], summary: "Penalaran STEM & koding OpenAI" }
  ],
  gemini: [
    { id: "gemini-1.5-pro", name: "Gemini 1.5 Pro", provider: "gemini", providerLabel: "Google", apiModel: "gemini-1.5-pro", tier: "PULSE", capabilities: ["text", "vision", "audio", "code", "long_context"], categories: ["Semua", "Teks", "Vision", "Kode"], summary: "Multimodal konteks 1M token Google" },
    { id: "gemini-2.5-flash", name: "Gemini 2.5 Flash", provider: "gemini", providerLabel: "Google", apiModel: "gemini-2.5-flash", tier: "CORE", capabilities: ["text", "vision", "code", "fast"], categories: ["Semua", "Teks", "Vision", "Kode"], summary: "Model cepat multimodal Google Gemini" },
    { id: "gemini-2.0-flash", name: "Gemini 2.0 Flash", provider: "gemini", providerLabel: "Google", apiModel: "gemini-2.0-flash", tier: "CORE", capabilities: ["text", "vision", "fast"], categories: ["Semua", "Teks", "Vision"], summary: "Generasi cepat Google Gemini" }
  ],
  anthropic: [
    { id: "claude-3-5-sonnet", name: "Claude 3.5 Sonnet", provider: "anthropic", providerLabel: "Anthropic", apiModel: "claude-3-5-sonnet-latest", tier: "SPARK", capabilities: ["text", "vision", "code", "reasoning"], categories: ["Semua", "Teks", "Vision", "Kode"], summary: "Analisis mendalam & koding Anthropic" },
    { id: "claude-3-5-haiku", name: "Claude 3.5 Haiku", provider: "anthropic", providerLabel: "Anthropic", apiModel: "claude-3-5-haiku-latest", tier: "CORE", capabilities: ["text", "code", "fast"], categories: ["Semua", "Teks", "Kode"], summary: "Respons instan & akurat Anthropic" }
  ],
  deepseek: [
    { id: "deepseek-v3", name: "DeepSeek-V3", provider: "deepseek", providerLabel: "DeepSeek", apiModel: "deepseek-chat", tier: "SPARK", capabilities: ["text", "code", "reasoning"], categories: ["Semua", "Teks", "Kode"], summary: "Model koding & penalaran DeepSeek" },
    { id: "deepseek-chat", name: "DeepSeek Chat", provider: "deepseek", providerLabel: "DeepSeek", apiModel: "deepseek-chat", tier: "CORE", capabilities: ["text", "code"], categories: ["Semua", "Teks", "Kode"], summary: "Percakapan teknis DeepSeek" },
    { id: "deepseek-reasoner", name: "DeepSeek R1 Reasoner", provider: "deepseek", providerLabel: "DeepSeek", apiModel: "deepseek-reasoner", tier: "APEX", capabilities: ["text", "code", "reasoning"], categories: ["Semua", "Teks", "Kode"], summary: "Chain-of-thought reasoning DeepSeek" }
  ],
  groq: [
    { id: "llama-3.3-70b-versatile", name: "Llama 3.3 70B Versatile", provider: "groq", providerLabel: "Groq", apiModel: "llama-3.3-70b-versatile", tier: "CORE", capabilities: ["text", "code", "fast"], categories: ["Semua", "Teks", "Kode"], summary: "Inferensi LPU ultra-cepat Groq" },
    { id: "openai/gpt-oss-120b", name: "GPT-OSS 120B (Groq)", provider: "groq", providerLabel: "Groq", apiModel: "openai/gpt-oss-120b", tier: "SPARK", capabilities: ["text", "code", "reasoning"], categories: ["Semua", "Teks", "Kode"], summary: "Model 120B berkecepatan tinggi di Groq" }
  ],
  meta: [
    { id: "llama-3.3-70b", name: "Llama 3.3 70B", provider: "meta", providerLabel: "Meta", apiModel: "llama-3.3-70b-versatile", tier: "CORE", capabilities: ["text", "code", "fast"], categories: ["Semua", "Teks", "Kode"], summary: "Efisien & Open · Meta Llama" }
  ],
  openrouter: [
    { id: "qwen-2.5-72b", name: "Qwen 2.5 72B", provider: "openrouter", providerLabel: "Qwen / OpenRouter", apiModel: "qwen/qwen-2.5-72b-instruct", tier: "APEX", capabilities: ["text", "code", "multilingual"], categories: ["Semua", "Teks", "Kode"], summary: "Multibahasa & general purpose via OpenRouter" },
    { id: "openai/gpt-4.1-mini", name: "OpenRouter GPT-4.1 Mini", provider: "openrouter", providerLabel: "OpenRouter", apiModel: "openai/gpt-4.1-mini", tier: "PULSE", capabilities: ["text", "code"], categories: ["Semua", "Teks", "Kode"], summary: "Model OpenRouter cepat" }
  ],
  custom: []
});

const customModels = new Map();

/**
 * Returns models strictly bound to a single provider (never mixing models from other providers).
 */
export function getProviderModels(providerId, { discoveredModels = [], includeCustom = true } = {}) {
  const cleanProvider = String(providerId || "").trim().toLowerCase();
  const baseList = PROVIDER_MODEL_CATALOG[cleanProvider] || [];
  const customForProvider = includeCustom
    ? [...customModels.values()].filter((m) => m.provider === cleanProvider)
    : [];

  const merged = new Map();
  for (const m of baseList) {
    merged.set(m.id, { ...m, provider: cleanProvider, available: true });
  }
  for (const m of customForProvider) {
    merged.set(m.id, { ...m, provider: cleanProvider, available: true });
  }
  if (Array.isArray(discoveredModels)) {
    for (const rawModel of discoveredModels) {
      const modelId = typeof rawModel === "string"
        ? rawModel.replace(/^models\//, "").trim()
        : String(rawModel?.id || rawModel?.name || "").replace(/^models\//, "").trim();
      if (!modelId) continue;
      if (!merged.has(modelId)) {
        merged.set(modelId, {
          id: modelId,
          name: typeof rawModel === "object" && rawModel?.displayName ? rawModel.displayName : modelId,
          provider: cleanProvider,
          providerLabel: cleanProvider.toUpperCase(),
          apiModel: modelId,
          tier: "SPARK",
          summary: `Discovered live from ${cleanProvider.toUpperCase()} API`,
          categories: ["Semua", "Teks", "Kode"],
          capabilities: ["text", "code"],
          discoveredLive: true,
          available: true
        });
      }
    }
  }
  return [...merged.values()];
}

export function listModelCatalog({
  category = "Semua",
  capability = "",
  provider = "",
  availableProviders = null,
  includeExtendedCatalog = false
} = {}) {
  const cleanProvider = String(provider || "").trim().toLowerCase();
  let all = [...BUILTIN_MODELS, ...customModels.values()];
  if (cleanProvider) {
    const providerScoped = getProviderModels(cleanProvider);
    const builtinForProvider = all.filter((m) => m.provider === cleanProvider);
    const byId = new Map();
    for (const item of [...builtinForProvider, ...providerScoped]) {
      byId.set(item.id, item);
    }
    all = [...byId.values()];
  } else if (includeExtendedCatalog) {
    const byId = new Map();
    for (const item of [
      ...BUILTIN_MODELS,
      ...Object.values(PROVIDER_MODEL_CATALOG).flat(),
      ...customModels.values()
    ]) {
      if (!byId.has(item.id)) {
        byId.set(item.id, item);
      }
    }
    all = [...byId.values()];
  }

  const activeProviderSet = Array.isArray(availableProviders)
    ? new Set(availableProviders.map((p) => String(p || "").toLowerCase()))
    : null;

  return all
    .map((m) => {
      const normalizedProv = m.provider === "meta" ? "groq" : m.provider;
      const isAvailable = activeProviderSet
        ? activeProviderSet.has(m.provider) || activeProviderSet.has(normalizedProv)
        : true;
      return {
        ...m,
        available: isAvailable,
        activeStatus: isAvailable ? "available" : "provider_not_connected"
      };
    })
    .filter((m) => {
      if (category && category !== "Semua" && !m.categories?.includes(category)) return false;
      if (capability && !m.capabilities?.includes(capability)) return false;
      return true;
    });
}

export function registerCustomModel(input = {}) {
  const id = boundedString(input.id || "", 80).toLowerCase().replace(/[^a-z0-9._/-]/g, "-");
  const name = boundedString(input.name || "", 100);
  const provider = boundedString(input.provider || "openrouter", 40).toLowerCase();
  const apiModel = boundedString(input.apiModel || id, 120);
  if (!id || !name) {
    throw new Error("Custom model requires valid id and name");
  }
  const tier = JARVIS_TIERS[String(input.tier || "SPARK").toUpperCase()]
    ? String(input.tier || "SPARK").toUpperCase()
    : "SPARK";
  const capabilities = Array.isArray(input.capabilities) && input.capabilities.length
    ? input.capabilities.map((c) => boundedString(c, 32))
    : ["text"];
  const categories = ["Semua", "Teks"];
  if (capabilities.includes("vision")) categories.push("Vision");
  if (capabilities.includes("code")) categories.push("Kode");
  const record = {
    id,
    name,
    provider,
    providerLabel: boundedString(input.providerLabel || provider.toUpperCase(), 60),
    apiModel,
    tier,
    summary: boundedString(input.summary || "Custom model configuration", 160),
    categories: [...new Set(categories)],
    capabilities: [...new Set(capabilities)],
    contextWindow: Math.max(2048, Number(input.contextWindow) || 32768),
    isCustom: true
  };
  customModels.set(id, record);
  return record;
}

function inferProviderFromModelId(modelId = "") {
  const lower = String(modelId || "").trim().toLowerCase();
  if (lower.startsWith("gemini-")) return "gemini";
  if (lower.startsWith("claude-")) return "anthropic";
  if (lower.startsWith("deepseek-")) return "deepseek";
  if (lower.startsWith("llama-") || lower.includes("versatile") || lower.includes("gpt-oss")) return "groq";
  if (lower.startsWith("qwen") || lower.includes("/")) return "openrouter";
  if (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3")) return "openai";
  return "";
}

export function resolveModelOrTier(selection = "APEX", availableProviders = []) {
  const raw = String(selection || "APEX").trim();
  const upper = raw.toUpperCase();
  const normalizedAvailable = Array.isArray(availableProviders)
    ? availableProviders.map((p) => String(p || "").toLowerCase())
    : [];
  const hasProviderCheck = normalizedAvailable.length > 0;

  const isProviderConnected = (prov) => {
    const clean = String(prov || "").toLowerCase();
    const mapped = clean === "meta" ? "groq" : clean;
    if (!hasProviderCheck) return true;
    return (
      normalizedAvailable.includes(clean) ||
      normalizedAvailable.includes(mapped) ||
      (mapped === "groq" && normalizedAvailable.includes("meta"))
    );
  };

  const tierObj = JARVIS_TIERS[upper];
  if (tierObj) {
    const candidates = tierObj.routingOrder.filter((item) => isProviderConnected(item.provider));
    const chosen = candidates[0] || tierObj.routingOrder[0];
    const isAvailable = !hasProviderCheck || candidates.length > 0;
    const modelSubstituted = chosen.modelId !== tierObj.defaultModelId;
    return {
      tier: tierObj.id,
      tierCode: tierObj.code,
      requestedSelection: raw,
      requestedModelId: tierObj.defaultModelId,
      modelId: chosen.modelId,
      provider: chosen.provider === "meta" ? "groq" : chosen.provider,
      apiModel: chosen.apiModel,
      available: isAvailable,
      activeStatus: isAvailable ? "available" : "provider_not_connected",
      modelSubstituted,
      capabilities: tierObj.capabilities,
      fallbackChain: tierObj.routingOrder
    };
  }

  const all = listModelCatalog({ includeExtendedCatalog: true });
  const found = all.find(
    (m) => m.id.toLowerCase() === raw.toLowerCase() || m.name.toLowerCase() === raw.toLowerCase()
  );
  if (found) {
    const mappedProvider = found.provider === "meta" ? "groq" : found.provider;
    const tierRef = JARVIS_TIERS[found.tier] || JARVIS_TIERS.SPARK;
    const isProviderAvailable = isProviderConnected(found.provider);
    return {
      tier: tierRef.id,
      tierCode: tierRef.code,
      requestedSelection: raw,
      requestedModelId: found.id,
      modelId: found.id,
      provider: mappedProvider,
      apiModel: found.apiModel,
      available: isProviderAvailable,
      activeStatus: isProviderAvailable ? "available" : "provider_not_connected",
      modelSubstituted: false,
      capabilities: found.capabilities,
      fallbackChain: [
        { provider: mappedProvider, modelId: found.id, apiModel: found.apiModel },
        ...tierRef.routingOrder.filter((r) => r.modelId !== found.id)
      ]
    };
  }

  const inferredProvider = inferProviderFromModelId(raw);
  if (inferredProvider) {
    const tierRef = JARVIS_TIERS.SPARK;
    const isProviderAvailable = isProviderConnected(inferredProvider);
    return {
      tier: tierRef.id,
      tierCode: tierRef.code,
      requestedSelection: raw,
      requestedModelId: raw,
      modelId: raw,
      provider: inferredProvider,
      apiModel: raw,
      available: isProviderAvailable,
      activeStatus: isProviderAvailable ? "available" : "provider_not_connected",
      modelSubstituted: false,
      capabilities: ["text", "code"],
      fallbackChain: [
        { provider: inferredProvider, modelId: raw, apiModel: raw },
        ...tierRef.routingOrder
      ]
    };
  }

  const fallback = resolveModelOrTier("APEX", availableProviders);
  return {
    ...fallback,
    requestedSelection: raw,
    requestedModelId: raw,
    modelSubstituted: true
  };
}