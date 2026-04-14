package JavaConcepts;

/**
 * SmallSpot - Concrete ParkingSpot for bikes.
 *
 * Demonstrates:
 * - Concrete subclass implementing the abstract hook from ParkingSpot
 * - Narrow type check using instanceof
 * - Single-responsibility: only bikes accepted
 */
public final class SmallSpot extends ParkingSpot {

    // 'final' class — cannot be subclassed further

    public SmallSpot(SpotId id) {
        super(id, SpotType.SMALL);
    }

    /**
     * Hook implementation: SmallSpots only accept Bike instances.
     * Uses instanceof to verify the runtime type.
     */
    @Override
    protected boolean canAccept(Vehicle vehicle) {
        // instanceof check — demonstrates runtime type inspection
        return vehicle instanceof Bike;
    }
}
