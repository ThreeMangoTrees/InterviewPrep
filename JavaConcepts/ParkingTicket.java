package JavaConcepts;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * ParkingTicket - Immutable value object representing an issued parking ticket.
 *
 * Demonstrates:
 * - Builder pattern: inner static Builder class for readable object construction
 * - Immutable class design: all fields are final, no setters
 * - UUID for unique identifiers
 * - Java Time API (LocalDateTime, ChronoUnit)
 * - Enum for ticket state (TicketStatus inner enum)
 */
public final class ParkingTicket {

    /**
     * TicketStatus - lifecycle states of a parking ticket.
     * Inner enum (non-static nested type inside a class).
     */
    public enum TicketStatus {
        ACTIVE,     // vehicle is currently parked
        PAID,       // fee collected; vehicle may exit
        EXPIRED     // not collected within grace period
    }

    // All fields are final — once built the ticket never changes
    private final String ticketId;
    private final String licensePlate;
    private final VehicleType vehicleType;
    private final String spotId;
    private final LocalDateTime entryTime;
    private final double hourlyRate;

    // These are set after construction via a controlled mutator (payment flow)
    private LocalDateTime exitTime;      // set when vehicle exits
    private TicketStatus status;         // changes: ACTIVE -> PAID/EXPIRED

    /** Private constructor — enforces use of Builder */
    private ParkingTicket(Builder builder) {
        this.ticketId    = builder.ticketId;
        this.licensePlate = builder.licensePlate;
        this.vehicleType = builder.vehicleType;
        this.spotId      = builder.spotId;
        this.entryTime   = builder.entryTime;
        this.hourlyRate  = builder.hourlyRate;
        this.status      = TicketStatus.ACTIVE;
    }

    // -------------------------------------------------------------------------
    // Builder — static nested class for constructing ParkingTicket
    // -------------------------------------------------------------------------

    /**
     * Builder pattern: accumulates parameters, then calls build() once.
     * Avoids telescoping constructors and improves readability at the call site.
     */
    public static class Builder {
        // Required fields
        private final String licensePlate;
        private final VehicleType vehicleType;

        // Optional/defaulted fields
        private String ticketId   = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        private String spotId     = "UNKNOWN";
        private LocalDateTime entryTime = LocalDateTime.now();
        private double hourlyRate = 0.0;

        public Builder(String licensePlate, VehicleType vehicleType) {
            this.licensePlate = licensePlate;
            this.vehicleType  = vehicleType;
        }

        public Builder spotId(String spotId) {
            this.spotId = spotId;
            return this; // method chaining
        }

        public Builder entryTime(LocalDateTime entryTime) {
            this.entryTime = entryTime;
            return this;
        }

        public Builder hourlyRate(double hourlyRate) {
            this.hourlyRate = hourlyRate;
            return this;
        }

        /** Validates state and constructs the immutable ParkingTicket. */
        public ParkingTicket build() {
            if (licensePlate == null || licensePlate.isBlank()) {
                throw new IllegalStateException("License plate is required to build a ticket");
            }
            return new ParkingTicket(this);
        }
    }

    // -------------------------------------------------------------------------
    // Business logic
    // -------------------------------------------------------------------------

    /**
     * Calculates the parking fee based on hours parked.
     * If exit time is not yet recorded, uses the current time as an estimate.
     */
    public double calculateFee() {
        LocalDateTime end = (exitTime != null) ? exitTime : LocalDateTime.now();
        long minutes = ChronoUnit.MINUTES.between(entryTime, end);

        // Minimum 30 minutes charge; round up to next 30-min block
        long billableBlocks = Math.max(1, (long) Math.ceil(minutes / 30.0));
        double hoursCharged = billableBlocks * 0.5; // each block = 0.5 hour

        return hoursCharged * hourlyRate;
    }

    /** Marks the ticket as paid and records exit time. */
    public void markPaid(LocalDateTime exitTime) {
        if (this.status != TicketStatus.ACTIVE) {
            throw new ParkingException.ParkingSystemException(
                    "Cannot mark ticket " + ticketId + " as paid — current status: " + status);
        }
        this.exitTime = exitTime;
        this.status = TicketStatus.PAID;
    }

    /** Marks the ticket as expired (e.g., grace period exceeded). */
    public void markExpired() {
        this.status = TicketStatus.EXPIRED;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getTicketId()          { return ticketId; }
    public String getLicensePlate()      { return licensePlate; }
    public VehicleType getVehicleType()  { return vehicleType; }
    public String getSpotId()            { return spotId; }
    public LocalDateTime getEntryTime()  { return entryTime; }
    public LocalDateTime getExitTime()   { return exitTime; }
    public double getHourlyRate()        { return hourlyRate; }
    public TicketStatus getStatus()      { return status; }

    @Override
    public String toString() {
        return String.format("Ticket[%s | %s | %s | Entry: %s | Fee: $%.2f | %s]",
                ticketId, licensePlate, vehicleType, entryTime, calculateFee(), status);
    }
}
