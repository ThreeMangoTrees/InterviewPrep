package JavaConcepts;

import java.time.LocalDateTime;

/**
 * Rentable - Interface for vehicles that can be rented.
 *
 * Demonstrates:
 * - Interface declaration with abstract methods
 * - Default methods (Java 8+): provide a default implementation
 * - Static interface methods (Java 8+): utility methods on the interface itself
 * - Constants in interfaces (implicitly public static final)
 */
public interface Rentable {

    // Interface constant — implicitly public static final
    int MAX_RENTAL_DAYS = 30;

    // Abstract methods — every implementing class must provide these
    void startRental(String customerId, LocalDateTime startTime);
    void endRental(LocalDateTime endTime);
    boolean isCurrentlyRented();
    String getCurrentCustomerId();

    /**
     * Default method — provides a base implementation.
     * Implementing classes can override this if needed.
     */
    default double calculateRentalCost(double dailyRate) {
        // Base implementation: not rented means no cost
        if (!isCurrentlyRented()) {
            return 0.0;
        }
        // Subclasses with richer state should override this
        return dailyRate;
    }

    /**
     * Default method — checks if the vehicle has exceeded the max rental period.
     * Demonstrates how default methods can call other interface methods.
     */
    default boolean isOverdue(LocalDateTime rentalStart) {
        return rentalStart.plusDays(MAX_RENTAL_DAYS).isBefore(LocalDateTime.now());
    }

    /**
     * Static interface method — utility that doesn't need an instance.
     * Cannot be overridden by implementing classes.
     */
    static boolean isValidRentalPeriod(int days) {
        return days > 0 && days <= MAX_RENTAL_DAYS;
    }
}
