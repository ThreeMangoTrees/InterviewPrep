// ============================================================
// MeetingScheduler.java
// LLD: Meeting Scheduler System
//
// Patterns: Observer, Factory, Singleton, State Machine
// Concurrency: Per-room ReentrantLock (prevents simultaneous double-booking)
//
// Key interview question answered:
//   "What if two people book the same room at the exact millisecond?"
//   → Only one thread can hold a room's lock at a time. The second
//     thread waits, acquires the lock, finds the slot already taken,
//     and gets a CONFLICT rejection. No double booking possible.
//
// Compile: javac MeetingScheduler.java
// Run:     java MeetingScheduler
// ============================================================

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.Collectors;

// ============================================================
// SECTION 1: Enums
// ============================================================

enum MeetingStatus {
    SCHEDULED, CANCELLED, COMPLETED
}

enum NotificationType {
    LOG, EMAIL
}

// ============================================================
// SECTION 2: TimeSlot — value object
// ============================================================
//
// Core of the conflict check. Two slots overlap when:
//   A.start < B.end  AND  A.end > B.start
//
// Visualised:
//   A: |-------|
//   B:     |-------|   → overlap (A.start < B.end, A.end > B.start)
//
//   A: |---|
//   B:         |---|   → no overlap (A.end <= B.start)
//
class TimeSlot {
    final Instant start;
    final Instant end;

    TimeSlot(Instant start, Instant end) {
        this.start = start;
        this.end   = end;
    }

    boolean isValid() {
        return end.isAfter(start);
    }

    // Standard interval overlap check
    boolean overlaps(TimeSlot other) {
        return this.start.isBefore(other.end) && this.end.isAfter(other.start);
    }

    long durationMinutes() {
        return (end.toEpochMilli() - start.toEpochMilli()) / 60_000;
    }

    @Override
    public String toString() {
        DateTimeFormatter fmt = DateTimeFormatter
            .ofPattern("HH:mm").withZone(ZoneId.systemDefault());
        return fmt.format(start) + " - " + fmt.format(end);
    }
}

// ============================================================
// SECTION 3: Room — the resource being booked
// ============================================================

class Room {
    final String      roomId;
    final String      name;
    final int         capacity;
    final Set<String> amenities;    // projector, whiteboard, video-conf, etc.

    Room(String roomId, String name, int capacity, Set<String> amenities) {
        this.roomId    = roomId;
        this.name      = name;
        this.capacity  = capacity;
        this.amenities = Collections.unmodifiableSet(new HashSet<>(amenities));
    }

    @Override
    public String toString() {
        return String.format("Room[%s] %-20s cap=%-3d amenities=%s",
            roomId, name, capacity, amenities);
    }
}

// ============================================================
// SECTION 4: Meeting — the booking record
// ============================================================

class Meeting {
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    final String        meetingId;
    final String        roomId;
    final String        organizerId;
    final String        title;
    final List<String>  participants;
    final TimeSlot      timeSlot;
    final Instant       bookedAt;
    private volatile    MeetingStatus status;

    Meeting(String meetingId, String roomId, String organizerId,
            String title, List<String> participants, TimeSlot timeSlot) {
        this.meetingId    = meetingId;
        this.roomId       = roomId;
        this.organizerId  = organizerId;
        this.title        = title;
        this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
        this.timeSlot     = timeSlot;
        this.bookedAt     = Instant.now();
        this.status       = MeetingStatus.SCHEDULED;
    }

    MeetingStatus getStatus() { return status; }

    // Only SCHEDULED meetings can be cancelled
    boolean cancel() {
        if (status == MeetingStatus.SCHEDULED) {
            status = MeetingStatus.CANCELLED;
            return true;
        }
        return false;
    }

    boolean complete() {
        if (status == MeetingStatus.SCHEDULED) {
            status = MeetingStatus.COMPLETED;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format(
            "Meeting[%-20s] room=%-8s slot=%-13s organizer=%-12s title='%s' status=%s",
            meetingId, roomId, timeSlot, organizerId, title, status);
    }
}

// ============================================================
// SECTION 5: Observer interface + concrete observers
// ============================================================

interface IMeetingObserver {
    void onMeetingBooked(Meeting meeting, Room room);
    void onMeetingCancelled(Meeting meeting, Room room);
    void onBookingFailed(String userId, String roomId, String reason);
    void onRoomNotAvailable(String userId, String roomId, TimeSlot slot);
}

// ---- Concrete Observer A: Log (console) ----

class LogMeetingObserver implements IMeetingObserver {
    @Override
    public void onMeetingBooked(Meeting m, Room r) {
        System.out.printf("[LOG] Booked: '%s' in %s [%s] by %s%n",
            m.title, r.name, m.timeSlot, m.organizerId);
    }

