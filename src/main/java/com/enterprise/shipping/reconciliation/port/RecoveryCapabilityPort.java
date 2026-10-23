package com.enterprise.shipping.reconciliation.port;

import java.util.Objects;
import com.enterprise.shipping.booking.domain.ManualReviewReason;

/** Local provider/account capabilities only. This port cannot create or retry a shipment. */
public interface RecoveryCapabilityPort {
    enum Support { SUPPORTED, UNSUPPORTED, UNVERIFIED }

    Capabilities capabilities(String providerId, String accountAlias);

    record Capabilities(Support authoritativeLookup, Support creationIdempotency) {
        public Capabilities {
            Objects.requireNonNull(authoritativeLookup, "Lookup capability required");
            Objects.requireNonNull(creationIdempotency, "Idempotency capability required");
        }

        public ManualReviewReason manualReviewReason() {
            return switch (authoritativeLookup) {
                case UNSUPPORTED -> ManualReviewReason.AUTHORITATIVE_LOOKUP_UNSUPPORTED;
                case UNVERIFIED -> ManualReviewReason.AUTHORITATIVE_LOOKUP_UNVERIFIED;
                // Capability metadata alone never authorizes an unimplemented recovery operation.
                case SUPPORTED -> ManualReviewReason.AUTOMATIC_RECOVERY_UNAVAILABLE;
            };
        }
    }
}
