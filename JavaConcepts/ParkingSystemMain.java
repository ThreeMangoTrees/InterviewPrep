package JavaConcepts;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ParkingSystemMain - Entry point and integration showcase for the Parking System.
 *
 * This file weaves together ALL Java concepts introduced across the other classes
 * and demonstrates them through realistic parking scenarios:
 *
 *   1.  Enums                        (VehicleType, SpotType, TicketStatus)
 *   2.  Abstract classes             (Vehicle, ParkingSpot)
 *   3.  Interfaces                   (Rentable, Payable, ParkingObserver)
 *   4.  Inheritance & Polymorphism   (Bike/Car/Truck/RentalVehicle all as Vehicle)
 *   5.  Encapsulation                (private fields + getters everywhere)
 *   6.  Generics                     (ParkingFloor.findAvailableSpot)
 *   7.  Collections                  (List, Map, Queue, PriorityQueue)
 *   8.  Streams & Lambdas            (filter, map, sorted, collect, forEach)
 *   9.  Optional                     (parking-spot lookups)
 *  10.  Singleton pattern            (ParkingLot.getInstance())
 *  11.  Builder pattern              (ParkingTicket.Builder)
 *  12.  Observer pattern             (CapacityMonitor, EntryGate anonymous class)
 *  13.  Factory pattern              (ParkingLotFactory)
 *  14.  Exception handling           (ParkingException, try-catch-finally)
 *  15.  Functional interfaces        (Payable as lambda target)
 *  16.  Method references            (System.out::println, ParkingSpot::isAvailable)
 *  17.  Comparable & Comparator      (Vehicle, sorted ticket list)
 *  18.  Iterator                     (ParkingFloor.printOccupiedSpots())
 *  19.  Varargs                      (ParkingFloor.addSpots(...))
 *  20.  Thread safety                (synchronized, volatile, ConcurrentHashMap)
 *  21.  instanceof                   (SmallSpot, MediumSpot canAccept)
 *  22.  Anonymous classes            (inline ParkingObserver in EntryGate)
 *  23.  Static nested classes        (ParkingTicket.Builder, ParkingSpot.SpotId)
 *  24.  Inner enum (nested)          (ParkingTicket.TicketStatus, ParkingObserver.Event)
 *  25.  Autoboxing/Unboxing          (int <-> Integer in stream operations)
 *  26.  Switch expression (Java 14+) (CapacityMonitor.onParkingEvent)
 *  27.  Java Time API                (LocalDateTime, ChronoUnit, LocalTime)
 *  28.  Immutable class design       (ParkingTicket, SpotId)
 *  29.  String manipulation          (format, join, split)
 *  30.  PriorityQueue / Comparator   (revenue report below)
 */
public class ParkingSystemMain {

    public static void main(String[] args) {

        System.out.println("========================================");
        System.out.println("  CITY PARKING SYSTEM — DEMO");
        System.out.println("========================================\n");

        // -----------------------------------------------------------------
        // 1. Build the parking lot via Factory pattern
        // -----------------------------------------------------------------
        ParkingLot lot = ParkingLotFactory.buildSmallCityLot();
        lot.printStatus();

        // -----------------------------------------------------------------
        // 2. Create a variety of vehicles (polymorphism: all stored as Vehicle)
        // -----------------------------------------------------------------
        List<Vehicle> vehicles = new ArrayList<>();
        vehicles.add(new Bike("MH12-EB-0001", "Anjali Singh", true));   // electric bike
        vehicles.add(new Bike("MH12-PB-0002", "Rahul Verma"));          // petrol bike
        vehicles.add(new Car("MH01-CC-1234", "Priya Nair", Car.CATEGORY_COMPACT));
        vehicles.add(new Car("MH02-CS-5678", "Suresh Patel", Car.CATEGORY_SUV));
        vehicles.add(new Car("MH03-CS-9999", "Deepa Rao"));              // standard car
        vehicles.add(new Truck("MH14-TK-3001", "FastFreight Ltd.", 8.5, false));
        vehicles.add(new Truck("MH14-TK-3002", "HazMat Express",   2.0, true));
        vehicles.add(new RentalVehicle("RC-FLEET-01", VehicleType.RENTAL_CAR,   55.0));
        vehicles.add(new RentalVehicle("RT-FLEET-01", VehicleType.RENTAL_TRUCK, 120.0));

        // -----------------------------------------------------------------
        // 3. Set up gates
        // -----------------------------------------------------------------
        EntryGate entryGate1 = new EntryGate("ENTRY-A", lot);
        EntryGate entryGate2 = new EntryGate("ENTRY-B", lot);
        ExitGate  exitGate1  = new ExitGate("EXIT-A", lot);

        // -----------------------------------------------------------------
        // 4. Park vehicles through the entry gates
        //    Ticket map: plate -> ticket (for later exit processing)
        // -----------------------------------------------------------------
        Map<String, ParkingTicket> issuedTickets = new LinkedHashMap<>();

        System.out.println("\n--- Parking vehicles ---");
        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            // Alternate between the two entry gates
            EntryGate gate = (i % 2 == 0) ? entryGate1 : entryGate2;
            ParkingTicket ticket = gate.arrive(v);
            if (ticket != null) {
                issuedTickets.put(v.getLicensePlate(), ticket);
            }
        }

