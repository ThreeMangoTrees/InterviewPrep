# LLD: Flash Sale System

## Problem Statement

Design a Flash Sale System where products are offered at heavily discounted prices for a limited time with limited inventory. The system must handle high concurrency — thousands of users attempting to purchase simultaneously — without overselling, while enforcing sale lifecycle rules and notifying users of outcomes.

---

## 1. Requirements

### Functional Requirements

1. Create a **Flash Sale** with: product details, sale price, inventory quantity, start time, and end time
2. A sale has a **lifecycle**: `SCHEDULED → ACTIVE → ENDED / CANCELLED`
3. Users can place an **order** only when a sale is in ACTIVE state and within its time window
4. The system must **prevent overselling** — inventory must never go below zero, even under concurrent load
5. Each user can place **at most one order per sale** (deduplication)
6. Users can **cancel** a confirmed order; cancelled inventory is released and the user may reorder
7. Order failures must include a **reason**: `sold_out`, `duplicate_order`, `sale_not_started`, `sale_ended`, `sale_cancelled`
8. All state changes and order events are **broadcast to observers** (notifications, audit log, etc.)

### Non-Functional Requirements

- **Thread-safe** — concurrent `placeOrder()` calls must not oversell or corrupt state
- **Extensible** — new notification channels (SMS, Slack), new sale states, or new order flows must not require changes to core engine
- **Low coupling** — `FlashSaleEngine` does not know about concrete notification classes
- **Correctness over throughput** — no overselling under any concurrency level

---

## 2. State Machines

### Flash Sale State Machine

```
                   ┌──────────────────────────────┐
                   │          SCHEDULED           │
                   └──────────────┬───────────────┘
                                  │
                       ┌──────────┴──────────┐
                       ▼                     ▼
                    ACTIVE               CANCELLED
                       │                 (terminal)
               ┌───────┴───────┐
               ▼               ▼
             ENDED          CANCELLED
           (terminal)       (terminal)
```

### Valid Flash Sale Transition Table

| From State  | Allowed Transitions       | Notes                              |
|-------------|---------------------------|------------------------------------|
| SCHEDULED   | ACTIVE, CANCELLED         | Activated by scheduler or manually |
| ACTIVE      | ENDED, CANCELLED          | Normal expiry or manual cancel     |
| ENDED       | (none — terminal)         | Immutable once ended               |
| CANCELLED   | (none — terminal)         | Immutable once cancelled           |

### Order State Machine

```
    (created as)
     CONFIRMED ──────────────► CANCELLED
                (user cancels)
```

| From State  | Allowed Transitions | Notes                                    |
|-------------|---------------------|------------------------------------------|
| CONFIRMED   | CANCELLED           | User or system cancels the order         |
| CANCELLED   | (none — terminal)   | Inventory released, user may reorder     |

> Orders are created directly as CONFIRMED (not PENDING) because reservation is atomic — if `placeOrder()` returns an order, it is already confirmed. There is no intermediate pending state.

---

## 3. Core Data Model

### `FlashSaleItem` — product value object

```
FlashSaleItem {
    itemId          : string
    name            : string
    originalPrice   : double
    salePrice       : double
    discountPercent : double  (derived)
}
```

### `Inventory` — thread-safe stock counter

```
Inventory {
    total     : int               // original quantity
    remaining : AtomicInteger     // current remaining stock
}

Methods:
    tryReserve() : bool    // CAS loop — atomic decrement, returns false if sold out
    release()              // increment on cancellation
    isSoldOut() : bool
```

### `FlashSaleEvent` — the sale entity

```
FlashSaleEvent {
    saleId     : string
    item       : FlashSaleItem
    inventory  : Inventory
    startTime  : Instant
    endTime    : Instant
    state      : FlashSaleState   (mutable, guarded by ReentrantLock)
}
```

A sale `isAcceptingOrders()` only when:
- `state == ACTIVE`
- `now > startTime`
- `now < endTime`

### `Order` — purchase record

```
Order {
    orderId    : string    // globally unique (ORD-000001)
    saleId     : string
    userId     : string
    itemId     : string
    pricePaid  : double    // locked at sale price at order creation
    createdAt  : Instant
    status     : OrderStatus  (volatile — mutable)
}
```

---

