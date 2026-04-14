package JavaConcepts;

/**
 * Bike - Concrete subclass of Vehicle for two-wheelers.
 *
 * Demonstrates:
 * - extends keyword: single inheritance from Vehicle
 * - super() call: invoking the parent constructor
 * - Method overriding with @Override annotation
 * - final method: cannot be overridden by any further subclass
 * - Additional fields specific to this subclass
 */
public class Bike extends Vehicle {

    private static final double HOURLY_RATE = 1.50; // bikes are cheapest to park

    // Additional field unique to Bike
    private final boolean isElectric;

    /**
     * @param licensePlate  registration number
     * @param ownerName     owner's name
     * @param isElectric    true for electric bikes (may qualify for discount)
     */
    public Bike(String licensePlate, String ownerName, boolean isElectric) {
        // super() MUST be the first statement — calls Vehicle's protected constructor
        super(licensePlate, VehicleType.BIKE, ownerName);
        this.isElectric = isElectric;
    }

    /** Convenience constructor for non-electric bikes */
    public Bike(String licensePlate, String ownerName) {
        this(licensePlate, ownerName, false); // delegates to the main constructor
    }

    // -------------------------------------------------------------------------
    // Abstract method implementations
    // -------------------------------------------------------------------------

    @Override
    public double getHourlyRate() {
        // Electric bikes get a 10% discount to encourage green vehicles
        return isElectric ? HOURLY_RATE * 0.90 : HOURLY_RATE;
    }

    @Override
    public String getDescription() {
        return String.format("%s Bike [%s]",
                isElectric ? "Electric" : "Petrol",
                getLicensePlate());
    }

    // -------------------------------------------------------------------------
    // Bike-specific behaviour
    // -------------------------------------------------------------------------

    /**
     * final method — no subclass of Bike can override this.
     * Bikes always need only a small spot.
     */
    public final SpotType requiredSpotType() {
        return SpotType.SMALL;
    }

    public boolean isElectric() {
        return isElectric;
    }
}
