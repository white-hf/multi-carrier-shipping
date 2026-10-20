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
        CarrierReceipt receipt = null;
        try {
            var result = carrier.inquire(owned.customerReference());
            if (result.isPresent()) {
                BookingService.validateReceipt(owned, result.get());
                receipt = result.get();
            }
        } catch (RuntimeException error) {
            // Inquiry is read-only; bounded retry is safe even if its response is lost.
            log.warn("Booking inquiry unavailable bookingId={} attempt={} type={}",
                    owned.bookingId(), owned.inquiryAttempts(), error.getClass().getSimpleName());
        }
        if (receipt != null) {
            BookingResponse response = new BookingResponse(owned.bookingId(), BookingStatus.CONFIRMED,
                    owned.carrierId(), owned.customerReference(), receipt.carrierBookingId(),
                    receipt.trackingNumber(), receipt.labelUrl(), null);
            boolean completed = store.complete(owned, BookingStatus.CONFIRMED, receipt, null,
                    json.write(response), clock.millis());
            if (completed) {
                observer.outcome("inquiry", BookingStatus.CONFIRMED, System.nanoTime() - start);
                log.info("Booking reconciled bookingId={} attempt={}", owned.bookingId(), owned.inquiryAttempts());
            }
            return;
        }
        boolean exhausted = owned.inquiryAttempts() >= properties.inquiryMaxAttempts();
        BookingStatus status = exhausted ? BookingStatus.MANUAL_REVIEW : BookingStatus.PENDING_INQUIRY;
        long delay = Math.min(properties.inquiryMaxDelayMs(),
                properties.inquiryBaseDelayMs() * (1L << Math.min(owned.inquiryAttempts() - 1, 20)));
        boolean updated = store.defer(owned, status, exhausted ? "INQUIRY_EXHAUSTED" : "CARRIER_OUTCOME_UNKNOWN",
                clock.millis() + delay, clock.millis());
        if (updated) {
            observer.outcome("inquiry", status, System.nanoTime() - start);
            log.warn("Booking inquiry unresolved bookingId={} status={} attempt={}",
                    owned.bookingId(), status, owned.inquiryAttempts());
        }
    }
}
