// ============================================================
// differences.java — Java code illustrating:
//   1. volatile vs final
//   2. Singleton vs static
//   3. synchronized vs Atomic CAS vs ReentrantLock (Mutex)
// Standard: Java 17
// Compile: javac differences.java
// Run:     java Differences
// ============================================================

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

// ============================================================
// PART 1: volatile vs final
// ============================================================

class VolatileVsFinal {

    // final: assigned once at construction, can never be changed.
    // Safe to read from any thread — immutability IS thread safety.
    private final String appName;

    // volatile: can change at runtime; every read goes to main memory,
    // every write is immediately flushed — solves visibility, not atomicity.
    private volatile boolean running;

    // volatile does NOT make ++ atomic.
    // Two threads doing counter++ can still lose updates.
    private volatile int visibleButNotAtomic;

    VolatileVsFinal(String appName) {
        this.appName = appName;    // final assigned once in constructor
        this.running = true;
    }

    // Worker thread: reads `running` — must see the latest value, not a cached one.
    void workerLoop() {
        System.out.println("[Worker] starting in " + appName);
        int iterations = 0;
        while (running) {           // volatile read — bypasses CPU cache
            iterations++;
            if (iterations >= 3) break;  // safeguard for demo
        }
        System.out.println("[Worker] stopped after " + iterations + " iterations");
    }

    // Main thread: sets `running = false` — must be visible to the worker immediately.
    void stop() {
        running = false;            // volatile write — flushed to main memory immediately
        System.out.println("[Main] requested stop (volatile write)");
    }

    // This is UNSAFE — volatile does not protect read-modify-write
    void unsafeIncrement() {
        visibleButNotAtomic++;      // read, add, write — three non-atomic steps
    }

    static void demo() throws InterruptedException {
        System.out.println("\n======= PART 1: volatile vs final =======");

        VolatileVsFinal obj = new VolatileVsFinal("MyApp");

        Thread worker = new Thread(obj::workerLoop);
        worker.start();
        Thread.sleep(10);
        obj.stop();
        worker.join();

        // final: attempting to reassign appName would be a compile error:
        // obj.appName = "other";   // ← compile error: cannot assign to final

        System.out.println("[Demo] appName (final) = " + obj.appName);

        // Show volatile does NOT prevent lost updates:
        VolatileVsFinal counter = new VolatileVsFinal("Counter");
        Thread t1 = new Thread(() -> { for (int i = 0; i < 1000; i++) counter.unsafeIncrement(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 1000; i++) counter.unsafeIncrement(); });
        t1.start(); t2.start(); t1.join(); t2.join();
        // Expected 2000 — volatile alone does NOT guarantee 2000
        System.out.println("[Demo] volatile counter (expected 2000, may be less): "
            + counter.visibleButNotAtomic);

        // Fix: use AtomicInteger for read-modify-write
        AtomicInteger atomicCounter = new AtomicInteger(0);
        Thread t3 = new Thread(() -> { for (int i = 0; i < 1000; i++) atomicCounter.incrementAndGet(); });
        Thread t4 = new Thread(() -> { for (int i = 0; i < 1000; i++) atomicCounter.incrementAndGet(); });
        t3.start(); t4.start(); t3.join(); t4.join();
        System.out.println("[Demo] AtomicInteger counter (always 2000): " + atomicCounter.get());
    }
}

// ============================================================
// PART 2: Singleton vs static
// ============================================================

// --- Static utility class: no instance, pure functions ---
// No state; just groups related methods. Cannot be injected or mocked.
class MathUtils {
    // Prevent instantiation
    private MathUtils() {}

    static int add(int a, int b)      { return a + b; }
    static int multiply(int a, int b) { return a * b; }
    static boolean isPrime(int n) {
        if (n < 2) return false;
        for (int i = 2; i * i <= n; i++) if (n % i == 0) return false;
        return true;
    }
}

// --- Singleton: one instance, implements an interface, injectable/mockable ---
interface IdGenerator {
    String nextId();
}

class SequentialIdGenerator implements IdGenerator {
    // Initialization-on-demand holder: lazy + thread-safe, zero sync cost after first call
    private static class Holder {
        static final SequentialIdGenerator INSTANCE = new SequentialIdGenerator();
    }

    public static SequentialIdGenerator getInstance() { return Holder.INSTANCE; }

    private final AtomicLong counter = new AtomicLong(0);
    private SequentialIdGenerator() {}   // private — prevents external construction

    @Override
    public String nextId() {
        return "ID-" + String.format("%06d", counter.incrementAndGet());
    }
}

