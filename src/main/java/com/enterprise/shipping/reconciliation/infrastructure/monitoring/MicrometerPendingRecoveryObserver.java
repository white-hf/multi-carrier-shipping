package com.enterprise.shipping.reconciliation.infrastructure.monitoring;

import com.enterprise.shipping.reconciliation.port.PendingRecoveryObserver;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;

public final class MicrometerPendingRecoveryObserver implements PendingRecoveryObserver {
    private final MeterRegistry metrics;
    private final AtomicLong oldestCandidateMs = new AtomicLong();

    public MicrometerPendingRecoveryObserver(MeterRegistry metrics) {
        this.metrics = metrics;
        metrics.gauge(
                "shipping.recovery.oldest_batch_candidate_overdue_seconds",
                oldestCandidateMs,
                value -> value.get() / 1000.0);
    }

    @Override
    public void scan(
            int discovered,
            int created,
            long age,
            long nanos,
            boolean failed) {
        try {
            oldestCandidateMs.set(Math.max(0, age));
            metrics.counter("shipping.recovery.candidates").increment(discovered);
            metrics.counter("shipping.recovery.manual_tasks_created").increment(created);
            SafeMetrics.record(
                    metrics,
                    "shipping.recovery.scan",
                    nanos,
                    "outcome",
                    failed ? "FAILED" : "SUCCESS");
        } catch (RuntimeException ignored) {
            /* Monitoring must not change task delivery. */

          }
    }
}
