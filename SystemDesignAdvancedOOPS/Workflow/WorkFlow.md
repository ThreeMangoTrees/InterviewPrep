# LLD: Workflow State Transition Logger

## Problem Statement

Design a component that logs state transitions of workflow steps. Each workflow consists of multiple steps with defined states (Pending, Running, Completed, Failed). The system must track and persist state changes over time.

---

## 1. Requirements

### Functional Requirements

1. Define a **Workflow** as an ordered collection of named **Steps**
2. Each Step has a **current state**: `PENDING | RUNNING | COMPLETED | FAILED | CANCELLED | SKIPPED`
3. Steps transition between states according to a **defined state machine** (not arbitrary jumps)
4. Every state transition produces a **log record** containing: who triggered it, when, what state changed to what, and optional metadata
5. Transition history must be **queryable per step** and **per workflow**
6. Invalid transitions must be **rejected with an error** — the step stays in its current state
7. Observers can **subscribe** to receive notifications on every transition (for alerts, audits, etc.)
8. Transition log must be **persistable** (file, database, etc.) via a pluggable backend

### Non-Functional Requirements

- **Thread-safe** — concurrent workflows must not corrupt state
- **Extensible** — easy to add new states, new logger backends, new observers
- **Low coupling** — WorkflowStep does not know about logger or observers
- **Separation of concerns** — validation, logging, and notification are independent responsibilities

---

## 2. State Machine

```
                   ┌─────────────────────────────┐
                   │         PENDING              │◄─────── (retry from FAILED)
                   └────────────┬────────────────┘
                                │
                    ┌───────────┼───────────┐
                    ▼           ▼           ▼
                RUNNING      CANCELLED   SKIPPED
                    │         (terminal)  (terminal)
         ┌──────────┼──────────┐
         ▼          ▼          ▼
     COMPLETED   FAILED     CANCELLED
     (terminal)    │         (terminal)
                   │
                   ▼
                PENDING  ← retry path
```

### Valid Transition Table

| From State  | Allowed Transitions                          | Notes                          |
|-------------|----------------------------------------------|--------------------------------|
| PENDING     | RUNNING, CANCELLED, SKIPPED                  | Normal start or skip           |
| RUNNING     | COMPLETED, FAILED, CANCELLED                 | Normal end or error/cancel     |
| FAILED      | PENDING                                      | Retry: reset and re-queue      |
| COMPLETED   | (none — terminal)                            | Immutable once complete        |
| CANCELLED   | (none — terminal)                            | Immutable once cancelled       |
| SKIPPED     | (none — terminal)                            | Immutable once skipped         |

Any transition not in this table is **rejected** with a descriptive error.

---

## 3. Core Data Model

### `StateTransition` — the log record

```
StateTransition {
    transition_id  : string           // globally unique (T1, T2, ...)
    workflow_id    : string           // which workflow this belongs to
    step_id        : string           // which step transitioned
    from_state     : WorkflowState    // previous state
    to_state       : WorkflowState    // new state
    timestamp      : time_point       // wall-clock time of transition
    triggered_by   : string           // service / user / system that caused it
    metadata       : map<string,str>  // arbitrary key-value context (error codes, etc.)
}
```

This is an **append-only record**. Transitions are never updated or deleted.

### `WorkflowStep` — tracks its own state

```
WorkflowStep {
    step_id        : string
    step_name      : string
    workflow_id    : string
    current_state  : WorkflowState    // current state (mutable)
    history        : List<StateTransition>
}
```

### `Workflow` — container of ordered steps

```
Workflow {
    workflow_id    : string
    workflow_name  : string
    steps          : List<WorkflowStep>    // ordered
}
```

The workflow's **overall status** is derived from its steps:
- Any step FAILED → workflow is FAILED
- Any step CANCELLED → workflow is CANCELLED
- Any step RUNNING → workflow is RUNNING
- Any step PENDING (and none running/failed) → workflow is PENDING
- All steps COMPLETED or SKIPPED → workflow is COMPLETED

