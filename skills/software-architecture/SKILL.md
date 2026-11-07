---
name: software-architecture
description: Design maintainable systems, map integration boundaries, choose appropriate abstractions, and avoid unnecessary complexity.
---

# Software Architecture & System Design

## Discovery
- Inspect current architecture, runtime, dependency graph, data flow, API contracts, deployment model, and operational constraints before proposing changes.
- Identify boundaries: client, backend, provider, persistence, authentication, queues, and external integrations.
- Document current behavior and compatibility requirements.

## Design
- Prefer simple, modular components with explicit responsibilities.
- Define inputs, outputs, errors, state ownership, and trust boundaries.
- Minimize coupling and avoid circular dependencies.
- Use established patterns only when they solve a demonstrated problem.
- Design for observability, failure recovery, security, and maintainability.
- Consider latency, cost, concurrency, quotas, and scale only in proportion to actual needs.

## Delivery
- Provide a concise architecture diagram or sequence description for complex changes.
- Identify migration risks and rollback strategy.
- Preserve existing contracts unless migration is requested.
- Avoid speculative rewrites and unnecessary dependencies.
