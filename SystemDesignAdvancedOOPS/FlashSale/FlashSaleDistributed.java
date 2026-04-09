// ============================================================
// FlashSaleDistributed.java
// Flash Sale System — Distributed Architecture
//
// Mocked infrastructure:
//   MockRedis    — INCR/DECR, SETNX (distributed lock), SADD (dedup), HSET/HGET
//   MockKafka    — async event bus (order-events, sale-events topics)
//   MockDatabase — durable order persistence
//   Snowflake    — distributed 64-bit ID generation
//
// Key distributed concepts demonstrated:
//   1. Inventory    — Redis DECR (atomic, no oversell across nodes)
//   2. Dedup        — Redis SADD (one order per user per sale)
//   3. Locking      — Redis SETNX with TTL (distributed lock for state transitions)
//   4. Async notify — Kafka producer/consumer (decouple order path from notification)
//   5. Rate limit   — Redis sliding window counter (bot protection)
//   6. IDs          — Snowflake (unique across nodes without coordination)
//
// Compile: javac FlashSaleDistributed.java
// Run:     java FlashSaleDistributed
// ============================================================

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// ============================================================
// SECTION 1: Enums and Event Types
// ============================================================

enum SaleState { SCHEDULED, ACTIVE, ENDED, CANCELLED }

enum OrderStatus { CONFIRMED, CANCELLED }

enum OrderEventType { ORDER_CONFIRMED, ORDER_FAILED, ORDER_CANCELLED }

enum SaleEventType  { SALE_ACTIVATED, SALE_ENDED, SALE_CANCELLED, SOLD_OUT }

// Lightweight event record — serialised as a string message on Kafka
class KafkaMessage {
    final String topic;
    final String key;        // partition key (saleId or userId)
    final String eventType;
    final Map<String, String> payload;
    final Instant timestamp;

    KafkaMessage(String topic, String key, String eventType, Map<String, String> payload) {
        this.topic     = topic;
        this.key       = key;
        this.eventType = eventType;
        this.payload   = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        this.timestamp = Instant.now();
    }

    String get(String field) { return payload.getOrDefault(field, ""); }

    @Override
    public String toString() {
        return String.format("KafkaMessage{topic=%s key=%s type=%s payload=%s}",
            topic, key, eventType, payload);
    }
}

// ============================================================
// SECTION 2: MockRedis
// ============================================================
//
// Simulates Redis with:
//   - String store  (GET / SET / DEL / SETNX)
//   - Atomic counters (INCR / DECR) — maps to AtomicLong, simulating Redis
//     single-threaded atomicity
//   - Sets   (SADD / SISMEMBER / SREM)
//   - Hashes (HSET / HGET / HGETALL)
//   - TTL expiry (EXPIRE / key auto-expiry)
//
// SETNX uses a ReentrantLock to simulate Redis's atomic SET NX PX.
// INCR/DECR use AtomicLong.getAndIncrement() — equivalent to Redis's
// single-threaded increment.
//
class MockRedis {
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final ConcurrentHashMap<String, String>                          strings  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong>                      counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>>                     sets     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String,String>>hashes   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>                            expiry   = new ConcurrentHashMap<>();

    // Separate lock for SETNX to guarantee check-and-set atomicity
    private final ReentrantLock setnxLock = new ReentrantLock();

    // ---- String ----

    String get(String key) {
        if (isExpired(key)) { del(key); return null; }
        return strings.get(key);
    }

    void set(String key, String value) { strings.put(key, value); }

    boolean del(String key) {
        boolean existed = strings.remove(key) != null
                       || counters.remove(key) != null
                       || sets.remove(key) != null
                       || hashes.remove(key) != null;
        expiry.remove(key);
        return existed;
    }

    // ---- Atomic counter (INCR / DECR) ----
    // AtomicLong.getAndXxx() maps to Redis's single-threaded atomic INCR/DECR

    long incr(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    long decr(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicLong(0)).decrementAndGet();
    }

    long getCounter(String key) {
        AtomicLong c = counters.get(key);
        return c == null ? 0L : c.get();
    }

