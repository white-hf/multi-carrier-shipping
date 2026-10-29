package com.enterprise.shipping.provider.sandbox.shipments;

public record CreateSubmission(
        String providerId, String accountAlias, String providerQuoteId) {}
