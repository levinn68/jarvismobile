export const JARVIS_CONFIG = {
  maxHistoryItems: 20,
  maxVisualCards: 5,
  defaultTimeoutMs: 45000,
  rateLimitWindowMs: 60000,
  rateLimitMax: 30,
};

export function readRuntimeConfig(env = {}) {
  return {
    ...JARVIS_CONFIG,
    geminiModel: env.GEMINI_MODEL || "gemini-2.5-flash",
    fishModel: env.FISH_MODEL || "s2.1-pro-free",
    fishVoiceId: env.FISH_VOICE_ID || "",
    supabaseUrl: env.SUPABASE_URL || "",
  };
}
