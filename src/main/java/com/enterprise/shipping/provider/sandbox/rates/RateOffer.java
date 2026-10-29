package com.enterprise.shipping.provider.sandbox.rates;

public record RateOffer(
        String providerId,
        String accountAlias,
        String providerQuoteId,
        String carrierId,
        String carrierName,
        String serviceId,
        String serviceName,
        long totalMinor,
        String currency,
        Integer transitDays,
        long expiresAt) {}
