package com.enterprise.shipping.booking.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Scheduling is separate from recovery logic so tests and operators can run deterministic batches. */
public final class BookingReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(BookingReconciliationScheduler.class);
    private final BookingReconciliationWorker worker;
    public BookingReconciliationScheduler(BookingReconciliationWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${shipping.booking.reconciliation-interval-ms:1000}")
    public void tick() {
        try { worker.reconcileBatch(); }
        catch (RuntimeException error) {
            log.error("Booking reconciliation batch unavailable type={}", error.getClass().getSimpleName());
        }
    }
}
