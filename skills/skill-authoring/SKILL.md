---
name: skill-authoring
description: Create, validate, version, and maintain modular agent skills with clear metadata and safe progressive disclosure.
---

# Skill Authoring & Maintenance

## Structure
- One directory per skill with a `SKILL.md`.
- Include YAML frontmatter with a stable name and a clear, trigger-oriented description.
- Keep the main instructions concise and procedural.
- Put long references, examples, and scripts in separate files and load them only when needed.
- State when the skill should and should not activate.

## Quality
- Write instructions as observable steps, not vague aspirations.
- Include failure modes, safety boundaries, and verification criteria.
- Avoid conflicting instructions and duplicated global policy.
- Keep tool capabilities separate from skill instructions: a skill cannot create a missing tool or permission.
- Version changes and test activation on representative prompts.

## Validation
- Check frontmatter syntax and required metadata.
- Check file paths and referenced resources.
- Test trigger precision and ensure unrelated prompts do not activate the skill.
- Review scripts for network access, destructive operations, secret access, and obfuscation before execution.
