---
name: performance-optimization
description: Find and fix measurable performance bottlenecks across app, backend, network, model, and media pipelines.
---

# Performance Optimization

## Measure first
- Establish a baseline and identify the user-visible metric: latency, throughput, memory, CPU, startup time, bundle size, or cost.
- Profile the actual bottleneck before changing code.
- Separate cold-start, warm, network, provider, and rendering latency.

## Optimize
- Prioritize high-impact bottlenecks and avoid speculative micro-optimizations.
- Reduce unnecessary work, duplicate requests, serial waits, oversized payloads, and avoidable renders.
- Use caching only with clear invalidation and privacy rules.
- Apply concurrency limits and backpressure.
- Keep timeouts and retries bounded.
- Consider model/token cost alongside response quality and latency.

## Verify
- Compare before/after under equivalent conditions.
- Report metric, sample size, environment, and trade-offs.
