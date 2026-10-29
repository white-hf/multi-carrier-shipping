package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.booking.port.ShipmentNotDispatchedException;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.provider.aggregator.shipments.AggregatorShipmentApi;
import com.enterprise.shipping.provider.aggregator.shipments.CreateSubmission;
import com.enterprise.shipping.shared.infrastructure.http.OutboundAdmissionRejectedException;

/** Booking-owned bridge; provider transport values do not escape into the application. */
public final class AggregatorShipmentAdapter implements ShipmentProviderPort {

    private final AggregatorShipmentApi shipmentApi;

    public AggregatorShipmentAdapter(AggregatorShipmentApi shipmentApi) {
        this.shipmentApi = shipmentApi;
    }

    @Override
    public Outcome create(Submission submission) {
        try {
            var result =
                    shipmentApi.create(
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
