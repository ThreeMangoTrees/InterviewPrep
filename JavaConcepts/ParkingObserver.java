package JavaConcepts;

/**
 * ParkingObserver - Observer pattern interface for lot-capacity events.
 *
 * Demonstrates:
 * - Observer design pattern in Java
 * - Interface used as a callback contract
 * - Nested enum inside an interface (Java allows this)
 */
public interface ParkingObserver {

    /** Events that observers are notified about */
    enum Event {
        VEHICLE_PARKED,       // a new vehicle occupied a spot
        VEHICLE_EXITED,       // a vehicle left and freed a spot
        LOT_FULL,             // occupancy reached 100 %
        LOT_AVAILABLE,        // occupancy dropped back below 100 %
        CAPACITY_WARNING      // occupancy crossed a configurable threshold (e.g. 90 %)
    }

    /**
     * Called whenever a parking event occurs.
     *
     * @param event       the type of event
     * @param lotId       identifier of the parking lot that raised the event
     * @param occupancy   current occupancy percentage (0–100)
     */
    void onParkingEvent(Event event, String lotId, int occupancy);
}
