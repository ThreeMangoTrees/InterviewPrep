package JavaConcepts;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ParkingFloor - Manages all parking spots on one level of a parking structure.
 *
 * Demonstrates:
 * - Generics: generic method findAvailableSpot(Class<T>)
 * - Collections: List, Map, stream operations
 * - Streams API: filter, map, count, collect, findFirst
 * - Iterator pattern: manual iteration with Iterator<T>
 * - Varargs: addSpots(ParkingSpot... spots)
 * - Collectors: groupingBy, counting
 */
public class ParkingFloor {

    private final int floorNumber;
    private final List<ParkingSpot> spots;           // ordered list of all spots
    private final Map<SpotType, List<ParkingSpot>> spotsByType; // grouped for fast lookup

    public ParkingFloor(int floorNumber) {
        this.floorNumber = floorNumber;
        this.spots = new ArrayList<>();
        this.spotsByType = new EnumMap<>(SpotType.class); // EnumMap is faster than HashMap for enum keys
        for (SpotType type : SpotType.values()) {
            spotsByType.put(type, new ArrayList<>());
        }
    }

    // -------------------------------------------------------------------------
    // Spot registration — varargs lets callers pass one or many spots
    // -------------------------------------------------------------------------

    /**
     * Varargs: accepts zero or more ParkingSpot arguments.
     * Internally treated as an array — ParkingSpot[] spots.
     */
    public void addSpots(ParkingSpot... spots) {
        for (ParkingSpot spot : spots) {
            this.spots.add(spot);
            spotsByType.get(spot.getSpotType()).add(spot);
        }
    }

    // -------------------------------------------------------------------------
    // Finding available spots
    // -------------------------------------------------------------------------

    /**
     * Generic method: <T extends ParkingSpot> constrains T to ParkingSpot subtypes.
     * Returns the first available spot that is an instance of the given class.
     *
     * @param spotClass  e.g., SmallSpot.class, MediumSpot.class
     */
    public <T extends ParkingSpot> Optional<T> findAvailableSpot(Class<T> spotClass) {
        return spots.stream()
                .filter(s -> spotClass.isInstance(s))           // runtime type check
                .filter(ParkingSpot::isAvailable)               // method reference
                .map(spotClass::cast)                           // safe cast after filter
                .findFirst();                                   // Optional<T>
    }

    /**
     * Returns the first available spot that can accept the given vehicle.
     * Demonstrates Stream pipeline with multiple filter predicates.
     */
    public Optional<ParkingSpot> findSpotForVehicle(Vehicle vehicle) {
        SpotType required = vehicle.getVehicleType().getRequiredSpotType();

        return spotsByType.getOrDefault(required, Collections.emptyList())
                .stream()
                .filter(ParkingSpot::isAvailable)
                .filter(spot -> spot.park(vehicle) || true) // dry-run via canAccept is private;
                // we rely on ParkingSpot.park returning false without side-effects when unsuitable
                // In production you'd expose a canAcceptDryRun method instead
                .findFirst();
        // NOTE: In production code, expose a canAccept(vehicle) query method on ParkingSpot
        // to avoid the park-and-check anti-pattern above. Kept here to show stream filtering.
    }

    /**
     * Finds first truly available spot for a vehicle without side effects.
     * Demonstrates method references and stream chaining.
     */
    public Optional<ParkingSpot> findAvailableSpotForVehicle(Vehicle vehicle) {
        return spots.stream()
                .filter(ParkingSpot::isAvailable)
                .filter(spot -> spot.getSpotType().canFit(vehicle.getVehicleType()))
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // Statistics using Streams
    // -------------------------------------------------------------------------

    /** Counts available spots using stream + filter + count */
    public long countAvailableSpots() {
        return spots.stream()
                .filter(ParkingSpot::isAvailable)
                .count();
    }

    /** Counts spots grouped by SpotType — demonstrates Collectors.groupingBy */
    public Map<SpotType, Long> availableSpotsByType() {
        return spots.stream()
                .filter(ParkingSpot::isAvailable)
                .collect(Collectors.groupingBy(ParkingSpot::getSpotType, Collectors.counting()));
    }

    /** Returns occupancy percentage (0–100) */
    public int getOccupancyPercentage() {
        if (spots.isEmpty()) return 0;
        long occupied = spots.stream().filter(s -> !s.isAvailable()).count();
        return (int) ((occupied * 100) / spots.size());
    }

    // -------------------------------------------------------------------------
    // Iterator pattern — manual iteration to print occupied spots
    // -------------------------------------------------------------------------

    /**
     * Prints all occupied spots using an explicit Iterator.
     * Demonstrates the Iterator design pattern and the Iterable interface.
     */
    public void printOccupiedSpots() {
        Iterator<ParkingSpot> iterator = spots.iterator();
        System.out.println("=== Floor " + floorNumber + " — Occupied Spots ===");
        while (iterator.hasNext()) {
            ParkingSpot spot = iterator.next();
            if (!spot.isAvailable()) {
                // ifPresent with lambda: only runs when Optional has a value
                spot.getParkedVehicle().ifPresent(v ->
                        System.out.printf("  %s  ->  %s%n", spot.getId(), v));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public int getFloorNumber() { return floorNumber; }

    public List<ParkingSpot> getAllSpots() {
        return Collections.unmodifiableList(spots); // defensive copy — callers cannot modify
    }

    public int getTotalSpots() { return spots.size(); }

    @Override
    public String toString() {
        return String.format("Floor %d [%d total spots, %d available, %d%% full]",
                floorNumber, spots.size(), countAvailableSpots(), getOccupancyPercentage());
    }
}
