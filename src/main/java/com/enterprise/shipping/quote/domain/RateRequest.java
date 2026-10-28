package com.enterprise.shipping.quote.domain;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Represents one durable customer shipping intent.
 *
 * Rate execution state and version can advance on explicit retry or quote-expiry refresh
 * without creating a duplicate customer request record. Active deadline is null when not RATING.
 */
public record RateRequest(
        String userId,
        String requestId,
        String key,
        String fingerprint,
        ShippingIntent intent,
        State state,
        long version,
        Long deadline) {

    public enum State {
        RATING,
        READY,
        NO_RATES,
        RATE_FAILED
    }

    public RateRequest {
        Objects.requireNonNull(intent, "Shipment snapshot required");
        Objects.requireNonNull(state, "Rate state required");
    }

    public enum BookingEligibility {
        ELIGIBLE,
        DATE_EXPIRED,
        NOT_READY
    }

    /** The caller supplies the business date; domain rules never read a system clock. */
    public BookingEligibility bookingEligibility(LocalDate today) {
        if (intent.shippingDate() == null) {
            return BookingEligibility.DATE_EXPIRED;
        }
        return new BookingContext(state, intent.shippingDate()).eligibility(today);
    }

    /** Minimal facts needed to validate booking without loading the customer snapshot. */
    public record BookingContext(State state, LocalDate shippingDate) {
        public BookingContext {
            Objects.requireNonNull(state, "Rate state required");
            Objects.requireNonNull(shippingDate, "Shipping date required");
        }

        public BookingEligibility eligibility(LocalDate today) {
            Objects.requireNonNull(today, "Business date required");
            if (shippingDate.isBefore(today)) {
                return BookingEligibility.DATE_EXPIRED;
            }
            return state == State.READY ? BookingEligibility.ELIGIBLE : BookingEligibility.NOT_READY;
        }
    }

    /** Only the current, unexpired execution may publish a result; SQL repeats this fence atomically. */
    public RateRequest completed(boolean hasOffers, long now) {
        requireRating();
        if (deadline == null || overdue(now)) {
            throw new IllegalStateException("Rate execution is no longer open for completion");
        }
        return finished(hasOffers ? State.READY : State.NO_RATES);
    }

    public RateRequest failed() {
        requireRating();
        return finished(State.RATE_FAILED);
    }

    private RateRequest finished(State target) {
        // The execution version changes on claim, not on completion or failure.
        return new RateRequest(userId, requestId, key, fingerprint, intent, target, version, null);
    }

    private void requireRating() {
        if (state != State.RATING) {
            throw new IllegalStateException("No active rate execution");
        }
    }

    public boolean matches(String candidate) {
        return Objects.equals(fingerprint, candidate);
    }

    public boolean overdue(long now) {
        return state == State.RATING && deadline != null && deadline <= now;
    }

    public boolean mayClaim(long now, boolean quotesExpired) {
        return state == State.RATE_FAILED
                || overdue(now)
                || (state == State.READY && quotesExpired);
    }

    public RateRequest claimed(long now, long newDeadline, boolean quotesExpired) {
        if (!mayClaim(now, quotesExpired)) {
            throw new IllegalStateException("Rate execution is not claimable");
        }
        return new RateRequest(
                userId,
                requestId,
                key,
                fingerprint,
                intent,
                State.RATING,
                version + 1,
                newDeadline);
    }
}
