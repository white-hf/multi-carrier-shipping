package com.enterprise.shipping.reconciliation.infrastructure.monitoring;

import com.enterprise.shipping.reconciliation.port.ReconciliationObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MicrometerReconciliationObserver implements ReconciliationObserver {
    private static final Logger log = LoggerFactory.getLogger(MicrometerReconciliationObserver.class);
    private final MeterRegistry registry;
    public MicrometerReconciliationObserver(MeterRegistry registry) { this.registry = registry; }
    @Override public void record(Operation operation, Outcome outcome, long elapsedNanos) {
        try {
            registry.counter("shipping.reconciliation.events", "operation", operation.name(), "outcome", outcome.name()).increment();
            registry.timer("shipping.reconciliation.latency", "operation", operation.name(), "outcome", outcome.name())
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException error) {
            log.warn("Reconciliation telemetry unavailable type={}", error.getClass().getSimpleName());
        }
    }
}
