import { buildPersonalityPrompt } from "./personality.js";
import { buildAdaptiveProfilePrompt, loadPersonalityProfile, savePersonalityProfile, deletePersonalityProfile } from "./adaptive-personality.js";
import { handleDeviceRequest, deviceSessions } from "./device/router.js";
import { executeDeviceIntent } from "./device/ai-tool-service.js";
import { authenticateSupabaseUser, authenticateSupabaseUserDetailed, bearerToken, enforceAccountRateLimit, requestOriginAllowed, corsHeadersForRequest } from "./security/request-guard.js";
import { detectSpeechLanguage, fishVoiceConfig } from "./voice/fish-audio.js";
import { createCommunicationHub } from "./voice/communication-hub.js";
import { createKeyRotator } from "./ai/key-rotation.js";
import { isRetryableProviderError, withTimeout } from "./ai/fallback-engine.js";
import { runJarvisProviders } from "./ai/provider-orchestrator.js";
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

function makeVoiceSummary(answer) {
  const plain = String(answer || "")
    .replace(/```[\\s\\S]*?```/g, " ")
    .replace(/!\\[[^\\]]*\\]\\([^)]*\\)/g, " ")
    .replace(/[#>*_`|]/g, " ")
    .replace(/\\s+/g, " ")
    .trim();
  if (!plain) return "I have prepared the response on screen, Sir.";
  const sentences = plain.match(/[^.!?]+[.!?]+|[^.!?]+$/g) || [plain];
  return sentences.slice(0, 2).join(" ").trim();
}

function buildContextualCards(answer) {
  const source = String(answer || "").trim();
  const headings = [...source.matchAll(/^#{1,3}\\s+(.+)$/gm)].map((match) => match[1].trim());
  const paragraphs = source.split(/\\n\\s*\\n/).map((part) => part.replace(/^#{1,6}\\s+/gm, "").trim()).filter(Boolean);
  const cards = [];
  if (headings.length) {
    cards.push({ id: "answer-structure", type: "text", title: "Struktur jawaban", data: { items: headings.slice(0, 8) }, interactive: false });
  }
  const keyPoint = paragraphs.find((part) => part.length > 40 && !part.startsWith("```"));
  if (keyPoint) {
    cards.push({ id: "key-insight", type: "text", title: "Inti jawaban", data: { text: keyPoint.slice(0, 1200) }, interactive: false });
  }
  if (!cards.length) {
    cards.push({ id: "context-summary", type: "text", title: "Ringkasan visual", data: { text: source.slice(0, 1200) }, interactive: false });
  }
  return normalizeCards(cards, 8);
}


async function handleVoiceProfileUpload(request, env) {
  const auth = await authenticateSupabaseUserDetailed(request, env);
  if (!auth.ok) return json({ ok: false, error: auth.error }, auth.status);
  if (!env.DEVICE_REGISTRY) return json({ ok: false, error: "Voice profile storage is not configured." }, 503);
  let body = {};
  try { body = await request.json(); } catch (_) { return json({ ok: false, error: "Invalid JSON" }, 400); }
  const audioBase64 = String(body.audioBase64 || "");
  if (!audioBase64 || audioBase64.length > 11_200_000) return json({ ok: false, error: "Audio must be WAV and no larger than 8 MB." }, 413);
  let bytes;
  try { bytes = Uint8Array.from(atob(audioBase64), (c) => c.charCodeAt(0)); } catch (_) { return json({ ok: false, error: "Invalid audio encoding." }, 400); }
  if (bytes.length < 32000 || bytes.length > 8 * 1024 * 1024) return json({ ok: false, error: "Reference audio must be 2–30 seconds and no larger than 8 MB." }, 400);
  const isWav = bytes.length > 12 && String.fromCharCode(...bytes.slice(0, 4)) === "RIFF" && String.fromCharCode(...bytes.slice(8, 12)) === "WAVE";
  if (!isWav) return json({ ok: false, error: "Reference audio must be a WAV file." }, 400);
  const transcript = boundedString(body.transcript || "", 1200);
  await env.DEVICE_REGISTRY.put(`voice-profile:v1:${auth.user.id}`, JSON.stringify({
    audioBase64,
    transcript,
    updatedAt: new Date().toISOString(),
    bytes: bytes.length,
  }));
  return json({ ok: true, engine: "VoxCPM2", bytes: bytes.length, transcriptSaved: Boolean(transcript) });
}

async function handleVoiceProfileDelete(request, env) {
  const auth = await authenticateSupabaseUserDetailed(request, env);
  if (!auth.ok) return json({ ok: false, error: auth.error }, auth.status);
  if (!env.DEVICE_REGISTRY) return json({ ok: false, error: "Voice profile storage is not configured." }, 503);
  await env.DEVICE_REGISTRY.delete(`voice-profile:v1:${auth.user.id}`);
  return json({ ok: true });
}

async function handleTts(request, env) {
  const auth = await authenticateSupabaseUserDetailed(request, env);
  if (!auth.ok) return json({ ok: false, error: auth.error }, auth.status);
  let body = {};
  try { body = await request.json(); } catch (_) { return json({ ok: false, error: "Invalid JSON" }, 400); }
  const text = boundedString(body.text || "", 1800);
  if (!text) return json({ ok: false, error: "text is required" }, 400);

  if (!env.FISH_API_KEY) return json({ ok: false, error: "Fish Audio is not configured. Set FISH_API_KEY to enable Jarvis voice." }, 503);

  const { language, voiceId } = fishVoiceConfig(env, text);
  const referenceId = voiceId || "612b878b113047d9a770c069c8b4fdfe";
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 80000);
  try {
    const upstream = await fetch("https://api.fish.audio/v1/tts", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${env.FISH_API_KEY}`,
        "Content-Type": "application/json",
        "model": env.FISH_MODEL || "s2.1-pro-free",
      },
      body: JSON.stringify({ text, reference_id: referenceId, format: "mp3", latency: "balanced" }),
      signal: controller.signal,
    });
    if (!upstream.ok) {
      const detail = (await upstream.text().catch(() => "")).slice(0, 500);
      return json({ ok: false, error: `Fish Audio returned HTTP ${upstream.status}: ${detail}` }, upstream.status >= 500 ? 502 : upstream.status);
    }
    const audio = await upstream.arrayBuffer();
    if (!audio.byteLength) return json({ ok: false, error: "Fish Audio returned empty audio." }, 502);
    return new Response(audio, { status: 200, headers: {
      ...CORS,
      "Content-Type": upstream.headers.get("Content-Type") || "audio/mpeg",
      "Cache-Control": "no-store",
      "X-Jarvis-Voice-Language": language,
      "X-Jarvis-Voice-Profile": referenceId,
    }});
  } catch (error) {
    const message = error?.name === "AbortError" ? "Fish Audio request timed out." : String(error?.message || error).slice(0, 240);
    return json({ ok: false, error: message }, 502);
  } finally {
    clearTimeout(timeout);
  }
}

async function handlePersonalityProfile(request, env) {
  const auth = await authenticateSupabaseUserDetailed(request, env);
  if (!auth.ok) return json({ ok: false, error: auth.error }, auth.status);
  if (!env.DEVICE_REGISTRY) return json({ ok: false, error: "Personality profile storage is not configured." }, 503);
  const key = "personality-profile:v1:" + auth.user.id;
  try {
    if (request.method === "GET") {
      const profile = await loadPersonalityProfile(env.DEVICE_REGISTRY, auth.user.id);
      return json({ ok: true, profile: profile || {
        language: "auto", tone: "balanced", humor: "subtle",
        verbosity: "balanced", addressStyle: "auto", empathy: "high"
      } });
    }
    if (request.method === "PUT") {
      let body = {};
      try { body = await request.json(); } catch (_) { return json({ ok: false, error: "Invalid JSON" }, 400); }
      if (!body || typeof body !== "object" || Array.isArray(body)) return json({ ok: false, error: "Profile must be a JSON object." }, 400);
      const profile = await savePersonalityProfile(env.DEVICE_REGISTRY, auth.user.id, body);
      return json({ ok: true, profile });
    }
    if (request.method === "DELETE") {
      await deletePersonalityProfile(env.DEVICE_REGISTRY, auth.user.id);
      return json({ ok: true, reset: true });
    }
    return json({ ok: false, error: "Method not allowed" }, 405);
  } catch (error) {
    return json({ ok: false, error: String(error?.message || error).slice(0, 200) }, 500);
  }
}

async function handleChat(request, env) {
  let body = {};
  try { body = await request.json(); } catch (_) { return json({ ok: false, error: "Invalid JSON" }, 400); }
  const prompt = boundedString(body.message || body.prompt || body.text || "", 12000);
  if (!prompt) return json({ ok: false, error: "message is required" }, 400);
  const clientRequestId = boundedString(body.clientRequestId || body.requestId || "", 64) || null;
  const history = normalizeConversationHistory(body.history || body.messages || []);
  let profile = null;
  if (bearerToken(request)) {
    const auth = await authenticateSupabaseUserDetailed(request, env);
    if (!auth.ok) return json({ ok: false, error: auth.error }, auth.status);
    if (env.DEVICE_REGISTRY) {
      profile = await loadPersonalityProfile(env.DEVICE_REGISTRY, auth.user.id);
    }
  }
  const profilePrompt = profile ? buildAdaptiveProfilePrompt(profile) : "";
  const system = [buildPersonalityPrompt(prompt, history), profilePrompt].filter(Boolean).join("\n\n");
  const messages = [
    { role: "system", content: system },
    ...history.map((item) => ({ role: item.role, content: item.content })),
    { role: "user", content: prompt },
  ];
  const level = classifyTask(prompt);
  const attempts = [];
  const result = await runJarvisProviders({
    env,
    messages,
    level,
    onAttempt: (event) => {
      attempts.push({
        provider: event.provider,
        status: event.status,
        attempt: event.attempt,
        ...(event.error ? { detail: String(event.error).slice(0, 180) } : {}),
      });
    },
  });
  if (!result.ok) {
    return json({
      ok: false,
      error: "All configured AI providers failed. Please check provider configuration and availability.",
      requestId: clientRequestId,
      clientRequestId,
      provider: {
        selected: null,
        fallbackUsed: attempts.length > 1,
        attempts,
        configured: result.available || [],
      },
    }, 503);
  }

  const answer = String(result.result || "").trim();
  if (!answer) {
    return json({ ok: false, error: "AI provider returned an empty answer.", requestId: clientRequestId, clientRequestId }, 502);
  }
  const cards = buildContextualCards(answer);
  const voiceSummary = makeVoiceSummary(answer);
  const hologram = buildHologramState({ voiceState: "idle", taskActive: false });
  const imageResults = cards
    .filter((card) => card.type === "image" && card.data?.image)
    .map((card) => ({ title: card.title, image: card.data.image, source: "response", link: card.data.image }));
  return json({
    ok: true,
    answer,
    reply: answer,
    voice_summary: voiceSummary,
    requestId: clientRequestId,
    clientRequestId,
    visuals: {
      kind: imageResults.length ? "images" : "context",
      hologram,
      results: imageResults,
      cards,
    },
    cards,
    provider: {
      selected: result.provider,
      fallbackUsed: result.provider !== result.order?.[0],
      attempts,
      configured: result.available,
    },
    conversation: { continue: true, historyItems: history.length },
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

    if (url.pathname === "/v1/personality-profile" && ["GET", "PUT", "DELETE"].includes(request.method)) {
      return handlePersonalityProfile(request, env);
    }

    if (url.pathname === "/v1/chat" && request.method === "POST") {
      return handleChat(request, env);
    }

    if (url.pathname === "/v1/voice-profile" && request.method === "PUT") {
      return handleVoiceProfileUpload(request, env);
    }
    if (url.pathname === "/v1/voice-profile" && request.method === "DELETE") {
      return handleVoiceProfileDelete(request, env);
    }
    if (url.pathname === "/v1/tts" && request.method === "POST") {
      return handleTts(request, env);
    }

    return json({ ok: false, error: "Not found" }, 404);
  },
};
