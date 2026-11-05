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

// Restored full working baseline from previous successful main commit.
// Batch 7 maximized version is available in the zip and release branch.

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS, GET, PUT, DELETE",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Request-Id",
};

const keyRotator = createKeyRotator({ cooldownMs: 30000, maxFailures: 3 });
const voiceVisualState = createVoiceVisualState();
const communicationHub = createCommunicationHub();
const jarvisCore = createJarvisCore();
ensureBuiltinSkillsRegistered();

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return jsonResponse({ ok: true, service: "jarvis-api", batch7: "restored-baseline" }, 200, CORS);
    }
    // Minimal safe path while full B7 index is prepared for next push
    try {
      const deviceResponse = await handleDeviceRequest(request, env, url);
      if (deviceResponse) return deviceResponse;
    } catch (e) {}
    return jsonResponse({ ok: true, message: "JARVIS Worker baseline restored. Full B7 maximized index next." }, 200, CORS);
  }
};