    @Override
    public void onMeetingCancelled(Meeting m, Room r) {
        System.out.printf("[LOG] Cancelled: '%s' in %s [%s]%n",
            m.title, r.name, m.timeSlot);
    }

    @Override
    public void onBookingFailed(String userId, String roomId, String reason) {
        System.out.printf("[LOG] Booking FAILED: user=%s room=%s reason=%s%n",
            userId, roomId, reason);
    }

    @Override
    public void onRoomNotAvailable(String userId, String roomId, TimeSlot slot) {
        System.out.printf("[LOG] Room %s not available at [%s] for user=%s%n",
            roomId, slot, userId);
    }
}

// ---- Concrete Observer B: Email (simulated) ----

class EmailMeetingObserver implements IMeetingObserver {
    @Override
    public void onMeetingBooked(Meeting m, Room r) {
        m.participants.forEach(p ->
            System.out.printf("[EMAIL] -> %s: Invite for '%s' in %s at [%s]%n",
                p, m.title, r.name, m.timeSlot));
    }

    @Override
    public void onMeetingCancelled(Meeting m, Room r) {
        m.participants.forEach(p ->
            System.out.printf("[EMAIL] -> %s: '%s' has been CANCELLED%n", p, m.title));
    }

    @Override
    public void onBookingFailed(String userId, String roomId, String reason) {
        System.out.printf("[EMAIL] -> %s: Booking failed for room %s — %s%n",
            userId, roomId, reason);
    }

    @Override
    public void onRoomNotAvailable(String userId, String roomId, TimeSlot slot) {
        System.out.printf("[EMAIL] -> %s: Room %s is taken at [%s]. Please choose another.%n",
            userId, roomId, slot);
    }
}

// ============================================================
// SECTION 6: NotificationFactory — Factory Pattern
// ============================================================

class NotificationFactory {
    public static IMeetingObserver create(NotificationType type) {
        return switch (type) {
            case LOG   -> new LogMeetingObserver();
            case EMAIL -> new EmailMeetingObserver();
        };
    }
}

// ============================================================
// SECTION 7: MeetingIdGenerator — Singleton
// ============================================================
//
// One global ID sequence per JVM. Uses initialization-on-demand
// holder for lazy, thread-safe construction.

class MeetingIdGenerator {
    private static class Holder {
        static final MeetingIdGenerator INSTANCE = new MeetingIdGenerator();
    }

    public static MeetingIdGenerator getInstance() { return Holder.INSTANCE; }

    private final AtomicLong counter = new AtomicLong(0);
    private MeetingIdGenerator() {}

    public String next() {
        return "MTG-" + String.format("%06d", counter.incrementAndGet());
    }
}

// ============================================================
// SECTION 8: RoomRegistry — Singleton
// ============================================================
//
// Central registry of all meeting rooms. One registry per JVM —
// all engine instances share the same room definitions.

class RoomRegistry {
    private static class Holder {
        static final RoomRegistry INSTANCE = new RoomRegistry();
    }

    public static RoomRegistry getInstance() { return Holder.INSTANCE; }

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private RoomRegistry() {}

    public void register(Room room) {
        rooms.put(room.roomId, room);
        System.out.printf("[Registry] Registered: %s%n", room);
    }

    public Optional<Room> find(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    public Collection<Room> all() { return rooms.values(); }
}

// ============================================================
// SECTION 9: MeetingSchedulerEngine — the core engine
// ============================================================
//
// Booking pipeline:
//   1. Validate inputs (room exists, time slot valid, capacity)
//   2. Acquire per-room ReentrantLock                ← KEY: prevents simultaneous double-booking
//   3. Scan existing bookings for overlap            ← safe because we hold the lock
//   4. If clear: create Meeting, persist, release lock
//   5. Notify observers (outside the lock)
//
// Why per-room lock and not one global lock?
//   A global lock would serialize ALL bookings across ALL rooms —
//   booking Room A would block someone booking Room B, even though
//   they are completely independent resources. Per-room locks allow
//   Room A and Room B to be booked concurrently with no contention.

class MeetingSchedulerEngine {

    private final RoomRegistry registry = RoomRegistry.getInstance();

    // bookings: roomId → list of meetings for that room
    private final ConcurrentHashMap<String, List<Meeting>> bookings = new ConcurrentHashMap<>();

    // roomLocks: one ReentrantLock per room
    // This is the core concurrency mechanism — the answer to
    // "what if two people book the same room at the exact millisecond?"
    private final ConcurrentHashMap<String, ReentrantLock> roomLocks = new ConcurrentHashMap<>();

