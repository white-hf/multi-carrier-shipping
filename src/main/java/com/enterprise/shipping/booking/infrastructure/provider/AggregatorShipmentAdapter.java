package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.booking.port.ShipmentNotDispatchedException;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.provider.aggregator.AggregatorProviderClient;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.CreateSubmission;
import com.enterprise.shipping.shared.infrastructure.http.OutboundAdmissionRejectedException;

/** Booking-owned bridge; provider transport values do not escape into the application. */
public final class AggregatorShipmentAdapter implements ShipmentProviderPort {

    private final AggregatorProviderClient client;

    public AggregatorShipmentAdapter(AggregatorProviderClient client) {
        this.client = client;
    }

    @Override
    public Outcome create(Submission submission) {
        try {
            var result =
                    client.create(
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