## 4. Class Design

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              CLASS DIAGRAM                                       │
└──────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
  │   <<enum>>           │   │   <<enum>>           │   │   <<enum>>           │
  │   FlashSaleState     │   │   OrderStatus        │   │   NotificationType   │
  │──────────────────────│   │──────────────────────│   │──────────────────────│
  │  SCHEDULED           │   │  CONFIRMED           │   │  LOG                 │
  │  ACTIVE              │   │  CANCELLED           │   │  EMAIL               │
  │  ENDED               │   │  EXPIRED             │   └──────────────────────┘
  │  CANCELLED           │   └──────────────────────┘
  └──────────────────────┘

  ┌──────────────────────────┐      ┌───────────────────────────────────────────┐
  │     FlashSaleItem        │      │              Inventory                    │
  │  (value object)          │      │──────────────────────────────────────────│
  │──────────────────────────│      │  -total        : int                      │
  │  +itemId                 │      │  -remaining    : AtomicInteger            │
  │  +name                   │      │──────────────────────────────────────────│
  │  +originalPrice          │      │  +tryReserve() : bool  ← CAS loop        │
  │  +salePrice              │      │  +release()                              │
  │  +discountPercent()      │      │  +isSoldOut()  : bool                    │
  └──────────────────────────┘      └───────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                           FlashSaleEvent                                 │
  │──────────────────────────────────────────────────────────────────────────│
  │  +saleId          : string                                               │
  │  +item            : FlashSaleItem                                        │
  │  +inventory       : Inventory                                            │
  │  +startTime       : Instant                                              │
  │  +endTime         : Instant                                              │
  │  -state           : FlashSaleState  (volatile, guarded by ReentrantLock) │
  │  -VALID_TRANSITIONS : static Map  ← embedded state machine              │
  │──────────────────────────────────────────────────────────────────────────│
  │  +transitionTo(newState) : bool                                          │
  │  +isAcceptingOrders()    : bool                                          │
  │  +getState()             : FlashSaleState                                │
  └──────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                               Order                                      │
  │──────────────────────────────────────────────────────────────────────────│
  │  +orderId    : string                                                    │
  │  +saleId     : string                                                    │
  │  +userId     : string                                                    │
  │  +pricePaid  : double                                                    │
  │  +createdAt  : Instant                                                   │
  │  -status     : OrderStatus  (volatile)                                   │
  │──────────────────────────────────────────────────────────────────────────│
  │  +cancel()   : bool                                                      │
  │  +getStatus(): OrderStatus                                               │
  └──────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────┐
  │         <<interface>> INotificationObserver                │
  │────────────────────────────────────────────────────────────│
  │  +onOrderConfirmed(order, sale)                            │
  │  +onOrderFailed(userId, saleId, reason)                    │
  │  +onSaleStateChanged(sale, previousState)                  │
  │  +onSoldOut(sale)                                          │
  └────────────────────────┬───────────────────────────────────┘
                           │ implements
               ┌───────────┴───────────────┐
               ▼                           ▼
  ┌────────────────────────┐   ┌─────────────────────────┐
  │  LogNotification       │   │  EmailNotification      │
  │  Observer              │   │  Observer               │
  │────────────────────────│   │─────────────────────────│
  │  prints to stdout      │   │  simulates email send   │
  └────────────┬───────────┘   └────────────┬────────────┘
               │                            │
               └──────────────┬─────────────┘
                              │ creates
                   ┌──────────▼────────────┐
                   │  NotificationFactory  │  ← Factory Pattern
                   │───────────────────────│
                   │  +create(type)        │
                   │  : INotification      │
                   │    Observer           │
                   └───────────────────────┘

  ┌──────────────────────────────────┐
  │   OrderIdGenerator <<Singleton>> │
  │──────────────────────────────────│
  │  -counter : AtomicLong           │
  │──────────────────────────────────│
  │  +getInstance() : self           │
  │  +next()        : string         │
  └──────────────────────────────────┘

  ┌──────────────────────────────────┐
  │   SaleIdGenerator  <<Singleton>> │
  │──────────────────────────────────│
  │  -counter : AtomicInteger        │
  │──────────────────────────────────│
  │  +getInstance() : self           │
  │  +next()        : string         │
  └──────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                           FlashSaleEngine                                │
  │──────────────────────────────────────────────────────────────────────────│
  │  -sales         : ConcurrentHashMap<saleId, FlashSaleEvent>             │
  │  -ordersBySale  : ConcurrentHashMap<saleId, List<Order>>                │
  │  -userOrderedMap: ConcurrentHashMap<saleId, Set<userId>>  ← dedup       │
  │  -observers     : List<INotificationObserver>   ← observer pattern      │
  │──────────────────────────────────────────────────────────────────────────│
  │  +createSale(item, qty, start, end) : FlashSaleEvent  ← Factory Method  │
  │  +activateSale(saleId) : bool                                            │
  │  +endSale(saleId)      : bool                                            │
  │  +cancelSale(saleId)   : bool                                            │
  │  +placeOrder(saleId, userId) : Order                                     │
  │  +cancelOrder(saleId, orderId) : bool                                    │
  │  +addObserver(observer)                                                  │
  │  +getOrdersForSale(saleId) : List<Order>                                 │
  └──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Design Patterns Used

