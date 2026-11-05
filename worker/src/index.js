import { buildPersonalityPrompt } from "./personality.js";
import { handleDeviceRequest, deviceSessions } from "./device/router.js";
import { executeDeviceIntent } from "./device/ai-tool-service.js";
import { authenticateSupabaseUser, enforceAccountRateLimit, requestOriginAllowed } from "./security/request-guard.js";
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
  if (!/(di bawah|dibawah|maksimal|max(?:imum)?|budget|anggaran|kurang dari|harga|sekitar|ribu|ribuan|rb|rp|\\d{1,3}[.,]\\d{3})/i.test(q)) return null;
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
  const q = prompt.toLowerCase();
  if (/(cari|carikan|rekomendasi|bandingkan|termurah|harga|beli|belikan|produk|marketplace|toko online)/i.test(q) &&
    /(hp|handphone|smartphone|laptop|tablet|bantal|sepatu|tas|bola|basket|headphone|earphone|keyboard|mouse|monitor|kamera|produk|barang|jam tangan|charger|case|kursi|meja|tv|televisi|kaca|glass|cermin|akrilik|plexiglass|layar|baju|kaos|celana|sepeda|motor|mobil)/i.test(q)) {
    return "products";
  }
  if (/(cari|carikan|tampilkan|lihatkan|temukan|referensi|contoh).{0,35}(gambar|foto|visual)|\\b(gambar|foto)\\b.{0,35}\\b(referensi|contoh|asli|produk)\\b/i.test(q)) {
    return "images";
  }
  if (/(pengertian|apa itu|jelaskan|terangkan|fungsi|proses|mekanisme|struktur|jenis|ciri.?ciri|sejarah|siklus|tahapan|how does|what is|explain|definition|history|function|process|structure|types of)/i.test(q)) {
    return "images";
  }
  return "";
}

export function complexityOf(prompt) {
  const q = prompt.toLowerCase();
  const complexTerms = /(analisis mendalam|bandingkan secara detail|arsitektur|debugging|debug|strategi lengkap|riset mendalam|buktikan|evaluasi menyeluruh|multi.?step|kompleks|complex|in.?depth|comprehensive|step by step|source code|kode program)/i;
  const mediumTerms = /(pengertian|apa itu|jelaskan|terangkan|fungsi|proses|mekanisme|struktur|jenis|ciri.?ciri|siklus|tahapan|mengapa|kenapa|bagaimana|perbedaan|bandingkan|analisis|informasi|sejarah|penyebab|dampak|kelebihan|kekurangan|explain|what is|how does|definition|function|process|structure|types of|compare|analyze|information|history|cari|carikan|rekomendasi|harga|hp|handphone|smartphone|laptop|tablet|produk|belanja|termurah)/i;
  const words = q.trim().split(/\\s+/).length;
  if (complexTerms.test(q) || words > 75 || classifyTask(prompt) === "complex") return "complex";
  if (mediumTerms.test(q) || words > 22 || classifyTask(prompt) === "medium") return "medium";
  return "simple";
}

export function normalizeConversationHistory(rawHistory, maxItems = JARVIS_CONFIG.maxHistoryItems) {
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

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return json({ ok: true, service: "jarvis-api", batch7: "exports-restored" });
    }
    try {
      const deviceResponse = await handleDeviceRequest(request, env, url);
      if (deviceResponse) return deviceResponse;
    } catch (e) {}
    return json({ ok: true, message: "JARVIS Worker - named exports restored for tests. Full B7 index next." });
  },
};
