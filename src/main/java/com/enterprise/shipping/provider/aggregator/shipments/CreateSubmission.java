package com.enterprise.shipping.provider.aggregator.shipments;

public record CreateSubmission(
        String providerId, String accountAlias, String providerQuoteId) {}
