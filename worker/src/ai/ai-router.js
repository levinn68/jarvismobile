export function chooseProviders({ level = "simple", available = [] } = {}) {
  const preferred = level === "simple"
    ? ["groq", "gemini"]
    : level === "medium"
      ? ["gemini", "deepseek", "groq"]
      : ["gemini", "deepseek", "openai", "groq", "openrouter"];

  // Keep existing provider preferences, but append every additional configured
  // provider so optional adapters (including PeaProxy) remain eligible for fallback.
  const ordered = [
    ...preferred.filter((name) => available.includes(name)),
    ...available.filter((name) => !preferred.includes(name)),
  ];
  return ordered;
}

export function classifyTask(prompt = "") {
  const q = String(prompt).toLowerCase(), words = q.trim().split(/\\s+/).filter(Boolean).length;
  if (/(arsitektur|debug|riset mendalam|analisis mendalam|source code|multi.?step|comprehensive)/i.test(q) || words > 75) return "complex";
  if (/(jelaskan|bandingkan|analisis|cari|carikan|rekomendasi|bagaimana|kenapa|harga)/i.test(q) || words > 22) return "medium";
  return "simple";
}
