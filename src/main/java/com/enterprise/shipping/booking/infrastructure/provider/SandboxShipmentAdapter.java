package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.SandboxProviderClient;
import com.enterprise.shipping.provider.sandbox.model.ProviderModels.CreateSubmission;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.booking.port.ShipmentNotDispatchedException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundAdmissionRejectedException;

/** Booking-owned bridge; provider transport values do not escape into the application. */
public final class SandboxShipmentAdapter implements ShipmentProviderPort {
    private final SandboxProviderClient client;
    public SandboxShipmentAdapter(SandboxProviderClient client) { this.client = client; }
    @Override public Outcome create(Submission submission) {
        try {
            var result = client.create(new CreateSubmission(submission.providerId(), submission.accountAlias(), submission.providerQuoteId()));
            return new Outcome(result.shipmentId(), result.trackingNumber(), result.labelUrl(), result.rejected());
        } catch (OutboundAdmissionRejectedException rejected) {
            // Translate transport evidence into the booking-owned port contract.
            var reason = switch (rejected.reason()) {
                case BULKHEAD_FULL -> ShipmentNotDispatchedException.Reason.BULKHEAD_FULL;
                case CIRCUIT_OPEN -> ShipmentNotDispatchedException.Reason.CIRCUIT_OPEN;
            };
            throw new ShipmentNotDispatchedException(reason);
        }
    }
}