---

## 4. Class Design

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                            CLASS DIAGRAM                                        │
└─────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────┐   ┌──────────────────────────┐
  │   <<enum>>               │   │   <<enum>>               │
  │   WorkflowState          │   │   LoggerType             │
  │──────────────────────────│   │──────────────────────────│
  │  PENDING                 │   │  IN_MEMORY               │
  │  RUNNING                 │   │  FILE                    │
  │  COMPLETED               │   └──────────────────────────┘
  │  FAILED                  │
  │  CANCELLED               │   ┌──────────────────────────┐
  │  SKIPPED                 │   │   <<enum>>               │
  └──────────────────────────┘   │   ObserverType           │
                                 │──────────────────────────│
                                 │  ALERT                   │
                                 │  AUDIT                   │
                                 └──────────────────────────┘

  ┌──────────────────────────────┐        ┌──────────────────────────────────────┐
  │   TransitionValidator        │        │           StateTransition             │
  │  <<Singleton>>               │        │──────────────────────────────────────│
  │──────────────────────────────│        │  +transition_id : string             │
  │  -valid_transitions_         │        │  +workflow_id   : string             │
  │──────────────────────────────│        │  +step_id       : string             │
  │  +getInstance() : self       │        │  +from_state    : WorkflowState      │
  │  +isValid(from, to)          │        │  +to_state      : WorkflowState      │
  │  +validTargetsFor(from)      │        │  +timestamp     : time_point         │
  └──────────────────────────────┘        │  +triggered_by  : string             │
                                          │  +metadata      : map<str,str>       │
                                          │──────────────────────────────────────│
                                          │  +toString()    : string             │
                                          └──────────────────────────────────────┘

  ┌──────────────────────────┐    owns    ┌──────────────────────────────────┐
  │       Workflow           │────────────│        WorkflowStep              │
  │──────────────────────────│  1 : many  │──────────────────────────────────│
  │  -workflow_id_           │            │  -step_id_                       │
  │  -workflow_name_         │            │  -step_name_                     │
  │  -steps_                 │            │  -workflow_id_                   │
  │──────────────────────────│            │  -current_state_                 │
  │  +addStep(name)  ←── Factory Method   │  -history_  : List<Transition>   │
  │  +getStep(step_id)       │            │──────────────────────────────────│
  │  +getOverallStatus()     │            │  +transition(to, validator, ...)  │
  │  +printStatus()          │            │  +getCurrentState()              │
  └──────────────────────────┘            │  +getHistory()                   │
                                          │  +isTerminal()                   │
                                          └──────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │              <<interface>> ITransitionLogger             │
  │──────────────────────────────────────────────────────────│
  │  +log(transition)                                        │
  │  +getHistory(step_id) : List<StateTransition>            │
  │  +getAllHistory()      : List<StateTransition>           │
  └───────────────────┬──────────────────────────────────────┘
                      │ implements
          ┌───────────┴──────────────┐
          ▼                          ▼
  ┌────────────────────┐    ┌─────────────────────┐
  │   InMemoryLogger   │    │     FileLogger       │
  │────────────────────│    │─────────────────────│
  │  -step_history_    │    │  -filepath_          │
  │  -all_transitions_ │    │  -step_history_      │
  │────────────────────│    │─────────────────────│
  │  +exportToCSV()    │    │  writes on every log │
  └──────────┬─────────┘    └──────────┬──────────┘
             │                         │
             └────────────┬────────────┘
                          │ creates
                ┌─────────▼──────────────┐
                │     LoggerFactory      │   ← Factory Pattern
                │────────────────────────│
                │  +create(LoggerType,   │
                │          ...args)      │
                │  : ITransitionLogger   │
                └────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │            <<interface>> ITransitionObserver             │
  │──────────────────────────────────────────────────────────│
  │  +onTransition(transition)                               │
  └───────────────────┬──────────────────────────────────────┘
                      │ implements
          ┌───────────┴──────────────┐
          ▼                          ▼
  ┌────────────────────┐    ┌─────────────────────┐
  │   AlertObserver    │    │    AuditObserver     │
  │────────────────────│    │─────────────────────│
  │  alerts on FAILED  │    │  immutable audit log │
  │  and CANCELLED     │    │  in memory           │
  └──────────┬─────────┘    └──────────┬──────────┘
             │                         │
             └────────────┬────────────┘
                          │ creates
                ┌─────────▼──────────────┐
                │    ObserverFactory     │   ← Factory Pattern
                │────────────────────────│
                │  +create(ObserverType) │
                │  : ITransitionObserver │
                └────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────┐
  │                          WorkflowEngine                              │
  │──────────────────────────────────────────────────────────────────────│
  │  -workflows_    : map<id, Workflow>                                  │
  │  -logger_       : ITransitionLogger        ← strategy pattern       │
  │  -observers_    : List<ITransitionObserver> ← observer pattern      │
  │  -validator_    : TransitionValidator       ← singleton             │
  │──────────────────────────────────────────────────────────────────────│
  │  +createWorkflow(name)  ← Factory Method                            │
  │  +transitionStep(wf_id, step_id, new_state, triggered_by, metadata) │
  │  +addObserver(observer)                                              │
  │  +printStepHistory(step_id)                                          │
  │  +printAllWorkflows()                                                │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## 5. Design Patterns Used

