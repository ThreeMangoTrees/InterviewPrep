# Multi-Data-Center System Design — Interview Prep

## 1. Start with the goal
Say what you are optimizing for before jumping into architecture.

Example:
> We want a highly available system that can survive a regional outage, keep latency low for global users, and avoid data loss beyond an acceptable threshold.

This tells the interviewer you understand that multi-data-center design is about trade-offs, not just adding more servers.

---

## 2. Clarify the requirements
Ask or state the key requirements:

### Functional requirements
- Users should be able to read and write data from different geographies.
- The system should continue working if one data center goes down.
- Traffic should automatically fail over to another healthy region.

### Non-functional requirements
- High availability
- Low latency
- Disaster recovery
- Data durability
- Consistency expectations
- Compliance and data residency if needed

Then explicitly ask:
- Do we need strong consistency or is eventual consistency acceptable?
- What is the RPO (Recovery Point Objective)?
- What is the RTO (Recovery Time Objective)?

These two terms matter:
- **RPO** = how much data loss is acceptable during failure
- **RTO** = how quickly the system must recover

---

## 3. Choose the high-level topology
State the deployment model clearly. Usually there are three options:

### Active-Passive
- One primary region serves all traffic
- Another region is on standby
- Easier to build
- Failover is simpler
- Worse resource utilization
- Higher failover latency

Use this when:
- Simplicity matters
- Strong consistency matters more
- Traffic is concentrated in one geography

### Active-Active
- Multiple regions serve live traffic
- Better latency for global users
- Better availability
- Harder conflict resolution
- Harder consistency model

Use this when:
- Global low latency matters
- High availability matters
- Eventual consistency is acceptable for at least part of the system

### Active-Active with regional ownership
- Each user or tenant has a home region for writes
- Other regions may serve cached or replicated reads
- This is often the most practical design

This is usually the best interview answer because it balances scale, latency, and consistency.

---

## 4. Add global traffic routing
Explain how users reach the right region.

### Routing options
- Geo-based routing
- Latency-based routing
- Health-check-based failover
- Anycast for edge routing if needed

Typical answer:
- Use a global load balancer or DNS layer
- Route users to the nearest healthy region
- If a region fails, shift traffic to the next closest healthy region

Mention common tools conceptually:
- DNS-based traffic management
- Global load balancer
- Health probes

Important interview point:
DNS failover is not always instant because of caching and TTL. A more advanced global traffic layer can reduce failover delay.

---

## 5. Define data ownership
This is where many answers become strong.

You should say:
> To reduce cross-region write latency and avoid global write conflicts, I will assign a primary write region for a user, tenant, or shard.

### Common strategies
- User-based ownership: a user writes to one home region
- Tenant-based ownership: a company/account belongs to one region
- Key-range sharding: shard ranges mapped to different regions

Why this helps:
- Local writes are fast
- Fewer multi-region conflicts
- Easier failover planning
- Better scalability

This is a strong practical pattern for interviews.

---

## 6. Design the database replication strategy
Now explain how data moves between regions.

### Synchronous replication
- Write is acknowledged only after another region confirms
- Stronger durability
- Higher latency
- Harder at global scale

### Asynchronous replication
- Primary region commits locally first
- Changes are replicated to other regions later
- Lower latency
- Risk of replication lag and some data loss during sudden failure

Typical interview recommendation:
- Use local commit in the primary region
- Replicate asynchronously to secondary regions
- Accept eventual consistency for cross-region reads

Then explain the trade-off clearly:
- If business needs strict consistency, latency goes up
- If business needs high availability and low latency, eventual consistency is usually chosen

That is exactly the kind of trade-off interviewers want to hear.

---

## 7. Handle reads and writes separately
Say how reads and writes behave.

### Write path
- Client request reaches nearest region
- Router sends write to the owner region for that user/shard
- Data is written to the primary database
- Change is published to replication pipeline
- Other regions receive updates asynchronously

### Read path
- Read from local replica or cache when slightly stale data is acceptable
- Read from owner region when strict freshness is required

This is a very good answer because it separates latency-sensitive reads from correctness-sensitive writes.

---

## 8. Plan for failure scenarios
You should always walk through failures.

### Scenario A: Application server failure in one region
- Local load balancer removes unhealthy instances
- No cross-region failover needed

### Scenario B: Entire region fails
- Global traffic manager marks region unhealthy
- Traffic is routed to another healthy region
- Secondary region becomes active for affected users/shards

### Scenario C: Database primary fails
- Promote replica if replication state is sufficiently up to date
- Use leader election or managed failover mechanism
- Ensure clients stop writing to the failed primary

Mention split-brain explicitly:
> We must prevent two regions from both acting as primary for the same shard at the same time.

That is a big interview signal.

---

## 9. Explain consistency and conflict resolution
If multiple regions can accept writes, conflicts happen.

### Common options
- Single-writer per shard/user
- Last-write-wins
- Version vectors / logical clocks
- Application-level merge rules
- CRDTs for special collaborative use cases

