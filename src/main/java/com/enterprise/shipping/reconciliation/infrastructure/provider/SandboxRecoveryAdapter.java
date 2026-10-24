package com.enterprise.shipping.reconciliation.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.SandboxProviderClient;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort;

/** Capability metadata cannot authorize an unimplemented creation retry or lookup. */
public final class SandboxRecoveryAdapter implements RecoveryCapabilityPort {
    private final SandboxProviderClient client;
    public SandboxRecoveryAdapter(SandboxProviderClient client) { this.client = client; }
    @Override public Capabilities capabilities(String providerId, String accountAlias) {
        var result = client.capabilities(providerId, accountAlias);
        return new Capabilities(Support.valueOf(result.authoritativeLookup().name()),
                Support.valueOf(result.creationIdempotency().name()));
    }
}
