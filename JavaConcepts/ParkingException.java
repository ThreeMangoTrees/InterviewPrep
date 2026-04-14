package JavaConcepts;

/**
 * ParkingException - Custom checked exception hierarchy.
 *
 * Demonstrates:
 * - Custom exception classes (extending Exception)
 * - Exception hierarchy / inheritance among exceptions
 * - Chained exceptions (wrapping a cause)
 * - Static factory methods on exceptions
 */
public class ParkingException extends Exception {

    // serialVersionUID is required for Serializable (Exception implements Serializable)
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public ParkingException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Chained exception constructor — preserves root cause */
    public ParkingException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    // -------------------------------------------------------------------------
    // Static factory methods — descriptive, named constructors
    // -------------------------------------------------------------------------

    public static ParkingException noSpotAvailable(VehicleType type) {
        return new ParkingException(
                "No available spot for vehicle type: " + type,
                "ERR_NO_SPOT"
        );
    }

    public static ParkingException invalidTicket(String ticketId) {
        return new ParkingException(
                "Ticket not found or already used: " + ticketId,
                "ERR_INVALID_TICKET"
        );
    }

    public static ParkingException vehicleAlreadyParked(String licensePlate) {
        return new ParkingException(
                "Vehicle already has an active parking ticket: " + licensePlate,
                "ERR_ALREADY_PARKED"
        );
    }

    // -------------------------------------------------------------------------
    // Unchecked (runtime) sub-exception for programming errors
    // -------------------------------------------------------------------------

    /**
     * ParkingSystemException - unchecked exception for internal system errors.
     * Extends RuntimeException so callers are not forced to catch it.
     */
    public static class ParkingSystemException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ParkingSystemException(String message) {
            super(message);
        }

        public ParkingSystemException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
