package JavaConcepts;

import java.util.LinkedList;
import java.util.Queue;

/**
 * EntryGate - Models a physical entry gate that queues vehicles and issues tickets.
 *
 * Demonstrates:
 * - Queue data structure (LinkedList as Queue)
 * - Exception handling: catching and re-throwing / logging
 * - Encapsulation of gate-level logic away from ParkingLot
 * - Anonymous class: inline ParkingObserver for gate-level capacity check
 */
public class EntryGate {

    private final String gateId;
    private final ParkingLot parkingLot;

    /**
     * Queue of vehicles waiting to enter.
     * LinkedList implements both List and Queue — here used as a Queue (FIFO).
     */
    private final Queue<Vehicle> waitingQueue;

    /** When true, the gate is temporarily closed (e.g., lot full) */
    private boolean isOpen;

    public EntryGate(String gateId, ParkingLot parkingLot) {
        this.gateId = gateId;
        this.parkingLot = parkingLot;
        this.waitingQueue = new LinkedList<>();
        this.isOpen = true;

        /*
         * Anonymous class implementing ParkingObserver.
         * Demonstrates: anonymous class syntax — a one-off implementation
         * without creating a separate named class.
         */
        parkingLot.addObserver(new ParkingObserver() {
            @Override
            public void onParkingEvent(Event event, String lotId, int occupancy) {
                if (event == Event.LOT_FULL) {
                    isOpen = false;
                    System.out.printf("[GATE %s] LOT FULL — gate CLOSED%n", gateId);
                } else if (event == Event.LOT_AVAILABLE) {
                    isOpen = true;
                    System.out.printf("[GATE %s] Space available — gate OPEN%n", gateId);
                    processQueue(); // drain waiting queue when space frees up
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Gate operations
    // -------------------------------------------------------------------------

    /**
     * Accepts a vehicle and either parks it immediately or queues it.
     *
     * @param vehicle  arriving vehicle
     * @return         issued ticket, or null if vehicle was queued
     */
    public ParkingTicket arrive(Vehicle vehicle) {
        if (!isOpen) {
            System.out.printf("[GATE %s] Lot full — %s added to waiting queue (position %d)%n",
                    gateId, vehicle.getLicensePlate(), waitingQueue.size() + 1);
            waitingQueue.offer(vehicle); // offer: adds to the tail of the queue
            return null;
        }
        return admitVehicle(vehicle);
    }

    /**
     * Parks the vehicle and issues a ticket.
     * try-catch demonstrates checked exception handling.
     */
    private ParkingTicket admitVehicle(Vehicle vehicle) {
        try {
            ParkingTicket ticket = parkingLot.parkVehicle(vehicle);
            System.out.printf("[GATE %s] Admitted %s | Ticket: %s%n",
                    gateId, vehicle.getLicensePlate(), ticket.getTicketId());
            return ticket;
        } catch (ParkingException e) {
            // Checked exception: must be caught or declared
            System.err.printf("[GATE %s] Could not admit %s: %s (code: %s)%n",
                    gateId, vehicle.getLicensePlate(), e.getMessage(), e.getErrorCode());
            return null;
        }
    }

    /**
     * Processes the waiting queue — called when space becomes available.
     * Demonstrates Queue.poll() which removes and returns the head, or null if empty.
     */
    private void processQueue() {
        while (!waitingQueue.isEmpty() && isOpen) {
            Vehicle next = waitingQueue.poll(); // removes head of queue
            System.out.printf("[GATE %s] Processing queued vehicle: %s%n",
                    gateId, next.getLicensePlate());
            admitVehicle(next);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getGateId()          { return gateId; }
    public boolean isOpen()            { return isOpen; }
    public int getQueueLength()        { return waitingQueue.size(); }
}
