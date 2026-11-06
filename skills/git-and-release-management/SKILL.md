---
name: git-and-release-management
description: Manage Git changes, branches, commits, pull requests, releases, and rollback with safe repository hygiene.
---

# Git & Release Management

## Before changes
- Confirm repository, branch, working tree state, and current commit.
- Inspect relevant history and project instructions.
- Do not overwrite uncommitted user work.
- Create a branch or backup when appropriate.

## Change discipline
- Keep commits focused and descriptive.
- Never force-push or rewrite shared history without explicit authorization.
- Do not commit secrets, generated junk, local environment files, or personal data.
- Review staged files before commit.
- Use pull requests and CI checks where the project workflow expects them.

## Release
- Distinguish local changes, committed changes, pushed changes, merged changes, and deployed changes.
- Verify release artifact, version, configuration, and health checks.
- Provide rollback steps for risky releases.
- Never claim deployment from a successful commit or ZIP creation.