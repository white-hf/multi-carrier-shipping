package com.enterprise.shipping.reconciliation.infrastructure.provider;

import com.enterprise.shipping.provider.aggregator.shipments.AggregatorShipmentApi;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort;

/** Capability metadata cannot authorize an unimplemented creation retry or lookup. */
public final class AggregatorRecoveryAdapter implements RecoveryCapabilityPort {

    private final AggregatorShipmentApi shipmentApi;

    public AggregatorRecoveryAdapter(AggregatorShipmentApi shipmentApi) {
        this.shipmentApi = shipmentApi;
    }

    @Override
    public Capabilities capabilities(String providerId, String accountAlias) {
        var result = shipmentApi.capabilities(providerId, accountAlias);
        return new Capabilities(
                Support.valueOf(result.authoritativeLookup().name()),
                Support.valueOf(result.creationIdempotency().name()));
    }
}