### Observer Pattern
**Where:** `WorkflowEngine` notifies `ITransitionObserver` implementations after every successful transition.
**Why:** Decouples alerting, auditing, and monitoring from core state machine logic. Adding a new notification type (e.g., Slack alert, metrics counter) requires zero changes to existing classes.

### Strategy Pattern
**Where:** `ITransitionLogger` is injected into `WorkflowEngine` at construction time.
**Why:** Swap between `InMemoryLogger` (tests, single-instance) and `FileLogger` (durability) or a future `DatabaseLogger` without changing the engine. Open/Closed Principle.

### State Pattern (lightweight)
**Where:** `WorkflowStep.transition()` enforces legal state transitions via `TransitionValidator`.
**Why:** The valid transitions are defined once in a table. Adding a new state (e.g., PAUSED) means updating the validator, not scattering `if` checks everywhere.

### Template Method / Separation of Concerns
**Where:** `WorkflowEngine.transitionStep()` orchestrates: validate → transition → log → notify.
**Why:** Each responsibility is isolated. The step does not know about the logger. The logger does not know about observers.

### Singleton Pattern
**Where:** `TransitionValidator` is a Singleton.
**Why:** The valid-transition adjacency map is purely read-only and identical for every caller. There is no benefit to multiple instances — they would all hold the same data. Using a Singleton eliminates redundant construction and makes the shared nature explicit.
- **Java:** Initialization-on-demand holder idiom — lazy, thread-safe, zero synchronization cost after first call.
- **C++:** Local static (`static TransitionValidator instance`) — thread-safe by the C++11 magic-statics guarantee.

### Factory Pattern
**Where:** `LoggerFactory` and `ObserverFactory`.

**`LoggerFactory`**
```java
// Java
ITransitionLogger logger = LoggerFactory.create(LoggerType.IN_MEMORY);
ITransitionLogger logger = LoggerFactory.create(LoggerType.FILE, "app.log");
```
```cpp
// C++
auto logger = LoggerFactory::create(LoggerType::IN_MEMORY);
auto logger = LoggerFactory::create(LoggerType::FILE, "app.log");
```
**Why:** `InMemoryLogger` and `FileLogger` have different constructor signatures — the file logger requires a filepath, the in-memory logger does not. Without a factory, the caller must know which concrete class to instantiate and how. The factory hides that detail: the caller only provides a `LoggerType` enum value and gets back an `ITransitionLogger`.