Best practical interview answer:
- Avoid multi-writer when possible
- Use single-writer ownership per shard
- Use async replication to distribute data
- This reduces conflict resolution complexity significantly

This answer is better than pretending conflicts are easy.

---

## 10. Add caching carefully
Each region usually has its own cache layer, such as Redis.

### Benefits
- Lower read latency
- Reduced database pressure
- Better regional performance

### Challenges
- Cache invalidation across regions
- Stale reads after failover
- Hot key imbalance

Good interview statement:
> I would keep cache local to each region and invalidate or update it using replication events. I would not try to make cache the source of truth.

That shows maturity.

---

## 11. Design the messaging / event layer
In multi-DC systems, an event bus is often critical.

Use it for:
- Replication
- Cache invalidation
- Search index updates
- Analytics
- Downstream processing

Key properties:
- At-least-once delivery
- Idempotent consumers
- Retry handling
- Dead-letter queue
- Ordering guarantees if required per key

Very solid interview line:
> Cross-region coordination should be asynchronous whenever possible. I would avoid putting cross-region synchronous calls in the user request path.

That is exactly right.

---

## 12. Add observability and operational safeguards
Multi-data-center systems are much harder to debug.

Mention:
- Centralized logging
- Distributed tracing
- Metrics per region
- Replication lag dashboards
- Synthetic health checks
- Alerting for failover, lag, error spikes, and split-brain risk

Also mention:
- Correlation IDs across services
- Regional error budget tracking

This part often differentiates mid-level from senior answers.

---

## 13. Handle schema and deployment strategy
You cannot safely run a global system with careless deploys.

Mention:
- Backward-compatible schema changes
- Rolling deployments by region
- Feature flags
- Canary in one region before global rollout
- Infrastructure as code
- Config consistency checks

Strong point:
> In a multi-region setup, deployments must tolerate mixed versions during rollout.

That matters a lot.

---

## 14. Address security and compliance
Even for interviews, briefly mention:
- Encryption in transit between regions
- Encryption at rest
- IAM/service-to-service authentication
- Secrets management
- Data residency controls
- Audit logs

If the interviewer mentions government or regulated environments, emphasize:
- Regional isolation
- Strict tenant placement
- Compliance-bound storage

---

## 15. Summarize the final architecture
A clean reference architecture could be:

1. Global DNS/load balancer routes traffic to the nearest healthy region  
2. Each region has stateless app servers, local cache, and local database replicas  
3. Each user/shard has a designated owner region for writes  
4. Writes go to the owner region’s primary database  
5. Changes are replicated asynchronously to other regions  
6. Reads are served locally when eventual consistency is acceptable  
7. Health checks and failover mechanisms redirect traffic during outages  
8. Observability tracks replication lag, failover status, and regional health  

This is usually enough to satisfy the design portion.

---

## 16. Mention trade-offs explicitly
Interviewers care a lot about trade-offs.

Say something like:
- Strong consistency across regions increases latency and reduces availability during partitions
- Asynchronous replication improves performance and availability but allows stale reads
- Active-active improves latency and resilience but adds operational and consistency complexity
- Active-passive is simpler but wastes resources and can increase failover time

If you say these clearly, your answer becomes much stronger.

---

## 17. A strong sample interview answer
Here is a concise interview-ready flow you can say:

> I would design the system using multiple geographic regions with regional traffic routing through a global load balancer or DNS layer. To avoid expensive cross-region writes on the critical path, I would assign each user or shard a home region that owns writes for that data. Each region would have stateless application servers, a local cache, and local database replicas. Writes would go to the owner region and then replicate asynchronously to other regions. Reads can be served from local replicas or caches when eventual consistency is acceptable, and strict reads can go to the owner region when needed. For failures, health checks would reroute traffic away from unhealthy regions, and failover would promote replicas carefully while preventing split-brain. I would use an event-driven replication and invalidation pipeline, make all consumers idempotent, and monitor replication lag, regional health, and failover state closely. The main trade-off is consistency versus latency and availability, and in most internet-scale systems I would prefer regional ownership with asynchronous replication rather than globally synchronous writes.

---

## 18. What not to say in an interview
Avoid these weak answers:
- “We will just replicate the database everywhere”
- “Consistency will be handled automatically”
- “Failover is easy with DNS”
- “We will keep all regions perfectly in sync in real time”
- “Redis solves the problem”

These sound shallow and usually break under follow-up questions.

---

## 19. Quick checklist for interview answers
Before finishing, check that you covered:
- Requirements
- Active-active vs active-passive
- Traffic routing
- Data ownership
- Replication model
- Read/write path
- Failure handling
- Consistency trade-offs
- Cache strategy
- Observability
- Security/compliance
- Trade-offs

If those are covered, your answer is in good shape.

---

## 20. One-line close for interviews
A strong closing line is:

> The hardest part of multi-data-center design is not deploying in more than one region; it is deciding where truth lives, how failure is handled, and what consistency trade-offs the business can accept.
