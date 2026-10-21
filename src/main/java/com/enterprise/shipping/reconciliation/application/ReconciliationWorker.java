package com.enterprise.shipping.reconciliation.application;

import com.enterprise.shipping.contracts.recovery.BookingRecoveryPort;
import com.enterprise.shipping.contracts.recovery.Recovery.*;
import com.enterprise.shipping.reconciliation.config.ReconciliationProperties;
import com.enterprise.shipping.reconciliation.domain.ReconciliationTask;
import com.enterprise.shipping.reconciliation.port.*;
import com.enterprise.shipping.reconciliation.port.ReconciliationObserver.*;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inquiry and outcome delivery occur outside DB transactions. No submission capability is available here. */
@Transactional(propagation = Propagation.NEVER)
public class ReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);
    private final ReconciliationTaskStore tasks;
    private final BookingRecoveryPort bookings;
    private final CarrierInquiryPort carrier;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final ReconciliationObserver observer;
    public ReconciliationWorker(ReconciliationTaskStore tasks, BookingRecoveryPort bookings, CarrierInquiryPort carrier,
            ReconciliationProperties properties, Clock clock, ReconciliationObserver observer) {
        this.tasks = tasks; this.bookings = bookings; this.carrier = carrier;
        this.properties = properties; this.clock = clock; this.observer = observer;
    }
    public void runBatch() {
        for (var due : tasks.findDue(clock.millis(), properties.batchSize())) {
            long now = clock.millis();
            var owned = tasks.claim(due, UUID.randomUUID().toString(), now, now + properties.leaseMs());
            if (owned.isEmpty()) continue;
            try { process(owned.get()); }
            catch (RuntimeException failure) {
                // Stored observations and expired leases remain recoverable even if task storage is unavailable.
                log.warn("Reconciliation task interrupted taskId={} type={}", due.taskId(), failure.getClass().getSimpleName());
            }
        }
    }
    private void process(ReconciliationTask owned) {
        if (owned.observation() == null) {
            long start = System.nanoTime();
            Observation observation;
            String error = null;
            try {
                var evidence = carrier.inquire(owned.reference(), properties.inquiryTimeoutMs());
                observation = evidence.isPresent() ? new Observation(owned.reference(), ObservationType.FOUND, evidence.get())
                        : unresolved(owned);
            } catch (RuntimeException unavailable) {
                observation = unresolved(owned);
                error = "INQUIRY_UNAVAILABLE";
                log.warn("Carrier inquiry unavailable taskId={} attempt={} type={}", owned.taskId(), owned.attempts(),
                        unavailable.getClass().getSimpleName());
            }
            var saved = tasks.saveObservation(owned, observation, error, clock.millis());
            if (saved.isEmpty()) {
                observer.record(Operation.INQUIRY, Outcome.FENCED, System.nanoTime() - start);
                return;
            }
            observer.record(Operation.INQUIRY, Outcome.OBSERVED, System.nanoTime() - start);
            owned = saved.get();
        }
        deliver(owned);
    }
    private Observation unresolved(ReconciliationTask task) {
        return new Observation(task.reference(), task.attempts() >= properties.maxAttempts()
                ? ObservationType.EXHAUSTED : ObservationType.UNRESOLVED, null);
    }
    private void deliver(ReconciliationTask owned) {
        long start = System.nanoTime();
        ApplyResult result;
        try { result = bookings.apply(owned.observation()); }
        catch (RuntimeException unavailable) {
            // Persisted evidence survives a lost acknowledgement; do not spend another carrier inquiry attempt.
            tasks.retry(owned, nextAt(owned), "BOOKING_PORT_UNAVAILABLE", true, clock.millis());
            observer.record(Operation.DELIVERY, Outcome.RETRY, System.nanoTime() - start);
            return;
        }
        boolean changed;
        Outcome outcome;
        switch (result) {
            case RESOLVED -> { changed = tasks.delete(owned); outcome = Outcome.RESOLVED; }
            case UNRESOLVED -> {
                changed = tasks.retry(owned, nextAt(owned), "CARRIER_OUTCOME_UNKNOWN", false, clock.millis());
                outcome = Outcome.RETRY;
            }
            case RETRY -> {
                changed = tasks.retry(owned, nextAt(owned), "BUSINESS_VERSION_CONFLICT", true, clock.millis());
                outcome = Outcome.RETRY;
            }
            case REVIEW_REQUIRED, NOT_FOUND -> {
                changed = tasks.pause(owned, result.name(), clock.millis()); outcome = Outcome.PAUSED;
            }
            default -> throw new IllegalStateException("Unknown recovery result");
        }
        observer.record(Operation.DELIVERY, changed ? outcome : Outcome.FENCED, System.nanoTime() - start);
        log.info("Reconciliation delivery taskId={} result={} applied={}", owned.taskId(), result, changed);
    }
    private long nextAt(ReconciliationTask task) {
        return clock.millis() + task.retryDelay(properties.baseDelayMs(), properties.maxDelayMs());
    }
}
