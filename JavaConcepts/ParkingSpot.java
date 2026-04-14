package JavaConcepts;

import java.util.Optional;

/**
 * ParkingSpot - Abstract class representing a single parking space.
 *
 * Demonstrates:
 * - Abstract class with a mix of abstract and concrete methods
 * - Optional<T>: null-safe container for the currently parked vehicle
 * - synchronized methods for thread-safe spot management
 * - Template Method pattern: park() calls the abstract canAccept() hook
 * - Inner static class for spot identifiers
 */
public abstract class ParkingSpot {

    /**
     * SpotId - Immutable value object identifying a spot.
     * Static nested class: does NOT need an outer instance to exist.
     */
    public static final class SpotId {
        private final int floor;
        private final int row;
        private final int number;

        public SpotId(int floor, int row, int number) {
            this.floor = floor;
            this.row = row;
            this.number = number;
        }

        @Override
        public String toString() {
            // e.g., "F1-R2-S04"
            return String.format("F%d-R%d-S%02d", floor, row, number);
        }
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final SpotId id;
    private final SpotType spotType;

    // Optional wraps the vehicle reference — explicitly communicates nullability
    private Optional<Vehicle> parkedVehicle;

    protected ParkingSpot(SpotId id, SpotType spotType) {
        this.id = id;
        this.spotType = spotType;
        this.parkedVehicle = Optional.empty(); // no vehicle initially
    }

    // -------------------------------------------------------------------------
    // Abstract hook — subclasses define extra acceptance criteria
    // -------------------------------------------------------------------------

    /**
     * Returns true if this specific spot can accept the given vehicle
     * (beyond the basic SpotType size check).
     * Template Method pattern: the concrete park() calls this.
     */
    protected abstract boolean canAccept(Vehicle vehicle);

    // -------------------------------------------------------------------------
    // Template Method: park() — defines the algorithm; calls the abstract hook
    // -------------------------------------------------------------------------

    /**
     * Parks the vehicle in this spot.
     * synchronized: only one thread may park at a time.
     *
     * @return true if parking succeeded
     */
    public synchronized boolean park(Vehicle vehicle) {
        // Spot must be empty
        if (!isAvailable()) return false;

        // Size check via SpotType
        if (!spotType.canFit(vehicle.getVehicleType())) return false;

        // Subclass-specific check (Template Method hook)
        if (!canAccept(vehicle)) return false;

        parkedVehicle = Optional.of(vehicle);
        vehicle.setParked(true); // package-private setter on Vehicle
        return true;
    }

    /**
     * Removes the currently parked vehicle.
     * synchronized: pairs with the synchronized park() to avoid race conditions.
     *
     * @return the vehicle that was parked, or Optional.empty() if the spot was free
     */
    public synchronized Optional<Vehicle> release() {
        Optional<Vehicle> released = parkedVehicle;
        released.ifPresent(v -> v.setParked(false)); // lambda: concise single-method call
        parkedVehicle = Optional.empty();
        return released;
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    public boolean isAvailable() {
        return parkedVehicle.isEmpty(); // Optional.isEmpty() — Java 11+
    }

    /** Returns the vehicle wrapped in Optional — never returns null */
    public Optional<Vehicle> getParkedVehicle() {
        return parkedVehicle;
    }

    public SpotId getId() {
        return id;
    }

    public SpotType getSpotType() {
        return spotType;
    }

    @Override
    public String toString() {
        String vehicleInfo = parkedVehicle
                .map(v -> v.getLicensePlate())     // method reference on Optional
                .orElse("EMPTY");
        return String.format("Spot[%s | %s | %s]", id, spotType.getLabel(), vehicleInfo);
    }
}
