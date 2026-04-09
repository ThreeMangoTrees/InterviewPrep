# LLD: Meeting Scheduler System

## Problem Statement

Design a Meeting Scheduler where users can book conference rooms for specific time slots. The system must handle high concurrency — two users may attempt to book the same room at the exact same millisecond — while preventing double-booking, enforcing room capacity limits, managing meeting lifecycle states, and notifying participants of booking outcomes.

---

## 1. Requirements

### Functional Requirements

1. Register **conference rooms** with a name, capacity, and amenity set (projector, whiteboard, video-conf, etc.)
2. **Book a room** for a time slot with a list of participants — only if no conflicting booking exists
3. A meeting has a **lifecycle**: `SCHEDULED → CANCELLED / COMPLETED` (terminal states)
4. The system must **prevent double-booking** — the same room cannot have two overlapping SCHEDULED meetings, even under concurrent load
5. **Capacity** must be respected — total headcount (participants + organizer) must not exceed room capacity
6. **Cancel** a meeting — only SCHEDULED meetings can be cancelled; cancelling frees the slot for re-booking
7. **Find available rooms** for a given time slot and minimum capacity requirement
8. All booking outcomes (success, conflict, failure, cancellation) are **broadcast to observers**

### Non-Functional Requirements

- **Thread-safe** — concurrent `bookRoom()` calls on the same room must result in at most one successful booking
- **Concurrent across rooms** — booking Room A must not block a concurrent booking of Room B
- **Extensible** — new notification channels (SMS, Slack, Teams) must not require changes to the engine
- **Low coupling** — `MeetingSchedulerEngine` must not depend on concrete observer implementations

---

## 2. State Machines

### Meeting State Machine

```
                  ┌──────────────────────────────┐
                  │          SCHEDULED           │
                  └──────────┬─────────┬─────────┘
                             │         │
                   ┌─────────▼──┐  ┌───▼──────────┐
                   │ CANCELLED  │  │  COMPLETED   │
                   │ (terminal) │  │  (terminal)  │
                   └────────────┘  └──────────────┘
```

### Valid Meeting Transition Table

| From State  | Allowed Transitions  | Notes                                          |
|-------------|----------------------|------------------------------------------------|
| SCHEDULED   | CANCELLED, COMPLETED | Normal cancel or mark meeting as done          |
| CANCELLED   | (none — terminal)    | Slot is freed; a new booking may use it        |
| COMPLETED   | (none — terminal)    | Historical record only                         |

> Meetings are created directly as SCHEDULED. If a booking attempt fails (conflict, capacity, etc.), no `Meeting` object is created at all — there is no intermediate PENDING state.

### Time Slot Overlap Rule

Two time slots A and B overlap when:

```
A.start < B.end  AND  A.end > B.start
```

Visualised:

```
A: |-------|
B:     |-------|   → overlap (A.start < B.end, A.end > B.start)

A: |---|
B:         |---|   → no overlap (A.end <= B.start — touching endpoints are NOT an overlap)
```

Touching endpoints (e.g., A ends at 10:00 and B starts at 10:00) are **not** an overlap. This allows back-to-back meetings.

---

## 3. Core Data Model

### `TimeSlot` — value object for time intervals

```
TimeSlot {
    start : Instant
    end   : Instant
}

Methods:
    isValid()              : bool   // end must be after start
    overlaps(other)        : bool   // interval overlap formula
    durationMinutes()      : long
```

### `Room` — the physical resource being booked

```
Room {
    roomId    : string
    name      : string
    capacity  : int
    amenities : Set<string>  // projector, whiteboard, video-conf, etc.
}
```

### `Meeting` — the booking record

```
Meeting {
    meetingId    : string       // globally unique (MTG-000001)
    roomId       : string
    organizerId  : string
    title        : string
    participants : List<string>  // immutable after creation
    timeSlot     : TimeSlot
    bookedAt     : Instant
    status       : MeetingStatus  (volatile — mutable)
}

Methods:
    cancel()   : bool   // SCHEDULED → CANCELLED
    complete() : bool   // SCHEDULED → COMPLETED
```

---

