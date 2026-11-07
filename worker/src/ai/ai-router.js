export function chooseProviders({level="simple",available=[]}={}) {
 const preferred=level==="simple"?["groq","gemini"]:level==="medium"?["gemini","deepseek","groq"]:["gemini","deepseek","openai","groq","openrouter"];
 const chosen=preferred.filter(name=>available.includes(name));
 return chosen.length?chosen:available.slice(0,1);
}
export function classifyTask(prompt="") {
 const q=String(prompt).toLowerCase(),words=q.trim().split(/\s+/).filter(Boolean).length;
 if(/(arsitektur|debug|riset mendalam|analisis mendalam|source code|multi.?step|comprehensive)/i.test(q)||words>75)return "complex";
 if(/(jelaskan|bandingkan|analisis|cari|carikan|rekomendasi|bagaimana|kenapa|harga)/i.test(q)||words>22)return "medium";
 return "simple";
}
