package com.enterprise.shipping.provider.aggregator.shipments;

public record CreationOutcome(
        String shipmentId,
        String trackingNumber,
        String labelUrl,
        boolean rejected) {

    public static CreationOutcome rejection() {
        return new CreationOutcome(null, null, null, true);
    }
}
