package com.enterprise.shipping.quote.infrastructure.monitoring;

import com.enterprise.shipping.quote.domain.ComparisonStatus;
import com.enterprise.shipping.quote.domain.OutcomeCode;
import com.enterprise.shipping.quote.port.QuoteObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses bounded labels and preserves existing Prometheus metric names. */
public final class MicrometerQuoteObserver implements QuoteObserver {
    private static final Logger log = LoggerFactory.getLogger(MicrometerQuoteObserver.class);
    private final MeterRegistry registry;

    public MicrometerQuoteObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void workers(String carrierId, IntSupplier activeCount) {
        // The supplier is created at registration; retain it so Micrometer's weak reference cannot
        // expire.
        record(
                () ->
                        Gauge.builder(
                                        "shipping.carrier.workers.active",
                                        activeCount,
                                        value -> value.getAsInt())
                                .tag("carrier", carrierId)
                                .strongReference(true)
                                .register(registry));
    }

    @Override
    public void carrierOutcome(String carrierId, OutcomeCode outcome) {
        record(
                () ->
                        registry.counter(
                                        "shipping.carrier.outcomes",
                                        "carrier",
                                        carrierId,
                                        "outcome",
                                        outcome.name())
                                .increment());
    }

    @Override
    public void comparison(ComparisonStatus status, long elapsedNanos) {
        record(
                () -> {
                    registry.timer("shipping.aggregation.latency", "status", status.name())
                            .record(elapsedNanos, TimeUnit.NANOSECONDS);
                    registry.counter("shipping.aggregation.requests", "status", status.name())
                            .increment();
                });
    }

    private void record(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            log.warn("Quote metric recording failed type={}", error.getClass().getSimpleName());
        }
    }
}
