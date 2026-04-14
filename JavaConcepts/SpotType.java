package JavaConcepts;

/**
 * SpotType - Enum representing the size categories of parking spots.
 *
 * Demonstrates:
 * - Enum with abstract method (each constant must implement it)
 * - Enum implementing an interface
 * - Per-constant class bodies (anonymous class behavior inside enum)
 */
public enum SpotType {

    /**
     * SMALL spots fit only Bikes.
     * Each constant can override abstract methods — like anonymous inner classes.
     */
    SMALL("Small", 1) {
        @Override
        public boolean canFit(VehicleType vehicleType) {
            return vehicleType == VehicleType.BIKE;
        }
    },

    /**
     * MEDIUM spots fit Cars and Rental Cars.
     */
    MEDIUM("Medium", 2) {
        @Override
        public boolean canFit(VehicleType vehicleType) {
            return vehicleType == VehicleType.CAR || vehicleType == VehicleType.RENTAL_CAR;
        }
    },

    /**
     * LARGE spots fit everything — a large spot can also accommodate smaller vehicles.
     */
    LARGE("Large", 4) {
        @Override
        public boolean canFit(VehicleType vehicleType) {
            // Trucks and rental trucks need large spots; smaller vehicles can use large spots too
            return true;
        }
    };

    private final String label;
    private final int capacityUnits; // relative size measure

    SpotType(String label, int capacityUnits) {
        this.label = label;
        this.capacityUnits = capacityUnits;
    }

    /** Abstract method — every constant must provide its own implementation */
    public abstract boolean canFit(VehicleType vehicleType);

    public String getLabel() {
        return label;
    }

    public int getCapacityUnits() {
        return capacityUnits;
    }
}