## 4. Class Design

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              CLASS DIAGRAM                                       │
└──────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────┐   ┌──────────────────────┐
  │   <<enum>>           │   │   <<enum>>           │
  │   MeetingStatus      │   │   NotificationType   │
  │──────────────────────│   │──────────────────────│
  │  SCHEDULED           │   │  LOG                 │
  │  CANCELLED           │   │  EMAIL               │
  │  COMPLETED           │   └──────────────────────┘
  └──────────────────────┘

  ┌──────────────────────────┐      ┌───────────────────────────────────────────┐
  │     TimeSlot             │      │              Room                         │
  │  (value object)          │      │──────────────────────────────────────────│
  │──────────────────────────│      │  +roomId     : string                    │
  │  +start : Instant        │      │  +name       : string                    │
  │  +end   : Instant        │      │  +capacity   : int                       │
  │──────────────────────────│      │  +amenities  : Set<string>               │
  │  +isValid()   : bool     │      └───────────────────────────────────────────┘
  │  +overlaps()  : bool     │
  │  +durationMinutes(): long│
  └──────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                               Meeting                                    │
  │──────────────────────────────────────────────────────────────────────────│
  │  +meetingId    : string                                                  │
  │  +roomId       : string                                                  │
  │  +organizerId  : string                                                  │
  │  +title        : string                                                  │
  │  +participants : List<string>  (immutable)                               │
  │  +timeSlot     : TimeSlot                                                │
  │  +bookedAt     : Instant                                                 │
  │  -status       : MeetingStatus  (volatile)                               │
  │──────────────────────────────────────────────────────────────────────────│
  │  +cancel()     : bool                                                    │
  │  +complete()   : bool                                                    │
  │  +getStatus()  : MeetingStatus                                           │
  └──────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────┐
  │          <<interface>> IMeetingObserver                    │
  │────────────────────────────────────────────────────────────│
  │  +onMeetingBooked(meeting, room)                           │
  │  +onMeetingCancelled(meeting, room)                        │
  │  +onBookingFailed(userId, roomId, reason)                  │
  │  +onRoomNotAvailable(userId, roomId, slot)                 │
  └────────────────────────┬───────────────────────────────────┘
                           │ implements
               ┌───────────┴───────────────┐
               ▼                           ▼
  ┌────────────────────────┐   ┌─────────────────────────┐
  │  LogMeetingObserver    │   │  EmailMeetingObserver   │
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
                   │  : IMeetingObserver   │
                   └───────────────────────┘

  ┌──────────────────────────────────────┐
  │   MeetingIdGenerator  <<Singleton>>  │
  │──────────────────────────────────────│
  │  -counter : AtomicLong               │
  │──────────────────────────────────────│
  │  +getInstance() : self               │
  │  +next()        : string  (MTG-...)  │
  └──────────────────────────────────────┘

  ┌──────────────────────────────────────┐
  │   RoomRegistry        <<Singleton>>  │
  │──────────────────────────────────────│
  │  -rooms : ConcurrentHashMap          │
  │──────────────────────────────────────│
  │  +getInstance() : self               │
  │  +register(room)                     │
  │  +find(roomId)  : Optional<Room>     │
  │  +all()         : Collection<Room>   │
  └──────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                        MeetingSchedulerEngine                            │
  │──────────────────────────────────────────────────────────────────────────│
  │  -registry     : RoomRegistry  (Singleton)                               │
  │  -bookings     : ConcurrentHashMap<roomId, List<Meeting>>                │
  │  -roomLocks    : ConcurrentHashMap<roomId, ReentrantLock>  ← KEY         │
  │  -userMeetings : ConcurrentHashMap<userId, List<Meeting>>                │
  │  -meetingsById : ConcurrentHashMap<meetingId, Meeting>                   │
  │  -observers    : List<IMeetingObserver>                                  │
  │──────────────────────────────────────────────────────────────────────────│
  │  +registerRoom(room)                                                     │
  │  +bookRoom(roomId, organizerId, title, participants, timeSlot): Meeting  │
  │  +cancelMeeting(meetingId) : bool                                        │
  │  +findAvailableRooms(slot, minCapacity) : List<Room>                     │
  │  +getMeetingsForRoom(roomId) : List<Meeting>                             │
  │  +getMeetingsForUser(userId) : List<Meeting>                             │
  │  +addObserver(observer)                                                  │
  └──────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Design Patterns Used

### Observer Pattern
**Where:** `MeetingSchedulerEngine` notifies `IMeetingObserver` implementations after every booking success, conflict, failure, or cancellation.
**Why:** Completely decouples the engine from notification logic. Adding Teams, Slack, or calendar integration requires zero changes to `MeetingSchedulerEngine` — just implement the interface and register via `addObserver()`.