// --- Why Singleton beats static for ID generation ---
// You can pass the Singleton as an IdGenerator interface:
//   register(SequentialIdGenerator.getInstance())
// You can swap it in tests:
//   register(new MockIdGenerator())
// You CANNOT do either of those with a static method.

class SingletonVsStaticDemo {
    static void demo() {
        System.out.println("\n======= PART 2: Singleton vs static =======");

        // Static: called directly on the class — no instance
        System.out.println("[Static]    2 + 3 = "          + MathUtils.add(2, 3));
        System.out.println("[Static]    isPrime(17) = "     + MathUtils.isPrime(17));

        // Singleton: one instance, but accessed via an interface reference
        IdGenerator gen = SequentialIdGenerator.getInstance();  // typed as interface
        System.out.println("[Singleton] " + gen.nextId());
        System.out.println("[Singleton] " + gen.nextId());
        System.out.println("[Singleton] " + gen.nextId());

        // Same instance — both references point to the same object
        SequentialIdGenerator a = SequentialIdGenerator.getInstance();
        SequentialIdGenerator b = SequentialIdGenerator.getInstance();
        System.out.println("[Singleton] same instance? " + (a == b));   // true

        // Interface abstraction: works with any IdGenerator implementation
        printNextId(SequentialIdGenerator.getInstance());
        printNextId(() -> "MOCK-001");  // lambda as mock — impossible with static
    }

    // Accepts IdGenerator interface — Singleton can be passed here, static method cannot
    static void printNextId(IdGenerator generator) {
        System.out.println("[Interface] generated: " + generator.nextId());
    }
}

// ============================================================
// PART 3: synchronized vs Atomic CAS vs ReentrantLock (Mutex)
// ============================================================

// Shared counter — three implementations of the same contract
interface SafeCounter {
    void increment();
    long get();
}

// --- A: synchronized ---
// Coarse lock on the whole method. Simple. Works for any multi-field invariant.
// Threads that cannot acquire the lock BLOCK (suspended by the OS).
class SynchronizedCounter implements SafeCounter {
    private long count = 0;

    @Override
    public synchronized void increment() {
        count++;    // entire method is locked — only one thread at a time
    }

    @Override
    public synchronized long get() { return count; }
}

// --- B: Atomic CAS (AtomicLong) ---
// Uses a single CPU instruction (LOCK CMPXCHG). No thread blocking.
// Threads that lose the CAS spin and retry — no OS involvement.
// Fastest for a single variable under low-to-moderate contention.
class AtomicCASCounter implements SafeCounter {
    private final AtomicLong count = new AtomicLong(0);

    @Override
    public void increment() {
        count.incrementAndGet();    // atomic CAS under the hood: no lock, no blocking
    }

    @Override
    public long get() { return count.get(); }
}

// --- Manual CAS loop to show how AtomicLong.incrementAndGet() works internally ---
class ManualCASCounter implements SafeCounter {
    private final AtomicLong count = new AtomicLong(0);

    @Override
    public void increment() {
        long current, next;
        do {
            current = count.get();   // step 1: read current value
            next    = current + 1;   // step 2: compute desired value
            // step 3: atomically set to `next` ONLY IF count still equals `current`
            //         if another thread changed count between step 1 and here, retry
        } while (!count.compareAndSet(current, next));
    }

    @Override
    public long get() { return count.get(); }
}

// --- C: ReentrantLock (Mutex) ---
// Explicit lock object. Supports: timeout, interruptible wait, fair ordering,
// multiple condition variables. More powerful than synchronized.
class MutexCounter implements SafeCounter {
    private long count = 0;
    final ReentrantLock lock = new ReentrantLock();

    @Override
    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock();   // ALWAYS in finally — ensures unlock even on exception
        }
    }

    @Override
    public long get() {
        lock.lock();
        try { return count; }
        finally { lock.unlock(); }
    }

    // tryLock — unique to ReentrantLock; impossible with synchronized
    boolean tryIncrement(long timeoutMs) throws InterruptedException {
        if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            try {
                count++;
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false;  // could not acquire lock within timeout — back off
    }
}

// --- ReentrantLock with Condition: producer-consumer ---
// synchronized uses wait()/notify() — error-prone and coarse.
// ReentrantLock uses named Condition objects — clearer and more flexible.
class BoundedBuffer {
    private final int[]          buffer;
    private int                  head = 0, tail = 0, size = 0;
    private final ReentrantLock  lock      = new ReentrantLock();
    private final Condition      notFull   = lock.newCondition();
    private final Condition      notEmpty  = lock.newCondition();

