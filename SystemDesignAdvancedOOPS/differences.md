# Java Concepts: Differences, Usage, and Distributed Systems

---

## 1. `volatile` vs `final`

### Conceptual Difference

| Aspect | `volatile` | `final` |
|---|---|---|
| **What it does** | Guarantees visibility — every read sees the latest write from any thread | Guarantees immutability — the reference (or primitive) cannot be reassigned after construction |
| **Mutability** | The field CAN be changed; volatile just ensures all threads see the change | The field CANNOT be changed once assigned |
| **When resolved** | At runtime — value may change many times | At compile/construction time — value is fixed forever |
| **Memory effect** | Forces a CPU cache flush on write; reads bypass local CPU cache | No runtime memory effect; it is a compile-time constraint |
| **Use case** | Flags, state fields that one thread writes and others read | Constants, injected dependencies, value objects |
| **Thread safety** | Solves visibility but NOT atomicity (read-modify-write still unsafe) | Inherently safe — nothing can change it |

### The Visibility Problem `volatile` Solves

Without `volatile`, the JVM may cache a field in a CPU register or L1 cache. A write by Thread A may not be visible to Thread B for an indefinite time — this is called the **visibility problem** (Java Memory Model).

`volatile` inserts a **memory barrier**:
- On write: flushes the updated value to main memory immediately
- On read: bypasses CPU cache and reads directly from main memory

### What `volatile` Does NOT Solve

```
Thread A: read balance (100) → add 50 → write 150
Thread B: read balance (100) → add 30 → write 130   ← lost Thread A's update
```

`volatile` does not make compound operations (check-then-act, read-modify-write) atomic. For that, use `AtomicInteger`, `synchronized`, or a lock.

### When to Use

| Situation | Use |
|---|---|
| One thread writes, many threads read a boolean flag | `volatile` |
| You want a field that can never be reassigned | `final` |
| Counter incremented by multiple threads | `AtomicInteger` (not `volatile`) |
| Immutable config object, injected dependency | `final` |
| State variable polled by a watchdog thread | `volatile` |

### Distributed Systems

Neither `volatile` nor `final` has meaning outside a single JVM process — they are JVM memory model constructs.

| In distributed systems… | Alternative |
|---|---|
| `volatile` visibility across nodes | Distributed cache (Redis), Zookeeper, etcd — nodes read from a shared source |
| `final` immutable config across services | Distributed config stores (Consul, AWS Parameter Store, Spring Cloud Config) |

---

## 2. Singleton vs `static`

### Conceptual Difference

| Aspect | Singleton | `static` |
|---|---|---|
| **What it is** | A design pattern — one instance of a class, accessed via `getInstance()` | A language keyword — a member that belongs to the class, not to any instance |
| **Instance** | Is an object — has `this`, can implement interfaces, be injected, mocked | Not an object — no `this`, cannot implement interfaces or be passed as a dependency |
| **Lazy initialization** | Can be lazy (created on first `getInstance()` call) | Always loaded when the class is loaded by the JVM |
| **Testability** | Can be mocked or replaced (implement an interface, swap in tests) | Cannot be mocked without a framework like PowerMock |
| **Inheritance** | Can extend classes, implement interfaces | Cannot be overridden (static methods hide, not override) |
| **State** | Lives on the heap as a regular object | Static fields live in the method area (PermGen / Metaspace) |
| **Lifecycle** | Controlled — only created when `getInstance()` is first called (if lazy) | Created when the class is loaded; destroyed when the class is unloaded |

### Key Insight

A Singleton IS an object. A static member IS NOT an object.

This distinction matters:
- You can pass a Singleton as a method argument (`ILogger logger = LoggerSingleton.getInstance()`)
- You cannot pass a `static` class as an argument
- You can mock a Singleton in tests if it implements an interface
- You cannot mock a `static` method without bytecode manipulation tools

### When to Use

| Situation | Use |
|---|---|
| One shared resource that needs to implement an interface | Singleton |
| Pure utility functions with no state | `static` methods |
| Global ID generator that must be injectable/mockable | Singleton |
| Math helpers, string formatters, conversions | `static` methods |
| Shared connection pool, thread pool, registry | Singleton |
| Constants | `static final` |

### Distributed Systems

A Singleton is per-JVM-process. In a distributed system with 10 nodes, each node has its own Singleton instance — they are NOT shared.

| In distributed systems… | Alternative |
|---|---|
| Singleton ID generator (collides across nodes) | Snowflake IDs, UUID, Redis INCR |
| Singleton config (stale across nodes) | Consul, etcd, Zookeeper, AWS Parameter Store |
| Singleton connection pool | Per-node pool is fine; use connection limits at the DB level |
| Singleton rate limiter (misses cross-node traffic) | Redis token bucket / sliding window |
| Singleton cache | Distributed cache (Redis, Memcached, Hazelcast) |

---

## 3. `synchronized` vs Atomic CAS vs Mutex

### Conceptual Difference

