package JavaConcepts;

/**
 * ExitGate - Models a physical exit gate that validates tickets and processes payments.
 *
 * Demonstrates:
 * - Method references as Payable lambda arguments
 * - try-with-resources style structured exception handling
 * - Logging with formatted output
 * - Delegation to ParkingLot for the actual work
 */
public class ExitGate {

    private final String gateId;
    private final ParkingLot parkingLot;

    // Simple counters — primitive fields, demonstrate autoboxing when passed to generics
    private int totalExitsProcessed;
    private double totalRevenueCollected;

    public ExitGate(String gateId, ParkingLot parkingLot) {
        this.gateId = gateId;
        this.parkingLot = parkingLot;
        this.totalExitsProcessed = 0;
        this.totalRevenueCollected = 0.0;
    }

    // -------------------------------------------------------------------------
    // Exit processing
    // -------------------------------------------------------------------------

    /**
     * Processes a vehicle exit with cash payment.
     * Demonstrates passing a lambda as a Payable (functional interface) argument.
     *
     * @param ticketId  the ticket ID from the parking ticket
     */
    public void processExitCash(String ticketId) {
        // Lambda: amount -> true simulates cash always accepted
        processExit(ticketId, amount -> {
            System.out.printf("[GATE %s] Cash received: $%.2f%n", gateId, amount);
            return true; // cash accepted
        });
    }

    /**
     * Processes a vehicle exit with card payment.
     * Uses a method reference from the PaymentProcessor helper class.
     *
     * @param ticketId  ticket ID
     * @param cardNumber last 4 digits of card (for display only)
     */
    public void processExitCard(String ticketId, String cardNumber) {
        // Method reference: PaymentProcessor::processCardPayment matches Payable signature
        PaymentProcessor processor = new PaymentProcessor(cardNumber);
        processExit(ticketId, processor::processCardPayment);
    }

    /**
     * Core exit logic — delegates to ParkingLot and handles exceptions.
     *
     * @param ticketId  ticket to process
     * @param payment   Payable implementation (lambda, method ref, or object)
     */
    private void processExit(String ticketId, Payable payment) {
        try {
            double fee = parkingLot.processExit(ticketId, payment);
            totalExitsProcessed++;             // post-increment (++)
            totalRevenueCollected += fee;      // compound assignment

            System.out.printf("[GATE %s] Exit complete — ticket %s — $%.2f collected%n",
                    gateId, ticketId, fee);

        } catch (ParkingException e) {
            System.err.printf("[GATE %s] Exit failed — %s (code: %s)%n",
                    gateId, e.getMessage(), e.getErrorCode());
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getGateId()                  { return gateId; }
    public int getTotalExitsProcessed()        { return totalExitsProcessed; }
    public double getTotalRevenueCollected()   { return totalRevenueCollected; }

    // -------------------------------------------------------------------------
    // Static nested helper class
    // -------------------------------------------------------------------------

    /**
     * PaymentProcessor - simulates a card terminal.
     * Static nested class: has no implicit reference to the enclosing ExitGate instance.
     * Can be instantiated independently of ExitGate.
     */
    private static class PaymentProcessor {

        private final String maskedCard;

        PaymentProcessor(String lastFourDigits) {
            this.maskedCard = "**** **** **** " + lastFourDigits;
        }

        /**
         * Processes a card payment.
         * Method signature matches Payable: boolean processPayment(double amount).
         * This is what allows it to be used as a method reference.
         */
        public boolean processCardPayment(double amount) {
            // Simulate: amounts above $50 require additional authorization
            if (amount > 50.0) {
                System.out.printf("[CARD] High-value transaction $%.2f on %s — requires auth%n",
                        amount, maskedCard);
                // In production, call an authorization service here
            }
            System.out.printf("[CARD] Charged $%.2f to %s%n", amount, maskedCard);
            return true; // simulate always-success card terminal
        }
    }
}
