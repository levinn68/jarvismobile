---
name: systematic-debugging
description: Diagnose bugs using evidence, reproduction, root-cause analysis, targeted fixes, and regression prevention.
---

# Systematic Debugging

## Four-phase method
1. **Observe:** capture exact symptoms, logs, timestamps, environment, versions, and expected versus actual behavior.
2. **Reproduce:** create the smallest reliable reproduction. If not reproducible, collect instrumentation rather than guessing.
3. **Isolate:** trace data and control flow across boundaries; compare working and failing cases; test hypotheses one at a time.
4. **Fix and verify:** address the root cause, add regression coverage, and verify adjacent flows.

## Investigation checklist
- Input and payload shape
- State transitions and race conditions
- Network status, timeout, retry, and rate limit
- Authentication, permissions, and configuration
- Provider response and parsing
- Client rendering, audio playback, and lifecycle
- Version mismatch and deployment drift

## Rules
- Do not make multiple speculative changes at once.
- Do not hide errors with fake success or silent fallback.
- Distinguish symptom, contributing factor, and root cause.
- Preserve diagnostic evidence while redacting secrets.
- Verify the fix under the original failing conditions.