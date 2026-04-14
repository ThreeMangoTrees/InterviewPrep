package JavaConcepts;

/**
 * LargeSpot - Concrete ParkingSpot for Trucks and rental trucks.
 * Large spots can also accommodate smaller vehicles when no other spot is available.
 *
 * Demonstrates:
 * - Flexible acceptance logic
 * - Boolean field to control overflow policy
 * - Method overriding for dynamic behaviour changes
 */
public final class LargeSpot extends ParkingSpot {

    /**
     * When true, smaller vehicles (cars, bikes) may use this spot as overflow.
     * When false, reserved strictly for trucks.
     */
    private boolean allowOverflow;

    public LargeSpot(SpotId id, boolean allowOverflow) {
        super(id, SpotType.LARGE);
        this.allowOverflow = allowOverflow;
    }

    /** Defaults to NOT allowing overflow (reserved for trucks) */
    public LargeSpot(SpotId id) {
        this(id, false);
    }

    /**
     * Hook implementation:
     * - Always accepts Trucks and rental trucks.
     * - Accepts cars/bikes only when overflow mode is enabled.
     */
    @Override
    protected boolean canAccept(Vehicle vehicle) {
        // Always accept trucks
        if (vehicle instanceof Truck) return true;

        if (vehicle instanceof RentalVehicle) {
            return vehicle.getVehicleType() == VehicleType.RENTAL_TRUCK;
        }

        // Overflow: accept smaller vehicles if policy allows
        return allowOverflow;
    }

    // Setter to toggle overflow policy at runtime (e.g., during peak hours)
    public void setAllowOverflow(boolean allowOverflow) {
        this.allowOverflow = allowOverflow;
    }

    public boolean isAllowOverflow() {
        return allowOverflow;
    }
}
