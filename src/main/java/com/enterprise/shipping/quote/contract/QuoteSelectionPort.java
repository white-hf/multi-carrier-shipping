package com.enterprise.shipping.quote.contract;

/** Immutable submission facts only: booking must not receive quote entities or repositories. */
public interface QuoteSelectionPort {
    Selection resolve(String userId, String quoteId);
    record Selection(String requestId, String quoteId, String providerId, String accountAlias, String providerQuoteId,
            String carrierId, String serviceId, long acceptedMinor, String currency, long expiresAt) {}
}
