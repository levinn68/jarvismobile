import test from "node:test";
import assert from "node:assert/strict";
import { normalizePersonalityProfile, mergePersonalityProfile, buildAdaptiveProfilePrompt, loadPersonalityProfile, savePersonalityProfile, deletePersonalityProfile } from "../src/adaptive-personality.js";
function memoryKv() {
  const values = new Map();
  return { values, async get(k) { return values.get(k) ?? null; }, async put(k,v) { values.set(k,v); }, async delete(k) { values.delete(k); } };
}
test("normalization rejects unsupported values and unknown fields", () => {
  assert.deepEqual(normalizePersonalityProfile({ tone:"casual", humor:"unlimited", isAdmin:true }), { language:"auto", tone:"casual", humor:"subtle", verbosity:"balanced", addressStyle:"auto", empathy:"high" });
});
test("updates merge without erasing existing preferences", () => {
  const p=mergePersonalityProfile({tone:"formal",verbosity:"detailed"},{humor:"off"});
  assert.equal(p.tone,"formal"); assert.equal(p.verbosity,"detailed"); assert.equal(p.humor,"off");
});
test("prompt respects chosen style and retains safety boundaries", () => {
  const prompt=buildAdaptiveProfilePrompt({language:"id",humor:"off",verbosity:"concise"});
  assert.match(prompt,/Bahasa Indonesia/); assert.match(prompt,/Do not add jokes/); assert.match(prompt,/never override safety/i);
});
test("profiles persist per user and reset independently", async () => {
  const kv=memoryKv();
  await savePersonalityProfile(kv,"user-a",{tone:"casual"});
  await savePersonalityProfile(kv,"user-b",{tone:"formal"});
  assert.equal((await loadPersonalityProfile(kv,"user-a")).tone,"casual");
  assert.equal((await loadPersonalityProfile(kv,"user-b")).tone,"formal");
  await deletePersonalityProfile(kv,"user-a");
  assert.equal(await loadPersonalityProfile(kv,"user-a"),null);
  assert.equal((await loadPersonalityProfile(kv,"user-b")).tone,"formal");
});
