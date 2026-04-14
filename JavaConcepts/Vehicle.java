package JavaConcepts;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Vehicle - Abstract base class for all vehicles in the parking system.
 *
 * Demonstrates:
 * - Abstract class: cannot be instantiated directly; acts as a template
 * - Encapsulation: private fields exposed via public getters
 * - Abstract methods: subclasses MUST provide concrete implementations
 * - this keyword: refers to the current instance
 * - super keyword: used by subclasses to call this constructor
 * - equals() and hashCode() contract
 * - Comparable<T>: natural ordering by registration time
 */
public abstract class Vehicle implements Comparable<Vehicle> {

    // Encapsulated fields — private, accessible only through getters
    private final String licensePlate;    // immutable after creation
    private final VehicleType vehicleType;
    private final String ownerName;
    private final LocalDateTime registeredAt;

    // Mutable state — current parking status
    private boolean isParked;

    /**
     * Protected constructor — only subclasses and classes in the same package
     * can call this. Forces creation through concrete subclasses.
     *
     * @param licensePlate  unique plate number (cannot be null or blank)
     * @param vehicleType   type enum value
     * @param ownerName     owner's name
     */
    protected Vehicle(String licensePlate, VehicleType vehicleType, String ownerName) {
        // Validate inputs at the boundary — fail fast with clear messages
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("License plate cannot be null or blank");
        }
        if (vehicleType == null) {
            throw new IllegalArgumentException("VehicleType cannot be null");
        }

        // 'this' keyword refers to the current instance being constructed
        this.licensePlate = licensePlate.toUpperCase().trim();
        this.vehicleType = vehicleType;
        this.ownerName = ownerName != null ? ownerName : "Unknown";
        this.registeredAt = LocalDateTime.now();
        this.isParked = false;
    }

    // -------------------------------------------------------------------------
    // Abstract methods — subclasses MUST implement these
    // -------------------------------------------------------------------------

    /**
     * Returns the hourly parking rate specific to this vehicle type.
     * Each vehicle subclass defines its own rate.
     */
    public abstract double getHourlyRate();

    /**
     * Returns a human-readable description of the vehicle.
     * Demonstrates abstract method used for polymorphic display.
     */
    public abstract String getDescription();

    // -------------------------------------------------------------------------
    // Concrete methods — available to all subclasses and callers
    // -------------------------------------------------------------------------

    /**
     * Checks whether the vehicle fits in the given spot type.
     * Delegates to VehicleType, demonstrating encapsulation of logic in the enum.
     */
    public boolean fitsInSpot(SpotType spotType) {
        return spotType.canFit(this.vehicleType);
    }

    // -------------------------------------------------------------------------
    // Getters — part of the public API
    // -------------------------------------------------------------------------

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public boolean isParked() {
        return isParked;
    }

    // Package-private setter — only ParkingSpot should change parking state
    void setParked(boolean parked) {
        this.isParked = parked;
    }

    // -------------------------------------------------------------------------
    // Comparable<Vehicle> — natural ordering by registration time
    // -------------------------------------------------------------------------

    @Override
    public int compareTo(Vehicle other) {
        // Older registrations come first (ascending order)
        return this.registeredAt.compareTo(other.registeredAt);
    }

    // -------------------------------------------------------------------------
    // equals / hashCode — identity based on license plate (business key)
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;              // same reference
        if (!(obj instanceof Vehicle)) return false; // type check via instanceof
        Vehicle other = (Vehicle) obj;
        return Objects.equals(this.licensePlate, other.licensePlate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(licensePlate);
    }

    @Override
    public String toString() {
        return String.format("[%s | %s | Owner: %s | Parked: %s]",
                vehicleType, licensePlate, ownerName, isParked);
    }
}
