export const JARVIS_CONFIG = Object.freeze({
  serviceName: "JARVIS API",
  protocolVersion: "1.0.0",
  maxPromptLength: 12000,
  maxHistoryItems: 30,
  deviceCommandTtlSeconds: 300,
  supportedPlatforms: ["android", "windows", "macos", "linux", "ios", "ipados", "wearos", "web", "iot"],
});

export function readRuntimeConfig(env = {}) {
  return {
    serviceName: JARVIS_CONFIG.serviceName,
    protocolVersion: JARVIS_CONFIG.protocolVersion,
    aiProviders: ["gemini", "deepseek", "groq", "openai", "openrouter"],
    fishAudioConfigured: Boolean(env.FISH_API_KEY),
    serpApiConfigured: Boolean(env.SERPAPI_API_KEY),
    deviceRegistryConfigured: Boolean(env.DEVICE_REGISTRY),
    deviceCommandsConfigured: Boolean(env.DEVICE_COMMANDS),
    deviceAuthConfigured: Boolean(env.DEVICE_TOKEN_SECRET && (env.SUPABASE_ANON_KEY || env.DEVICE_ENROLLMENT_SECRET)),
    legacyBootstrapConfigured: Boolean(env.DEVICE_TOKEN_SECRET && env.DEVICE_ENROLLMENT_SECRET)
  };
}