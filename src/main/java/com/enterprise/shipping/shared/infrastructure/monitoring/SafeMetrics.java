package com.enterprise.shipping.shared.infrastructure.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

/** Metrics failures must not change the result of an external chargeable operation. */
public final class SafeMetrics {
    private SafeMetrics() {
    }

    public static void record(
            MeterRegistry registry,
            String name,
            long nanos,
            String... tags) {
        try {
            registry.timer(name, tags).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            org.slf4j.LoggerFactory.getLogger(SafeMetrics.class)
                    .warn("Metric recording unavailable");
        }
    }
}