    void initCounter(String key, long value) {
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).set(value);
    }

    // ---- SETNX — SET key value NX PX ttlMs ----
    // Used for distributed locks. Returns true only if key did NOT exist.
    // Lock simulates Redis's atomic "set-if-not-exists-with-expiry".

    boolean setnx(String key, String value, long ttlMs) {
        setnxLock.lock();
        try {
            if (isExpired(key)) del(key);
            if (strings.containsKey(key)) return false;   // key exists — lock held by someone else
            strings.put(key, value);
            if (ttlMs > 0) expiry.put(key, System.currentTimeMillis() + ttlMs);
            return true;
        } finally {
            setnxLock.unlock();
        }
    }

    // Safe delete: only deletes if the stored value matches (prevents releasing another holder's lock)
    boolean delIfEquals(String key, String expectedValue) {
        setnxLock.lock();
        try {
            if (expectedValue.equals(strings.get(key))) {
                del(key);
                return true;
            }
            return false;
        } finally {
            setnxLock.unlock();
        }
    }

    // ---- Set operations ----
    // SADD returns true if member was NEW — used for dedup (one order per user per sale)

    boolean sadd(String key, String member) {
        return sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(member);
    }

    boolean sismember(String key, String member) {
        Set<String> s = sets.get(key);
        return s != null && s.contains(member);
    }

    void srem(String key, String member) {
        Set<String> s = sets.get(key);
        if (s != null) s.remove(member);
    }

    // ---- Hash operations — store sale metadata as a Redis Hash ----

    void hset(String key, String field, String value) {
        hashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
    }

    String hget(String key, String field) {
        ConcurrentHashMap<String, String> h = hashes.get(key);
        return h == null ? null : h.get(field);
    }

    Map<String, String> hgetall(String key) {
        ConcurrentHashMap<String, String> h = hashes.get(key);
        return h == null ? Collections.emptyMap() : new HashMap<>(h);
    }

    // ---- TTL ----

    void expire(String key, long ttlMs) {
        expiry.put(key, System.currentTimeMillis() + ttlMs);
    }

    private boolean isExpired(String key) {
        Long exp = expiry.get(key);
        return exp != null && System.currentTimeMillis() > exp;
    }

    void printStats() {
        System.out.printf("[Redis] strings=%d counters=%d sets=%d hashes=%d%n",
            strings.size(), counters.size(), sets.size(), hashes.size());
    }
}

// ============================================================
// SECTION 3: MockKafka
// ============================================================
//
// Simulates Kafka with:
//   - Topics as LinkedBlockingQueues
//   - publish() — producer side
//   - subscribe() — consumer runs on a background thread
//   - poll()     — one-shot blocking read (used by consumers)
//
// In real Kafka: producers batch messages, consumers track offsets,
// consumer groups allow parallel consumption. Here we use one queue per
// topic for simplicity.
//
class MockKafka {
    private final ConcurrentHashMap<String, LinkedBlockingQueue<KafkaMessage>> topics =
        new ConcurrentHashMap<>();

    void createTopic(String name) {
        topics.putIfAbsent(name, new LinkedBlockingQueue<>());
        System.out.println("[Kafka] Topic created: " + name);
    }

    // Producer: publish a message to a topic (non-blocking, like Kafka async send)
    void publish(String topic, KafkaMessage message) {
        LinkedBlockingQueue<KafkaMessage> q = topics.get(topic);
        if (q == null) throw new IllegalArgumentException("Topic not found: " + topic);
        q.offer(message);
    }

