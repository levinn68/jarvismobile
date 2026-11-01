---
name: test-driven-development
description: Use a disciplined red-green-refactor workflow and meaningful regression tests when implementing behavior changes.
---

# Test-Driven Development

## RED
- Write or identify a test that expresses the expected behavior.
- Ensure it fails for the intended reason before implementation.
- Avoid tests that merely restate implementation details.

## GREEN
- Implement the smallest change that makes the test pass.
- Do not add unrelated features during the fix.
- Keep the test focused on observable behavior.

## REFACTOR
- Improve structure while keeping tests green.
- Remove duplication and clarify boundaries.
- Re-run relevant tests after refactoring.

## Test quality
- Cover success, invalid input, boundary cases, and failure paths.
- Prefer deterministic tests.
- Mock only external boundaries; do not mock the very behavior under test.
- Add regression coverage for every confirmed bug where practical.
- Never claim tests passed unless they were actually executed and results observed.
