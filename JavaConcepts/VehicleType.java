package JavaConcepts;

/**
 * VehicleType - Enum demonstrating Java Enums with fields, constructors, and methods.
 *
 * Enums in Java are special classes that represent a fixed set of constants.
 * They can have fields, constructors, and methods just like regular classes.
 */
public enum VehicleType {

    // Each constant has associated metadata: display name and required spot size
    BIKE("Bike", SpotType.SMALL),
    CAR("Car", SpotType.MEDIUM),
    TRUCK("Truck", SpotType.LARGE),
    RENTAL_CAR("Rental Car", SpotType.MEDIUM),
    RENTAL_TRUCK("Rental Truck", SpotType.LARGE);

    // Enum fields — final because enum constants are immutable
    private final String displayName;
    private final SpotType requiredSpotType;

    // Enum constructor — always private by default
    VehicleType(String displayName, SpotType requiredSpotType) {
        this.displayName = displayName;
        this.requiredSpotType = requiredSpotType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public SpotType getRequiredSpotType() {
        return requiredSpotType;
    }

    /**
     * Demonstrates enum method: checks if this vehicle type is a rental.
     * Uses 'this' to reference the current enum constant.
     */
    public boolean isRental() {
        return this == RENTAL_CAR || this == RENTAL_TRUCK;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