### Factory Pattern
**Where:** `NotificationFactory.create(NotificationType)` returns an `IMeetingObserver`.
**Why:** Caller specifies only a token (`LOG`, `EMAIL`). Construction details are encapsulated in the factory. Adding a new channel (e.g., `SLACK`) = add one enum value + one `case` in the factory.

```java
engine.addObserver(NotificationFactory.create(NotificationType.LOG));
engine.addObserver(NotificationFactory.create(NotificationType.EMAIL));
```

### Singleton Pattern
**Where:** `MeetingIdGenerator` (one global ID sequence) and `RoomRegistry` (one central room catalog shared across all engine instances).
**Why:**
- `MeetingIdGenerator` must be unique system-wide — two instances would produce colliding IDs.
- `RoomRegistry` represents a physical fact: rooms exist independently of any engine instance. Multiple engines (or engine restarts) must see the same room definitions.

Both use the **initialization-on-demand holder** idiom — lazy, thread-safe, zero synchronization overhead after first construction.

```java
private static class Holder {
    static final MeetingIdGenerator INSTANCE = new MeetingIdGenerator();
}
public static MeetingIdGenerator getInstance() { return Holder.INSTANCE; }
```

### State Machine Pattern
**Where:** `Meeting.cancel()` and `Meeting.complete()` enforce the valid transition rules before mutating state.
**Why:** Prevents illegal transitions (e.g., completing an already-cancelled meeting). The rules are co-located with the data they protect.

---

## 6. Critical Design: Preventing Simultaneous Double-Booking

This is the core interview question:

> **"What if two people book the same room at the exact millisecond?"**

### The Race Condition Without Locking

```
Thread A (user-X):  read bookings for R1 → empty → no conflict
Thread B (user-Y):  read bookings for R1 → empty → no conflict  (reads stale — A hasn't written yet)
Thread A:           write booking for R1 / 9-10am
Thread B:           write booking for R1 / 9-10am   ← DOUBLE BOOKING
```

### Solution: Per-Room ReentrantLock

Each room gets its own dedicated `ReentrantLock` when it is registered:

```java
private final ConcurrentHashMap<String, ReentrantLock> roomLocks = new ConcurrentHashMap<>();

void registerRoom(Room room) {
    registry.register(room);
    bookings.put(room.roomId, Collections.synchronizedList(new ArrayList<>()));
    roomLocks.put(room.roomId, new ReentrantLock());   // one lock per room
}
```

The booking pipeline acquires the room's lock before the conflict check:

```java
ReentrantLock roomLock = roomLocks.get(roomId);
roomLock.lock();
try {
    // Conflict check is safe — we exclusively own this room's data
    for (Meeting m : existing) {
        if (m.getStatus() == MeetingStatus.SCHEDULED && m.timeSlot.overlaps(timeSlot)) {
            // CONFLICT — notify and return null
            return null;
        }
    }
    // No conflict — create and persist the meeting
    Meeting meeting = new Meeting(...);
    existing.add(meeting);
    return meeting;
} finally {
    roomLock.unlock();
}
```

### What Happens When Two Threads Race

```
Thread A (user-X) and Thread B (user-Y) both call bookRoom("R1", ...) simultaneously.

Step 1: Both reach roomLock.lock().
Step 2: One thread (say A) wins the OS lock arbitration — B blocks and WAITS.
Step 3: A scans bookings, finds no conflict, writes the booking, calls roomLock.unlock().
Step 4: B unblocks, acquires the lock, scans bookings — finds A's booking overlapping.
Step 5: B returns null (CONFLICT). Only one booking was created.

Result: Exactly 1 success, 1 rejection. No double-booking possible.
```

### Why Per-Room Lock and Not One Global Lock?

```
Global lock:    bookRoom("R1") blocks bookRoom("R2") — completely independent rooms
                are serialized. Under load, all booking threads queue up behind one lock.

Per-room lock:  bookRoom("R1") and bookRoom("R2") proceed in PARALLEL.
                Only bookings targeting the same room are serialized.
                This is optimal — contention only where resources actually conflict.
```

---

## 7. Sequence Diagram — bookRoom()

