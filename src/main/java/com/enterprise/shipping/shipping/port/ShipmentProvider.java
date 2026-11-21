package com.enterprise.shipping.shipping.port;

import com.enterprise.shipping.shipping.model.CreateResult;

/** Consumer-owned creation capability; the provider quote handle is opaque to business code. */
public interface ShipmentProvider {
    CreateResult create(ShipmentSubmission submission);

    record ShipmentSubmission(
            String providerId,
            String accountAlias,
            String providerQuoteId) {}
}
