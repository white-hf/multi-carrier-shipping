package com.enterprise.shipping.quote.infrastructure.monitoring;

import com.enterprise.shipping.quote.port.ShippingQuoteObserver;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerShippingQuoteObserver implements ShippingQuoteObserver {

    private final MeterRegistry registry;

    public MicrometerShippingQuoteObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(Outcome outcome, long elapsed) {
        SafeMetrics.record(registry, "shipping.quote", elapsed, "outcome", outcome.name());
    }
}
