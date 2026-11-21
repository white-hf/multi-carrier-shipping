package com.enterprise.shipping.shipping.model;

import java.util.Objects;

/** Evidence from one provider attempt; only NOT_DISPATCHED permits an explicit retry. */
public record CreateResult(
        Status status,
        String shipmentId,
        String trackingNumber,
        String labelUrl) {
    public enum Status {
        CONFIRMED, REJECTED, NOT_DISPATCHED, UNKNOWN
    }

    public record Request(String providerId, String accountAlias, String providerQuoteId) {}

    public CreateResult {
        Objects.requireNonNull(status, "Submission outcome required");
        if (status == Status.CONFIRMED) {
            if (shipmentId == null || shipmentId.isBlank()) {
                throw new IllegalArgumentException("Shipment ID required");
            }
        } else if (shipmentId != null || trackingNumber != null || labelUrl != null) {
            throw new IllegalArgumentException("Only confirmed outcomes carry a receipt");
        }
    }

    public static CreateResult confirmed(String id, String tracking, String url) {
        return new CreateResult(Status.CONFIRMED, id, tracking, url);
    }

    public static CreateResult rejection() {
        return new CreateResult(Status.REJECTED, null, null, null);
    }

    public static CreateResult notDispatched() {
        return new CreateResult(Status.NOT_DISPATCHED, null, null, null);
    }

    public static CreateResult unknown() {
        return new CreateResult(Status.UNKNOWN, null, null, null);
    }
}
