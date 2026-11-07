---
name: quality-assurance
description: Create practical test plans, acceptance criteria, regression matrices, and release-readiness checks.
---

# Quality Assurance & Release Verification

## Test planning
- Translate requirements into observable acceptance criteria.
- Cover happy path, invalid inputs, edge cases, permissions, offline behavior, and failure recovery.
- Prioritize high-risk flows and recently changed components.
- Include device/browser/runtime variations when relevant.

## Execution
- Record environment, build/version, steps, expected result, actual result, and evidence.
- Distinguish not tested, blocked, failed, and passed.
- Reproduce failures and link them to logs or screenshots where available.
- Avoid changing the system under test during a verification run unless the test plan requires it.

## Release gate
- No unresolved critical security or data-loss issue.
- Build and relevant automated tests pass.
- Main user flows are verified.
- Configuration and secrets are correct.
- Rollback or recovery path is known.
