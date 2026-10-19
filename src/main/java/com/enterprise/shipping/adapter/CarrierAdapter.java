package com.enterprise.shipping.adapter;

import com.enterprise.shipping.dto.ShipmentDTO.CarrierQuote;
import com.enterprise.shipping.dto.ShipmentDTO.QuoteRequest;
import java.util.List;

/** Isolates carrier-specific transport and payloads from the rate comparison domain. */
public interface CarrierAdapter {
    String getCarrierId();

    String getCarrierName();

    /** Carrier-specific ceiling; the remaining overall request budget may be shorter. */
    long getMaxSubTaskTimeoutMs();

    /**
     * Returns normalized service offers; an empty list means no eligible service.
     * Must enforce the supplied remaining budget, honor interruption, and never return null.
     * Failures propagate to the engine, which produces a typed, client-safe outcome.
     */
    List<CarrierQuote> fetchQuotes(QuoteRequest request, long timeoutMs);
}
