package com.enterprise.shipping.provider.sandbox.shipment;

/** Opaque provider quote handle plus routing context; the provider interprets the handle. */
public record CreateSubmission(
        String providerId,
        String accountAlias,
        String providerQuoteId) {}
