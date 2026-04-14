package JavaConcepts;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ParkingLot - Central coordinator for the entire parking system.
 *
 * Demonstrates:
 * - Singleton pattern (thread-safe double-checked locking)
 * - ConcurrentHashMap for thread-safe ticket storage
 * - Observer pattern: notifying registered ParkingObserver listeners
 * - Generics in collections
 * - Streams: complex pipelines with flatMap
 * - try-catch-finally with custom exceptions
 * - Autoboxing / unboxing
 */
public class ParkingLot {

    // -------------------------------------------------------------------------
    // Singleton — only one parking lot instance per JVM
    // -------------------------------------------------------------------------

    /** volatile ensures visibility of the instance across threads */
    private static volatile ParkingLot instance;

    /** Private constructor prevents external instantiation */
    private ParkingLot() {
        this.floors   = new ArrayList<>();
        this.tickets  = new ConcurrentHashMap<>(); // thread-safe map
        this.observers = new ArrayList<>();
        this.rate      = ParkingRate.standardPlan();
    }

    /**
     * Double-checked locking: avoids synchronization overhead on every call.
     * 'synchronized' block runs only when instance hasn't been created yet.
     */
    public static ParkingLot getInstance() {
        if (instance == null) {                       // first check (no lock)
            synchronized (ParkingLot.class) {         // acquire class-level lock
                if (instance == null) {               // second check (under lock)
                    instance = new ParkingLot();
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final List<ParkingFloor>             floors;
    private final Map<String, ParkingTicket>     tickets;  // ticketId -> ticket
    private final Map<String, ParkingTicket>     activeByPlate = new ConcurrentHashMap<>(); // plate -> active ticket
    private final List<ParkingObserver>          observers;
    private ParkingRate                          rate;
    private String                               lotId = "CITY-LOT-01";

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public void setRate(ParkingRate rate) {
        this.rate = Objects.requireNonNull(rate, "ParkingRate cannot be null");
    }

    /** Observer pattern: register a listener for parking events */
    public void addObserver(ParkingObserver observer) {
        observers.add(observer);
    }

    // -------------------------------------------------------------------------
    // Core operations: park and exit
    // -------------------------------------------------------------------------

    /**
     * Parks a vehicle in the first available appropriate spot.
     *
     * @param vehicle  vehicle to park
     * @return         the issued ParkingTicket
     * @throws ParkingException if no spot is available or vehicle is already parked
     */
    public ParkingTicket parkVehicle(Vehicle vehicle) throws ParkingException {
        // Guard: vehicle already parked
        if (activeByPlate.containsKey(vehicle.getLicensePlate())) {
            throw ParkingException.vehicleAlreadyParked(vehicle.getLicensePlate());
        }

        // Find a free spot across all floors (flatMap flattens floor->spot stream)
        Optional<ParkingSpot> spotOpt = floors.stream()
                .flatMap(floor -> floor.getAllSpots().stream())
                .filter(ParkingSpot::isAvailable)
                .filter(spot -> spot.getSpotType().canFit(vehicle.getVehicleType()))
                .findFirst();

        // Throw custom exception if nothing found
        ParkingSpot spot = spotOpt.orElseThrow(
                () -> ParkingException.noSpotAvailable(vehicle.getVehicleType()));

        // Park the vehicle — spot.park() returns false if another thread grabbed it first
        boolean parked = spot.park(vehicle);
        if (!parked) {
            // Race condition: spot became unavailable; retry would be next step in production
            throw ParkingException.noSpotAvailable(vehicle.getVehicleType());
        }

        // Build the ticket using the Builder pattern
        double hourlyRate = rate.getHourlyRate(vehicle.getVehicleType());
        ParkingTicket ticket = new ParkingTicket.Builder(
                vehicle.getLicensePlate(), vehicle.getVehicleType())
                .spotId(spot.getId().toString())
                .entryTime(LocalDateTime.now())
                .hourlyRate(hourlyRate)
                .build();

        // Store ticket
        tickets.put(ticket.getTicketId(), ticket);
        activeByPlate.put(vehicle.getLicensePlate(), ticket);

        // Notify observers — lambda iterates and calls the interface method
        int occupancy = getOverallOccupancyPercentage();
        notifyObservers(ParkingObserver.Event.VEHICLE_PARKED, occupancy);
        if (occupancy >= 90) {
            notifyObservers(ParkingObserver.Event.CAPACITY_WARNING, occupancy);
        }
        if (occupancy == 100) {
            notifyObservers(ParkingObserver.Event.LOT_FULL, occupancy);
        }

        System.out.printf("[LOT] %s parked at spot %s | Ticket: %s%n",
                vehicle.getLicensePlate(), spot.getId(), ticket.getTicketId());
        return ticket;
    }

    /**
     * Processes exit for a vehicle: validates ticket, collects payment, releases spot.
     *
     * @param ticketId  the ID from the issued ticket
     * @param payment   Payable functional interface — can be a lambda, method reference, or object
     * @throws ParkingException if the ticket is invalid
     */
    public double processExit(String ticketId, Payable payment) throws ParkingException {
        // Retrieve ticket — throws if not found
        ParkingTicket ticket = Optional.ofNullable(tickets.get(ticketId))
                .orElseThrow(() -> ParkingException.invalidTicket(ticketId));

        if (ticket.getStatus() != ParkingTicket.TicketStatus.ACTIVE) {
            throw ParkingException.invalidTicket(ticketId + " (status=" + ticket.getStatus() + ")");
        }

        double fee = ticket.calculateFee();
        LocalDateTime exitTime = LocalDateTime.now();

        try {
            // Payable is a functional interface — process via its default helper
            boolean paid = payment.processWithReceipt(fee, ticketId);
            if (!paid) {
                System.err.println("[LOT] Payment failed for ticket " + ticketId);
                return fee; // vehicle stays parked; don't release spot
            }

            // Mark ticket paid and record exit time
            ticket.markPaid(exitTime);

            // Release the spot — find it across all floors
            releaseSpotForVehicle(ticket.getLicensePlate());

            // Remove active entry
            activeByPlate.remove(ticket.getLicensePlate());

        } finally {
            // finally block always runs — good for audit logging
            System.out.printf("[AUDIT] Exit attempt — ticket %s — status now: %s%n",
                    ticketId, ticket.getStatus());
        }

        // Post-exit observer notification
        int occupancy = getOverallOccupancyPercentage();
        notifyObservers(ParkingObserver.Event.VEHICLE_EXITED, occupancy);
        if (occupancy < 100) {
            notifyObservers(ParkingObserver.Event.LOT_AVAILABLE, occupancy);
        }

        return fee;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Finds and releases the spot occupied by a vehicle with the given plate. */
    private void releaseSpotForVehicle(String licensePlate) {
        floors.stream()
                .flatMap(floor -> floor.getAllSpots().stream())
                .filter(spot -> !spot.isAvailable())
                .filter(spot -> spot.getParkedVehicle()
                        .map(v -> v.getLicensePlate().equals(licensePlate))
                        .orElse(false))
                .findFirst()
                .ifPresent(ParkingSpot::release); // method reference: calls release() on the spot
    }

    /**
     * Notifies all registered observers.
     * Demonstrates the Observer pattern: observers decouple event producers from consumers.
     */
    private void notifyObservers(ParkingObserver.Event event, int occupancy) {
        // forEach with lambda — cleaner than a for loop for side-effect iteration
        observers.forEach(obs -> obs.onParkingEvent(event, lotId, occupancy));
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Returns a snapshot of all active tickets sorted by entry time */
    public List<ParkingTicket> getActiveTickets() {
        return activeByPlate.values().stream()
                .sorted(Comparator.comparing(ParkingTicket::getEntryTime))
                .collect(Collectors.toList());
    }

    /** Counts total spots across all floors */
    public int getTotalSpots() {
        // mapToInt + sum demonstrates IntStream
        return floors.stream()
                .mapToInt(ParkingFloor::getTotalSpots)
                .sum();
    }

    /** Counts available spots across all floors */
    public long getAvailableSpots() {
        return floors.stream()
                .flatMap(f -> f.getAllSpots().stream())
                .filter(ParkingSpot::isAvailable)
                .count();
    }

    /** Returns occupancy as an int percentage (autoboxing: long -> int) */
    public int getOverallOccupancyPercentage() {
        int total = getTotalSpots();
        if (total == 0) return 0;
        long occupied = total - getAvailableSpots();
        // Autoboxing: arithmetic on primitives, result assigned to int (unboxing)
        return (int) ((occupied * 100L) / total);
    }

    public List<ParkingFloor> getFloors() {
        return Collections.unmodifiableList(floors);
    }

    public void printStatus() {
        System.out.println("============================");
        System.out.println("  PARKING LOT STATUS: " + lotId);
        System.out.println("============================");
        floors.forEach(System.out::println);           // method reference to println
        System.out.printf("  Total  : %d spots%n", getTotalSpots());
        System.out.printf("  Free   : %d spots%n", getAvailableSpots());
        System.out.printf("  Full   : %d%%%n", getOverallOccupancyPercentage());
        System.out.println("============================");
    }
}
