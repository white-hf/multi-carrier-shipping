package com.enterprise.shipping.quote.port;

import com.enterprise.shipping.quote.domain.ComparisonStatus;
import com.enterprise.shipping.quote.domain.OutcomeCode;
import java.util.function.IntSupplier;

/** Failure-isolated observability contract; implementations must not throw into application code. */
public interface QuoteObserver {
    void workers(String carrierId, IntSupplier activeCount);

    void carrierOutcome(String carrierId, OutcomeCode outcome);

    void comparison(ComparisonStatus status, long elapsedNanos);
}
