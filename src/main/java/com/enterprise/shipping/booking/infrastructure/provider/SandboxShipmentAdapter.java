package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.booking.port.ShipmentNotDispatchedException;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.provider.sandbox.shipments.CreateSubmission;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxCreateOperation;
import com.enterprise.shipping.shared.infrastructure.http.OutboundAdmissionRejectedException;

/** Booking-owned bridge; provider transport values do not escape into the application. */
public final class SandboxShipmentAdapter implements ShipmentProviderPort {

    private final SandboxCreateOperation createOperation;

    public SandboxShipmentAdapter(SandboxCreateOperation createOperation) {
        this.createOperation = createOperation;
    }

    @Override
    public Outcome create(Submission submission) {
        try {
            var result =
                    createOperation.create(
                            new CreateSubmission(
                                    submission.providerId(),
                                    submission.accountAlias(),
                                    submission.providerQuoteId()));
            return result.rejected()
                    ? Outcome.rejection()
                    : Outcome.confirmed(
                            result.shipmentId(), result.trackingNumber(), result.labelUrl());
        } catch (OutboundAdmissionRejectedException rejected) {
            // Translate transport evidence into the booking-owned port contract.
            var reason =
                    switch (rejected.reason()) {
                        case BULKHEAD_FULL -> ShipmentNotDispatchedException.Reason.BULKHEAD_FULL;
                        case CIRCUIT_OPEN -> ShipmentNotDispatchedException.Reason.CIRCUIT_OPEN;
                    };
            throw new ShipmentNotDispatchedException(reason);
        }
    }
}
