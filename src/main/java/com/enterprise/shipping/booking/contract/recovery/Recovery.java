package com.enterprise.shipping.booking.contract.recovery;

import java.util.List;
import java.util.Objects;

/** Transport-neutral recovery contract. No booking aggregate, SQL, credentials, or parcel payloads. */
public final class Recovery {
    private Recovery() {}
    public record Reference(String bookingId, String bookingLocator, String carrierId,
                            String carrierContextReference, String customerReference) {
        public Reference {
            required(bookingId); required(bookingLocator); required(carrierId);
            required(carrierContextReference); required(customerReference);
        }
    }
    public record Cursor(long createdAt, String bookingId) {
        public static final Cursor START = new Cursor(-1, "");
    }
    public record Candidate(Reference reference, long createdAt) {}
    public record Page(List<Candidate> candidates, Cursor nextCursor) {
        public Page { candidates = List.copyOf(candidates); }
    }
    public record Evidence(String customerReference, String carrierBookingId, String trackingNumber, String labelUrl) {
        public Evidence { required(customerReference); required(carrierBookingId); required(trackingNumber); required(labelUrl); }
    }
    public enum ObservationType { FOUND, UNRESOLVED, EXHAUSTED }
    public record Observation(Reference reference, ObservationType type, Evidence evidence) {
        public Observation {
            Objects.requireNonNull(reference); Objects.requireNonNull(type);
            if (type == ObservationType.FOUND) {
                if (evidence == null || !reference.customerReference().equals(evidence.customerReference()))
                    throw new IllegalArgumentException("Evidence must match the registered carrier reference");
            } else if (evidence != null) throw new IllegalArgumentException("Unexpected carrier evidence");
        }
    }
    public enum ApplyResult { RESOLVED, UNRESOLVED, RETRY, REVIEW_REQUIRED, NOT_FOUND }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Recovery reference field required");
    }
}
