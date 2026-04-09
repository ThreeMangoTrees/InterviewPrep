// ============================================================
// Flash Sale System — Java Implementation
// Design: Observer + Factory + Singleton + State Machine patterns
// Standard: Java 17
// Compile: javac FlashSale.java
// Run:     java FlashSale
// ============================================================

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

// ============================================================
// SECTION 1: Enums
// ============================================================

enum FlashSaleState {
    SCHEDULED, ACTIVE, ENDED, CANCELLED
}

enum OrderStatus {
    CONFIRMED, CANCELLED, EXPIRED
}

enum NotificationType {
    LOG, EMAIL
}

// ============================================================
// SECTION 2: FlashSaleItem — product details (value object)
// ============================================================

class FlashSaleItem {
    final String itemId;
    final String name;
    final double originalPrice;
    final double salePrice;

    FlashSaleItem(String itemId, String name, double originalPrice, double salePrice) {
        this.itemId         = itemId;
        this.name           = name;
        this.originalPrice  = originalPrice;
        this.salePrice      = salePrice;
    }

    double discountPercent() {
        return (1.0 - salePrice / originalPrice) * 100.0;
    }
}

// ============================================================
// SECTION 3: Inventory — lock-free CAS reservation
// ============================================================
//
// Critical design: two concurrent threads can both read remaining > 0
// and both proceed to decrement, causing an oversell.
// CAS (compareAndSet) loop solves this without a mutex:
//   - thread reads current value
//   - atomically swaps to current-1 ONLY IF it still equals current
//   - if another thread changed it first, retry from the new value
//
class Inventory {
    private final AtomicInteger remaining;
    private final int total;

    Inventory(int total) {
        this.total     = total;
        this.remaining = new AtomicInteger(total);
    }

    // Returns true if one unit was reserved, false if sold out
    boolean tryReserve() {
        int current;
        do {
            current = remaining.get();
            if (current <= 0) return false;
        } while (!remaining.compareAndSet(current, current - 1));
        return true;
    }

    // Release a previously reserved unit (e.g. order cancelled)
    void release() {
        remaining.incrementAndGet();
    }

    int getRemaining() { return remaining.get(); }
    int getTotal()     { return total;           }
    boolean isSoldOut(){ return remaining.get() <= 0; }
}

// ============================================================
// SECTION 4: Order — the purchase record
// ============================================================

class Order {
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    final String orderId;
    final String saleId;
    final String userId;
    final String itemId;
    final double pricePaid;
    final Instant createdAt;
    private volatile OrderStatus status;

    Order(String orderId, String saleId, String userId,
          String itemId, double pricePaid) {
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
        if (status == OrderStatus.CONFIRMED) {
            status = OrderStatus.CANCELLED;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("Order[%s] sale=%-9s user=%-12s price=$%-8.2f status=%-10s at=%s",
            orderId, saleId, userId, pricePaid, status, FMT.format(createdAt));
    }
}

// ============================================================
// SECTION 5: FlashSaleEvent — the sale entity with state machine
// ============================================================

class FlashSaleEvent {
    final String saleId;
    final FlashSaleItem item;
    final Inventory inventory;
    final Instant startTime;
    final Instant endTime;
    private volatile FlashSaleState state;
    private final ReentrantLock stateLock = new ReentrantLock();

    // State machine adjacency map
    private static final Map<FlashSaleState, Set<FlashSaleState>> VALID_TRANSITIONS;
    static {
        VALID_TRANSITIONS = new EnumMap<>(FlashSaleState.class);
        VALID_TRANSITIONS.put(FlashSaleState.SCHEDULED,
            EnumSet.of(FlashSaleState.ACTIVE, FlashSaleState.CANCELLED));
        VALID_TRANSITIONS.put(FlashSaleState.ACTIVE,
            EnumSet.of(FlashSaleState.ENDED, FlashSaleState.CANCELLED));
        VALID_TRANSITIONS.put(FlashSaleState.ENDED,
            EnumSet.noneOf(FlashSaleState.class));     // terminal
        VALID_TRANSITIONS.put(FlashSaleState.CANCELLED,
            EnumSet.noneOf(FlashSaleState.class));     // terminal
    }

    FlashSaleEvent(String saleId, FlashSaleItem item, int inventoryCount,
                   Instant startTime, Instant endTime) {
        this.saleId    = saleId;
        this.item      = item;
        this.inventory = new Inventory(inventoryCount);
        this.startTime = startTime;
        this.endTime   = endTime;
        this.state     = FlashSaleState.SCHEDULED;
    }