```
Caller            MeetingSchedulerEngine   RoomRegistry    ReentrantLock    IMeetingObserver
  │                        │                    │               │                  │
  │──bookRoom()───────────►│                    │               │                  │
  │                        │──registry.find()──►│               │                  │
  │                        │◄─Optional<Room>────│               │                  │
  │                        │                    │               │                  │
  │                        │  validate: slot, capacity          │                  │
  │                        │                    │               │                  │
  │                        │──roomLock.lock()──────────────────►│                  │
  │                        │  (Thread B blocks here if racing)  │                  │
  │                        │                    │               │                  │
  │                        │  scan bookings for overlap         │                  │
  │                        │                    │               │                  │
  │                        │  (if conflict)     │               │                  │
  │                        │──roomLock.unlock()────────────────►│                  │
  │◄──null─────────────────│──notifyRoomNotAvailable()─────────────────────────── ►│
  │                        │                    │               │                  │
  │                        │  (if no conflict)  │               │                  │
  │                        │──new Meeting()     │               │                  │
  │                        │──bookings.add()    │               │                  │
  │                        │──meetingsById.put()│               │                  │
  │                        │──roomLock.unlock()────────────────►│                  │
  │                        │  (lock released BEFORE notify)     │                  │
  │                        │──onMeetingBooked()─────────────────────────────────── ►│
  │◄──Meeting ref──────────│                    │               │                  │
```

> **Critical:** The room lock is released **before** observers are notified. This prevents a deadlock if an observer tried to call back into `bookRoom()` for the same room.

---

## 8. Sequence Diagram — Simultaneous Booking Race (Scenario 8)

```
main thread          Thread X (user-X)           Thread Y (user-Y)
     │                      │                           │
     │  ready.await() ◄─────┤ ready.countDown()         │
     │  ready.await() ◄─────┼───────────────────────────┤ ready.countDown()
     │                      │                           │
     │  go.countDown() ─────┼───────────────────────────┤─── (both unblock)
     │                      │                           │
     │                      │◄── roomLock("R3") ────────┤
     │                      │    ONE wins, ONE waits    │
     │                      │                           │
     │                      │  scan → no conflict       │
     │                      │  write booking            │
     │                      │  unlock                   │
     │                      │  notify → win++           │
     │                      │                           │ acquire lock
     │                      │                           │ scan → conflict found
     │                      │                           │ unlock
     │                      │                           │ notify → loss++
     │                      │                           │
     │  Result: wins=1, losses=1 (always)               │
```

This scenario is reproduced in code using `CountDownLatch`:
- `ready` latch ensures both threads are parked at `go.await()` before the starting gun fires
- `go` latch fires simultaneously — maximizing the chance of a true race
- Result is deterministically: exactly 1 success, 1 conflict

---

## 9. Key Design Decisions and Tradeoffs

### Decision 1: Per-Room Lock vs. Global Lock

**Option A:** One `ReentrantLock` for all booking operations.
**Option B:** One `ReentrantLock` per room (stored in `ConcurrentHashMap<roomId, ReentrantLock>`).

**Choice:** Per-room lock.

**Tradeoff:** A global lock is simpler but serializes all bookings — booking Room A prevents concurrent booking of Room B. Per-room lock is slightly more complex (must manage a map of locks) but allows full parallelism across rooms, with contention only where it is logically required (same room).

---

### Decision 2: Interval Overlap Formula

**Formula used:** `A.start < B.end AND A.end > B.start`

**Why strict inequalities?** Back-to-back meetings (A ends at 10:00, B starts at 10:00) should be allowed. Using `<=` would incorrectly flag these as conflicts.

**Why this formula and not a simpler check?** A simpler check like "A.start >= B.start AND A.start < B.end" misses cases where A fully contains B or B fully contains A. The two-condition formula covers all overlap cases:
- Partial overlap: A starts before B ends, and A ends after B starts
- Full containment (either direction): both conditions still hold

---

### Decision 3: Notify Observers Outside the Lock

**Why:** If an observer's `onMeetingBooked()` called `bookRoom()` for the same room (e.g., to create a follow-up meeting), it would attempt to acquire a lock that the same thread already holds.

`ReentrantLock` is *reentrant* — the same thread can re-acquire it — so it would not deadlock. However, the design comment documents the intent: observers should not call back into the engine during notification. Releasing the lock first enforces this as a structural constraint (non-reentrant locks would deadlock, making the constraint enforceable).

Additionally, holding a room lock while calling external code (email send, HTTP call) would increase lock contention unnecessarily.

---

### Decision 4: `Meeting.status` is `volatile`, Not Guarded by the Room Lock

