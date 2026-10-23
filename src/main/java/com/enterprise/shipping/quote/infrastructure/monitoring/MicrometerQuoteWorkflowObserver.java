package com.enterprise.shipping.quote.infrastructure.monitoring;

import com.enterprise.shipping.quote.port.QuoteWorkflowObserver;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerQuoteWorkflowObserver implements QuoteWorkflowObserver {
    private final MeterRegistry registry;
    public MicrometerQuoteWorkflowObserver(MeterRegistry registry){this.registry=registry;}
    @Override public void record(Outcome outcome,long elapsed){SafeMetrics.record(registry,"shipping.workflow.quote",elapsed,"outcome",outcome.name());}
}
