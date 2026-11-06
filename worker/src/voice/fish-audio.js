export function detectSpeechLanguage(text) {
 const q=String(text||"").toLowerCase();
 const id=(q.match(/\b(aku|saya|kamu|anda|yang|dan|dengan|untuk|tidak|bisa|buat|sekarang|besok|tolong|berapa|bagaimana)\b/g)||[]).length;
 const en=(q.match(/\b(the|and|you|your|what|how|why|please|with|from|about|create|build|make|explain|find|search)\b/g)||[]).length;
 return en>id?"en":"id";
}
export function fishVoiceConfig(env,text) {
 const language=detectSpeechLanguage(text);
 return {language,voiceId:language==="en"?(env.FISH_VOICE_ID_EN||env.FISH_VOICE_ID||"c7b2977d49894310866a70e6412afe10"):(env.FISH_VOICE_ID_ID||env.FISH_VOICE_ID||"c7b2977d49894310866a70e6412afe10"),model:env.FISH_MODEL||"s2.1-pro-free",configured:Boolean(env.FISH_API_KEY)};
}