### Observer Pattern
**Where:** `FlashSaleEngine` notifies `INotificationObserver` implementations after every order event and sale state change.
**Why:** Completely decouples the engine from notification logic. Adding SMS, Slack, or metrics notifications requires zero changes to `FlashSaleEngine` — just implement the interface and register via `addObserver()`.

### Factory Pattern
**Where:** `NotificationFactory.create(NotificationType)` returns an `INotificationObserver`.
**Why:** Caller only specifies a token (`LOG`, `EMAIL`). Construction details (which class, which constructor arguments) are encapsulated in the factory. Adding a new channel (e.g., `SMS`) = add one enum value + one `case` in the factory.

```java
engine.addObserver(NotificationFactory.create(NotificationType.LOG));
engine.addObserver(NotificationFactory.create(NotificationType.EMAIL));
```

`FlashSaleEngine.createSale()` is also a **Factory Method** — it constructs `FlashSaleEvent` with an auto-generated ID, hiding the ID-generation detail from callers.

### Singleton Pattern
**Where:** `OrderIdGenerator` and `SaleIdGenerator`.
**Why:** There must be exactly one global ID sequence. Multiple instances would produce colliding IDs. Both use the **initialization-on-demand holder** idiom — lazy, thread-safe, and zero synchronization cost after first construction.

```java
// Correct: one sequence across all threads and workflows
String id = OrderIdGenerator.getInstance().next();
```

### State Machine Pattern
**Where:** `FlashSaleEvent` embeds a static `VALID_TRANSITIONS` map and enforces it in `transitionTo()`. `Order` has a simpler two-state machine in `cancel()`.
**Why:** Prevents illegal jumps (e.g., ENDED → ACTIVE). Centralizing transition rules in a single map means adding a new state (e.g., PAUSED) requires updating only that map — no scattered `if` checks.

---

## 6. Critical Design: Inventory — Preventing Overselling

The most important part of a flash sale system is preventing inventory from going below zero under concurrent load.

### The Race Condition

```
Thread 1: remaining = 1 → check: 1 > 0 → pass
Thread 2: remaining = 1 → check: 1 > 0 → pass
Thread 1: remaining-- → remaining = 0
Thread 2: remaining-- → remaining = -1  ← OVERSELL
```

### Solution: CAS (Compare-And-Set) Loop

```java
boolean tryReserve() {
    int current;
    do {
        current = remaining.get();
        if (current <= 0) return false;       // sold out — exit
    } while (!remaining.compareAndSet(current, current - 1));
    // compareAndSet succeeds ONLY if remaining still equals `current`
    // If another thread changed it, the loop retries with the new value
    return true;
}
```

- **No mutex needed** — `AtomicInteger.compareAndSet` is a CPU-level atomic instruction
- **No busy-spin in practice** — contention on the last few units is brief
- **Correct under all concurrency levels** — inventory is always ≥ 0

### Why Not `synchronized`?

```java
// Works but serializes ALL orders through one lock
synchronized (this) {
    if (remaining > 0) remaining--;
}
```

CAS is preferred because:
- It does not block threads that read non-conflicting data
- Under low contention (most orders), CAS succeeds on the first try
- Under high contention (last unit), the retry loop is still faster than a mutex

---

## 7. Sequence Diagram — placeOrder()

```
Caller          FlashSaleEngine    FlashSaleEvent    Inventory     INotificationObserver
  │                   │                  │               │                  │
  │──placeOrder()────►│                  │               │                  │
  │                   │──getSale()──────►│               │                  │
  │                   │◄─sale ref────────│               │                  │
  │                   │                  │               │                  │
  │                   │──isAccepting     │               │                  │
  │                   │  Orders()───────►│               │                  │
  │                   │◄─bool────────────│               │                  │
  │                   │                  │               │                  │
  │                   │──userOrdered     │               │                  │
  │                   │  Map.add(userId) │               │                  │
  │                   │  (atomic Set.add)│               │                  │
  │                   │                  │               │                  │
  │                   │──tryReserve()─────────────────── ►│                 │
  │                   │◄─bool────────────────────────────│                  │
  │                   │                  │               │                  │
  │                   │  (if reserved)   │               │                  │
  │                   │──new Order()     │               │                  │
  │                   │──orders.add()    │               │                  │
  │                   │──notifyObservers(onOrderConfirmed)────────────────► │
  │◄──Order ref───────│                  │               │                  │
  │                   │                  │               │                  │
  │                   │  (if not reserved / duplicate / inactive)           │
  │                   │──notifyObservers(onOrderFailed)────────────────────►│
  │◄──null────────────│                  │               │                  │
```

