package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.port.BookingStore;
import com.enterprise.shipping.booking.contract.recovery.BookingRecoveryPort;
import com.enterprise.shipping.booking.contract.recovery.Recovery.*;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Booking-owned internal boundary. Reconciliation cannot read this service's tables or mutate its aggregate. */
@Transactional(propagation = Propagation.NEVER)
public class BookingRecoveryService implements BookingRecoveryPort {
    private static final Logger log = LoggerFactory.getLogger(BookingRecoveryService.class);
    private final BookingStore store;
    private final Clock clock;
    public BookingRecoveryService(BookingStore store, Clock clock) { this.store = store; this.clock = clock; }

    @Override public Page discover(Cursor after, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Discovery batch must be 1-500");
        var rows = store.discoverUnresolved(clock.millis(), after, limit);
        Cursor next = rows.size() < limit ? Cursor.START
                : new Cursor(rows.get(rows.size() - 1).createdAt(), rows.get(rows.size() - 1).reference().bookingId());
        return new Page(rows, next);
    }

    @Override public ApplyResult apply(Observation observation) {
        var found = store.findForRecovery(observation.reference());
        if (found.isEmpty()) return ApplyResult.NOT_FOUND;
        var record = found.get();
        var ref = observation.reference();
        if (!record.carrierId().equals(ref.carrierId()) || !record.customerReference().equals(ref.customerReference())
                || !record.carrierContextReference().equals(ref.carrierContextReference()))
            throw new IllegalArgumentException("Recovery reference does not match persisted booking attempt");
        ShipmentBooking booking = record.restoreBooking();
        if (booking.status() == BookingStatus.CONFIRMED) {
            if (observation.type() == ObservationType.FOUND && !booking.receipt().equals(receipt(observation.evidence())))
                return ApplyResult.REVIEW_REQUIRED;
            return ApplyResult.RESOLVED;
        }
        if (booking.status() == BookingStatus.FAILED)
            return observation.type() == ObservationType.FOUND ? ApplyResult.REVIEW_REQUIRED : ApplyResult.RESOLVED;
        if (booking.status() == BookingStatus.MANUAL_REVIEW) return ApplyResult.REVIEW_REQUIRED;
        switch (observation.type()) {
            case FOUND -> booking.confirm(receipt(observation.evidence()));
            case UNRESOLVED -> {
                if (booking.status() == BookingStatus.PENDING_INQUIRY) return ApplyResult.UNRESOLVED;
                booking.markOutcomeUnknown();
            }
            case EXHAUSTED -> {
                booking.markOutcomeUnknown();
                booking.requireManualReview();
            }
        }
        if (!store.saveOutcome(record, booking, clock.millis())) return ApplyResult.RETRY;
        log.info("Booking recovery applied bookingId={} status={}", record.bookingId(), booking.status());
        return switch (booking.status()) {
            case CONFIRMED -> ApplyResult.RESOLVED;
            case MANUAL_REVIEW -> ApplyResult.REVIEW_REQUIRED;
            default -> ApplyResult.UNRESOLVED;
        };
    }

    private CarrierReceipt receipt(Evidence evidence) {
        return new CarrierReceipt(evidence.customerReference(), evidence.carrierBookingId(), evidence.trackingNumber(), evidence.labelUrl());
    }
}
