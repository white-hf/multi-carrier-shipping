package com.enterprise.shipping.reconciliation;

import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import com.enterprise.shipping.shipping.contract.ManualReviewReason;
import com.enterprise.shipping.shipping.contract.ShipmentRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Current provider cannot query an unknown create result; discovery only registers manual work.
 *
 * <p>This belongs to reconciliation, not shipping: it is a background recovery concern with its own
 * tables, and it reaches bookings only through the published recovery contract.
 */
public final class RecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);
    private static final int BATCH = 100;
    private final ShipmentRecovery bookings;
    private final ReviewTaskRepository tasks;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicLong oldest = new AtomicLong();
    private long afterDeadline = -1;
    private String afterId = "";

    public RecoveryJob(
            ShipmentRecovery bookings,
            ReviewTaskRepository tasks,
            Clock clock,
            MeterRegistry metrics) {
        this.bookings = bookings;
        this.tasks = tasks;
        this.clock = clock;
        this.metrics = metrics;
        try {
            metrics.gauge(
                    "shipping.recovery.oldest_batch_candidate_overdue_seconds",
                    oldest,
                    value -> value.get() / 1000.0);
        } catch (RuntimeException ignored) {
            // Monitoring must not prevent recovery composition.
        }
    }

    @Scheduled(fixedDelayString = "${shipping.recovery.discovery-interval-ms:15000}")
    public void discover() {
        long started = System.nanoTime();
        int discovered = 0;
        int created = 0;
        long age = 0;
        boolean failed = false;
        try {
            var rows = bookings.overdue(clock.millis(), afterDeadline, afterId, BATCH);
            discovered = rows.size();
            for (var row : rows) {
                bookings.markUnknown(row, clock.millis());
                // Task first: interruption leaves the booking discoverable. Existing tasks do not
                // suppress a version-fenced transition on a subsequent scan.
                var reason = ManualReviewReason.AUTHORITATIVE_LOOKUP_UNSUPPORTED;
                if (tasks.registerManualReview(row, reason, clock.millis())) {
                    created++;
                }
                bookings.requireManualReview(row, reason, clock.millis());
                age = Math.max(age, clock.millis() - row.deadline());
            }
            if (rows.size() < BATCH) {
                afterDeadline = -1;
                afterId = "";
            } else {
                var last = rows.get(rows.size() - 1);
                afterDeadline = last.deadline();
                afterId = last.bookingId();
            }
        } catch (RuntimeException unavailable) {
            failed = true;
            log.warn("Recovery discovery interrupted type={}", unavailable.getClass().getSimpleName());
        } finally {
            try {
                oldest.set(Math.max(0, age));
                metrics.counter("shipping.recovery.candidates").increment(discovered);
                metrics.counter("shipping.recovery.manual_tasks_created").increment(created);
                SafeMetrics.record(
                        metrics,
                        "shipping.recovery.scan",
                        System.nanoTime() - started,
                        "outcome",
                        failed ? "FAILED" : "SUCCESS");
            } catch (RuntimeException ignored) {
                // Monitoring must not change task delivery.
            }
        }
    }
}
