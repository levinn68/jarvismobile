# J.A.R.V.I.S. Experience v2 — Implementation Specification

## Status
This document is the acceptance contract for the Chat, Voice, wake-word, personality, visual composition, and AI-provider work. It does not claim that a feature is implemented merely because it is specified here.

## Product invariants
- Android target: ASUS ROG Phone 6, Android 14, no root.
- Two primary modes only: Chat and Voice.
- Indonesian and English are first-class languages.
- The backend remains the source of truth for answer content, voice summary, personality, visual data, and supported actions.
- The Android client must not impose an arbitrary character limit on rendered assistant replies. Network/body safety limits may exist at the server boundary, but must be explicit, generous, and must not silently truncate a valid answer.
- Preserve existing authentication, conversation history, device pairing, and account sync behavior.

## Chat Mode
- Render the complete backend reply with Markdown/structured content.
- Every assistant turn must include a contextually relevant visual. Prefer actual image results when available; otherwise render a meaningful typed visual (diagram, chart, structured card, or visual summary) derived from the answer. Never use unrelated or random stock imagery as a fallback.
- Compose text and visuals together using content hierarchy; do not append a generic image strip to every answer.
- Cards are data-driven. Their type, order, and layout depend on the response.
- Keep text and voice input available and preserve continuous conversation history.
- Do not truncate the reply in the UI. Long answers remain scrollable and readable.
- Keep conversation history accessible and restorable.

## Voice Mode
- Keep the holographic orb as the main visual identity.
- When answer visuals are presented, animate the orb to a compact top position; animate the visual/content region into the newly available space.
- Use a short backend-provided voice_summary for TTS while showing the relevant visual cards and answer content.
- Do not independently rewrite the voice summary in the Android client.
- Provide visible states: STANDBY, ACTIVATING, ACTIVE/LISTENING, PROCESSING, SPEAKING, ERROR.
- Voice session activation must require the exact configured wake phrase: Jarvis, open.
- An open microphone or Voice screen alone must not authorize command processing. Before activation, only local wake-word detection may run. Ignore non-wake speech without sending it to the chat backend.
- Once activated, show a persistent ACTIVE indicator. The user may converse naturally within the active session; a wake phrase is not required for every follow-up utterance. Explicit commands may still begin with Jarvis, ...
- On session end, return to STANDBY and require the wake phrase again.

## Personality
- worker/src/personality.js remains the canonical personality source.
- Apply the same personality to all providers and to both text and speech.
- Preserve the user's language and conversational context.
- Avoid canned acknowledgements on every turn. Never claim an action or capability that did not occur.
- The voice summary must preserve the meaning and important result of the full reply.

## Response contract
Maintain backward compatibility with current Android fields while adding:
- reply: complete display response (same semantic content as legacy answer).
- voice_summary: concise spoken version generated/selected by backend.
- visuals: normalized contextual visual payload; preserve existing kind and results fields.
- cards: typed dynamic cards, with no hardcoded frontend character cap.
- provider: selected provider, fallback indicator, and safe attempt diagnostics (never secrets).
- requestId and clientRequestId: preserve request correlation.
A response must not return a successful empty placeholder when every AI provider fails.

## Five-provider orchestration
Configured providers: Gemini, DeepSeek, Groq, OpenAI, and OpenRouter.
- Select provider order by task complexity and configured availability.
- Use sequential fallback, per-provider timeout, bounded retries with exponential backoff for retryable failures, and safe diagnostics.
- Treat 401/403 as configuration/authentication errors, not transient retry candidates.
- Never expose API keys to Android or logs.
- Keep all provider output normalized into one response contract and one personality prompt.

## Motion and accessibility
- New assistant content enters from above with a subtle downward motion and opacity transition.
- Visual cards may use a short stagger, but motion must not delay input or reading.
- Use transform/opacity rather than animating layout dimensions where possible.
- Respect reduced-motion preferences and Android safe areas.
- Maintain accessible contrast, touch targets, and semantic labels.

## Acceptance tests
1. A long backend reply renders completely without client-side truncation.
2. Every successful assistant turn contains a relevant visual payload or a typed visual fallback.
3. Chat and Voice use the same backend personality and conversation context.
4. Voice speaks voice_summary, not a client-generated generic phrase.
5. Voice visuals cause the orb to move to the top smoothly and restore its resting position afterward.
6. Opening Voice mode does not activate command processing.
7. Non-wake speech is ignored before activation; Jarvis, open changes the visible state to ACTIVE.
8. Follow-up conversation works naturally inside the active session.
9. Each of the five providers can be selected when configured; a failed provider falls back to the next eligible provider.
10. If all providers fail, return a truthful error response rather than a fabricated success.
11. Existing sign-in, history, device pairing, and device permissions remain functional.