package com.enterprise.shipping.provider.sandbox.rate;

/** Provider-owned normalized offer; the consumer-owned adapter maps it to the business model. */
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
