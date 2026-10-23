package com.enterprise.shipping.quote.domain;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.util.Objects;

/**
 * Represents one durable customer shipping intent.
 *
 * Rate execution state and version can advance on explicit retry or quote-expiry refresh
 * without creating a duplicate customer request record.
 */
public record RateRequest(
        String userId,
        String requestId,
        String key,
        String fingerprint,
        ShippingIntent intent,
        State state,
        long version,
        long deadline) {

    public enum State { RATING, READY, NO_RATES, RATE_FAILED }

    public RateRequest {
        Objects.requireNonNull(intent, "Shipment snapshot required");
        Objects.requireNonNull(state, "Rate state required");
    }

    public boolean matches(String candidate) {
        return Objects.equals(fingerprint, candidate);
    }

    public boolean overdue(long now) {
        return state == State.RATING && deadline <= now;
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
                newDeadline
        );
    }
}
