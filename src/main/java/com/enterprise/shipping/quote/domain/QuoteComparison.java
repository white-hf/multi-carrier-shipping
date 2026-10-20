package com.enterprise.shipping.quote.domain;

import java.util.List;

public record QuoteComparison(String requestId, ComparisonStatus status,
        List<CarrierQuote> successfulQuotes, List<CarrierOutcome> carrierOutcomes, long totalExecutionTimeMs) {
    public QuoteComparison {
        successfulQuotes = List.copyOf(successfulQuotes);
        carrierOutcomes = List.copyOf(carrierOutcomes);
    }
}
