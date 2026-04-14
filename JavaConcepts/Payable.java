package JavaConcepts;

/**
 * Payable - Functional interface for processing parking payments.
 *
 * Demonstrates:
 * - @FunctionalInterface annotation: exactly one abstract method
 * - Can be used as the target of a lambda expression or method reference
 * - Default and static methods are allowed alongside the single abstract method
 */
@FunctionalInterface
public interface Payable {

    /**
     * The single abstract method — defines the functional contract.
     * @param amount  amount due in dollars
     * @return        true if payment succeeded, false otherwise
     */
    boolean processPayment(double amount);

    /**
     * Default method — logs the payment attempt before processing.
     * Demonstrates chaining behavior around the functional method.
     */
    default boolean processWithReceipt(double amount, String ticketId) {
        System.out.printf("[PAYMENT] Processing $%.2f for ticket %s%n", amount, ticketId);
        boolean success = processPayment(amount);
        System.out.printf("[PAYMENT] %s — ticket %s%n", success ? "SUCCESS" : "FAILED", ticketId);
        return success;
    }

    // -------------------------------------------------------------------------
    // Static factory helpers — return ready-to-use Payable lambdas
    // -------------------------------------------------------------------------

    /** Always succeeds — useful for testing or free-parking scenarios */
    static Payable alwaysSucceeds() {
        return amount -> true;
    }

    /** Always fails — useful for simulating payment gateway outages */
    static Payable alwaysFails() {
        return amount -> false;
    }
}
