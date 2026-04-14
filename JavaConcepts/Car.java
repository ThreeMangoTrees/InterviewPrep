package JavaConcepts;

/**
 * Car - Concrete subclass of Vehicle for standard passenger cars.
 *
 * Demonstrates:
 * - Inheritance from an abstract class
 * - Overloaded constructors
 * - Static fields for class-level constants
 * - Method overriding
 * - Using 'super' to call parent methods
 */
public class Car extends Vehicle {

    private static final double HOURLY_RATE = 3.00;

    // Enum-like string for car category — demonstrates using a nested static constant
    public static final String CATEGORY_STANDARD  = "Standard";
    public static final String CATEGORY_COMPACT   = "Compact";
    public static final String CATEGORY_SUV       = "SUV";

    private final String category;

    /**
     * Full constructor.
     *
     * @param licensePlate  plate number
     * @param ownerName     owner's full name
     * @param category      one of Car.CATEGORY_* constants
     */
    public Car(String licensePlate, String ownerName, String category) {
        super(licensePlate, VehicleType.CAR, ownerName);
        this.category = (category != null) ? category : CATEGORY_STANDARD;
    }

    /** Convenience constructor — defaults to STANDARD category */
    public Car(String licensePlate, String ownerName) {
        this(licensePlate, ownerName, CATEGORY_STANDARD);
    }

    // -------------------------------------------------------------------------
    // Abstract method implementations
    // -------------------------------------------------------------------------

    @Override
    public double getHourlyRate() {
        // SUVs charged a premium because they take more space
        if (CATEGORY_SUV.equals(category)) {
            return HOURLY_RATE * 1.25;
        }
        return HOURLY_RATE;
    }

    @Override
    public String getDescription() {
        // super.toString() calls Vehicle's toString for base info
        return String.format("%s Car [%s] — %s", category, getLicensePlate(), getOwnerName());
    }

    // -------------------------------------------------------------------------
    // Car-specific getters
    // -------------------------------------------------------------------------

    public String getCategory() {
        return category;
    }

    public boolean isSUV() {
        return CATEGORY_SUV.equals(category);
    }
}
