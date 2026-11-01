---
name: code-review
description: Review code for correctness, regressions, security, performance, readability, and compatibility with severity-ranked findings.
---

# Code Review & Quality Gate

Review in this order:
1. Correctness and behavior against requirements.
2. Regressions and backward compatibility.
3. Security, privacy, and trust boundaries.
4. Error handling, concurrency, and reliability.
5. Performance and resource usage.
6. Readability and maintainability.
7. Test adequacy and deployment impact.

## Findings
- Report actionable findings first.
- Include file and line references when available.
- Classify severity: Critical, High, Medium, Low, or Informational.
- Explain impact and a concrete remediation.
- Do not report style preferences as bugs.
- Avoid speculative findings without a plausible exploit or failure path.
- If no issues are found, state the scope and limitations of the review.
