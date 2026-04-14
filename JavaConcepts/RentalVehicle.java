package JavaConcepts;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * RentalVehicle - A car or truck that belongs to a rental fleet.
 *
 * Demonstrates:
 * - Implementing multiple interfaces (Rentable) alongside inheritance
 * - Interface default method override
 * - Mutable state managed with synchronized methods (thread safety)
 * - Delegation to super class methods via 'super'
 * - Null-safety with Optional-style pattern (isCurrentlyRented guard)
 */
public class RentalVehicle extends Vehicle implements Rentable {

    // Daily rate charged by the rental company to their customer
    private final double dailyRentalRate;

    // Rental state — mutable, protected by synchronized access
    private volatile String currentCustomerId;    // volatile: visible across threads
    private volatile LocalDateTime rentalStartTime;

    /**
     * @param licensePlate    fleet registration plate
     * @param vehicleType     must be RENTAL_CAR or RENTAL_TRUCK
     * @param dailyRentalRate price per day charged to the renter
     */
    public RentalVehicle(String licensePlate, VehicleType vehicleType, double dailyRentalRate) {
        super(licensePlate, vehicleType, "CityRent Fleet");

        if (!vehicleType.isRental()) {
            throw new IllegalArgumentException(
                    "RentalVehicle must use a RENTAL_CAR or RENTAL_TRUCK type, got: " + vehicleType);
        }
        if (dailyRentalRate <= 0) {
            throw new IllegalArgumentException("Daily rental rate must be positive");
        }

        this.dailyRentalRate = dailyRentalRate;
    }

    // -------------------------------------------------------------------------
    // Rentable interface implementation
    // -------------------------------------------------------------------------

    /**
     * synchronized method: only one thread can execute this at a time.
     * Prevents two customers from renting the same vehicle simultaneously.
     */
    @Override
    public synchronized void startRental(String customerId, LocalDateTime startTime) {
        if (isCurrentlyRented()) {
            throw new ParkingException.ParkingSystemException(
                    "Vehicle " + getLicensePlate() + " is already rented to " + currentCustomerId);
        }
        this.currentCustomerId = customerId;
        this.rentalStartTime = startTime;
        System.out.printf("[RENTAL] Vehicle %s rented to customer %s at %s%n",
                getLicensePlate(), customerId, startTime);
    }

    @Override
    public synchronized void endRental(LocalDateTime endTime) {
        if (!isCurrentlyRented()) {
            throw new ParkingException.ParkingSystemException(
                    "Vehicle " + getLicensePlate() + " is not currently rented");
        }
        System.out.printf("[RENTAL] Rental ended for customer %s — vehicle %s — cost: $%.2f%n",
                currentCustomerId, getLicensePlate(), calculateRentalCost(dailyRentalRate));
        this.currentCustomerId = null;
        this.rentalStartTime = null;
    }

    @Override
    public boolean isCurrentlyRented() {
        return currentCustomerId != null;
    }

    @Override
    public String getCurrentCustomerId() {
        return currentCustomerId;
    }

    /**
     * Override default method from Rentable to use actual elapsed days.
     */
    @Override
    public double calculateRentalCost(double dailyRate) {
        if (!isCurrentlyRented() || rentalStartTime == null) {
            return 0.0;
        }
        // ChronoUnit.DAYS.between: demonstrates Java Time API
        long daysRented = ChronoUnit.DAYS.between(rentalStartTime, LocalDateTime.now());
        // Minimum charge: 1 day
        long billableDays = Math.max(1, daysRented);
        return billableDays * dailyRate;
    }

    // -------------------------------------------------------------------------
    // Vehicle abstract method implementations
    // -------------------------------------------------------------------------

    @Override
    public double getHourlyRate() {
        // Rental vehicles pay while parked at the city lot; smaller rate because
        // the company parks many vehicles here
        return 2.00;
    }

    @Override
    public String getDescription() {
        String status = isCurrentlyRented()
                ? "Rented to: " + currentCustomerId
                : "Available";
        return String.format("Rental %s [%s] | Daily: $%.2f | %s",
                getVehicleType().getDisplayName(), getLicensePlate(), dailyRentalRate, status);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public double getDailyRentalRate() {
        return dailyRentalRate;
    }

    public LocalDateTime getRentalStartTime() {
        return rentalStartTime;
    }
}