        // -----------------------------------------------------------------
        // 5. Try to start a rental
        // -----------------------------------------------------------------
        System.out.println("\n--- Starting a rental ---");
        vehicles.stream()
                .filter(v -> v instanceof RentalVehicle)          // polymorphic filter
                .map(v -> (RentalVehicle) v)                      // safe cast after filter
                .findFirst()
                .ifPresent(rv -> {
                    rv.startRental("CUST-7890", LocalDateTime.now().minusDays(3));
                    System.out.println(rv.getDescription());
                });

        // -----------------------------------------------------------------
        // 6. Display lot status
        // -----------------------------------------------------------------
        System.out.println();
        lot.printStatus();

        // -----------------------------------------------------------------
        // 7. Show active tickets sorted by entry time (Comparator)
        // -----------------------------------------------------------------
        System.out.println("\n--- Active Tickets (sorted by entry time) ---");
        lot.getActiveTickets().forEach(t -> System.out.println("  " + t));

        // -----------------------------------------------------------------
        // 8. Process some exits
        // -----------------------------------------------------------------
        System.out.println("\n--- Processing exits ---");

        // Exit the bike with cash
        Optional.ofNullable(issuedTickets.get("MH12-EB-0001"))
                .ifPresent(t -> exitGate1.processExitCash(t.getTicketId()));

        // Exit the SUV car with card
        Optional.ofNullable(issuedTickets.get("MH02-CS-5678"))
                .ifPresent(t -> exitGate1.processExitCard(t.getTicketId(), "4242"));

        // Exit a truck with cash
        Optional.ofNullable(issuedTickets.get("MH14-TK-3001"))
                .ifPresent(t -> exitGate1.processExitCash(t.getTicketId()));

        // -----------------------------------------------------------------
        // 9. Demonstrate try-catch with an invalid ticket
        // -----------------------------------------------------------------
        System.out.println("\n--- Testing invalid ticket ---");
        exitGate1.processExitCash("INVALID-TICKET-ID");

        // -----------------------------------------------------------------
        // 10. Try to park the same vehicle again (vehicle already parked exception)
        // -----------------------------------------------------------------
        System.out.println("\n--- Testing duplicate park attempt ---");
        entryGate1.arrive(new Car("MH03-CS-9999", "Deepa Rao")); // already parked

        // -----------------------------------------------------------------
        // 11. Streams: revenue report by vehicle type (groupingBy + summingDouble)
        // -----------------------------------------------------------------
        System.out.println("\n--- Revenue by vehicle type (paid tickets) ---");
        Map<VehicleType, Double> revenueByType = issuedTickets.values().stream()
                .filter(t -> t.getStatus() == ParkingTicket.TicketStatus.PAID)
                .collect(Collectors.groupingBy(
                        ParkingTicket::getVehicleType,
                        Collectors.summingDouble(ParkingTicket::calculateFee)
                ));
        revenueByType.forEach((type, revenue) ->
                System.out.printf("  %-15s  $%.2f%n", type.getDisplayName(), revenue));

        // -----------------------------------------------------------------
        // 12. PriorityQueue sorted by fee (descending) — Comparator.comparingDouble
        // -----------------------------------------------------------------
        System.out.println("\n--- Top fees (all tickets) ---");
        PriorityQueue<ParkingTicket> feeQueue = new PriorityQueue<>(
                Comparator.comparingDouble(ParkingTicket::calculateFee).reversed()
        );
        feeQueue.addAll(issuedTickets.values());
        int rank = 1;
        while (!feeQueue.isEmpty() && rank <= 5) {
            ParkingTicket t = feeQueue.poll(); // removes the highest-fee ticket
            System.out.printf("  #%d  %s — $%.2f (%s)%n",
                    rank++, t.getLicensePlate(), t.calculateFee(), t.getVehicleType());
        }

        // -----------------------------------------------------------------
        // 13. Demonstrate Comparable: sort vehicles by registration time
        // -----------------------------------------------------------------
        System.out.println("\n--- Vehicles sorted by registration time (Comparable) ---");
        List<Vehicle> sorted = new ArrayList<>(vehicles);
        Collections.sort(sorted); // uses Vehicle.compareTo()
        sorted.forEach(v -> System.out.println("  " + v));

        // -----------------------------------------------------------------
        // 14. Demonstrate Iterator via ParkingFloor.printOccupiedSpots()
        // -----------------------------------------------------------------
        System.out.println();
        lot.getFloors().forEach(ParkingFloor::printOccupiedSpots); // method reference

        // -----------------------------------------------------------------
        // 15. Demonstrate static interface method on Rentable
        // -----------------------------------------------------------------
        System.out.println("\n--- Rental validation ---");
        int[] testDays = {0, 1, 15, 30, 31}; // vararg-style test values
        for (int days : testDays) {
            System.out.printf("  %d days valid? %s%n", days, Rentable.isValidRentalPeriod(days));
        }

        // -----------------------------------------------------------------
        // 16. Demonstrate enum methods
        // -----------------------------------------------------------------
        System.out.println("\n--- VehicleType enum capabilities ---");
        for (VehicleType vt : VehicleType.values()) {
            System.out.printf("  %-15s | Requires: %-8s | Rental: %s%n",
                    vt.getDisplayName(), vt.getRequiredSpotType().getLabel(), vt.isRental());
        }

        // -----------------------------------------------------------------
        // 17. Final status
        // -----------------------------------------------------------------
        System.out.println();
        lot.printStatus();
        System.out.printf("%nExit gate stats: %d exits, $%.2f total revenue%n",
                exitGate1.getTotalExitsProcessed(),
                exitGate1.getTotalRevenueCollected());

        System.out.println("\n========================================");
        System.out.println("  DEMO COMPLETE");
        System.out.println("========================================");
    }
}
