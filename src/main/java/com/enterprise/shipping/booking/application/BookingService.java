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

/** Coordinates durable claims and remote side effects, never inside a database transaction. */
@Transactional(propagation = Propagation.NEVER)
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._:-]{1,64}";
    private final BookingStore store;
    private final CarrierBookingPort carrier;
    private final BookingJson json;
    private final BookingProperties properties;
    private final Clock clock;
    private final BookingObserver observer;

    public BookingService(BookingStore store, CarrierBookingPort carrier, BookingJson json,
            BookingProperties properties, Clock clock, BookingObserver observer) {
        this.store = store;
        this.carrier = carrier;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.observer = observer;
    }

    public BookingResponse create(BookingOwner owner, String key, BookingCommand command) {
        if (key == null || !key.matches(IDEMPOTENCY_KEY_PATTERN)) {
            throw new BookingException(BookingException.Code.INVALID_IDEMPOTENCY_KEY);
        }
        String hash = json.hash(command);
        var existing = store.find(owner, key);
        if (existing.isPresent()) return replay(existing.get(), hash);

        long now = clock.millis();
        String id = UUID.randomUUID().toString();
        // Persist the exact carrier/reference/intent before any network operation.
        BookingRecord candidate = new BookingRecord(id, owner, key, hash, json.write(command),
                command.carrierId(), "booking-" + id, BookingStatus.PENDING,
                null, null, null, null, null, now, now, now + properties.leaseMs(), 0,
                UUID.randomUUID().toString(), now + properties.leaseMs(), 0);
        if (!store.insertClaim(candidate)) {
            // A conflict is a replay path, regardless of whether the winner is pending or in inquiry.
            BookingRecord winner = store.find(owner, key)
                    .orElseThrow(() -> new BookingException(BookingException.Code.STORE_UNAVAILABLE));
            return replay(winner, hash);
        }
        observer.claim(true);
        log.info("Booking claimed bookingId={} carrier={} status={}", id, command.carrierId(), candidate.status());

        long start = System.nanoTime();
        CarrierReceipt receipt;
        try {
            receipt = carrier.submit(candidate.customerReference(), command);
            validateReceipt(candidate, receipt);
        } catch (CarrierBookingPort.Rejected rejected) {
            return finish(candidate, BookingStatus.FAILED, null, "CARRIER_REJECTED", start);
        } catch (RuntimeException uncertain) {
            // A timeout/transport fault cannot establish whether the carrier accepted the booking.
            log.warn("Booking outcome uncertain bookingId={} carrier={} type={}", id, command.carrierId(),
                    uncertain.getClass().getSimpleName());
            store.defer(candidate, BookingStatus.PENDING_INQUIRY, "CARRIER_OUTCOME_UNKNOWN",
                    clock.millis() + properties.inquiryBaseDelayMs(), clock.millis());
            observer.outcome("submit", BookingStatus.PENDING_INQUIRY, System.nanoTime() - start);
            return get(owner, id);
        }
        // Persistence is deliberately outside the carrier catch: a failed save is not a carrier rejection.
        return finish(candidate, BookingStatus.CONFIRMED, receipt, null, start);
    }

    public BookingResponse get(BookingOwner owner, String id) {
        BookingRecord booking = store.findById(owner, id)
                .orElseThrow(() -> new BookingException(BookingException.Code.BOOKING_NOT_FOUND));
        return response(booking);
    }

    private BookingResponse replay(BookingRecord booking, String hash) {
        if (!booking.requestHash().equals(hash)) {
            observer.conflict();
            log.warn("Booking idempotency conflict bookingId={}", booking.bookingId());
            throw new BookingException(BookingException.Code.IDEMPOTENCY_CONFLICT);
        }
        observer.claim(false);
        log.info("Booking replay bookingId={} status={}", booking.bookingId(), booking.status());
        return response(booking);
    }

    private BookingResponse finish(BookingRecord owned, BookingStatus status, CarrierReceipt receipt,
                                   String errorCode, long start) {
        BookingResponse response = new BookingResponse(owned.bookingId(), status, owned.carrierId(),
                owned.customerReference(), receipt == null ? null : receipt.carrierBookingId(),
                receipt == null ? null : receipt.trackingNumber(), receipt == null ? null : receipt.labelUrl(), errorCode);
        boolean updated = store.complete(owned, status, receipt, errorCode, json.write(response), clock.millis());
        if (!updated) {
            log.info("Booking completion fenced bookingId={}", owned.bookingId());
            return get(owned.owner(), owned.bookingId());
        }
        observer.outcome("submit", status, System.nanoTime() - start);
        log.info("Booking completed bookingId={} status={}", owned.bookingId(), status);
        return response;
    }

    private BookingResponse response(BookingRecord booking) {
        if (booking.status().isTerminal()) {
            if (booking.responseJson() == null) throw new IllegalStateException("Terminal response snapshot missing");
            return json.readResponse(booking.responseJson());
        }
        return booking.currentResponse();
    }

    static void validateReceipt(BookingRecord booking, CarrierReceipt receipt) {
        if (receipt == null || !booking.customerReference().equals(receipt.customerReference())
                || receipt.carrierBookingId() == null || receipt.carrierBookingId().isBlank()
                || receipt.trackingNumber() == null || receipt.trackingNumber().isBlank()
                || receipt.labelUrl() == null || receipt.labelUrl().isBlank()) {
            throw new CarrierBookingPort.UnknownOutcome(new IllegalStateException("Invalid carrier receipt"));
        }
    }
}
