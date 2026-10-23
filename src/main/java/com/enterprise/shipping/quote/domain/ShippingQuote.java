package com.enterprise.shipping.quote.domain;

import java.math.BigDecimal;

/** Internal and provider identities stay distinct, even when one provider returns many carriers. */
public record ShippingQuote(String quoteId, String requestId, String providerId, String accountAlias, String providerQuoteId,
        String carrierId, String carrierName, String serviceId, String serviceName,
        long totalMinor, String currency, Integer transitDays, long expiresAt) {
    public Money total() { return new Money(BigDecimal.valueOf(totalMinor,2).toPlainString(),currency); }
    public record Money(String amount, String currency) {}
}