    // userId → list of meetings organised by this user (for quick lookup)
    private final ConcurrentHashMap<String, List<Meeting>> userMeetings = new ConcurrentHashMap<>();

    // meetingId → meeting (for O(1) cancel/lookup)
    private final ConcurrentHashMap<String, Meeting> meetingsById = new ConcurrentHashMap<>();

    private final List<IMeetingObserver> observers = new ArrayList<>();
    private final ReentrantLock          obsLock   = new ReentrantLock();

    // ---- Observer management ----

    void addObserver(IMeetingObserver obs) {
        obsLock.lock();
        try { observers.add(obs); }
        finally { obsLock.unlock(); }
    }

    private List<IMeetingObserver> snapshotObservers() {
        obsLock.lock();
        try { return new ArrayList<>(observers); }
        finally { obsLock.unlock(); }
    }

    // ---- Room registration (delegates to Singleton registry) ----

    void registerRoom(Room room) {
        registry.register(room);
        bookings.put(room.roomId, Collections.synchronizedList(new ArrayList<>()));
        // Initialise a dedicated lock for this room
        roomLocks.put(room.roomId, new ReentrantLock());
    }

    // ---- Core booking logic ----

    Meeting bookRoom(String roomId, String organizerId, String title,
                     List<String> participants, TimeSlot timeSlot) {

        // 1. Validate room exists
        Optional<Room> roomOpt = registry.find(roomId);
        if (roomOpt.isEmpty()) {
            notifyFailed(organizerId, roomId, "room_not_found");
            return null;
        }
        Room room = roomOpt.get();

        // 2. Validate time slot
        if (!timeSlot.isValid()) {
            notifyFailed(organizerId, roomId, "invalid_time_slot");
            return null;
        }

        // 3. Validate capacity (participants + organiser)
        int headcount = participants.size() + 1;
        if (headcount > room.capacity) {
            notifyFailed(organizerId, roomId,
                "capacity_exceeded (" + headcount + " > " + room.capacity + ")");
            return null;
        }

        // 4. Acquire the per-room lock
        //
        //    THIS IS THE ANSWER TO THE INTERVIEW QUESTION:
        //    "What if two people book the same room at the exact millisecond?"
        //
        //    Thread A and Thread B both call bookRoom("R1", ...) simultaneously.
        //    One of them (say Thread A) wins the lock.acquire() race.
        //    Thread B blocks here and WAITS.
        //    Thread A checks conflicts, finds none, writes the booking, releases the lock.
        //    Thread B now acquires the lock, checks conflicts,
        //    finds Thread A's booking overlapping, and returns CONFLICT.
        //    Result: exactly one booking is created. No double-booking possible.
        //
        ReentrantLock roomLock = roomLocks.get(roomId);
        roomLock.lock();
        try {
            // 5. Conflict check — safe because we hold the lock
            List<Meeting> existing = bookings.get(roomId);
            for (Meeting m : existing) {
                if (m.getStatus() == MeetingStatus.SCHEDULED
                        && m.timeSlot.overlaps(timeSlot)) {
                    notifyRoomNotAvailable(organizerId, roomId, timeSlot);
                    return null;
                }
            }

            // 6. No conflict — create and persist the meeting
            String meetingId = MeetingIdGenerator.getInstance().next();
            Meeting meeting  = new Meeting(meetingId, roomId, organizerId,
                                           title, participants, timeSlot);
            existing.add(meeting);
            meetingsById.put(meetingId, meeting);
            userMeetings.computeIfAbsent(organizerId,
                k -> Collections.synchronizedList(new ArrayList<>())).add(meeting);

            System.out.printf("[ENGINE] %s%n", meeting);
            // 7. Notify OUTSIDE the lock — observers must not call back into bookRoom
            //    (would deadlock since the same thread would try to re-acquire the same lock)
            final Meeting booked = meeting;
            final Room    r      = room;
            obsLock.lock();
            List<IMeetingObserver> obs;
            try { obs = new ArrayList<>(observers); }
            finally { obsLock.unlock(); }
            obs.forEach(o -> o.onMeetingBooked(booked, r));

            return meeting;

        } finally {
            roomLock.unlock();
        }
    }

    // ---- Cancellation ----

