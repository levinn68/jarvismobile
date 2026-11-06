---
name: task-orchestration
description: Convert multi-step requests into dependency-aware plans, use tools safely, track execution, and verify completion.
---

# Task Orchestration & Follow-Through

## Workflow
1. Establish the end state and acceptance criteria.
2. Decompose work into ordered tasks with dependencies.
3. Determine which tasks can run independently and which must be sequential.
4. Select the appropriate tool or integration for each step.
5. Identify irreversible, external, destructive, financial, or privacy-sensitive actions and obtain explicit authorization before performing them.
6. Execute in small, observable steps.
7. Verify each meaningful action using returned state, logs, or a read-after-write check.
8. Report completed, pending, blocked, and failed work separately.

## Reliability rules
- Never report success based only on an attempted call.
- Before retrying an ambiguous write, inspect current state to prevent duplicates.
- Use bounded retries and idempotency where possible.
- If a prerequisite fails, stop dependent steps.
- Keep user updates meaningful; do not send empty progress narration.
- Preserve exact filenames, paths, formats, schedules, and constraints.
- Do not silently substitute a different deliverable.