    FlashSaleState getState() { return state; }

    // Returns true if transition succeeded; false if it was invalid
    boolean transitionTo(FlashSaleState newState) {
        stateLock.lock();
        try {
            Set<FlashSaleState> allowed = VALID_TRANSITIONS.get(state);
            if (allowed == null || !allowed.contains(newState)) {
                System.err.printf("[FlashSaleEvent] Invalid transition: %s -> %s (sale %s)%n",
                    state, newState, saleId);
                return false;
            }
            state = newState;
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    // A sale is only purchasable if it is in ACTIVE state AND within its time window
    boolean isAcceptingOrders() {
        Instant now = Instant.now();
        return state == FlashSaleState.ACTIVE
            && now.isAfter(startTime)
            && now.isBefore(endTime);
    }

    void printStatus() {
        System.out.printf("  [%-9s] %-25s $%-8.2f (%.0f%% off)  inventory: %d/%-4d  state: %s%n",
            saleId, item.name, item.salePrice, item.discountPercent(),
            inventory.getRemaining(), inventory.getTotal(), state);
    }
}

// ============================================================
// SECTION 6: Observer interface + concrete observers
// ============================================================

interface INotificationObserver {
    void onOrderConfirmed(Order order, FlashSaleEvent sale);
    void onOrderFailed(String userId, String saleId, String reason);
    void onSaleStateChanged(FlashSaleEvent sale, FlashSaleState previousState);
    void onSoldOut(FlashSaleEvent sale);
}

// ---- Concrete Observer A: Log (console) ----

class LogNotificationObserver implements INotificationObserver {
    @Override
    public void onOrderConfirmed(Order order, FlashSaleEvent sale) {
        System.out.printf("[LOG] Order confirmed: %s | '%s' | user=%s%n",
            order.orderId, sale.item.name, order.userId);
    }

    @Override
    public void onOrderFailed(String userId, String saleId, String reason) {
        System.out.printf("[LOG] Order FAILED: user=%s sale=%s reason=%s%n",
            userId, saleId, reason);
    }

    @Override
    public void onSaleStateChanged(FlashSaleEvent sale, FlashSaleState previousState) {
        System.out.printf("[LOG] Sale %s: %s -> %s%n",
            sale.saleId, previousState, sale.getState());
    }

    @Override
    public void onSoldOut(FlashSaleEvent sale) {
        System.out.printf("[LOG] SOLD OUT: '%s' [%s]%n", sale.item.name, sale.saleId);
    }
}

// ---- Concrete Observer B: Email (simulated) ----

class EmailNotificationObserver implements INotificationObserver {
    @Override
    public void onOrderConfirmed(Order order, FlashSaleEvent sale) {
        System.out.printf("[EMAIL] -> %s: Order %s confirmed for '%s' at $%.2f%n",
            order.userId, order.orderId, sale.item.name, order.pricePaid);
    }

    @Override
    public void onOrderFailed(String userId, String saleId, String reason) {
        System.out.printf("[EMAIL] -> %s: Your order for sale %s failed (%s)%n",
            userId, saleId, reason);
    }

    @Override
    public void onSaleStateChanged(FlashSaleEvent sale, FlashSaleState previousState) {
        if (sale.getState() == FlashSaleState.ACTIVE) {
            System.out.printf("[EMAIL] FLASH SALE LIVE: '%s' at $%.2f (%.0f%% off) — hurry!%n",
                sale.item.name, sale.item.salePrice, sale.item.discountPercent());
        } else if (sale.getState() == FlashSaleState.ENDED) {
            System.out.printf("[EMAIL] Flash sale for '%s' has ended.%n", sale.item.name);
        }
    }

    @Override
    public void onSoldOut(FlashSaleEvent sale) {
        System.out.printf("[EMAIL] '%s' flash sale is SOLD OUT!%n", sale.item.name);
    }
}

// ============================================================
// SECTION 7: NotificationFactory — Factory Pattern
// ============================================================
//
// Caller passes a NotificationType token and gets back an
// INotificationObserver without knowing the concrete class.
// Adding a new channel (SMS, Slack) = add enum value + one case here.
//
class NotificationFactory {
    public static INotificationObserver create(NotificationType type) {
        switch (type) {
            case LOG:   return new LogNotificationObserver();
            case EMAIL: return new EmailNotificationObserver();
            default: throw new IllegalArgumentException("Unknown notification type: " + type);
        }
    }
}

// ============================================================
// SECTION 8: OrderIdGenerator — Singleton Pattern
// ============================================================
//
// There must be exactly one global ID counter.
// Multiple instances would produce duplicate IDs.
// Uses initialization-on-demand holder for lazy, thread-safe construction.
//
class OrderIdGenerator {
    private static class Holder {
        static final OrderIdGenerator INSTANCE = new OrderIdGenerator();
    }

    public static OrderIdGenerator getInstance() { return Holder.INSTANCE; }

    private final AtomicLong counter = new AtomicLong(0);
    private OrderIdGenerator() {}

    public String next() {
        return "ORD-" + String.format("%06d", counter.incrementAndGet());
    }
}

// ============================================================
// SECTION 9: SaleIdGenerator — Singleton Pattern
// ============================================================

class SaleIdGenerator {
    private static class Holder {
        static final SaleIdGenerator INSTANCE = new SaleIdGenerator();
    }

    public static SaleIdGenerator getInstance() { return Holder.INSTANCE; }

    private final AtomicInteger counter = new AtomicInteger(0);
    private SaleIdGenerator() {}

    public String next() {
        return "SALE-" + String.format("%04d", counter.incrementAndGet());
    }
}

// ============================================================
// SECTION 10: FlashSaleEngine — orchestrates everything
// ============================================================
//
// Order placement pipeline:
//   1. Validate sale exists and is active
//   2. Dedup — reject if user already placed an order for this sale
//   3. Reserve inventory (lock-free CAS)
//   4. Create and persist order
//   5. Notify observers
//
class FlashSaleEngine {
    // ConcurrentHashMap for lock-free concurrent read access
    private final Map<String, FlashSaleEvent> sales          = new ConcurrentHashMap<>();
    private final Map<String, List<Order>>    ordersBySale   = new ConcurrentHashMap<>();
    // Per-sale set of userIds who already ordered — prevents duplicate purchases
    private final Map<String, Set<String>>    userOrderedMap = new ConcurrentHashMap<>();

    private final List<INotificationObserver> observers  = new ArrayList<>();
    private final ReentrantLock               obsLock    = new ReentrantLock();

    // ---- Observer management ----

    void addObserver(INotificationObserver obs) {
        obsLock.lock();
        try { observers.add(obs); }
        finally { obsLock.unlock(); }
    }

    private List<INotificationObserver> snapshotObservers() {
        obsLock.lock();
        try { return new ArrayList<>(observers); }
        finally { obsLock.unlock(); }
    }

    // ---- Sale lifecycle — embedded Factory Method ----

    FlashSaleEvent createSale(FlashSaleItem item, int inventoryCount,
                              Instant startTime, Instant endTime) {
        String saleId = SaleIdGenerator.getInstance().next();
        FlashSaleEvent sale = new FlashSaleEvent(saleId, item, inventoryCount, startTime, endTime);
        sales.put(saleId, sale);
        ordersBySale.put(saleId, Collections.synchronizedList(new ArrayList<>()));
        userOrderedMap.put(saleId, ConcurrentHashMap.newKeySet());
        System.out.printf("[ENGINE] Created  %-9s  %-25s  $%.2f (%.0f%% off)  qty=%d%n",
            saleId, item.name, item.salePrice, item.discountPercent(), inventoryCount);
        return sale;
    }

    boolean activateSale(String saleId) {
        return changeSaleState(saleId, FlashSaleState.ACTIVE);
    }

    boolean endSale(String saleId) {
        return changeSaleState(saleId, FlashSaleState.ENDED);
    }

    boolean cancelSale(String saleId) {
        return changeSaleState(saleId, FlashSaleState.CANCELLED);
    }

    private boolean changeSaleState(String saleId, FlashSaleState newState) {
        FlashSaleEvent sale = sales.get(saleId);
        if (sale == null) {
            System.err.println("[ENGINE] Sale not found: " + saleId);
            return false;
        }
        FlashSaleState prev = sale.getState();
        boolean ok = sale.transitionTo(newState);
        if (ok) notifySaleStateChanged(sale, prev);
        return ok;
    }

    // ---- Order placement — the critical path ----

    Order placeOrder(String saleId, String userId) {
        FlashSaleEvent sale = sales.get(saleId);

        // 1. Validate sale exists
        if (sale == null) {
            notifyFailed(userId, saleId, "sale_not_found");
            return null;
        }

        // 2. Validate sale is active and within its time window
        if (!sale.isAcceptingOrders()) {
            String reason;
            switch (sale.getState()) {
                case SCHEDULED: reason = "sale_not_started"; break;
                case ENDED:     reason = "sale_ended";       break;
                case CANCELLED: reason = "sale_cancelled";   break;
                default:        reason = "sale_inactive";    break;
            }
            notifyFailed(userId, saleId, reason);
            return null;
        }

        // 3. Dedup — one order per user per sale
        //    ConcurrentHashMap.newKeySet() makes .add() atomic
        Set<String> buyers = userOrderedMap.get(saleId);
        if (!buyers.add(userId)) {
            notifyFailed(userId, saleId, "duplicate_order");
            return null;
        }

        // 4. Reserve inventory (CAS — no overselling)
        if (!sale.inventory.tryReserve()) {
            buyers.remove(userId);   // undo dedup; user may retry if stock is released
            notifyFailed(userId, saleId, "sold_out");
            if (sale.inventory.isSoldOut()) notifySoldOut(sale);
            return null;
        }

        // 5. Create and persist order
        Order order = new Order(
            OrderIdGenerator.getInstance().next(),
            saleId, userId, sale.item.itemId, sale.item.salePrice
        );
        ordersBySale.get(saleId).add(order);
        System.out.printf("[ENGINE] %s%n", order);

        // 6. Notify on success
        notifyOrderConfirmed(order, sale);

        // 7. Check sold-out after this reservation
        if (sale.inventory.isSoldOut()) notifySoldOut(sale);

        return order;
    }

    // Cancel an order and release its inventory unit
    boolean cancelOrder(String saleId, String orderId) {
        List<Order> orders = ordersBySale.get(saleId);
        if (orders == null) return false;
        synchronized (orders) {
            for (Order o : orders) {
                if (o.orderId.equals(orderId)) {
                    boolean cancelled = o.cancel();
                    if (cancelled) {
                        FlashSaleEvent sale = sales.get(saleId);
                        if (sale != null) {
                            sale.inventory.release();
                            // Allow the user to reorder after a cancellation
                            Set<String> buyers = userOrderedMap.get(saleId);
                            if (buyers != null) buyers.remove(o.userId);
                            System.out.printf("[ENGINE] Order %s cancelled, inventory released.%n", orderId);
                        }
                    }
                    return cancelled;
                }
            }
        }
        return false;
    }

    FlashSaleEvent getSale(String saleId) { return sales.get(saleId); }

    List<Order> getOrdersForSale(String saleId) {
        List<Order> orders = ordersBySale.get(saleId);
        if (orders == null) return Collections.emptyList();
        synchronized (orders) { return new ArrayList<>(orders); }
    }

    void printAllSales() {
        System.out.println("\n=== All Flash Sales ===");
        sales.values().forEach(FlashSaleEvent::printStatus);
        System.out.println();
    }

    // ---- Notification dispatch ----

    private void notifyOrderConfirmed(Order o, FlashSaleEvent sale) {
        snapshotObservers().forEach(obs -> obs.onOrderConfirmed(o, sale));
    }

    private void notifyFailed(String userId, String saleId, String reason) {
        snapshotObservers().forEach(obs -> obs.onOrderFailed(userId, saleId, reason));
    }

    private void notifySaleStateChanged(FlashSaleEvent sale, FlashSaleState prev) {
        snapshotObservers().forEach(obs -> obs.onSaleStateChanged(sale, prev));
    }

    private void notifySoldOut(FlashSaleEvent sale) {
        snapshotObservers().forEach(obs -> obs.onSoldOut(sale));
    }
}

// ============================================================
// SECTION 11: Main — demonstration scenarios
// ============================================================

public class FlashSale {

    public static void main(String[] args) {
        System.out.println("================================================");
        System.out.println("        Flash Sale System — Demo");
        System.out.println("================================================\n");

        FlashSaleEngine engine = new FlashSaleEngine();

        // Wire observers via factory — caller only sees NotificationType
        engine.addObserver(NotificationFactory.create(NotificationType.LOG));
        engine.addObserver(NotificationFactory.create(NotificationType.EMAIL));

        // Products
        FlashSaleItem laptop  = new FlashSaleItem("ITEM-001", "MacBook Pro 14\"",  2499.00, 1799.00);
        FlashSaleItem airpods = new FlashSaleItem("ITEM-002", "AirPods Pro",         249.00,  149.00);
        FlashSaleItem watch   = new FlashSaleItem("ITEM-003", "Apple Watch Ultra",   799.00,  549.00);
        FlashSaleItem ipad    = new FlashSaleItem("ITEM-004", "iPad Pro 12.9\"",    1099.00,  799.00);

        Instant now = Instant.now();
        Instant active_start = now.minusSeconds(60);   // started 1 min ago
        Instant active_end   = now.plusSeconds(3600);  // ends in 1 hour
        Instant future_start = now.plusSeconds(3600);  // 1 hour from now
        Instant future_end   = now.plusSeconds(7200);

        // -----------------------------------------------
        // Scenario 1: Normal flash sale — happy path
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 1: Laptop Flash Sale (qty=3, happy path) ===");
        FlashSaleEvent laptopSale = engine.createSale(laptop, 3, active_start, active_end);
        engine.activateSale(laptopSale.saleId);

        Order o1 = engine.placeOrder(laptopSale.saleId, "user-alice");
        Order o2 = engine.placeOrder(laptopSale.saleId, "user-bob");
        Order o3 = engine.placeOrder(laptopSale.saleId, "user-carol");

        laptopSale.printStatus();

        // -----------------------------------------------
        // Scenario 2: Inventory exhaustion — sold out
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 2: AirPods Sale (qty=2) — sells out ===");
        FlashSaleEvent airpodsSale = engine.createSale(airpods, 2, active_start, active_end);
        engine.activateSale(airpodsSale.saleId);

        engine.placeOrder(airpodsSale.saleId, "user-alice");
        engine.placeOrder(airpodsSale.saleId, "user-bob");
        engine.placeOrder(airpodsSale.saleId, "user-carol"); // sold out — rejected

        airpodsSale.printStatus();

        // -----------------------------------------------
        // Scenario 3: Duplicate order by same user
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 3: Duplicate Order Prevention ===");
        FlashSaleEvent watchSale = engine.createSale(watch, 10, active_start, active_end);
        engine.activateSale(watchSale.saleId);

        engine.placeOrder(watchSale.saleId, "user-alice");
        engine.placeOrder(watchSale.saleId, "user-alice"); // duplicate — rejected

        // -----------------------------------------------
        // Scenario 4: Order cancellation + re-order
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 4: Order Cancellation and Re-order ===");
        Order watchOrder = engine.placeOrder(watchSale.saleId, "user-dave");
        System.out.printf("  Inventory before cancel: %d%n", watchSale.inventory.getRemaining());
        engine.cancelOrder(watchSale.saleId, watchOrder.orderId);
        System.out.printf("  Inventory after cancel:  %d%n", watchSale.inventory.getRemaining());
        engine.placeOrder(watchSale.saleId, "user-dave"); // re-order should succeed

        // -----------------------------------------------
        // Scenario 5: Order on an ended sale
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 5: Order on Ended Sale ===");
        engine.endSale(watchSale.saleId);
        engine.placeOrder(watchSale.saleId, "user-eve"); // sale ended — rejected

        // -----------------------------------------------
        // Scenario 6: Order on a sale not yet started
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 6: Sale Not Yet Started ===");
        FlashSaleEvent futureSale = engine.createSale(ipad, 50, future_start, future_end);
        // intentionally NOT activated — remains SCHEDULED
        engine.placeOrder(futureSale.saleId, "user-bob"); // not started — rejected

        // -----------------------------------------------
        // Scenario 7: Invalid state transition
        // -----------------------------------------------
        System.out.println("\n=== SCENARIO 7: Invalid State Transition (ENDED -> ACTIVE) ===");
        engine.activateSale(watchSale.saleId); // watchSale is ENDED — rejected

        // -----------------------------------------------
        // Summary
        // -----------------------------------------------
        engine.printAllSales();

        System.out.println("=== Orders for Laptop Sale ===");
        engine.getOrdersForSale(laptopSale.saleId)
              .forEach(o -> System.out.println("  " + o));

        System.out.println("\n=== Orders for Watch Sale ===");
        engine.getOrdersForSale(watchSale.saleId)
              .forEach(o -> System.out.println("  " + o));
    }
}