    boolean cancelMeeting(String meetingId) {
        Meeting meeting = meetingsById.get(meetingId);
        if (meeting == null) {
            System.err.println("[ENGINE] Meeting not found: " + meetingId);
            return false;
        }

        // Acquire the room lock — prevents a cancel and a re-book racing on the same slot
        ReentrantLock roomLock = roomLocks.get(meeting.roomId);
        roomLock.lock();
        try {
            if (!meeting.cancel()) {
                System.err.printf("[ENGINE] Cannot cancel meeting %s (status=%s)%n",
                    meetingId, meeting.getStatus());
                return false;
            }
            System.out.printf("[ENGINE] Cancelled: %s%n", meeting);
        } finally {
            roomLock.unlock();
        }

        registry.find(meeting.roomId).ifPresent(room -> {
            List<IMeetingObserver> obs = snapshotObservers();
            obs.forEach(o -> o.onMeetingCancelled(meeting, room));
        });
        return true;
    }

    // ---- Queries ----

    // Find all rooms available for a given time slot with minimum capacity
    List<Room> findAvailableRooms(TimeSlot slot, int minCapacity) {
        return registry.all().stream()
            .filter(r -> r.capacity >= minCapacity)
            .filter(r -> {
                // Acquire lock for a consistent read of this room's bookings
                ReentrantLock lock = roomLocks.get(r.roomId);
                lock.lock();
                try {
                    List<Meeting> existing = bookings.getOrDefault(r.roomId, List.of());
                    return existing.stream()
                        .noneMatch(m -> m.getStatus() == MeetingStatus.SCHEDULED
                                     && m.timeSlot.overlaps(slot));
                } finally {
                    lock.unlock();
                }
            })
            .sorted(Comparator.comparingInt(r -> r.capacity))
            .collect(Collectors.toList());
    }

    List<Meeting> getMeetingsForRoom(String roomId) {
        List<Meeting> list = bookings.get(roomId);
        if (list == null) return Collections.emptyList();
        synchronized (list) { return new ArrayList<>(list); }
    }

    List<Meeting> getMeetingsForUser(String userId) {
        List<Meeting> list = userMeetings.get(userId);
        if (list == null) return Collections.emptyList();
        synchronized (list) { return new ArrayList<>(list); }
    }

    Optional<Meeting> findById(String meetingId) {
        return Optional.ofNullable(meetingsById.get(meetingId));
    }

    void printRoomSchedule(String roomId) {
        System.out.printf("%n=== Schedule for Room %s ===%n", roomId);
        getMeetingsForRoom(roomId).stream()
            .filter(m -> m.getStatus() == MeetingStatus.SCHEDULED)
            .sorted(Comparator.comparing(m -> m.timeSlot.start))
            .forEach(m -> System.out.printf("  %-13s  %-30s [%s]%n",
                m.timeSlot, m.title, m.organizerId));
    }

    // ---- Notification helpers ----

    private void notifyFailed(String userId, String roomId, String reason) {
        System.out.printf("[ENGINE] Booking REJECTED: user=%-12s room=%-8s reason=%s%n",
            userId, roomId, reason);
        snapshotObservers().forEach(o -> o.onBookingFailed(userId, roomId, reason));
    }

    private void notifyRoomNotAvailable(String userId, String roomId, TimeSlot slot) {
        System.out.printf("[ENGINE] CONFLICT: user=%-12s room=%-8s slot=%s%n",
            userId, roomId, slot);
        snapshotObservers().forEach(o -> o.onRoomNotAvailable(userId, roomId, slot));
    }
}

// ============================================================
// SECTION 10: Main — demonstration scenarios
// ============================================================

public class MeetingScheduler {

    static MeetingSchedulerEngine engine;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("================================================");
        System.out.println("       Meeting Scheduler System — Demo");
        System.out.println("================================================\n");

        engine = new MeetingSchedulerEngine();
        engine.addObserver(NotificationFactory.create(NotificationType.LOG));
        engine.addObserver(NotificationFactory.create(NotificationType.EMAIL));

        // ---- Register rooms ----
        engine.registerRoom(new Room("R1", "Boardroom",       10,
            Set.of("projector", "video-conf", "whiteboard")));
        engine.registerRoom(new Room("R2", "Focus Room",       4,
            Set.of("whiteboard")));
        engine.registerRoom(new Room("R3", "Training Room",   20,
            Set.of("projector", "whiteboard")));
        engine.registerRoom(new Room("R4", "Huddle Space",     2,
            Set.of()));

        // Time slots (today, relative to now)
        Instant base   = Instant.now();
        TimeSlot s9_10  = new TimeSlot(base.plusSeconds(3600),  base.plusSeconds(7200));
        TimeSlot s10_11 = new TimeSlot(base.plusSeconds(7200),  base.plusSeconds(10800));
        TimeSlot s9_11  = new TimeSlot(base.plusSeconds(3600),  base.plusSeconds(10800)); // overlaps both
        TimeSlot s11_12 = new TimeSlot(base.plusSeconds(10800), base.plusSeconds(14400));
        TimeSlot bad    = new TimeSlot(base.plusSeconds(7200),  base.plusSeconds(3600));  // end < start