**`ObserverFactory`**
```java
// Java
engine.addObserver(ObserverFactory.create(ObserverType.ALERT));
engine.addObserver(ObserverFactory.create(ObserverType.AUDIT));
```
**Why:** Caller depends only on `ITransitionObserver`, not on `AlertObserver` or `AuditObserver`. New observer types can be added by extending the enum and adding one case to the factory — no changes to `WorkflowEngine` or call sites.

**`Workflow.addStep()` and `WorkflowEngine.createWorkflow()`** are **embedded Factory Methods** — they construct `WorkflowStep` and `Workflow` objects with auto-generated IDs internally, hiding that ID-generation logic from the caller. These were present from the original design.

---

## 6. Sequence Diagram — transitionStep() call

```
Caller           WorkflowEngine        WorkflowStep       TransitionValidator    ITransitionLogger    ITransitionObserver
  │                    │                    │                     │                      │                   │
  │──transitionStep──► │                    │                     │                      │                   │
  │                    │──getStep()────────►│                     │                      │                   │
  │                    │◄──step ref─────────│                     │                      │                   │
  │                    │                    │                     │                      │                   │
  │                    │──step.transition()─►│                    │                      │                   │
  │                    │                    │──isValid(from,to)──►│                      │                   │
  │                    │                    │◄──bool──────────────│                      │                   │
  │                    │                    │                     │                      │                   │
  │                    │                    │──(if valid)──────────────────────────────► │                   │
  │                    │◄──StateTransition──│   build transition record                  │                   │
  │                    │                    │   append to step history                   │                   │
  │                    │                    │   update current_state                     │                   │
  │                    │                    │                     │                      │                   │
  │                    │──logger_.log(t)────────────────────────────────────────────────►│                   │
  │                    │                    │                     │                      │                   │
  │                    │──notifyObservers(t)─────────────────────────────────────────────────────────────────►│
  │                    │                    │                     │                      │                   │
  │◄───────────────────│                    │                     │                      │                   │
      returns true                         │
  │                    │                    │                     │                      │                   │
  │  (if invalid)      │                    │                     │                      │                   │
  │                    │──step.transition()─►│                    │                      │                   │
  │                    │                    │──isValid()─────────►│                      │                   │
  │                    │                    │◄──false─────────────│                      │                   │
  │                    │                    │──throw invalid_argument                    │                   │
  │                    │◄──catch exception──│                     │                      │                   │
  │◄──returns false────│                    │                     │                      │                   │
```

---

## 7. Key Design Decisions and Tradeoffs

### Decision 1: Who owns the transition history?

**Option A:** `WorkflowStep` stores its own history locally.
**Option B:** Only the logger stores history; steps are stateless.

**Choice:** Both. `WorkflowStep` holds a local `history_` vector (for fast in-process access, unit testing), while the `ITransitionLogger` provides the persistent/queryable backend.

**Tradeoff:** Slight duplication, but enables the step to be interrogated independently of the logger (useful in unit tests and in-memory simulations).

---

### Decision 2: Atomic ID generation vs. UUIDs

**Option A:** Atomic counter (`T1`, `T2`, ...) — simple, ordered, compact.
**Option B:** UUID — globally unique across machines, no central counter.

**Choice:** Atomic counter for this design. In a distributed system, use Snowflake or UUID.

**Tradeoff:** Atomic counter is simpler but not safe across multiple processes/machines. For production, replace `generateTransitionId()` with a distributed ID generator.

---

### Decision 3: Fan-out to observers synchronously vs. asynchronously

**Choice:** Synchronous fan-out in this design.

**Tradeoff:**
- **Synchronous:** Simple, no dropped events, but a slow observer blocks the transition call.
- **Asynchronous (queue + thread pool):** Better throughput, but adds complexity and requires handling failed/slow consumers.

For a production system, use an async worker queue (e.g., bounded `std::queue` with a background thread per observer).

---

