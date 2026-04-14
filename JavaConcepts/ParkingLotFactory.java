package JavaConcepts;

/**
 * ParkingLotFactory - Factory class for building pre-configured parking lots.
 *
 * Demonstrates:
 * - Factory Method pattern: static methods returning configured objects
 * - Method chaining (fluent API) via the builder in ParkingTicket
 * - Organizing complex object construction away from the caller
 * - final class: utility/factory classes are often made final to prevent subclassing
 */
public final class ParkingLotFactory {

    // Private constructor: this is a utility class — should never be instantiated
    private ParkingLotFactory() {
        throw new UnsupportedOperationException("ParkingLotFactory is a utility class");
    }

    /**
     * Builds a small city parking lot with 2 floors.
     * Floor 1: primarily bikes and cars
     * Floor 2: trucks and overflow cars
     *
     * @return a fully configured ParkingLot singleton
     */
    public static ParkingLot buildSmallCityLot() {
        ParkingLot lot = ParkingLot.getInstance();

        // ---- Floor 1 ----
        ParkingFloor floor1 = new ParkingFloor(1);

        // 5 small spots for bikes (row 1)
        for (int i = 1; i <= 5; i++) {
            floor1.addSpots(new SmallSpot(new ParkingSpot.SpotId(1, 1, i)));
        }
        // 8 medium spots for cars (row 2)
        for (int i = 1; i <= 8; i++) {
            floor1.addSpots(new MediumSpot(new ParkingSpot.SpotId(1, 2, i)));
        }
        // 2 large spots for trucks (row 3)
        floor1.addSpots(new LargeSpot(new ParkingSpot.SpotId(1, 3, 1)));
        floor1.addSpots(new LargeSpot(new ParkingSpot.SpotId(1, 3, 2)));

        // ---- Floor 2 ----
        ParkingFloor floor2 = new ParkingFloor(2);

        // 3 more small spots for bikes
        for (int i = 1; i <= 3; i++) {
            floor2.addSpots(new SmallSpot(new ParkingSpot.SpotId(2, 1, i)));
        }
        // 4 medium spots for cars
        for (int i = 1; i <= 4; i++) {
            floor2.addSpots(new MediumSpot(new ParkingSpot.SpotId(2, 2, i)));
        }
        // 2 large spots with overflow enabled (can take cars when no trucks present)
        floor2.addSpots(new LargeSpot(new ParkingSpot.SpotId(2, 3, 1), true));
        floor2.addSpots(new LargeSpot(new ParkingSpot.SpotId(2, 3, 2), true));

        lot.addFloor(floor1);
        lot.addFloor(floor2);

        // Attach the capacity monitor (Observer pattern)
        CapacityMonitor monitor = new CapacityMonitor("MON-1", 80);
        lot.addObserver(monitor);

        return lot;
    }
}
