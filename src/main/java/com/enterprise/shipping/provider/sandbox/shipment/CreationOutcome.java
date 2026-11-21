package com.enterprise.shipping.provider.sandbox.shipment;

import java.util.Objects;

/** Evidence from one provider attempt; only {@code NOT_DISPATCHED} proves nothing was sent. */
public record CreationOutcome(
        Status status,
        String shipmentId,
        String trackingNumber,
        String labelUrl) {
    public enum Status {
        CONFIRMED, REJECTED, NOT_DISPATCHED, UNKNOWN
    }

    public CreationOutcome {
        Objects.requireNonNull(status, "Submission outcome required");
        if (status == Status.CONFIRMED) {
            if (shipmentId == null || shipmentId.isBlank()) {
                throw new IllegalArgumentException("Shipment ID required");
            }
        } else if (shipmentId != null || trackingNumber != null || labelUrl != null) {
            throw new IllegalArgumentException("Only confirmed outcomes carry a receipt");
        }
    }

    public static CreationOutcome confirmed(String id, String tracking, String url) {
        return new CreationOutcome(Status.CONFIRMED, id, tracking, url);
    }

    public static CreationOutcome rejection() {
        return new CreationOutcome(Status.REJECTED, null, null, null);
    }

    public static CreationOutcome notDispatched() {
        return new CreationOutcome(Status.NOT_DISPATCHED, null, null, null);
    }

    public static CreationOutcome unknown() {
        return new CreationOutcome(Status.UNKNOWN, null, null, null);
    }
}