    BoundedBuffer(int capacity) { buffer = new int[capacity]; }

    void put(int item) throws InterruptedException {
        lock.lock();
        try {
            while (size == buffer.length) notFull.await();   // wait if full
            buffer[tail] = item;
            tail = (tail + 1) % buffer.length;
            size++;
            notEmpty.signal();   // wake ONE waiting consumer
        } finally { lock.unlock(); }
    }

    int take() throws InterruptedException {
        lock.lock();
        try {
            while (size == 0) notEmpty.await();   // wait if empty
            int item = buffer[head];
            head = (head + 1) % buffer.length;
            size--;
            notFull.signal();    // wake ONE waiting producer
            return item;
        } finally { lock.unlock(); }
    }
}

class SynchronizationDemo {

    // Run each counter with N threads doing M increments; verify final count
    static long runCounter(SafeCounter counter, int threads, int increments)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < increments; j++) counter.increment();
            });
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        return counter.get();
    }

    static void demo() throws InterruptedException {
        System.out.println("\n======= PART 3: synchronized vs CAS vs ReentrantLock =======");

        int THREADS = 10, INCREMENTS = 10_000;
        long expected = (long) THREADS * INCREMENTS;

        long r1 = runCounter(new SynchronizedCounter(), THREADS, INCREMENTS);
        System.out.printf("[synchronized]   result=%-9d expected=%-9d correct=%b%n",
            r1, expected, r1 == expected);

        long r2 = runCounter(new AtomicCASCounter(), THREADS, INCREMENTS);
        System.out.printf("[AtomicCAS]      result=%-9d expected=%-9d correct=%b%n",
            r2, expected, r2 == expected);

        long r3 = runCounter(new ManualCASCounter(), THREADS, INCREMENTS);
        System.out.printf("[Manual CAS]     result=%-9d expected=%-9d correct=%b%n",
            r3, expected, r3 == expected);

        long r4 = runCounter(new MutexCounter(), THREADS, INCREMENTS);
        System.out.printf("[ReentrantLock]  result=%-9d expected=%-9d correct=%b%n",
            r4, expected, r4 == expected);

        // --- tryLock demo ---
        System.out.println("\n--- tryLock (ReentrantLock only) ---");
        MutexCounter mutex = new MutexCounter();
        mutex.lock.lock();   // hold the lock from another thread
        try {
            boolean acquired = mutex.tryIncrement(50); // 50ms timeout — will fail
            System.out.println("[tryLock] acquired within 50ms: " + acquired);
        } finally {
            mutex.lock.unlock();
        }
        boolean acquired = mutex.tryIncrement(50);     // now lock is free
        System.out.println("[tryLock] acquired after lock released: " + acquired);

        // --- AtomicStampedReference: ABA problem solution ---
        System.out.println("\n--- AtomicStampedReference (ABA fix) ---");
        AtomicStampedReference<String> ref =
            new AtomicStampedReference<>("A", 0);

        int[] stamp = new int[1];
        String val = ref.get(stamp);   // val="A", stamp[0]=0
        System.out.println("[ABA] initial: value=" + val + " stamp=" + stamp[0]);

        // Simulate another thread doing A -> B -> A
        ref.compareAndSet("A", "B", 0, 1);
        ref.compareAndSet("B", "A", 1, 2);
        System.out.println("[ABA] after A->B->A: stamp=" + ref.getStamp());

        // Our CAS using old stamp (0) fails because stamp is now 2
        boolean result = ref.compareAndSet("A", "C", 0, 1);
        System.out.println("[ABA] CAS with old stamp (0): succeeded=" + result
            + " (correctly blocked ABA)");

        // CAS with current stamp (2) succeeds
        result = ref.compareAndSet("A", "C", 2, 3);
        System.out.println("[ABA] CAS with current stamp (2): succeeded=" + result);

        // --- Producer-Consumer with Condition ---
        System.out.println("\n--- BoundedBuffer (ReentrantLock + Condition) ---");
        BoundedBuffer buf = new BoundedBuffer(3);
        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    buf.put(i);
                    System.out.println("[Producer] put: " + i);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    int v = buf.take();
                    System.out.println("[Consumer] took: " + v);
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        producer.start(); consumer.start();
        producer.join();  consumer.join();
    }
}

// ============================================================
// Main entry point
// ============================================================

class Differences {
    public static void main(String[] args) throws InterruptedException {
        VolatileVsFinal.demo();
        SingletonVsStaticDemo.demo();
        SynchronizationDemo.demo();
    }
}
