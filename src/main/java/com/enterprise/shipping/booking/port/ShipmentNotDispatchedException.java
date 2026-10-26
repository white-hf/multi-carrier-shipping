package com.enterprise.shipping.booking.port;

/** A provider adapter may use this only when it can prove no creation was dispatched.
 * Timeouts, malformed receipts and generic connection failures do not provide that proof.
 */
public final class ShipmentNotDispatchedException extends RuntimeException {
    public enum Reason { BULKHEAD_FULL, CIRCUIT_OPEN }
    private final Reason reason;

    public ShipmentNotDispatchedException(Reason reason) {
        super("SHIPMENT_SUBMISSION_NOT_DISPATCHED");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
