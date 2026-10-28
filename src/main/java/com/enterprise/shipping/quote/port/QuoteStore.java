package com.enterprise.shipping.quote.port;

import com.enterprise.shipping.quote.domain.RateRequest;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Short committed writes only; no provider call runs inside a storage transaction.
 */
public interface QuoteStore {

    Optional<RateRequest> byKey(String userId, String key);

    Optional<RateRequest> request(String userId, String requestId);

    boolean insert(RateRequest request, long now);

    boolean claim(RateRequest expected, RateRequest claimed, long now);

    /** Successful writes return their committed facts; conflicts return a current storage snapshot. */
    Completion complete(RateRequest expected, List<ShippingQuote> quotes, long now);

    record Completion(RateRequest request, List<ShippingQuote> quotes, boolean applied) {
        public Completion {
            Objects.requireNonNull(request, "Rate request required");
            quotes = List.copyOf(quotes);
        }
    }

    boolean fail(RateRequest expected, long now);

    List<ShippingQuote> quotes(String userId, String requestId);

    Optional<ShippingQuote> find(String userId, String quoteId);
}