### Decision 4: Transition validation — centralized validator vs. per-state objects



**Option A:** A single `TransitionValidator` with a static adjacency map.
**Option B:** Each state is an object with a `canTransitionTo()` method (GoF State pattern fully).

**Choice:** Centralized validator. Adding a new state means editing one map, not creating a new class.

**Tradeoff:** Less OOP purity, but significantly simpler to maintain when the state set is small and stable.

---

### Decision 5: Factory — where to place construction logic

**Problem:** `InMemoryLogger` and `FileLogger` have different constructor signatures. `AlertObserver` and `AuditObserver` are concrete classes the caller should not need to know about.

**Option A:** Caller calls `new InMemoryLogger()` / `new FileLogger("path")` directly.
**Option B:** Centralize construction in `LoggerFactory` and `ObserverFactory`.

**Choice:** Factory classes with type enums.

**Tradeoff:**
- Adds two small classes and two enums.
- In return: call sites depend only on interfaces (`ITransitionLogger`, `ITransitionObserver`) and type tokens (`LoggerType`, `ObserverType`). Adding a new logger or observer requires changing only the factory, never the engine or call sites. This strictly follows the Open/Closed Principle.

---

## 8. Thread Safety

Every mutable shared resource is guarded by a `std::mutex`:

| Class               | Protected Resource           | Guard             |
|---------------------|------------------------------|-------------------|
| `WorkflowStep`      | `current_state_`, `history_` | per-step mutex    |
| `Workflow`          | `steps_` vector              | per-workflow mutex|
| `InMemoryLogger`    | `step_history_`, `all_`      | logger mutex      |
| `WorkflowEngine`    | `workflows_`, `observers_`   | engine mutex      |
| `AuditObserver`     | `audit_log_`                 | observer mutex    |

`transitionStep` in `WorkflowEngine` does not hold the engine mutex while calling `step.transition()` — this avoids a potential deadlock if an observer tries to call back into the engine.

---

## 9. Extensibility Guide

### Add a new state (e.g., PAUSED)

1. Add `PAUSED` to the `WorkflowState` enum
2. Add `stateToString()` case
3. Add valid transitions in `TransitionValidator` constructor:
   - `RUNNING → PAUSED`
   - `PAUSED → RUNNING` (resume)
   - `PAUSED → CANCELLED`

No changes to `WorkflowStep`, `Workflow`, or `WorkflowEngine`.

---

### Add a new logger backend (e.g., PostgreSQL)

**Step 1** — implement the interface:
```cpp
class PostgresLogger : public ITransitionLogger {
    void log(const StateTransition& t) override {
        // INSERT INTO transitions ...
    }
    // implement getHistory(), getAllHistory()
};
```

**Step 2** — add it to `LoggerFactory`:
```cpp
// Add to LoggerType enum:
enum class LoggerType { IN_MEMORY, FILE, POSTGRES };

// Add one case in LoggerFactory::create():
case LoggerType::POSTGRES:
    return std::make_shared<PostgresLogger>(args[0]); // connection string
```

**Step 3** — use it via the factory (no other changes):
```cpp
auto logger = LoggerFactory::create(LoggerType::POSTGRES, "host=localhost dbname=wf");
WorkflowEngine engine(logger);
```

---

### Add a new observer (e.g., Metrics counter)

**Step 1** — implement the interface:
```cpp
class MetricsObserver : public ITransitionObserver {
    void onTransition(const StateTransition& t) override {
        metrics_counter_.increment("transitions.total");
        if (t.to_state == WorkflowState::FAILED)
            metrics_counter_.increment("transitions.failures");
    }
};
```

**Step 2** — add it to `ObserverFactory`:
```cpp
// Add to ObserverType enum:
enum class ObserverType { ALERT, AUDIT, METRICS };

// Add one case in ObserverFactory::create():
case ObserverType::METRICS:
    return std::make_shared<MetricsObserver>();
```

