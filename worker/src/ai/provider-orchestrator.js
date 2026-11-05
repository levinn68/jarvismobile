import { runWithFallback } from "./fallback-engine.js";
import { chooseProviders } from "./ai-router.js";

const PROVIDERS = {
  gemini: {
    keys: ["GEMINI_API_KEY_1"],
    endpoint: (env) => `https://generativelanguage.googleapis.com/v1beta/models/${env.GEMINI_MODEL || "gemini-2.5-flash"}:generateContent`,
  },
  deepseek: {
    keys: ["DEEPSEEK_API_KEY_1"],
    endpoint: () => "https://api.deepseek.com/chat/completions",
    model: (env) => env.DEEPSEEK_MODEL || "deepseek-chat",
  },
  groq: {
    keys: ["GROQ_API_KEY_1"],
    endpoint: () => "https://api.groq.com/openai/v1/chat/completions",
    model: (env) => env.GROQ_MODEL || "openai/gpt-oss-120b",
  },
  openai: {
    keys: ["OPENAI_API_KEY_1"],
    endpoint: () => "https://api.openai.com/v1/chat/completions",
    model: (env) => env.OPENAI_MODEL || "gpt-4.1-mini",
  },
  openrouter: {
    keys: ["OPENROUTER_API_KEY_1"],
    endpoint: () => "https://openrouter.ai/api/v1/chat/completions",
    model: (env) => env.OPENROUTER_MODEL || "openai/gpt-4.1-mini",
  },
};

function availableProviders(env) {
  return Object.entries(PROVIDERS)
    .filter(([, config]) => config.keys.some((key) => typeof env[key] === "string" && env[key].trim()))
    .map(([name]) => name);
}

async function invokeGemini(env, messages) {
  const key = env.GEMINI_API_KEY_1;
  const system = messages.find((message) => message.role === "system")?.content || "";
  const contents = messages
    .filter((message) => message.role !== "system")
    .map((message) => ({
      role: message.role === "assistant" ? "model" : "user",
      parts: [{ text: String(message.content || "") }],
    }));
  const response = await fetch(`${PROVIDERS.gemini.endpoint(env)}?key=${encodeURIComponent(key)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents,
      generationConfig: { temperature: 0.55, maxOutputTokens: 8192 },
    }),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data?.error?.message || `Gemini HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  const answer = data?.candidates?.[0]?.content?.parts?.map((part) => part.text || "").join("").trim();
  if (!answer) throw new Error("Gemini returned an empty response");
  return answer;
}

async function invokeCompatible(name, env, messages) {
  const config = PROVIDERS[name];
  const key = env[config.keys[0]];
  const response = await fetch(config.endpoint(env), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
      ...(name === "openrouter" ? { "HTTP-Referer": "https://jarvismobile.app", "X-Title": "J.A.R.V.I.S. Mobile" } : {}),
    },
    body: JSON.stringify({
      model: config.model(env),
      messages,
      temperature: 0.55,
      max_tokens: 8192,
    }),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data?.error?.message || `${name} HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  const answer = data?.choices?.[0]?.message?.content?.trim();
  if (!answer) throw new Error(`${name} returned an empty response`);
  return answer;
}

export async function runJarvisProviders({ env, messages, level = "simple", onAttempt } = {}) {
  const available = availableProviders(env || {});
  if (!available.length) {
    return { ok: false, error: "No AI provider is configured.", attempts: [], available: [] };
  }
  const ordered = chooseProviders({ level, available });
  const result = await runWithFallback({
    providers: ordered,
    invoke: (name) => name === "gemini"
      ? invokeGemini(env, messages)
      : invokeCompatible(name, env, messages),
    timeoutMs: 45000,
    maxAttemptsPerProvider: 2,
    baseBackoffMs: 500,
    onAttempt,
  });
  return {
    ...result,
    available,
    order: ordered,
  };
}

export function listConfiguredJarvisProviders(env = {}) {
  return availableProviders(env);
}
