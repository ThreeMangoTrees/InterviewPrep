package JavaConcepts;

/**
 * MediumSpot - Concrete ParkingSpot for Cars and RentalVehicles (cars).
 *
 * Demonstrates:
 * - instanceof with type-pattern check
 * - Multiple type checks combined with logical operators
 */
public final class MediumSpot extends ParkingSpot {

    public MediumSpot(SpotId id) {
        super(id, SpotType.MEDIUM);
    }

    /**
     * Accepts Car instances and rental cars (RentalVehicle with RENTAL_CAR type).
     * Uses instanceof to check the runtime type hierarchy.
     */
    @Override
    protected boolean canAccept(Vehicle vehicle) {
        if (vehicle instanceof Car) {
            return true; // standard car
        }
        // Rental cars are RentalVehicle instances, not Car instances —
        // check the vehicleType enum field for them
        if (vehicle instanceof RentalVehicle) {
            return vehicle.getVehicleType() == VehicleType.RENTAL_CAR;
        }
        return false;
    }
}
