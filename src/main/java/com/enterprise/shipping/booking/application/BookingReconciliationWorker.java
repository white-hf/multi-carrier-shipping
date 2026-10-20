package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.config.BookingProperties;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.port.*;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inquiry-only recovery. A missing result never authorizes another booking submission. */
@Transactional(propagation = Propagation.NEVER)
public class BookingReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingReconciliationWorker.class);
    private final BookingStore store;
    private final CarrierBookingPort carrier;
    private final BookingJson json;
    private final BookingProperties properties;
    private final Clock clock;
    private final BookingObserver observer;

    public BookingReconciliationWorker(BookingStore store, CarrierBookingPort carrier, BookingJson json,
            BookingProperties properties, Clock clock, BookingObserver observer) {
        this.store = store; this.carrier = carrier; this.json = json;
        this.properties = properties; this.clock = clock; this.observer = observer;
    }

    public void reconcileBatch() {
        long now = clock.millis();
        for (BookingRecord due : store.findDue(now, properties.inquiryBatchSize())) {
            // Refresh time per item: previous network inquiries may have consumed part of the batch.
            long claimTime = clock.millis();
            var owned = store.acquireInquiryLease(due, UUID.randomUUID().toString(), claimTime,
                    claimTime + properties.leaseMs());
            owned.ifPresent(this::inquire);
        }
    }

    private void inquire(BookingRecord owned) {
        long start = System.nanoTime();
        ShipmentBooking booking = owned.restoreBooking();
        try {
            var result = carrier.inquire(booking.customerReference());
            if (result.isPresent()) booking.confirm(result.get());
            else booking.markOutcomeUnknown();
        } catch (RuntimeException error) {
            // Malformed evidence and read-only transport failures leave the outcome uncertain.
            booking.markOutcomeUnknown();
            log.warn("Booking inquiry unavailable bookingId={} attempt={} type={}",
                    owned.bookingId(), owned.inquiryAttempts(), error.getClass().getSimpleName());
        }
        if (booking.status() == BookingStatus.CONFIRMED) {
            BookingResponse response = BookingResponse.from(booking);
            // Persistence errors must not be reclassified as unknown carrier outcomes.
            boolean completed = store.complete(owned, booking, json.write(response), clock.millis());
            if (completed) {
                observer.outcome("inquiry", booking.status(), System.nanoTime() - start);
                log.info("Booking reconciled bookingId={} attempt={}", owned.bookingId(), owned.inquiryAttempts());
            }
            return;
        }
        boolean exhausted = owned.inquiryAttempts() >= properties.inquiryMaxAttempts();
        if (exhausted) booking.requireManualReview();
        long delay = Math.min(properties.inquiryMaxDelayMs(),
                properties.inquiryBaseDelayMs() * (1L << Math.min(owned.inquiryAttempts() - 1, 20)));
        boolean updated = store.defer(owned, booking, clock.millis() + delay, clock.millis());
        if (updated) {
            observer.outcome("inquiry", booking.status(), System.nanoTime() - start);
            log.warn("Booking inquiry unresolved bookingId={} status={} attempt={}",
                    owned.bookingId(), booking.status(), owned.inquiryAttempts());
        }
    }
}