---

## 8. Key Design Decisions and Tradeoffs

### Decision 1: CAS vs. Synchronized for inventory

**Option A:** `synchronized` block on `Inventory`
**Option B:** `AtomicInteger.compareAndSet()` loop

**Choice:** CAS loop.

**Tradeoff:** CAS is lock-free and faster under low contention (the common case). Under extreme contention on the last item, there may be a few retries, but this is still faster than acquiring a mutex. A mutex serializes every single order, even when there is no actual conflict.

---

### Decision 2: Deduplication — Set vs. Database constraint

**Option A:** `ConcurrentHashMap.newKeySet()` (concurrent Set) per sale.
**Option B:** Rely on a DB UNIQUE constraint on `(saleId, userId)`.

**Choice:** Concurrent in-memory Set.

**Tradeoff:** Fast and works without a database. The `Set.add()` on a `ConcurrentHashMap.newKeySet()` is atomic — it returns `false` if the element already exists, making it a one-step check-and-insert. In production, both should be used (in-memory for speed, DB as the ultimate safeguard).

---

### Decision 3: Order starts as CONFIRMED, not PENDING

**Option A:** Orders start as PENDING, then move to CONFIRMED after a payment step.
**Option B:** Orders start directly as CONFIRMED.

**Choice:** CONFIRMED — because inventory reservation and order creation are atomic in this model. The order only exists if the reservation succeeded.

**Tradeoff:** In a real system with payment processing, you would need PENDING (reservation held) → payment initiated → CONFIRMED (payment succeeded) or FAILED/EXPIRED. The PENDING state with a timeout (e.g., 5 minutes to pay) would prevent users from holding inventory indefinitely.

---

### Decision 4: Notification fan-out — synchronous vs. asynchronous

**Choice:** Synchronous fan-out — observers are called inline in `placeOrder()`.

**Tradeoff:**
- **Synchronous:** Simple, no dropped events, but a slow email service blocks the order response.
- **Asynchronous (queue + worker threads):** Faster order response, but requires a bounded queue, worker pool, and handling of failed/slow consumers.

For production, use async: publish a `OrderPlacedEvent` to a message queue (Kafka, SQS), and let notification workers consume independently.

---

### Decision 5: Sale time-window check in `isAcceptingOrders()`

A sale in ACTIVE state should only accept orders if `now` is between `startTime` and `endTime`. This means:
- A sale can be pre-activated (set to ACTIVE before `startTime`) without accepting orders early
- Orders automatically stop being accepted when `endTime` passes, without needing an explicit `endSale()` call

**Tradeoff:** In a distributed system, clocks may skew slightly across servers. Production systems use a centralized time source or NTP, and add a small buffer (e.g., ±1 second grace) to avoid edge-case rejections at the boundary.

---

## 9. Thread Safety

| Class / Resource            | Mutable State                          | Guard                          |
|-----------------------------|----------------------------------------|--------------------------------|
| `Inventory.remaining`       | AtomicInteger                          | CAS (lock-free)                |
| `FlashSaleEvent.state`      | volatile + ReentrantLock in transition | per-sale lock                  |
| `Order.status`              | volatile field                         | no contention (user-scoped)    |
| `FlashSaleEngine.sales`     | ConcurrentHashMap                      | lock-free concurrent reads     |
| `FlashSaleEngine.ordersBySale` | ConcurrentHashMap of `synchronized List` | per-list synchronization    |
| `FlashSaleEngine.userOrderedMap` | ConcurrentHashMap of `ConcurrentHashSet` | lock-free atomic add        |
| `FlashSaleEngine.observers` | ArrayList                              | ReentrantLock on add/snapshot  |
| `OrderIdGenerator.counter`  | AtomicLong                             | atomic increment               |
| `SaleIdGenerator.counter`   | AtomicInteger                          | atomic increment               |

`notifyObservers()` in `FlashSaleEngine` takes a snapshot of the observer list before iterating, so the engine lock is not held while calling observer methods — preventing deadlock if an observer calls back into the engine.

---

## 10. Extensibility Guide

### Add a new notification channel (e.g., SMS)

