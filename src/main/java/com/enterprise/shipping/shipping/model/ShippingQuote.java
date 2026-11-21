package com.enterprise.shipping.shipping.model;

import java.math.BigDecimal;

/** Transient normalized provider offer; never persisted or assigned an application quote ID. */
public record ShippingQuote(
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
        long expiresAt) {
    public Money total() {
        return new Money(BigDecimal.valueOf(totalMinor, 2).toPlainString(), currency);
    }

    public record Money(String amount, String currency) {}
}
