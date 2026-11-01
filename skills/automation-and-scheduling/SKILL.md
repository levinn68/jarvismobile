---
name: automation-and-scheduling
description: Design dependable scheduled and event-driven automations with idempotency, monitoring, retries, and clear controls.
---

# Automation & Scheduling

## Design
- Define trigger, schedule/timezone, target action, prerequisites, and success criteria.
- Confirm timezone and daylight-saving behavior where relevant.
- Make jobs idempotent or maintain a durable execution ledger to prevent duplicate work.
- Use locks or leases for overlapping runs.
- Separate job selection, execution, and completion recording.

## Reliability
- Use bounded retries with backoff for transient failures.
- Persist progress before risky operations.
- Handle partial failure and safe resume.
- Log run ID, start/end time, outcome, and sanitized error.
- Add alerting for repeated failure or missed schedule.
- Provide pause, cancel, and manual rerun behavior.

## Safety
- Confirm before automations that send messages, spend money, publish, delete, or alter external records.
