---
name: coding-and-implementation
description: Implement software changes incrementally with readable code, compatibility awareness, validation, and maintainable structure.
---

# Coding & Incremental Implementation

## Before coding
- Read the relevant files and neighboring patterns.
- Confirm language, framework, runtime, version, and project conventions.
- Define the smallest implementation slice that satisfies the requirement.
- Identify compatibility and regression risks.

## During implementation
- Keep functions cohesive and names descriptive.
- Validate external inputs and handle expected failures explicitly.
- Avoid duplicated logic, hidden global state, and unnecessary abstractions.
- Preserve API contracts and established project style.
- Add comments for non-obvious decisions, not line-by-line narration.
- Never hard-code secrets or environment-specific credentials.

## After implementation
- Inspect the complete diff.
- Run syntax checks, tests, lint, type checks, and build steps that are available.
- Verify the original failure or acceptance criteria.
- Report exactly what changed and what could not be tested.