`cancel()` and `complete()` update `status` directly on the `Meeting` object. These calls happen outside of any room lock. The `volatile` keyword ensures that any thread that reads `status` sees the latest written value without requiring synchronization.

**Why not use the room lock?** Cancellation does acquire the room lock (to prevent a cancel racing with a re-book). But the `status` field itself is volatile so that status reads elsewhere (e.g., in `findAvailableRooms()`) are always current without acquiring any lock.

---

### Decision 5: `findAvailableRooms()` Acquires Each Room Lock

Reading a room's bookings without its lock could see a partially-committed write (a booking just started being written but not yet fully added to the list). By acquiring the lock during the availability scan, we get a consistent snapshot.

**Tradeoff:** This means `findAvailableRooms()` sequentially locks each room. Under high concurrency with many rooms, this is a scan-time vs. correctness tradeoff. For production, a read-write lock (`ReadWriteLock`) would allow concurrent readers with exclusive writers.

---

## 10. Thread Safety

| Class / Resource                       | Mutable State              | Guard                                      |
|----------------------------------------|----------------------------|--------------------------------------------|
| `Meeting.status`                       | volatile field             | volatile visibility; cancel acquires lock  |
| `MeetingSchedulerEngine.bookings`      | ConcurrentHashMap + sync List | per-room ReentrantLock for writes       |
| `MeetingSchedulerEngine.roomLocks`     | ConcurrentHashMap          | ConcurrentHashMap guarantees on put/get    |
| `MeetingSchedulerEngine.userMeetings`  | ConcurrentHashMap + sync List | computeIfAbsent is atomic              |
| `MeetingSchedulerEngine.meetingsById`  | ConcurrentHashMap          | lock-free concurrent reads/writes          |
| `MeetingSchedulerEngine.observers`     | ArrayList                  | separate `obsLock` ReentrantLock           |
| `MeetingIdGenerator.counter`           | AtomicLong                 | atomic increment (lock-free)               |
| `RoomRegistry.rooms`                   | ConcurrentHashMap          | lock-free concurrent reads                 |

Observer notification in `bookRoom()` takes a snapshot of the observer list (under `obsLock`) before iterating, so the room lock is never held while calling observer methods — preventing any potential for deadlock or prolonged contention.

---

## 11. Extensibility Guide

### Add a new notification channel (e.g., Slack)

**Step 1** — implement the interface:
```java
class SlackMeetingObserver implements IMeetingObserver {
    public void onMeetingBooked(Meeting m, Room r) {
        // post to Slack channel: "Meeting '" + m.title + "' booked in " + r.name
    }
    public void onMeetingCancelled(Meeting m, Room r) { ... }
    public void onBookingFailed(String userId, String roomId, String reason) { ... }
    public void onRoomNotAvailable(String userId, String roomId, TimeSlot slot) { ... }
}
```

**Step 2** — add to `NotificationFactory`:
```java
// Add to enum:
enum NotificationType { LOG, EMAIL, SLACK }

// Add one case:
case SLACK: return new SlackMeetingObserver();
```

**Step 3** — register with the engine:
```java
engine.addObserver(NotificationFactory.create(NotificationType.SLACK));
```

No changes to `MeetingSchedulerEngine`, `Meeting`, `Room`, or any other observer.

---

### Add a new meeting state (e.g., RESCHEDULED)

1. Add `RESCHEDULED` to `MeetingStatus` enum
2. Add `reschedule(TimeSlot newSlot)` to `Meeting` — transitions `SCHEDULED → RESCHEDULED`, updates the time slot under the room lock
3. Add `onMeetingRescheduled(meeting, room, oldSlot)` to `IMeetingObserver`
4. Update `MeetingSchedulerEngine.cancelMeeting()` analog to handle re-conflict checks

---

### Upgrade to ReadWriteLock for higher concurrency

Replace `ReentrantLock` with `ReentrantReadWriteLock` in `roomLocks`:
- `findAvailableRooms()` acquires the **read lock** — multiple threads can scan concurrently
- `bookRoom()` and `cancelMeeting()` acquire the **write lock** — exclusive access during writes

This improves throughput for read-heavy workloads (many availability queries, few bookings).

---

## 12. Sample Output

