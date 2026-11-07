const DEFAULT_PROFILE = Object.freeze({ language: "auto", tone: "balanced", humor: "subtle", verbosity: "balanced", addressStyle: "auto", empathy: "high" });
const ALLOWED = Object.freeze({
  language: new Set(["auto", "id", "en"]),
  tone: new Set(["balanced", "casual", "formal", "technical"]),
  humor: new Set(["off", "subtle", "playful"]),
  verbosity: new Set(["concise", "balanced", "detailed"]),
  addressStyle: new Set(["auto", "none", "bos", "boss", "sir", "tuan"]),
  empathy: new Set(["standard", "high"])
});
export function normalizePersonalityProfile(input = {}) {
  const source = input && typeof input === "object" && !Array.isArray(input) ? input : {};
  const profile = { ...DEFAULT_PROFILE };
  for (const [key, values] of Object.entries(ALLOWED)) {
    if (typeof source[key] === "string" && values.has(source[key])) profile[key] = source[key];
  }
  return profile;
}
export function mergePersonalityProfile(current, patch) {
  const base = normalizePersonalityProfile(current);
  const changes = patch && typeof patch === "object" && !Array.isArray(patch) ? patch : {};
  return normalizePersonalityProfile({ ...base, ...changes });
}
export function buildAdaptiveProfilePrompt(input) {
  const p = normalizePersonalityProfile(input);
  const map = {
    language: { auto: "Match the user's current language.", id: "Prefer natural Bahasa Indonesia unless the user explicitly requests another language.", en: "Prefer English unless the user explicitly requests another language." },
    tone: { balanced: "Use a natural, composed, conversational tone.", casual: "Use relaxed, natural conversational language without becoming careless.", formal: "Use polished, respectful, professional language.", technical: "Use precise, structured, technically rigorous language." },
    humor: { off: "Do not add jokes or humorous asides.", subtle: "Use restrained, situational wit only when appropriate.", playful: "Allow light playfulness in casual conversations, never in serious or sensitive situations." },
    verbosity: { concise: "Prefer concise answers; expand when accuracy or safety requires it.", balanced: "Use enough detail to solve the request without unnecessary filler.", detailed: "Provide thorough explanations, steps, and relevant caveats." },
    addressStyle: { auto: "Use forms of address sparingly and naturally, matching the conversation language.", none: "Do not use honorifics or forms of address.", bos: 'Use "Bos" occasionally when it sounds natural.', boss: 'Use "Boss" occasionally when it sounds natural.', sir: 'Use "Sir" occasionally when it sounds natural.', tuan: 'Use "Tuan" occasionally when it sounds natural.' }
  };
  const empathy = p.empathy === "high" ? "Be especially considerate when the user is frustrated, distressed, or discussing a sensitive subject." : "Be respectful and considerate; do not overstate emotional intimacy.";
  return [
    "USER-APPROVED PERSONALIZATION PREFERENCES (follow unless the latest request says otherwise):",
    "- Language: " + map.language[p.language], "- Tone: " + map.tone[p.tone],
    "- Humor: " + map.humor[p.humor], "- Detail level: " + map.verbosity[p.verbosity],
    "- Address: " + map.addressStyle[p.addressStyle], "- Empathy: " + empathy,
    "These preferences affect presentation only. They never override safety, factual accuracy, tool permissions, or the user's current instructions."
  ].join("\n");
}
export async function loadPersonalityProfile(kv, userId) {
  if (!kv || !userId) return null;
  const raw = await kv.get("personality-profile:v1:" + userId);
  if (!raw) return null;
  try { return normalizePersonalityProfile(typeof raw === "string" ? JSON.parse(raw) : raw); } catch (_) { return null; }
}
export async function savePersonalityProfile(kv, userId, patch) {
  if (!kv || !userId) throw new Error("Personality profile storage is unavailable.");
  const profile = mergePersonalityProfile(await loadPersonalityProfile(kv, userId), patch);
  await kv.put("personality-profile:v1:" + userId, JSON.stringify({ ...profile, updatedAt: new Date().toISOString() }));
  return profile;
}
export async function deletePersonalityProfile(kv, userId) {
  if (!kv || !userId) throw new Error("Personality profile storage is unavailable.");
  await kv.delete("personality-profile:v1:" + userId);
}