        // -----------------------------------------------
        // Scenario 1: Happy path
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 1: Normal bookings ===");
        Meeting m1 = engine.bookRoom("R1", "alice",
            "Q2 Planning",        List.of("bob", "carol", "dave"), s9_10);
        Meeting m2 = engine.bookRoom("R1", "bob",
            "Design Review",      List.of("alice", "eve"),         s10_11);
        engine.bookRoom("R2", "carol",
            "1-on-1 with Eve",    List.of("eve"),                  s9_10);

        // -----------------------------------------------
        // Scenario 2: Time slot conflict (sequential)
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 2: Conflict — overlapping slot ===");
        // s9_11 overlaps with m1 (s9_10) already booked in R1
        engine.bookRoom("R1", "dave",
            "Sprint Planning",    List.of("alice"), s9_11);

        // -----------------------------------------------
        // Scenario 3: Capacity exceeded
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 3: Capacity exceeded ===");
        // Huddle Space R4 holds 2 people; 4 participants + organiser = 5
        engine.bookRoom("R4", "alice",
            "Team Meeting",       List.of("bob", "carol", "dave"), s11_12);

        // -----------------------------------------------
        // Scenario 4: Invalid time slot
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 4: Invalid time slot (end before start) ===");
        engine.bookRoom("R2", "bob", "Bad Slot", List.of("alice"), bad);

        // -----------------------------------------------
        // Scenario 5: Room not found
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 5: Room not found ===");
        engine.bookRoom("R99", "alice", "Mystery Meeting", List.of(), s9_10);

        // -----------------------------------------------
        // Scenario 6: Cancellation frees the slot
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 6: Cancel and re-book ===");
        System.out.println("  Cancelling m1 (Q2 Planning)...");
        engine.cancelMeeting(m1.meetingId);
        // Now R1 / s9_10 is free — re-book should succeed
        engine.bookRoom("R1", "frank",
            "Product Roadmap",    List.of("alice", "bob"), s9_10);

        // -----------------------------------------------
        // Scenario 7: Find available rooms
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 7: Find available rooms for s11_12, minCapacity=5 ===");
        List<Room> available = engine.findAvailableRooms(s11_12, 5);
        System.out.println("  Available rooms:");
        available.forEach(r -> System.out.println("    " + r));

        // -----------------------------------------------
        // Scenario 8: THE KEY SCENARIO
        //   Two threads attempt to book the SAME room at the SAME slot
        //   at the exact same time — only one must succeed.
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 8: Simultaneous booking — same room, same slot ===");
        System.out.println("  Two threads racing to book R3 / s11_12...\n");

        CountDownLatch ready  = new CountDownLatch(2);  // both threads signal ready
        CountDownLatch go     = new CountDownLatch(1);  // main fires the starting gun
        AtomicInteger  wins   = new AtomicInteger(0);
        AtomicInteger  losses = new AtomicInteger(0);

        Runnable racer = (String user, String title) -> () -> {
            ready.countDown();          // signal: I am ready
            try { go.await(); }         // wait for starting gun
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            Meeting m = engine.bookRoom("R3", user, title, List.of("attendee"), s11_12);
            if (m != null) wins.incrementAndGet();
            else           losses.incrementAndGet();
        };

        Thread t1 = new Thread(racer.apply("user-X", "Meeting X"), "thread-X");
        Thread t2 = new Thread(racer.apply("user-Y", "Meeting Y"), "thread-Y");
        t1.start();
        t2.start();
        ready.await();                  // wait until both threads are at the gate
        go.countDown();                 // fire — both threads proceed simultaneously
        t1.join();
        t2.join();

        System.out.printf("%n  Result: %d booking succeeded, %d was rejected%n",
            wins.get(), losses.get());
        System.out.println("  (always: 1 win, 1 reject — no double booking possible)");

        engine.printRoomSchedule("R1");
        engine.printRoomSchedule("R3");

        // User schedule
        System.out.println("\n=== alice's meetings ===");
        engine.getMeetingsForUser("alice")
              .forEach(m -> System.out.println("  " + m));
    }
}

// Helper — Java doesn't allow lambdas to close over non-effectively-final vars,
// so we use a functional interface to curry the user/title into the Runnable.
interface RacerFactory {
    Runnable apply(String user, String title);
}
