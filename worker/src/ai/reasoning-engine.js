import { chooseProviders, classifyTask } from "./ai-router.js";
import { runWithFallback } from "./fallback-engine.js";

export async function reason({
  prompt,
  availableProviders,
  invoke,
  review,
  synthesize,
  isCircuitOpen
}) {
  const level = classifyTask(prompt);
  const providers = chooseProviders({ level, available: availableProviders });
  const primary = await runWithFallback({ providers, invoke, isCircuitOpen });
  if (!primary.ok) return { ok: false, level, errors: primary.errors };
  let answer = primary.result;
  if (level !== "simple" && typeof synthesize === "function") {
    try {
      answer = await synthesize({
        prompt,
        provider: primary.provider,
        result: primary.result,
        review
      });
    } catch (_) {}
  }
  return { ok: true, level, provider: primary.provider, answer };
}
