import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;


enum FlashSaleState {
    SCHEDULE, CANCELED, ACTIVE, ENDED
}

enum OrderStatus {
    CONFIRMED, CANCELED, EXPIRED
}

enum NotificationType {
    LOG, EMAIL
}

class FlashSaleItem {
    final String itemId;
    final String name;
    final double originalPrice;
    final double salePrice;
    FlashSaleItem(String itemId, String name, double originalPrice, double salePrice) {
        this.itemId = itemId;
        this.name = name;
        this.originalPrice = originalPrice;
        this.salePrice = salePrice;
    }

    double discountPercent() {
        return (1.0 - salePrice / originalPrice) * 100.0;
    }
}

class Inventory {
    final int total;
    final AtomicInteger remaining;

    Inventory(int total)
    {
        this.total = total;
        this.remaining = new AtomicIntger(total);
    }

    boolean tryReserve() {
        int current;
        do {
            current = remaining.get();
            if(current <= 0) return false;
        } while (!remaining.compareAndSet(current, current - 1));
        return true;
    }
}