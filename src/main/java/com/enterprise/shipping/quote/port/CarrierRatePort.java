package com.enterprise.shipping.quote.port;

import com.enterprise.shipping.quote.domain.CarrierQuote;
import com.enterprise.shipping.quote.application.QuoteCommand;
import java.util.List;

/** Isolates carrier-specific transport and payloads from the rate comparison domain. */
public interface CarrierRatePort {
    String getCarrierId();

    String getCarrierName();

    /** Carrier-specific ceiling; the remaining overall request budget may be shorter. */
    long getMaxSubTaskTimeoutMs();

    /**
     * Returns normalized service offers; an empty list means no eligible service.
     * Must enforce the supplied remaining budget, honor interruption, and never return null.
     * Failures propagate to the engine, which produces a typed, client-safe outcome.
     */
    List<CarrierQuote> fetchQuotes(QuoteCommand request, long timeoutMs);
}
