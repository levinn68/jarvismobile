const SKILLS = new Map();

export const BUILTIN_SKILL_CATALOG = Object.freeze([
  { id: "jarvis-persona", name: "JARVIS Persona", description: "Maintain JARVIS's stable personality, natural bilingual communication, conversational timing, and voice-ready responses.", capabilities: ["conversation", "voice", "bilingual"] },
  { id: "reasoning-and-decision-making", name: "Analytical Reasoning", description: "Analyze ambiguous tasks, compare options, reason from evidence, and provide defensible decisions.", capabilities: ["reasoning", "analysis"] },
  { id: "task-orchestration", name: "Task Orchestration", description: "Convert multi-step requests into dependency-aware plans, use tools safely, track execution, and verify completion.", capabilities: ["planning", "orchestration"] },
  { id: "web-research", name: "Web Research", description: "Conduct current web research, compare source quality, verify claims, and provide source-backed answers.", capabilities: ["research", "grounding"] },
  { id: "memory-and-context", name: "Memory & Context", description: "Use conversation history and approved memory to maintain continuity while respecting privacy.", capabilities: ["memory", "context"] },
  { id: "software-architecture", name: "Software Architecture", description: "Design maintainable systems, map integration boundaries, and avoid unnecessary complexity.", capabilities: ["architecture", "engineering"] },
  { id: "coding-and-implementation", name: "Coding & Implementation", description: "Implement software changes incrementally with readable code, compatibility awareness, and validation.", capabilities: ["coding", "implementation"] },
  { id: "test-driven-development", name: "Test-Driven Development", description: "Use a disciplined red-green-refactor workflow and meaningful regression tests.", capabilities: ["testing", "qa"] },
  { id: "systematic-debugging", name: "Systematic Debugging", description: "Diagnose bugs using evidence, reproduction, root-cause analysis, and targeted fixes.", capabilities: ["debugging", "diagnostics"] },
  { id: "code-review", name: "Code Review", description: "Review code for correctness, regressions, security, performance, and compatibility.", capabilities: ["review", "audit"] },
  { id: "security-and-privacy", name: "Security & Privacy", description: "Apply secure-by-default practices to prompts, tools, APIs, credentials, and data handling.", capabilities: ["security", "privacy"] },
  { id: "api-integration", name: "API Integration", description: "Integrate third-party APIs with robust authentication, schemas, retries, quotas, and timeouts.", capabilities: ["api", "integration"] },
  { id: "browser-and-tool-use", name: "Browser & Tool Use", description: "Use browser and external tools deliberately, safely, and efficiently.", capabilities: ["tools", "browser"] },
  { id: "git-and-release-management", name: "Git & Release Management", description: "Manage Git changes, branches, commits, pull requests, releases, and rollback safely.", capabilities: ["git", "release"] },
  { id: "cloudflare-workers", name: "Cloudflare Workers", description: "Build and maintain Cloudflare Workers integrations with bindings, secrets, and runtime safety.", capabilities: ["cloudflare", "workers"] },
  { id: "android-mobile-engineering", name: "Android Mobile Engineering", description: "Diagnose and implement Android application behavior, Kotlin, lifecycle, permissions, and UI.", capabilities: ["android", "kotlin"] },
  { id: "voice-and-audio", name: "Voice & Audio", description: "Design reliable voice interaction including STT, turn-taking, TTS, language matching, and audio recovery.", capabilities: ["voice", "audio", "tts"] },
  { id: "multimodal-understanding", name: "Multimodal Understanding", description: "Interpret images, documents, and audio with explicit uncertainty and modality validation.", capabilities: ["multimodal", "vision"] },
  { id: "data-analysis", name: "Data Analysis", description: "Analyze structured data, spreadsheets, logs, and metrics with reproducible calculations.", capabilities: ["data", "analytics"] },
  { id: "documentation-and-writing", name: "Documentation & Writing", description: "Produce clear technical documentation, specifications, runbooks, and user-facing writing.", capabilities: ["docs", "writing"] },
  { id: "ui-ux-and-accessibility", name: "UI/UX & Accessibility", description: "Evaluate and improve interface usability, visual hierarchy, and accessibility.", capabilities: ["ui", "ux", "accessibility"] },
  { id: "performance-optimization", name: "Performance Optimization", description: "Find and fix measurable performance bottlenecks across app, backend, network, and model pipelines.", capabilities: ["performance", "latency"] },
  { id: "automation-and-scheduling", name: "Automation & Scheduling", description: "Design dependable scheduled and event-driven automations with idempotency and retries.", capabilities: ["automation", "scheduling"] },
  { id: "agent-tool-design", name: "Agent Tool Design", description: "Design tool-using agents with clear tool contracts, least privilege, routing, and safe execution.", capabilities: ["agents", "tools"] },
  { id: "prompt-injection-defense", name: "Prompt Injection Defense", description: "Detect and contain prompt injection and instruction-conflict attacks in untrusted data.", capabilities: ["security", "prompt-defense"] },
  { id: "productivity-and-personal-assistant", name: "Personal Assistant & Productivity", description: "Support everyday planning, reminders, prioritization, and decision follow-through.", capabilities: ["productivity", "assistant"] },
  { id: "quality-assurance", name: "Quality Assurance", description: "Create practical test plans, acceptance criteria, regression matrices, and release checks.", capabilities: ["qa", "verification"] },
  { id: "incident-response", name: "Incident Response", description: "Handle outages and production incidents with triage, containment, recovery, and postmortem.", capabilities: ["incident", "recovery"] },
  { id: "skill-authoring", name: "Skill Authoring", description: "Create, validate, version, and maintain modular agent skills.", capabilities: ["skills", "authoring"] }
]);

export function registerSkill(skill) {
  if (!skill?.id || typeof skill.run !== "function") {
    throw new Error("Skill requires id and run()");
  }
  SKILLS.set(skill.id, skill);
  return skill.id;
}

export function getSkill(id) {
  return SKILLS.get(id) || null;
}

export function listSkills() {
  return [...SKILLS.values()].map(({ id, name, description, capabilities = [] }) => ({
    id,
    name,
    description,
    capabilities
  }));
}

export async function runSkill(id, input, context) {
  const skill = getSkill(id);
  if (!skill) throw new Error("Unknown skill: " + id);
  return skill.run(input, context);
}

export function ensureBuiltinSkillsRegistered() {
  for (const item of BUILTIN_SKILL_CATALOG) {
    if (!SKILLS.has(item.id)) {
      registerSkill({
        ...item,
        run: async (input = {}) => ({
          id: item.id,
          name: item.name,
          description: item.description,
          capabilities: item.capabilities,
          appliedTo: typeof input?.prompt === "string" ? input.prompt.slice(0, 120) : ""
        })
      });
    }
  }
  return listSkills();
}

ensureBuiltinSkillsRegistered();