**Step 3** — use it via the factory (no other changes):
```cpp
engine.addObserver(ObserverFactory::create(ObserverType::METRICS));
```

---

## 10. Sample Output (Scenario 1 — Order Processing)

```
[ENGINE] Created workflow: Order Processing [WF1]

=== Workflow: Order Processing [WF1] ===
Overall Status: PENDING
-------------------------------------------------------
  [WF1_S1] Validate Order            => PENDING
  [WF1_S2] Process Payment           => PENDING
  [WF1_S3] Check Inventory           => PENDING
  [WF1_S4] Ship Order                => PENDING

[LOG] [2024-01-15 10:30:01] TID=T1 | WF=WF1 | STEP=WF1_S1 | PENDING -> RUNNING | by=order-service
[AUDIT] Recorded T1 (PENDING -> RUNNING) for step WF1_S1
[LOG] [2024-01-15 10:30:01] TID=T2 | WF=WF1 | STEP=WF1_S1 | RUNNING -> COMPLETED | by=order-service
...
[LOG] [2024-01-15 10:30:02] TID=T7 | WF=WF1 | STEP=WF1_S3 | RUNNING -> FAILED | by=inventory-service | meta={reason:out_of_stock, sku:SKU-9921}
[ALERT] *** Step WF1_S3 in workflow WF1 FAILED! (triggered by: inventory-service)
[AUDIT] Recorded T7 (RUNNING -> FAILED) for step WF1_S3
[LOG] [2024-01-15 10:30:02] TID=T8 | WF=WF1 | STEP=WF1_S3 | FAILED -> PENDING | by=retry-handler
...
[LOG] [2024-01-15 10:30:03] TID=T12 | WF=WF1 | STEP=WF1_S4 | RUNNING -> COMPLETED | by=shipping-service

=== Workflow: Order Processing [WF1] ===
Overall Status: COMPLETED
-------------------------------------------------------
  [WF1_S1] Validate Order            => COMPLETED
  [WF1_S2] Process Payment           => COMPLETED
  [WF1_S3] Check Inventory           => COMPLETED
  [WF1_S4] Ship Order                => COMPLETED

--- Transition history for step: WF1_S3 ---
  [2024-01-15 10:30:02] TID=T5 | PENDING -> RUNNING | by=inventory-service
  [2024-01-15 10:30:02] TID=T7 | RUNNING -> FAILED  | by=inventory-service | meta={reason:out_of_stock}
  [2024-01-15 10:30:02] TID=T8 | FAILED  -> PENDING | by=retry-handler
  [2024-01-15 10:30:02] TID=T9 | PENDING -> RUNNING | by=inventory-service
  [2024-01-15 10:30:03] TID=T10| RUNNING -> COMPLETED| by=inventory-service

[ENGINE] Transition rejected: Invalid transition for step 'Ship Order':
         COMPLETED -> RUNNING. Valid targets from COMPLETED: none (terminal state)
```

---

## 11. How to Compile and Run

```bash
# Compile with C++17
g++ -std=c++17 -O2 -pthread salesforce-workflow.cpp -o workflow_demo

# Run
./workflow_demo

# Output file
cat workflow_transitions.csv
```

---

## 12. Scalability Considerations (If Asked in Interview)

| Concern                  | Solution                                                                  |
|--------------------------|---------------------------------------------------------------------------|
| High write throughput    | Async logging with bounded queue; batch inserts to DB                    |
| Distributed workflows    | Replace atomic counter with Snowflake/UUID; use distributed KV store     |
| Millions of workflows    | Shard by `workflow_id`; use a message broker (Kafka) for transition events|
| Query by time range      | Index transitions on `(workflow_id, timestamp)` in a time-series DB     |
| Replay / audit           | Transition log is append-only; replaying all events rebuilds current state|
| Observer slow consumers  | Move to async observer fan-out with a thread pool or event queue          |
| Persistence              | Replace `InMemoryLogger` with `DatabaseLogger` via same interface         |
