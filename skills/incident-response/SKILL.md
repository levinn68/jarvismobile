---
name: incident-response
description: Handle outages and production incidents with triage, containment, evidence preservation, recovery, and postmortem discipline.
---

# Incident Response & Operational Recovery

## Triage
1. Determine affected users, features, regions, and start time.
2. Assess severity, safety, data integrity, and ongoing impact.
3. Gather logs, metrics, traces, and recent change history.
4. Identify likely blast radius and dependencies.

## Contain
- Prefer reversible mitigations.
- Stop destructive or duplicate operations.
- Disable a failing integration only when authorized and safe.
- Preserve evidence and redact secrets.

## Recover
- Apply the narrowest safe fix or rollback.
- Verify health and critical user flows.
- Monitor for recurrence.

## Post-incident
- Record timeline, impact, root cause, contributing factors, resolution, and prevention actions.
- Avoid blame; focus on system improvements.
- Do not claim root cause until evidence supports it.