**Step 1** — implement the interface:
```java
class SmsNotificationObserver implements INotificationObserver {
    public void onOrderConfirmed(Order order, FlashSaleEvent sale) {
        // send SMS to order.userId
    }
    // implement other methods...
}
```

**Step 2** — add to `NotificationFactory`:
```java
// Add to enum:
enum NotificationType { LOG, EMAIL, SMS }

// Add one case:
case SMS: return new SmsNotificationObserver();
```

**Step 3** — use via factory:
```java
engine.addObserver(NotificationFactory.create(NotificationType.SMS));
```

---

### Add a new sale state (e.g., PAUSED)

1. Add `PAUSED` to `FlashSaleState` enum
2. Add transitions in `FlashSaleEvent.VALID_TRANSITIONS`:
   - `ACTIVE → PAUSED`
   - `PAUSED → ACTIVE` (resume)
   - `PAUSED → CANCELLED`
3. Update `isAcceptingOrders()` to return false when `state == PAUSED`

No changes to `FlashSaleEngine`, `Order`, or any observer.

---

### Add a payment step (PENDING → CONFIRMED flow)

1. Add `PENDING` to `OrderStatus`
2. Change `Order` initial state to `PENDING`
3. Add `confirmOrder(orderId)` and `expireOrder(orderId)` to `FlashSaleEngine`
4. Add a scheduler that expires PENDING orders after a timeout and releases inventory

---

## 11. Sample Output

```
=== SCENARIO 1: Laptop Flash Sale (qty=3, happy path) ===
[ENGINE] Created  SALE-0001  MacBook Pro 14"   $1799.00 (28% off)  qty=3
[LOG]    Sale SALE-0001: SCHEDULED -> ACTIVE
[EMAIL]  FLASH SALE LIVE: 'MacBook Pro 14"' at $1799.00 (28% off) — hurry!
[ENGINE] Order[ORD-000001] sale=SALE-0001 user=user-alice  price=$1799.00 status=CONFIRMED
[LOG]    Order confirmed: ORD-000001 | 'MacBook Pro 14"' | user=user-alice
[EMAIL]  -> user-alice: Order ORD-000001 confirmed for 'MacBook Pro 14"' at $1799.00
...
[LOG]    SOLD OUT: 'MacBook Pro 14"' [SALE-0001]

=== SCENARIO 2: AirPods Sale (qty=2) — sells out ===
...
[LOG]    Order FAILED: user=user-carol sale=SALE-0002 reason=sold_out
[EMAIL]  -> user-carol: Your order for sale SALE-0002 failed (sold_out)

=== SCENARIO 3: Duplicate Order Prevention ===
[LOG]    Order FAILED: user=user-alice sale=SALE-0003 reason=duplicate_order

=== SCENARIO 4: Order Cancellation and Re-order ===
  Inventory before cancel: 8
[ENGINE] Order ORD-000007 cancelled, inventory released.
  Inventory after cancel:  9
[ENGINE] Order[ORD-000008] ...  status=CONFIRMED   ← re-order succeeds

=== SCENARIO 7: Invalid State Transition (ENDED -> ACTIVE) ===
[FlashSaleEvent] Invalid transition: ENDED -> ACTIVE (sale SALE-0003)
```

---

## 12. How to Compile and Run

```bash
# Navigate to the FlashSale directory
cd SalesforcePrep/FlashSale

# Compile
javac FlashSale.java

# Run
java FlashSale
```

---

## 13. Scalability Considerations (If Asked in Interview)

| Concern                        | Solution                                                                         |
|-------------------------------|----------------------------------------------------------------------------------|
| Millions of concurrent orders  | Distributed inventory counter (Redis DECR — atomic, single-threaded in Redis)  |
| Inventory in Redis             | `DECR inventory:saleId` returns new value; if < 0, undo with `INCR` and reject |
| Deduplication at scale         | Distributed Set in Redis (`SADD saleId:buyers userId` — returns 0 if exists)   |
| Notification fan-out           | Publish `OrderPlacedEvent` to Kafka; notification workers consume asynchronously|
| Sale activation scheduling     | Cron job or Kafka Streams timer event triggers `SCHEDULED → ACTIVE`             |
| Flash sale page load spike     | CDN caches the product page; only the `/placeOrder` endpoint hits the backend   |
| Preventing bot orders          | Rate limiting per user (token bucket), CAPTCHA at checkout                      |
| Order history queries          | Write orders to append-only DB (Cassandra/DynamoDB); query by `(saleId, userId)`|
| Sold-out broadcast             | Publish `SoldOutEvent` to Kafka; frontend subscribes via WebSocket for live UI  |
