package JavaConcepts;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * ParkingRate - Calculates parking fees with time-of-day and vehicle-type pricing.
 *
 * Demonstrates:
 * - Static factory pattern
 * - HashMap for O(1) rate lookups
 * - Java Time API: LocalTime for peak-hour logic
 * - Strategy pattern: rate strategies injected via functional interfaces (lambdas)
 * - Nested functional interface RateStrategy
 */
public class ParkingRate {

    /**
     * RateStrategy - Functional interface defining the rate-computation strategy.
     * Used as a lambda target: different strategies can be swapped at runtime.
     */
    @FunctionalInterface
    public interface RateStrategy {
        double computeRate(double baseRate, boolean isPeakHour);
    }

    // Peak hours: 8 AM – 10 AM and 5 PM – 8 PM (rush hour)
    private static final LocalTime MORNING_PEAK_START = LocalTime.of(8, 0);
    private static final LocalTime MORNING_PEAK_END   = LocalTime.of(10, 0);
    private static final LocalTime EVENING_PEAK_START = LocalTime.of(17, 0);
    private static final LocalTime EVENING_PEAK_END   = LocalTime.of(20, 0);

    private static final double PEAK_MULTIPLIER = 1.5;

    // Base rates per vehicle type — stored in a HashMap for quick lookup
    private final Map<VehicleType, Double> baseRates;

    // Strategy determines how the final rate is computed from base + peak info
    private final RateStrategy rateStrategy;

    // -------------------------------------------------------------------------
    // Private constructor — use static factories
    // -------------------------------------------------------------------------

    private ParkingRate(Map<VehicleType, Double> baseRates, RateStrategy strategy) {
        this.baseRates = new HashMap<>(baseRates); // defensive copy
        this.rateStrategy = strategy;
    }

    // -------------------------------------------------------------------------
    // Static factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a standard rate plan with peak-hour surcharges.
     * The lambda (base, isPeak) -> ... is assigned to the RateStrategy functional interface.
     */
    public static ParkingRate standardPlan() {
        Map<VehicleType, Double> rates = new HashMap<>();
        rates.put(VehicleType.BIKE,         1.50);
        rates.put(VehicleType.CAR,          3.00);
        rates.put(VehicleType.TRUCK,        6.00);
        rates.put(VehicleType.RENTAL_CAR,   2.50);
        rates.put(VehicleType.RENTAL_TRUCK, 5.00);

        // Lambda as RateStrategy: peak hours cost 50% more
        RateStrategy standard = (base, isPeak) -> isPeak ? base * PEAK_MULTIPLIER : base;
        return new ParkingRate(rates, standard);
    }

    /**
     * Creates a flat-rate plan (same price regardless of time of day).
     * Demonstrates a different lambda for the same functional interface.
     */
    public static ParkingRate flatRatePlan() {
        Map<VehicleType, Double> rates = new HashMap<>();
        rates.put(VehicleType.BIKE,         1.00);
        rates.put(VehicleType.CAR,          2.50);
        rates.put(VehicleType.TRUCK,        5.00);
        rates.put(VehicleType.RENTAL_CAR,   2.00);
        rates.put(VehicleType.RENTAL_TRUCK, 4.50);

        // Flat rate: peak flag is ignored
        RateStrategy flat = (base, isPeak) -> base;
        return new ParkingRate(rates, flat);
    }

    // -------------------------------------------------------------------------
    // Rate computation
    // -------------------------------------------------------------------------

    /**
     * Returns the effective hourly rate for a vehicle type at the current time.
     */
    public double getHourlyRate(VehicleType vehicleType) {
        double base = baseRates.getOrDefault(vehicleType, 3.00);
        boolean peak = isPeakHour(LocalTime.now());
        // Delegate computation to the strategy lambda
        return rateStrategy.computeRate(base, peak);
    }

    /**
     * Checks if a given time falls within peak hours.
     * Demonstrates LocalTime comparisons.
     */
    public static boolean isPeakHour(LocalTime time) {
        boolean morningPeak = !time.isBefore(MORNING_PEAK_START) && time.isBefore(MORNING_PEAK_END);
        boolean eveningPeak = !time.isBefore(EVENING_PEAK_START) && time.isBefore(EVENING_PEAK_END);
        return morningPeak || eveningPeak;
    }
}
