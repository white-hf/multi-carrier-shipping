package com.enterprise.shipping.shipping.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.shipment.CreateOperation;
import com.enterprise.shipping.provider.sandbox.shipment.CreateSubmission;
import com.enterprise.shipping.provider.sandbox.shipment.CreationOutcome;
import com.enterprise.shipping.shipping.model.CreateResult;
import com.enterprise.shipping.shipping.port.ShipmentProvider;

/** Maps provider evidence to business outcomes without changing their meaning. */
public final class SandboxShipmentAdapter implements ShipmentProvider {
    private final CreateOperation operation;

    public SandboxShipmentAdapter(CreateOperation operation) {
        this.operation = operation;
    }

    @Override
    public CreateResult create(ShipmentSubmission submission) {
        var outcome = operation.create(
                new CreateSubmission(
                        submission.providerId(),
                        submission.accountAlias(),
                        submission.providerQuoteId()));
        return switch (outcome.status()) {
            case CONFIRMED -> CreateResult.confirmed(
                    outcome.shipmentId(),
                    outcome.trackingNumber(),
                    outcome.labelUrl());
            case REJECTED -> CreateResult.rejection();
            case NOT_DISPATCHED -> CreateResult.notDispatched();
            case UNKNOWN -> CreateResult.unknown();
        };
    }
}
