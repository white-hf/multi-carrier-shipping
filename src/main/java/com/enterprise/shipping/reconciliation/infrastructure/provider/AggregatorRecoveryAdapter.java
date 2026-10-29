package com.enterprise.shipping.reconciliation.infrastructure.provider;

import com.enterprise.shipping.provider.aggregator.AggregatorProviderClient;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort;

/** Capability metadata cannot authorize an unimplemented creation retry or lookup. */
public final class AggregatorRecoveryAdapter implements RecoveryCapabilityPort {

    private final AggregatorProviderClient client;

    public AggregatorRecoveryAdapter(AggregatorProviderClient client) {
        this.client = client;
    }

    @Override
    public Capabilities capabilities(String providerId, String accountAlias) {
        var result = client.capabilities(providerId, accountAlias);
        return new Capabilities(
                Support.valueOf(result.authoritativeLookup().name()),
                Support.valueOf(result.creationIdempotency().name()));
    }
}