```
================================================
       Meeting Scheduler System — Demo
================================================

[Registry] Registered: Room[R1] Boardroom            cap=10  amenities=[projector, video-conf, whiteboard]
[Registry] Registered: Room[R2] Focus Room            cap=4   amenities=[whiteboard]
[Registry] Registered: Room[R3] Training Room         cap=20  amenities=[projector, whiteboard]
[Registry] Registered: Room[R4] Huddle Space          cap=2   amenities=[]

=== SCENARIO 1: Normal bookings ===
[ENGINE] Meeting[MTG-000001          ] room=R1       slot=09:00 - 10:00  organizer=alice        title='Q2 Planning' status=SCHEDULED
[LOG]    Booked: 'Q2 Planning' in Boardroom [09:00 - 10:00] by alice
[EMAIL]  -> bob: Invite for 'Q2 Planning' in Boardroom at [09:00 - 10:00]
[EMAIL]  -> carol: Invite for 'Q2 Planning' in Boardroom at [09:00 - 10:00]
...

=== SCENARIO 2: Conflict — overlapping slot ===
[ENGINE] CONFLICT: user=dave         room=R1      slot=09:00 - 11:00
[LOG]    Room R1 not available at [09:00 - 11:00] for user=dave
[EMAIL]  -> dave: Room R1 is taken at [09:00 - 11:00]. Please choose another.

=== SCENARIO 3: Capacity exceeded ===
[ENGINE] Booking REJECTED: user=alice        room=R4      reason=capacity_exceeded (5 > 2)
[LOG]    Booking FAILED: user=alice room=R4 reason=capacity_exceeded (5 > 2)

=== SCENARIO 4: Invalid time slot (end before start) ===
[ENGINE] Booking REJECTED: user=bob          room=R2      reason=invalid_time_slot

=== SCENARIO 5: Room not found ===
[ENGINE] Booking REJECTED: user=alice        room=R99     reason=room_not_found

=== SCENARIO 6: Cancel and re-book ===
  Cancelling m1 (Q2 Planning)...
[ENGINE] Cancelled: Meeting[MTG-000001] ...
[LOG]    Cancelled: 'Q2 Planning' in Boardroom [09:00 - 10:00]
[EMAIL]  -> bob: 'Q2 Planning' has been CANCELLED
[ENGINE] Meeting[MTG-000004] room=R1 slot=09:00 - 10:00 organizer=frank title='Product Roadmap' status=SCHEDULED

=== SCENARIO 7: Find available rooms for s11_12, minCapacity=5 ===
  Available rooms:
    Room[R1] Boardroom   cap=10  amenities=[projector, video-conf, whiteboard]
    Room[R3] Training Room cap=20 amenities=[projector, whiteboard]

=== SCENARIO 8: Simultaneous booking — same room, same slot ===
  Two threads racing to book R3 / 11:00-12:00...

[ENGINE] CONFLICT: user=user-Y ...     ← loser
[ENGINE] Meeting[MTG-000005] ...       ← winner

  Result: 1 booking succeeded, 1 was rejected
  (always: 1 win, 1 reject — no double booking possible)
```

---

## 13. How to Compile and Run

```bash
# Navigate to the MeetingScheduler directory
cd SystemDesignAdvancedOOPS/MeetingScheduler

# Compile
javac MeetingScheduler.java

# Run
java MeetingScheduler
```

---

## 14. Scalability Considerations (If Asked in Interview)

| Concern                              | Solution                                                                             |
|--------------------------------------|--------------------------------------------------------------------------------------|
| Millions of concurrent bookings      | Shard rooms across servers; each server owns a subset of rooms                       |
| Distributed double-booking prevention| Redis distributed lock (`SET roomId:slot NX EX 10`) — only one node can write a slot |
| Room availability queries            | Cache availability in Redis sorted set; invalidate on booking/cancellation           |
| Participant notification at scale    | Publish `MeetingBookedEvent` to Kafka; email/push workers consume asynchronously     |
| Meeting ID generation in distributed | Snowflake IDs (41-bit timestamp + 10-bit machineId + 12-bit sequence)               |
| Calendar sync (Google/Outlook)       | Webhook listener subscribes to calendar events; engine updates bookings accordingly  |
| Time zone handling                   | Store all times in UTC; convert to user's local timezone only at display layer       |
| Room booking analytics               | Write all events to append-only store (Kafka → S3); Spark/Flink for utilization reports |
| Recurring meetings                   | Store recurrence rule (RFC 5545 RRULE); expand to concrete `Meeting` instances on demand |
| Conflicting timezone edge cases      | Enforce DST-aware overlap check using `ZonedDateTime.toInstant()` for all comparisons |
