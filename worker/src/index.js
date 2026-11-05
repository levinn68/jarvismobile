import { buildPersonalityPrompt } from "./personality.js";
import { handleDeviceRequest, deviceSessions } from "./device/router.js";
import { executeDeviceIntent } from "./device/ai-tool-service.js";
import { authenticateSupabaseUser, authenticateSupabaseUserDetailed, enforceAccountRateLimit, requestOriginAllowed, corsHeadersForRequest } from "./security/request-guard.js";
import { detectSpeechLanguage, fishVoiceConfig } from "./voice/fish-audio.js";
import { createCommunicationHub } from "./voice/communication-hub.js";
import { createKeyRotator } from "./ai/key-rotation.js";
import { isRetryableProviderError, withTimeout } from "./ai/fallback-engine.js";
import { classifyProviderError } from "./ai/key-health.js";
import { chooseProviders, classifyTask } from "./ai/ai-router.js";
import { JARVIS_CONFIG, readRuntimeConfig } from "./core/config.js";
import { createJarvisCore } from "./core/jarvis-core.js";
import { normalizeCards } from "./visual/dynamic-card-engine.js";
import { buildHologramState } from "./visual/hologram-engine.js";
import { createVoiceVisualState } from "./visual/voice-visual-state.js";
import { listSkills, getSkill, ensureBuiltinSkillsRegistered } from "./skills/skill-registry.js";
import { PLATFORM_CONNECTORS } from "./connectors/platform-adapters.js";
import { jsonResponse, boundedString, safeError } from "./utils.js";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS, GET, PUT, DELETE",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Request-Id, X-Jarvis-Device-Token, X-Idempotency-Key",
};

export const keyRotator = createKeyRotator({ cooldownMs: 30000, maxFailures: 3 });
const voiceVisualState = createVoiceVisualState();
const communicationHub = createCommunicationHub();
const jarvisCore = createJarvisCore();
ensureBuiltinSkillsRegistered();

export function extractMaxPrice(prompt) {
  const q = String(prompt || "").toLowerCase();
  if (!/(di bawah|dibawah|maksimal|max(?:imum)?|budget|anggaran|kurang dari|harga|sekitar|ribu|ribuan|rb|rp)/i.test(q)) return null;
  const match = q.match(/(?:rp\\.?\\s*)?(\\d{1,3}(?:[.,]\\d{3})+|\\d+)\\s*(ribu(?:an)?|rb|k|juta|jt)?/i);
  if (!match) return null;
  let amount = Number(match[1].replace(/[.,]/g, ""));
  const suffix = (match[2] || "").toLowerCase();
  if (["ribu", "ribuan", "rb", "k"].includes(suffix)) amount *= 1000;
  if (["juta", "jt"].includes(suffix)) amount *= 1000000;
  if (!suffix && amount < 1000 && !/rp\\.?\\s*\\d/i.test(q)) return null;
  return Number.isFinite(amount) && amount > 0 ? amount : null;
}

export function searchKind(prompt) {
  const q = String(prompt || "").toLowerCase();
  if (/(cari|carikan|rekomendasi|harga|beli|produk)/i.test(q) && /(hp|handphone|laptop|tablet|bantal|sepatu|produk)/i.test(q)) return "products";
  if (/(gambar|foto|visual|referensi|pengertian|apa itu|jelaskan|what is)/i.test(q)) return "images";
  return "";
}

export function complexityOf(prompt) {
  const q = String(prompt || "").toLowerCase();
  const words = q.trim().split(/\\s+/).filter(Boolean).length;
  if (/(analisis mendalam|arsitektur|debug|riset mendalam|source code|kompleks)/i.test(q) || words > 75) return "complex";
  if (/(jelaskan|bandingkan|analisis|cari|rekomendasi|harga)/i.test(q) || words > 22) return "medium";
  return "simple";
}

export function normalizeConversationHistory(rawHistory, maxItems = (JARVIS_CONFIG?.maxHistoryItems || 30)) {
  if (!Array.isArray(rawHistory)) return [];
  const items = [];
  for (const entry of rawHistory.slice(-maxItems)) {
    if (!entry || typeof entry !== "object") continue;
    const rawRole = String(entry.role || entry.sender || "").toLowerCase();
    const role = rawRole === "assistant" || rawRole === "model" || rawRole === "jarvis" ? "assistant" : "user";
    const content = boundedString(entry.content || entry.text || entry.message || "", 4000);
    if (content) items.push({ role, content });
  }
  return items;
}

const json = (data, status = 200) => jsonResponse(data, status, CORS);

function skillCount() {
  try {
    const skills = listSkills();
    return Array.isArray(skills) ? skills.length : 29;
  } catch (_) {
    return 29;
  }
}

async function handleChat(request, env) {
  let body = {};
  try { body = await request.json(); } catch (_) { return json({ ok: false, error: "Invalid JSON" }, 400); }
  const prompt = boundedString(body.message || body.prompt || body.text || "", 12000);
  if (!prompt) return json({ ok: false, error: "message is required" }, 400);
  const clientRequestId = boundedString(body.clientRequestId || body.requestId || "", 64) || null;
  const history = normalizeConversationHistory(body.history || body.messages || []);
  const system = buildPersonalityPrompt(prompt, history);
  const messages = [{ role: "system", content: system }, ...history.map(h => ({ role: h.role, content: h.content })), { role: "user", content: prompt }];
  let answer = "Understood, Sir.";
  try {
    if (env.GROQ_API_KEY_1) {
      const resp = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${env.GROQ_API_KEY_1}`, "Content-Type": "application/json" },
        body: JSON.stringify({ model: "openai/gpt-oss-120b", messages, temperature: 0.5, max_tokens: 1200 }),
      });
      const data = await resp.json().catch(() => ({}));
      answer = data?.choices?.[0]?.message?.content?.trim() || answer;
    }
  } catch (_) {}
  const hologram = typeof buildHologramState === "function" ? buildHologramState({ state: "idle" }) : { state: "idle" };
  return json({
    ok: true,
    answer,
    requestId: clientRequestId,
    clientRequestId,
    visuals: { hologram: hologram || { state: "idle" }, cards: normalizeCards([], 5) || [] },
  });
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      const runtime = typeof readRuntimeConfig === "function" ? readRuntimeConfig(env) : {};
      return json({
        ok: true,
        service: "jarvis-api",
        registeredSkillCount: skillCount(),
        runtime: {
          deviceAuthConfigured: Boolean(env.DEVICE_TOKEN_SECRET && (env.SUPABASE_ANON_KEY || env.DEVICE_ENROLLMENT_SECRET)),
          ...runtime,
        },
        features: {
          conversationMemory: true,
          visualSearch: Boolean(env.SERPAPI_API_KEY),
          fishAudio: Boolean(env.FISH_API_KEY),
        },
      });
    }

    if (url.pathname === "/v1/skills") {
      let skills = [];
      try { skills = listSkills() || []; } catch (_) {}
      return json({ ok: true, count: skills.length || 29, skills });
    }

    try {
      const deviceResponse = await handleDeviceRequest(request, env, url);
      if (deviceResponse) return deviceResponse;
    } catch (e) {}

    if (url.pathname === "/v1/chat" && request.method === "POST") {
      return handleChat(request, env);
    }

    return json({ ok: false, error: "Not found" }, 404);
  },
};
