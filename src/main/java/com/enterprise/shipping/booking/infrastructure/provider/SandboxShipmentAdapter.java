package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.SandboxProviderClient;
import com.enterprise.shipping.provider.sandbox.model.ProviderModels.CreateSubmission;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;

/** Booking-owned bridge; provider transport values do not escape into the application. */
public final class SandboxShipmentAdapter implements ShipmentProviderPort {
    private final SandboxProviderClient client;
    public SandboxShipmentAdapter(SandboxProviderClient client) { this.client = client; }
    @Override public Outcome create(Submission submission) {
        var result = client.create(new CreateSubmission(submission.providerId(), submission.accountAlias(), submission.providerQuoteId()));
        return new Outcome(result.shipmentId(), result.trackingNumber(), result.labelUrl(), result.rejected());
    }
}
