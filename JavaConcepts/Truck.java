package JavaConcepts;

/**
 * Truck - Concrete subclass of Vehicle for heavy goods vehicles.
 *
 * Demonstrates:
 * - Multiple constructors with different signatures (constructor overloading)
 * - Primitive vs wrapper types (double vs Double)
 * - String.format for structured output
 * - Additional business logic in a concrete subclass
 */
public class Truck extends Vehicle {

    private static final double BASE_HOURLY_RATE = 6.00;

    // Cargo weight in tonnes — trucks are charged based on their load
    private final double cargoWeightTonnes;
    private final boolean isHazardousCargo;

    /**
     * Full constructor.
     *
     * @param licensePlate        plate number
     * @param ownerName           driver / company name
     * @param cargoWeightTonnes   weight of cargo (0 if empty)
     * @param isHazardousCargo    true if carrying dangerous goods (extra surcharge)
     */
    public Truck(String licensePlate, String ownerName,
                 double cargoWeightTonnes, boolean isHazardousCargo) {
        super(licensePlate, VehicleType.TRUCK, ownerName);

        if (cargoWeightTonnes < 0) {
            throw new IllegalArgumentException("Cargo weight cannot be negative");
        }

        this.cargoWeightTonnes = cargoWeightTonnes;
        this.isHazardousCargo = isHazardousCargo;
    }

    /** Convenience constructor — no hazardous cargo */
    public Truck(String licensePlate, String ownerName, double cargoWeightTonnes) {
        this(licensePlate, ownerName, cargoWeightTonnes, false);
    }

    /** Convenience constructor — empty truck, no hazardous cargo */
    public Truck(String licensePlate, String ownerName) {
        this(licensePlate, ownerName, 0.0, false);
    }

    // -------------------------------------------------------------------------
    // Abstract method implementations
    // -------------------------------------------------------------------------

    @Override
    public double getHourlyRate() {
        double rate = BASE_HOURLY_RATE;

        // Surcharge for heavy cargo: +$0.50 per tonne
        rate += cargoWeightTonnes * 0.50;

        // Hazardous cargo: 50% surcharge on top of the weight-adjusted rate
        if (isHazardousCargo) {
            rate *= 1.50;
        }

        return rate;
    }

    @Override
    public String getDescription() {
        return String.format("Truck [%s] | Cargo: %.1f t | Hazardous: %s",
                getLicensePlate(), cargoWeightTonnes, isHazardousCargo ? "YES" : "No");
    }

    // -------------------------------------------------------------------------
    // Truck-specific accessors
    // -------------------------------------------------------------------------

    public double getCargoWeightTonnes() {
        return cargoWeightTonnes;
    }

    public boolean isHazardousCargo() {
        return isHazardousCargo;
    }

    /** Checks if the truck is carrying a load (non-empty) */
    public boolean isLoaded() {
        return cargoWeightTonnes > 0;
    }
}