    // Consumer: blocking poll with timeout (like KafkaConsumer.poll(Duration))
    KafkaMessage poll(String topic, long timeoutMs) throws InterruptedException {
        LinkedBlockingQueue<KafkaMessage> q = topics.get(topic);
        if (q == null) return null;
        return q.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // Subscribe: background thread continuously consumes from a topic
    void subscribe(String topic, java.util.function.Consumer<KafkaMessage> handler,
                   ExecutorService executor, AtomicBoolean running) {
        executor.submit(() -> {
            while (running.get()) {
                try {
                    KafkaMessage msg = poll(topic, 100);
                    if (msg != null) handler.accept(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
}

// ============================================================
// SECTION 4: MockDatabase
// ============================================================
//
// Simulates a durable order store (e.g., Cassandra, DynamoDB, PostgreSQL).
// In production: orders are written with a unique (saleId, orderId) key,
// queried by saleId or userId.
//
class MockDatabase {
    // Simulates an append-only orders table
    private final ConcurrentHashMap<String, Order> ordersById      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Order>> bySaleId  = new ConcurrentHashMap<>();

    void saveOrder(Order order) {
        ordersById.put(order.orderId, order);
        bySaleId.computeIfAbsent(order.saleId,
            k -> Collections.synchronizedList(new ArrayList<>())).add(order);
    }

    Optional<Order> findById(String orderId) {
        return Optional.ofNullable(ordersById.get(orderId));
    }

    List<Order> findBySaleId(String saleId) {
        List<Order> orders = bySaleId.get(saleId);
        if (orders == null) return Collections.emptyList();
        synchronized (orders) { return new ArrayList<>(orders); }
    }
}

// ============================================================
// SECTION 5: Snowflake ID Generator — Singleton
// ============================================================
//
// Generates globally unique, time-sortable 64-bit IDs without coordination.
// Bit layout (Twitter Snowflake):
//   [sign 1bit][timestamp 41bit][machineId 10bit][sequence 12bit]
//
//   timestamp: ms since custom epoch — gives ~69 years of IDs
//   machineId: identifies the node (datacenter + worker, 0-1023)
//   sequence:  up to 4096 IDs per ms per machine
//
// Why Singleton: one ID sequence per JVM process. In a distributed system,
// each node gets a different machineId — IDs are globally unique without
// any coordination between nodes.
//
class SnowflakeIdGenerator {
    private static final long EPOCH          = 1700000000000L;
    private static final long MACHINE_BITS   = 10L;
    private static final long SEQUENCE_BITS  = 12L;
    private static final long MAX_SEQUENCE   = (1L << SEQUENCE_BITS) - 1;  // 4095
    private static final long MACHINE_SHIFT  = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT= SEQUENCE_BITS + MACHINE_BITS;

    private final long machineId;
    private long lastTimestamp = -1L;
    private long sequence      =  0L;

    // Singleton — each node in a cluster uses a different machineId
    private static class Holder {
        static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator(1L);
    }

    public static SnowflakeIdGenerator getInstance() { return Holder.INSTANCE; }

    private SnowflakeIdGenerator(long machineId) { this.machineId = machineId; }

    public synchronized long nextLong() {
        long now = System.currentTimeMillis() - EPOCH;
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {   // sequence exhausted — wait for next ms
                while ((now = System.currentTimeMillis() - EPOCH) <= lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return (now << TIMESTAMP_SHIFT) | (machineId << MACHINE_SHIFT) | sequence;
    }

    public String nextOrderId() { return "ORD-" + nextLong(); }
    public String nextSaleId()  { return "SALE-" + nextLong(); }
}

// ============================================================
// SECTION 6: Distributed Lock
// ============================================================
//
// Simulates the Redis distributed lock pattern:
//   ACQUIRE: SET lockKey randomValue NX PX ttlMs
//     - NX  = only set if key does not exist
//     - PX  = auto-expire after ttlMs (guards against crashed lock-holder)
//     - randomValue = unique per lock acquisition (prevents releasing another holder's lock)
//
//   RELEASE: if GET lockKey == randomValue: DEL lockKey
//     - Compare-before-delete ensures we only release our own lock
//
// In production: use Redlock algorithm (acquire on N/2+1 Redis nodes).
// Reference: https://redis.io/docs/manual/patterns/distributed-locks/
//
class DistributedLock {
    private final MockRedis redis;
    private final String    lockKey;
    private final long      ttlMs;
    private final String    lockValue;   // unique token — UUID in production

    DistributedLock(MockRedis redis, String resource, long ttlMs) {
        this.redis     = redis;
        this.lockKey   = "lock:" + resource;
        this.ttlMs     = ttlMs;
        this.lockValue = Thread.currentThread().getName() + "-" + System.nanoTime();
    }

    // Returns true if lock was acquired
    boolean tryAcquire() {
        return redis.setnx(lockKey, lockValue, ttlMs);
    }

    // Retry with exponential back-off up to maxAttempts
    boolean tryAcquireWithRetry(int maxAttempts, long backOffMs) throws InterruptedException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (tryAcquire()) {
                System.out.printf("[Lock] Acquired %s on attempt %d%n", lockKey, attempt);
                return true;
            }
            System.out.printf("[Lock] %s busy — retry %d/%d in %dms%n",
                lockKey, attempt, maxAttempts, backOffMs * attempt);
            Thread.sleep(backOffMs * attempt);   // exponential back-off
        }
        return false;
    }

    // Safe release: compare-and-delete (only release our own lock)
    void release() {
        boolean released = redis.delIfEquals(lockKey, lockValue);
        if (released)
            System.out.printf("[Lock] Released %s%n", lockKey);
        else
            System.out.printf("[Lock] Release skipped (expired or held by another) %s%n", lockKey);
    }
}

// ============================================================
// SECTION 7: Rate Limiter — Redis sliding window
// ============================================================
//
// Prevents bot flooding by counting requests per user per time window.
// Uses Redis INCR + EXPIRE:
//   key = ratelimit:{userId}:{windowEpochSecond}
//   INCR the key; if result == 1, set EXPIRE to windowSizeSeconds
//   If count > MAX_REQUESTS, reject the request
//
// In production: use a Lua script to make INCR+EXPIRE atomic, or use
// Redis sorted sets for a true sliding window.
//
class RateLimiter {
    private final MockRedis redis;
    private final int       maxRequests;
    private final long      windowMs;

    RateLimiter(MockRedis redis, int maxRequests, long windowMs) {
        this.redis       = redis;
        this.maxRequests = maxRequests;
        this.windowMs    = windowMs;
    }

    // Returns true if request is within rate limit
    boolean isAllowed(String userId) {
        long window = System.currentTimeMillis() / windowMs;   // current window bucket
        String key = "ratelimit:" + userId + ":" + window;
        long count = redis.incr(key);
        if (count == 1) redis.expire(key, windowMs * 2);       // set TTL on first request
        return count <= maxRequests;
    }
}

// ============================================================
// SECTION 8: Domain Classes
// ============================================================

class FlashSaleItem {
    final String itemId;
    final String name;
    final double originalPrice;
    final double salePrice;

    FlashSaleItem(String itemId, String name, double originalPrice, double salePrice) {
        this.itemId        = itemId;
        this.name          = name;
        this.originalPrice = originalPrice;
        this.salePrice     = salePrice;
    }

    double discountPercent() { return (1.0 - salePrice / originalPrice) * 100.0; }
}

class Order {
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    final String      orderId;
    final String      saleId;
    final String      userId;
    final String      itemId;
    final double      pricePaid;
    final Instant     createdAt;
    private volatile  OrderStatus status;

    Order(String orderId, String saleId, String userId, String itemId, double pricePaid) {
        this.orderId   = orderId;
        this.saleId    = saleId;
        this.userId    = userId;
        this.itemId    = itemId;
        this.pricePaid = pricePaid;
        this.createdAt = Instant.now();
        this.status    = OrderStatus.CONFIRMED;
    }

    OrderStatus getStatus() { return status; }

    boolean cancel() {
        if (status == OrderStatus.CONFIRMED) { status = OrderStatus.CANCELLED; return true; }
        return false;
    }

    @Override
    public String toString() {
        return String.format("Order[%-20s] sale=%-13s user=%-12s price=$%-8.2f status=%-10s at=%s",
            orderId, saleId, userId, pricePaid, status, FMT.format(createdAt));
    }
}

// ============================================================
// SECTION 9: SaleRepository — sale metadata in Redis
// ============================================================
//
// In a distributed system, sale metadata lives in Redis (not in-memory).
// Every node reads from Redis — no local cache that could be stale.
//
// Redis key schema:
//   sale:{saleId}           — Hash: {state, itemId, name, salePrice, startTime, endTime, inventoryTotal}
//   sale:{saleId}:inventory — Counter (INCR/DECR for reservations)
//   sale:{saleId}:buyers    — Set of userIds who placed an order
//
class SaleRepository {
    private final MockRedis redis;
    private final ConcurrentHashMap<String, FlashSaleItem> itemCache = new ConcurrentHashMap<>();

    SaleRepository(MockRedis redis) { this.redis = redis; }

    void save(String saleId, FlashSaleItem item, int inventoryCount,
              Instant startTime, Instant endTime) {
        String key = "sale:" + saleId;
        redis.hset(key, "state",          SaleState.SCHEDULED.name());
        redis.hset(key, "itemId",         item.itemId);
        redis.hset(key, "name",           item.name);
        redis.hset(key, "salePrice",      String.valueOf(item.salePrice));
        redis.hset(key, "originalPrice",  String.valueOf(item.originalPrice));
        redis.hset(key, "startTime",      String.valueOf(startTime.toEpochMilli()));
        redis.hset(key, "endTime",        String.valueOf(endTime.toEpochMilli()));
        redis.hset(key, "inventoryTotal", String.valueOf(inventoryCount));
        redis.initCounter("sale:" + saleId + ":inventory", inventoryCount);
        itemCache.put(saleId, item);
    }

    SaleState getState(String saleId) {
        String s = redis.hget("sale:" + saleId, "state");
        return s == null ? null : SaleState.valueOf(s);
    }

    boolean setState(String saleId, SaleState newState) {
        if (redis.hget("sale:" + saleId, "state") == null) return false;
        redis.hset("sale:" + saleId, "state", newState.name());
        return true;
    }

    boolean isAcceptingOrders(String saleId) {
        String stateStr = redis.hget("sale:" + saleId, "state");
        if (stateStr == null || !SaleState.ACTIVE.name().equals(stateStr)) return false;
        long now  = Instant.now().toEpochMilli();
        long start = Long.parseLong(redis.hget("sale:" + saleId, "startTime"));
        long end   = Long.parseLong(redis.hget("sale:" + saleId, "endTime"));
        return now > start && now < end;
    }

    double getSalePrice(String saleId) {
        String p = redis.hget("sale:" + saleId, "salePrice");
        return p == null ? 0.0 : Double.parseDouble(p);
    }

    FlashSaleItem getItem(String saleId) { return itemCache.get(saleId); }

    long getRemainingInventory(String saleId) {
        return redis.getCounter("sale:" + saleId + ":inventory");
    }

    // Returns remaining count after reservation; negative means oversold — caller must undo
    long reserveInventory(String saleId) {
        return redis.decr("sale:" + saleId + ":inventory");
    }

    void releaseInventory(String saleId) {
        redis.incr("sale:" + saleId + ":inventory");
    }

    // SADD: returns true only if userId was new (not a duplicate)
    boolean addBuyer(String saleId, String userId) {
        return redis.sadd("sale:" + saleId + ":buyers", userId);
    }

    void removeBuyer(String saleId, String userId) {
        redis.srem("sale:" + saleId + ":buyers", userId);
    }

    void printStatus(String saleId) {
        Map<String, String> data = redis.hgetall("sale:" + saleId);
        long inventory = redis.getCounter("sale:" + saleId + ":inventory");
        System.out.printf("  [%-15s] %-25s $%-8s (%.0f%% off)  inventory: %d/%-4s  state: %s%n",
            saleId,
            data.getOrDefault("name", "?"),
            data.getOrDefault("salePrice", "?"),
            (1.0 - Double.parseDouble(data.getOrDefault("salePrice","1"))
                      / Double.parseDouble(data.getOrDefault("originalPrice","1"))) * 100.0,
            inventory,
            data.getOrDefault("inventoryTotal", "?"),
            data.getOrDefault("state", "?"));
    }
}

// ============================================================
// SECTION 10: Notification Service — Kafka Consumer
// ============================================================
//
// In a distributed system, notifications are decoupled from the order path:
//   Order service   → publishes to Kafka
//   Notification svc → consumes from Kafka asynchronously
//
// Benefits:
//   - A slow email/SMS provider never blocks order placement
//   - If the notification service crashes, messages stay in Kafka (replayed on restart)
//   - Notification service scales independently from the order service
//
class NotificationService {
    private final MockKafka        kafka;
    private final ExecutorService  executor = Executors.newSingleThreadExecutor(
        r -> { Thread t = new Thread(r, "notification-consumer"); t.setDaemon(true); return t; });
    private final AtomicBoolean    running  = new AtomicBoolean(true);

    NotificationService(MockKafka kafka) {
        this.kafka = kafka;
    }

    void start() {
        kafka.subscribe("order-events", this::handleOrderEvent, executor, running);
        kafka.subscribe("sale-events",  this::handleSaleEvent,  executor, running);
        System.out.println("[NotifService] Started — consuming order-events, sale-events");
    }

    void stop() throws InterruptedException {
        running.set(false);
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
        System.out.println("[NotifService] Stopped");
    }

    private void handleOrderEvent(KafkaMessage msg) {
        switch (msg.eventType) {
            case "ORDER_CONFIRMED" ->
                System.out.printf("[EMAIL] -> %s: Order %s confirmed for '%s' at $%s%n",
                    msg.get("userId"), msg.get("orderId"), msg.get("itemName"), msg.get("price"));
            case "ORDER_FAILED" ->
                System.out.printf("[EMAIL] -> %s: Order failed for sale %s — %s%n",
                    msg.get("userId"), msg.get("saleId"), msg.get("reason"));
            case "ORDER_CANCELLED" ->
                System.out.printf("[EMAIL] -> %s: Order %s was cancelled%n",
                    msg.get("userId"), msg.get("orderId"));
        }
    }

    private void handleSaleEvent(KafkaMessage msg) {
        switch (msg.eventType) {
            case "SALE_ACTIVATED" ->
                System.out.printf("[PUSH]  Flash sale LIVE: '%s' at $%s (%.0f%% off) — hurry!%n",
                    msg.get("itemName"), msg.get("salePrice"),
                    Double.parseDouble(msg.get("discount")));
            case "SOLD_OUT" ->
                System.out.printf("[PUSH]  SOLD OUT: '%s' [%s]%n",
                    msg.get("itemName"), msg.get("saleId"));
            case "SALE_ENDED" ->
                System.out.printf("[PUSH]  Sale ended: '%s'%n", msg.get("itemName"));
        }
    }
}

// ============================================================
// SECTION 11: DistributedFlashSaleEngine
// ============================================================
//
// Order placement pipeline (distributed):
//   1. Rate limit check  — Redis sliding window (bot protection)
//   2. Validate sale     — read state from Redis (not in-memory)
//   3. Dedup             — Redis SADD (atomic one-order-per-user-per-sale)
//   4. Reserve inventory — Redis DECR (atomic, no oversell across nodes)
//   5. Create & persist  — Snowflake ID, write to MockDatabase
//   6. Publish to Kafka  — async notification (decoupled from order path)
//
// Sale state transitions:
//   - Acquire distributed lock (Redis SETNX)
//   - Read state from Redis
//   - Validate transition
//   - Write new state to Redis
//   - Release lock
//   - Publish sale-events to Kafka
//
class DistributedFlashSaleEngine {
    private static final Map<SaleState, Set<SaleState>> VALID_TRANSITIONS;
    static {
        VALID_TRANSITIONS = new EnumMap<>(SaleState.class);
        VALID_TRANSITIONS.put(SaleState.SCHEDULED,
            EnumSet.of(SaleState.ACTIVE, SaleState.CANCELLED));
        VALID_TRANSITIONS.put(SaleState.ACTIVE,
            EnumSet.of(SaleState.ENDED, SaleState.CANCELLED));
        VALID_TRANSITIONS.put(SaleState.ENDED,     EnumSet.noneOf(SaleState.class));
        VALID_TRANSITIONS.put(SaleState.CANCELLED, EnumSet.noneOf(SaleState.class));
    }

    private final MockRedis      redis;
    private final MockKafka      kafka;
    private final MockDatabase   db;
    final SaleRepository saleRepo;
    private final RateLimiter    rateLimiter;
    private final NotificationService notifService;

    // Known sale IDs — in production this would be a Redis sorted set by start time
    private final Set<String> knownSaleIds = ConcurrentHashMap.newKeySet();

    DistributedFlashSaleEngine(MockRedis redis, MockKafka kafka, MockDatabase db) {
        this.redis       = redis;
        this.kafka       = kafka;
        this.db          = db;
        this.saleRepo    = new SaleRepository(redis);
        this.rateLimiter = new RateLimiter(redis, 5, 1000); // 5 requests per second
        this.notifService= new NotificationService(kafka);
        this.notifService.start();
    }

    // ---- Sale lifecycle ----

    String createSale(FlashSaleItem item, int qty, Instant start, Instant end) {
        String saleId = SnowflakeIdGenerator.getInstance().nextSaleId();
        saleRepo.save(saleId, item, qty, start, end);
        knownSaleIds.add(saleId);
        System.out.printf("[ENGINE] Created  %-15s  %-25s  $%.2f (%.0f%% off)  qty=%d%n",
            saleId, item.name, item.salePrice, item.discountPercent(), qty);
        return saleId;
    }

    boolean activateSale(String saleId) {
        return changeSaleState(saleId, SaleState.ACTIVE);
    }

    boolean endSale(String saleId) {
        return changeSaleState(saleId, SaleState.ENDED);
    }

    boolean cancelSale(String saleId) {
        return changeSaleState(saleId, SaleState.CANCELLED);
    }

    // State transition protected by a distributed lock
    private boolean changeSaleState(String saleId, SaleState newState) {
        DistributedLock lock = new DistributedLock(redis, "sale:" + saleId, 5000);
        try {
            if (!lock.tryAcquireWithRetry(3, 50)) {
                System.err.printf("[ENGINE] Could not acquire lock for %s%n", saleId);
                return false;
            }
            SaleState current = saleRepo.getState(saleId);
            if (current == null) {
                System.err.println("[ENGINE] Sale not found: " + saleId);
                return false;
            }
            Set<SaleState> allowed = VALID_TRANSITIONS.get(current);
            if (allowed == null || !allowed.contains(newState)) {
                System.err.printf("[ENGINE] Invalid transition: %s -> %s (sale %s)%n",
                    current, newState, saleId);
                return false;
            }
            saleRepo.setState(saleId, newState);
            System.out.printf("[ENGINE] Sale %s: %s -> %s%n", saleId, current, newState);

            // Publish sale event to Kafka (async — does not block this thread)
            FlashSaleItem item = saleRepo.getItem(saleId);
            publishSaleEvent(saleId, newState, item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.release();
        }
    }

    // ---- Order placement — critical distributed path ----

    Order placeOrder(String saleId, String userId) {
        // 1. Rate limit — Redis sliding window
        if (!rateLimiter.isAllowed(userId)) {
            publishOrderFailed(userId, saleId, "rate_limited");
            return null;
        }

        // 2. Validate sale state (read from Redis — always fresh, no stale cache)
        SaleState state = saleRepo.getState(saleId);
        if (state == null) {
            publishOrderFailed(userId, saleId, "sale_not_found");
            return null;
        }
        if (!saleRepo.isAcceptingOrders(saleId)) {
            String reason = switch (state) {
                case SCHEDULED -> "sale_not_started";
                case ENDED     -> "sale_ended";
                case CANCELLED -> "sale_cancelled";
                default        -> "sale_inactive";
            };
            publishOrderFailed(userId, saleId, reason);
            return null;
        }

        // 3. Dedup — Redis SADD returns false if userId already in the set
        //    Atomic: check-and-insert is a single Redis command
        if (!saleRepo.addBuyer(saleId, userId)) {
            publishOrderFailed(userId, saleId, "duplicate_order");
            return null;
        }

        // 4. Reserve inventory — Redis DECR
        //    Atomic: even if 1000 threads call this simultaneously on different nodes,
        //    Redis serialises all DECR operations — no oversell possible
        long remaining = saleRepo.reserveInventory(saleId);
        if (remaining < 0) {
            // Inventory exhausted — undo the DECR and the dedup entry
            saleRepo.releaseInventory(saleId);
            saleRepo.removeBuyer(saleId, userId);
            publishOrderFailed(userId, saleId, "sold_out");
            if (remaining == -1) publishSoldOut(saleId);  // first thread to hit 0 announces it
            return null;
        }

        // 5. Create order — Snowflake ID (unique across all nodes, no coordination)
        FlashSaleItem item  = saleRepo.getItem(saleId);
        double price        = saleRepo.getSalePrice(saleId);
        String orderId      = SnowflakeIdGenerator.getInstance().nextOrderId();
        Order order         = new Order(orderId, saleId, userId, item.itemId, price);

        // 6. Persist to database (durable write)
        db.saveOrder(order);
        System.out.printf("[ENGINE] %s%n", order);

        // 7. Publish to Kafka — notification consumer handles email/push asynchronously
        publishOrderConfirmed(order, item);

        // 8. Check if just sold out — publish event if inventory hits zero
        if (remaining == 0) publishSoldOut(saleId);

        return order;
    }

    boolean cancelOrder(String saleId, String orderId) {
        return db.findById(orderId).map(order -> {
            if (order.cancel()) {
                saleRepo.releaseInventory(saleId);
                saleRepo.removeBuyer(saleId, order.userId);
                System.out.printf("[ENGINE] Order %s cancelled — inventory restored to %d%n",
                    orderId, saleRepo.getRemainingInventory(saleId));
                // Publish cancellation event
                kafka.publish("order-events", new KafkaMessage("order-events", order.userId,
                    "ORDER_CANCELLED", Map.of("orderId", orderId, "userId", order.userId)));
                return true;
            }
            return false;
        }).orElse(false);
    }

    void shutdown() throws InterruptedException { notifService.stop(); }

    void printAllSales() {
        System.out.println("\n=== All Sales (state read from Redis) ===");
        knownSaleIds.forEach(saleRepo::printStatus);
    }

    void printOrders(String saleId) {
        System.out.printf("=== Orders for %s ===%n", saleId);
        db.findBySaleId(saleId).forEach(o -> System.out.println("  " + o));
        System.out.println();
    }

    // ---- Kafka publishing helpers ----

    private void publishOrderConfirmed(Order order, FlashSaleItem item) {
        kafka.publish("order-events", new KafkaMessage("order-events", order.userId,
            "ORDER_CONFIRMED", Map.of(
                "orderId",  order.orderId,
                "saleId",   order.saleId,
                "userId",   order.userId,
                "itemName", item.name,
                "price",    String.valueOf(order.pricePaid))));
    }

    private void publishOrderFailed(String userId, String saleId, String reason) {
        System.out.printf("[ENGINE] Order REJECTED user=%-12s sale=%-15s reason=%s%n",
            userId, saleId, reason);
        kafka.publish("order-events", new KafkaMessage("order-events", userId,
            "ORDER_FAILED", Map.of("userId", userId, "saleId", saleId, "reason", reason)));
    }

    private void publishSaleEvent(String saleId, SaleState newState, FlashSaleItem item) {
        String eventType = switch (newState) {
            case ACTIVE    -> "SALE_ACTIVATED";
            case ENDED     -> "SALE_ENDED";
            case CANCELLED -> "SALE_CANCELLED";
            default        -> "SALE_UPDATED";
        };
        kafka.publish("sale-events", new KafkaMessage("sale-events", saleId, eventType,
            Map.of("saleId",    saleId,
                   "itemName",  item.name,
                   "salePrice", String.valueOf(item.salePrice),
                   "discount",  String.format("%.0f", item.discountPercent()))));
    }

    private void publishSoldOut(String saleId) {
        FlashSaleItem item = saleRepo.getItem(saleId);
        kafka.publish("sale-events", new KafkaMessage("sale-events", saleId, "SOLD_OUT",
            Map.of("saleId", saleId, "itemName", item == null ? "?" : item.name)));
    }
}

// ============================================================
// SECTION 12: Main — demonstration scenarios
// ============================================================

public class FlashSaleDistributed {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("====================================================");
        System.out.println("   Flash Sale System — Distributed Architecture");
        System.out.println("====================================================\n");

        // ---- Boot infrastructure ----
        MockRedis    redis  = new MockRedis();
        MockDatabase db     = new MockDatabase();
        MockKafka    kafka  = new MockKafka();
        kafka.createTopic("order-events");
        kafka.createTopic("sale-events");

        DistributedFlashSaleEngine engine =
            new DistributedFlashSaleEngine(redis, kafka, db);

        // Products
        FlashSaleItem laptop  = new FlashSaleItem("ITEM-001", "MacBook Pro 14\"",  2499.00, 1799.00);
        FlashSaleItem airpods = new FlashSaleItem("ITEM-002", "AirPods Pro",         249.00,  149.00);
        FlashSaleItem watch   = new FlashSaleItem("ITEM-003", "Apple Watch Ultra",   799.00,  549.00);
        FlashSaleItem ipad    = new FlashSaleItem("ITEM-004", "iPad Pro 12.9\"",    1099.00,  799.00);

        Instant now   = Instant.now();
        Instant start = now.minusSeconds(60);    // started 1 min ago
        Instant end   = now.plusSeconds(3600);   // ends in 1 hour
        Instant fStart= now.plusSeconds(3600);   // future sale

        // -----------------------------------------------
        // Scenario 1: Happy path — 3 different users
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 1: Laptop Sale (qty=3, happy path) ===");
        String laptopSaleId = engine.createSale(laptop, 3, start, end);
        engine.activateSale(laptopSaleId);
        pause();  // let Kafka consumer print SALE_ACTIVATED notification

        Order o1 = engine.placeOrder(laptopSaleId, "user-alice");
        Order o2 = engine.placeOrder(laptopSaleId, "user-bob");
        Order o3 = engine.placeOrder(laptopSaleId, "user-carol");
        pause();  // let Kafka consumer print ORDER_CONFIRMED notifications

        // -----------------------------------------------
        // Scenario 2: Sold out
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 2: AirPods (qty=2) — sells out ===");
        String airpodsSaleId = engine.createSale(airpods, 2, start, end);
        engine.activateSale(airpodsSaleId);
        engine.placeOrder(airpodsSaleId, "user-alice");
        engine.placeOrder(airpodsSaleId, "user-bob");
        engine.placeOrder(airpodsSaleId, "user-carol");  // sold out
        pause();

        // -----------------------------------------------
        // Scenario 3: Duplicate order prevention
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 3: Duplicate Order (Redis SADD dedup) ===");
        String watchSaleId = engine.createSale(watch, 10, start, end);
        engine.activateSale(watchSaleId);
        engine.placeOrder(watchSaleId, "user-alice");
        engine.placeOrder(watchSaleId, "user-alice");   // duplicate — Redis SADD returns false
        pause();

        // -----------------------------------------------
        // Scenario 4: Cancellation releases inventory (Redis INCR)
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 4: Cancel & Re-order ===");
        Order watchOrder = engine.placeOrder(watchSaleId, "user-dave");
        System.out.printf("  Inventory before cancel: %d%n",
            engine.saleRepo.getRemainingInventory(watchSaleId));
        if (watchOrder != null) engine.cancelOrder(watchSaleId, watchOrder.orderId);
        System.out.printf("  Inventory after  cancel: %d%n",
            engine.saleRepo.getRemainingInventory(watchSaleId));
        engine.placeOrder(watchSaleId, "user-dave");    // re-order succeeds
        pause();

        // -----------------------------------------------
        // Scenario 5: Order on ended sale
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 5: Order on Ended Sale ===");
        engine.endSale(watchSaleId);
        engine.placeOrder(watchSaleId, "user-eve");     // sale_ended
        pause();

        // -----------------------------------------------
        // Scenario 6: Sale not yet started
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 6: Sale Not Yet Started (SCHEDULED state) ===");
        String futureSaleId = engine.createSale(ipad, 50, fStart, fStart.plusSeconds(3600));
        // Note: NOT activated — stays SCHEDULED; no time window overlap either
        engine.placeOrder(futureSaleId, "user-bob");    // sale_not_started
        pause();

        // -----------------------------------------------
        // Scenario 7: Invalid state transition blocked by distributed lock
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 7: Invalid Transition (ENDED -> ACTIVE) ===");
        engine.activateSale(watchSaleId);               // watchSale is ENDED — rejected
        pause();

        // -----------------------------------------------
        // Scenario 8: Rate limiting (5 req/sec per user)
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 8: Rate Limiter (5 req/sec) ===");
        String ipadSaleId = engine.createSale(ipad, 100, start, end);
        engine.activateSale(ipadSaleId);
        for (int i = 1; i <= 7; i++) {
            Order o = engine.placeOrder(ipadSaleId, "bot-user");
            System.out.printf("  Request %d: %s%n", i,
                o != null ? "ACCEPTED (" + o.orderId + ")" : "REJECTED (rate_limited or duplicate)");
        }
        pause();

        // -----------------------------------------------
        // Scenario 9: Concurrent orders — show Snowflake IDs are unique
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 9: Concurrent Orders — Snowflake IDs ===");
        String concurrentSaleId = engine.createSale(
            new FlashSaleItem("ITEM-005", "Sony WH-1000XM5", 399.00, 249.00), 20, start, end);
        engine.activateSale(concurrentSaleId);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        Set<String> orderIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 10; i++) {
            final String user = "concurrent-user-" + i;
            pool.submit(() -> {
                Order o = engine.placeOrder(concurrentSaleId, user);
                if (o != null) orderIds.add(o.orderId);
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        Thread.sleep(200);  // let Kafka flush notifications
        System.out.printf("  Placed %d orders — all IDs unique: %b%n",
            orderIds.size(), orderIds.size() == db.findBySaleId(concurrentSaleId).size());

        // -----------------------------------------------
        // Summary
        // -----------------------------------------------
        Thread.sleep(300);  // let Kafka consumer drain remaining messages
        engine.printAllSales();
        engine.printOrders(laptopSaleId);

        System.out.println("=== Redis State ===");
        redis.printStats();

        engine.shutdown();
        System.out.println("\nDone.");
    }

    // Small pause so Kafka consumer thread can print notifications before next scenario
    static void pause() throws InterruptedException { Thread.sleep(80); }
}
