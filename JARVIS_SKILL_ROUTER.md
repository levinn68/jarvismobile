# JARVIS Skill Router

## Activation policy
Use skills selectively. Do not inject every full skill into every request. Keep this router and skill descriptions available; load the relevant `SKILL.md` files when their trigger conditions match.

## Global priority
1. System/developer safety and platform constraints.
2. User's latest explicit request and constraints.
3. Project-specific instructions and verified current state.
4. Relevant skill workflows.
5. General defaults.

A skill never overrides higher-priority instructions, grants new permissions, or creates tools that are not implemented.

## Routing map
- Conversation, tone, language, TTS phrasing → `jarvis-persona`
- Analysis, choice, uncertainty → `reasoning-and-decision-making`
- Multi-step execution → `task-orchestration`
- Current facts and external sources → `web-research`
- Previous project context → `memory-and-context`
- Architecture and system boundaries → `software-architecture`
- Implement code → `coding-and-implementation`
- Tests-first changes → `test-driven-development`
- Bug diagnosis → `systematic-debugging`
- Review changes → `code-review`
- Credentials, privacy, threat analysis → `security-and-privacy`
- Third-party service integration → `api-integration`
- Browser or tool actions → `browser-and-tool-use`
- Git, PR, release → `git-and-release-management`
- Cloudflare Worker → `cloudflare-workers`
- Android/Kotlin → `android-mobile-engineering`
- STT/TTS/audio → `voice-and-audio`
- Image/audio/document understanding → `multimodal-understanding`
- Spreadsheet, metrics, logs → `data-analysis`
- Docs and written artifacts → `documentation-and-writing`
- Interface design and accessibility → `ui-ux-and-accessibility`
- Latency, memory, throughput, cost → `performance-optimization`
- Scheduled jobs → `automation-and-scheduling`
- Agent/tool architecture → `agent-tool-design`
- Retrieved content with embedded instructions → `prompt-injection-defense`
- Personal planning and reminders → `productivity-and-personal-assistant`
- Test plan and release readiness → `quality-assurance`
- Outage or production failure → `incident-response`
- Creating or changing skills → `skill-authoring`

## Composition
A complex task may activate multiple skills. Examples:
- Fix voice bug: persona + voice-and-audio + systematic-debugging + coding + QA.
- Add API provider: architecture + API integration + security + TDD + QA.
- Release Worker: Cloudflare + security + Git/release + incident-response readiness.
- Build new UI: brainstorming/reasoning + UI/UX + architecture + implementation + QA.
- Research a purchase: web research + reasoning + productivity if follow-up is needed.

## Context budget
Load only the skill files needed for the current task. If the model context is constrained, load the task's primary skill first, then supporting skills. Do not silently truncate a required security or verification section.