| Aspect | `synchronized` | Atomic CAS (`AtomicInteger` etc.) | `ReentrantLock` (Mutex) |
|---|---|---|---|
| **Mechanism** | JVM intrinsic lock (monitor) on an object | CPU-level compare-and-swap instruction | Explicit lock object in `java.util.concurrent.locks` |
| **Blocking** | Yes — other threads block and wait | No — threads retry in a loop (spin) or back off | Yes — threads block and wait |
| **Granularity** | Method or block | Single field | Block or explicit lock/unlock |
| **Fairness** | Not guaranteed | N/A | Optional — `new ReentrantLock(true)` for fair queuing |
| **Interruptible** | No — cannot interrupt a blocked `synchronized` | N/A | Yes — `lockInterruptibly()` |
| **Try-lock** | No | N/A | Yes — `tryLock()` / `tryLock(timeout)` |
| **Condition variables** | `wait()` / `notify()` (awkward) | N/A | `lock.newCondition()` (clean) |
| **Reentrant** | Yes | N/A | Yes |
| **Performance** | Good for low contention | Best for single-variable, low contention | Good; better than `synchronized` for high contention with fairness |
| **Use case** | Protecting a block of multiple related operations | Protecting a single counter or reference | Advanced locking: timeouts, conditions, fairness |

### How Each Works Under the Hood

**`synchronized`**
The JVM uses an object's **monitor**. Each object has a hidden lock bit. The JVM emits `monitorenter` / `monitorexit` bytecodes. Under low contention, the JVM uses a **biased lock** (nearly free). Under contention, it escalates to a thin lock, then a heavyweight OS mutex.

**Atomic CAS**
Maps to a single CPU instruction (`LOCK CMPXCHG` on x86). The CPU guarantees the instruction is indivisible even across cores. No OS involvement — entirely in user space. Fastest possible synchronization for a single variable.

**`ReentrantLock` (Mutex)**
Built on `AbstractQueuedSynchronizer` (AQS) — a framework using a CAS-based state variable plus a queue of waiting threads. Threads park (via `LockSupport.park()`) when they cannot acquire — they do not spin, they yield to the OS scheduler.

### When to Use

| Situation | Use |
|---|---|
| Protecting a simple counter | `AtomicInteger` |
| Protecting an object reference swap | `AtomicReference` |
| Protecting a short block of multiple statements | `synchronized` |
| Need a lock with timeout (`tryLock`) | `ReentrantLock` |
| Need to interrupt a waiting thread | `ReentrantLock.lockInterruptibly()` |
| Producer-consumer with conditions | `ReentrantLock` + `Condition` |
| Need fair lock ordering (no starvation) | `new ReentrantLock(true)` |
| Lock-free stack / queue | CAS + `AtomicReference` |
| High contention on a single variable | `LongAdder` (striped CAS, better than `AtomicLong`) |

### The ABA Problem (CAS only)

CAS checks for reference/value equality. If a value goes A → B → A, CAS cannot distinguish the third state from the first:

```
Thread 1: reads A, prepares to CAS A → C
Thread 2: changes A → B → A
Thread 1: CAS succeeds (sees A) — but the world has changed
```

Fix: `AtomicStampedReference` — pairs the value with a version counter.

### Distributed Systems

`synchronized`, CAS, and `ReentrantLock` all operate within a single JVM process — they provide no coordination across network nodes.

| In distributed systems… | Alternative |
|---|---|
| `synchronized` / `ReentrantLock` across nodes | Distributed lock: Redis `SET key NX PX ttl` (Redlock algorithm), Zookeeper ephemeral nodes, etcd leases |
| Atomic counter across nodes | Redis `INCR` / `DECR` (single-threaded, atomic in Redis) |
| CAS across nodes | Redis `SET key value XX` with version check, DynamoDB conditional writes (`ConditionExpression`) |
| Fair queuing across nodes | Zookeeper sequential ephemeral nodes (each node grabs the lowest-numbered node) |

---

## Summary Reference Card

```
VISIBILITY ONLY (one writer, many readers):
  → volatile

IMMUTABILITY (never changes):
  → final

SINGLE COUNTER / REFERENCE, LOW CONTENTION:
  → AtomicInteger / AtomicLong / AtomicReference

MULTIPLE FIELDS OR COMPLEX INVARIANTS:
  → synchronized (simple) or ReentrantLock (advanced)

NEED TIMEOUT / INTERRUPTIBLE / FAIR LOCK:
  → ReentrantLock

HIGH CONTENTION ON A SINGLE COUNTER:
  → LongAdder (striped CAS, scales with core count)

ONE SHARED INSTANCE OF AN OBJECT:
  → Singleton pattern

UTILITY FUNCTIONS WITH NO STATE:
  → static methods

IN DISTRIBUTED SYSTEMS:
  visibility / state   → Redis, etcd, Zookeeper
  distributed lock     → Redlock (Redis), Zookeeper, etcd lease
  distributed counter  → Redis INCR / DECR
  distributed CAS      → Redis SET XX, DynamoDB conditional write
  global unique IDs    → Snowflake, UUID
```
