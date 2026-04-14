package JavaConcepts;

import java.util.ArrayList;
import java.util.List;

/**
 * CapacityMonitor - Concrete ParkingObserver that tracks lot occupancy history.
 *
 * Demonstrates:
 * - Implementing an interface (ParkingObserver)
 * - Inner static record-like class (CapacitySnapshot) using a regular class
 * - List operations with generics
 * - String.join and stream-based reporting
 * - Enhanced for-loop
 */
public class CapacityMonitor implements ParkingObserver {

    /**
     * CapacitySnapshot - a value object holding a moment-in-time reading.
     * Demonstrates: nested static class acting like a data record.
     */
    public static class CapacitySnapshot {
        private final ParkingObserver.Event event;
        private final int occupancyPercent;
        private final long timestampMillis;

        public CapacitySnapshot(Event event, int occupancyPercent) {
            this.event = event;
            this.occupancyPercent = occupancyPercent;
            this.timestampMillis = System.currentTimeMillis(); // static method call
        }

        @Override
        public String toString() {
            return String.format("[%s] %s @ %d%%", timestampMillis, event, occupancyPercent);
        }
    }

    // -------------------------------------------------------------------------
    // Monitor state
    // -------------------------------------------------------------------------

    private final String monitorId;
    private final int warningThreshold; // percentage that triggers a warning log

    /** History of all events received — unbounded in this demo; production would cap it */
    private final List<CapacitySnapshot> history;

    private int peakOccupancy; // tracks the highest occupancy seen

    public CapacityMonitor(String monitorId, int warningThreshold) {
        this.monitorId = monitorId;
        this.warningThreshold = warningThreshold;
        this.history = new ArrayList<>();
        this.peakOccupancy = 0;
    }

    // -------------------------------------------------------------------------
    // ParkingObserver implementation
    // -------------------------------------------------------------------------

    @Override
    public void onParkingEvent(Event event, String lotId, int occupancy) {
        // Record the snapshot
        CapacitySnapshot snapshot = new CapacitySnapshot(event, occupancy);
        history.add(snapshot);

        // Track peak using Math.max — demonstrates static utility method
        peakOccupancy = Math.max(peakOccupancy, occupancy);

        // Enhanced switch expression (Java 14+) for concise event handling
        String logLevel = switch (event) {
            case LOT_FULL, CAPACITY_WARNING -> "WARN";
            case VEHICLE_PARKED, VEHICLE_EXITED, LOT_AVAILABLE -> "INFO";
        };

        System.out.printf("[MONITOR %s] [%s] Lot %s — %s — occupancy: %d%%%n",
                monitorId, logLevel, lotId, event, occupancy);

        // Trigger alert if threshold exceeded
        if (occupancy >= warningThreshold && event != Event.LOT_FULL) {
            System.out.printf("[MONITOR %s] ALERT: occupancy %d%% exceeds threshold %d%%%n",
                    monitorId, occupancy, warningThreshold);
        }
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    /**
     * Prints the full event history.
     * Enhanced for-loop: cleaner than index-based iteration when index isn't needed.
     */
    public void printHistory() {
        System.out.println("=== Capacity History [Monitor: " + monitorId + "] ===");
        for (CapacitySnapshot snap : history) {
            System.out.println("  " + snap);
        }
        System.out.println("  Peak occupancy observed: " + peakOccupancy + "%");
        System.out.println("  Total events: " + history.size());
    }

    public int getPeakOccupancy()     { return peakOccupancy; }
    public int getEventCount()        { return history.size(); }
    public List<CapacitySnapshot> getHistory() { return List.copyOf(history); } // unmodifiable copy
}
