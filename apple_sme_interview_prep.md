# Apple SME Interview Prep - Vinit Kumar

## 1. Streaming Media Validation & Automation Platform Design

Designed a multi-layer platform with:
- Synthetic traffic generation (real devices + Kubernetes agents)
- Real-time observability pipeline
- Automated diagnosis engine
- Action orchestration system

Key Highlights:
- Global synthetic clients simulate playback scenarios
- Kubernetes used for scalable test execution and orchestration
- Real-time + batch pipelines for monitoring and analytics
- Correlation-based debugging across player, CDN, and backend
- Tiered sampling strategy for cost vs coverage optimization

---

## 2. Incident Reduction (1000+ → 70+)

Root Causes:
- Poor observability and noisy alerts
- Event-driven pipeline instability
- Weak deployment and operational processes

Fixes:
- Redesigned observability (metrics, logs, alerts)
- Fixed message replay/idempotency issues
- Introduced distributed caching (Redis)
- Automated deployments and hotfix workflows

Metrics:
- Reduced incident volume
- Improved MTTR
- Reduced duplicate alerts
- Prevented large-scale request loss

---

## 3. Debugging Scenario (iOS + Europe Buffering)

Approach:
- Scope issue (device, region, OS version)
- Compare telemetry across affected vs non-affected users
- Analyze:
  - CDN latency
  - Player decode metrics
  - Encoding differences

Isolation Strategy:
- CDN issue → high segment latency
- Player issue → high decode/drop frames
- Encoding issue → content-specific failures

---

## 4. Data Structure for Sliding Window Metrics

Approach:
- Use Queue/Deque
- Maintain running sum + count

Optimized Approach:
- Use time-bucketed ring buffer
- Trade accuracy for memory efficiency

---

## 5. Behavioral - Disagreement Example

- Challenged token-based auth design assumptions
- Provided data-driven concerns
- Aligned after decision
- Drove execution and successful migration

---

## What is Concurrency?

Concurrency is the ability of a system to handle multiple tasks at the same time.

Important clarification:
- Concurrency does NOT always mean tasks run simultaneously (parallelism)
- It means tasks can make progress independently

Example:
- A streaming system downloading video chunks while decoding previous chunks

Key Concepts:
- Threads / Processes
- Async programming (non-blocking I/O)
- Locks, Semaphores (for shared resources)
- Race conditions & Deadlocks

Why it matters:
- Improves system throughput
- Reduces latency
- Enables efficient resource utilization

In backend systems (like yours):
- Handling multiple requests
- Parallel processing pipelines
- Distributed systems coordination
