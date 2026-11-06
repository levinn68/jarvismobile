# JARVIS Skill System — Expanded Pack

This package contains **29 modular skills** plus a routing policy. It is a purpose-built adaptation for JARVIS, informed by public Agent Skills repositories and engineering workflows.

## Included capability areas
- Persona, Indonesian/English language matching, voice-ready conversation
- Reasoning, decision-making, task orchestration, and memory/context hygiene
- Web research, tool/browser use, prompt-injection defense
- Software architecture, implementation, TDD, debugging, code review, QA
- Security/privacy, API integration, Git/release, Cloudflare Workers, Android
- Voice/audio, multimodal understanding, data analysis, documentation
- UI/UX, performance, automation, productivity, incident response
- Skill authoring and maintenance

## Format
Each capability has its own directory:
`skills/<skill-name>/SKILL.md`

Each `SKILL.md` uses YAML frontmatter (`name`, `description`) and task-specific instructions. `JARVIS_SKILL_ROUTER.md` maps requests to the relevant skills.

## Important integration note
This is a skill library, not a backend deployment. The JARVIS backend must:
1. Discover the skill directories.
2. Keep skill names/descriptions available for routing.
3. Load only the relevant `SKILL.md` files for each request.
4. Add their contents to the model context with clear separation from user data.
5. Enforce tool permissions in backend code; Markdown instructions alone do not grant capabilities.

Do not load all 25 full skill files into every prompt. Use selective loading to control context size and reduce instruction collisions.

## Security
- No API keys, tokens, passwords, or `.dev.vars` are included.
- Review any external skill or script before execution.
- Tool outputs and retrieved documents remain untrusted data.
- Require explicit authorization for destructive or external side effects.
- A skill cannot bypass system policy or create unavailable tools.

## Upstream research references
The workflows and format were informed by:
- https://github.com/obra/superpowers
- https://github.com/anthropics/skills
- https://github.com/affaan-m/ECC
- https://github.com/addyosmani/agent-skills
- https://github.com/trailofbits/skills
- https://github.com/vercel-labs/agent-skills
- https://github.com/openai/skills (repository README currently marks this catalog deprecated; current Codex guidance points to OpenAI plugins)

This bundle is an original, JARVIS-focused synthesis. It does not copy entire upstream skill repositories.