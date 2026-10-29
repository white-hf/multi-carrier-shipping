package com.enterprise.shipping.booking.port;

import java.util.Objects;

/** External submission evidence; unknown acceptance never grants permission to resubmit. */
public interface ShipmentProviderPort {
    Outcome create(Submission submission);

    record Submission(String providerId, String accountAlias, String providerQuoteId) {}

    record Outcome(
            Status status,
            String shipmentId,
            String trackingNumber,
            String labelUrl) {
        public enum Status {
            CONFIRMED,
            REJECTED,
            NOT_DISPATCHED,
            AUTHENTICATION_FAILED,
            UNKNOWN
        }

        public Outcome {
            Objects.requireNonNull(status, "Submission outcome required");
            if (status == Status.CONFIRMED) {
                if (shipmentId == null || shipmentId.isBlank()) {
                    throw new IllegalArgumentException("Shipment ID required");
                }
            } else if (shipmentId != null || trackingNumber != null || labelUrl != null) {
                throw new IllegalArgumentException("Only confirmed outcomes carry a receipt");
            }
        }

        public static Outcome confirmed(String shipmentId, String trackingNumber, String labelUrl) {
            return new Outcome(Status.CONFIRMED, shipmentId, trackingNumber, labelUrl);
        }

        public static Outcome rejection() {
            return new Outcome(Status.REJECTED, null, null, null);
        }

        public static Outcome notDispatched() {
            return new Outcome(Status.NOT_DISPATCHED, null, null, null);
        }

        public static Outcome authenticationFailed() {
            return new Outcome(Status.AUTHENTICATION_FAILED, null, null, null);
        }

        public static Outcome unknown() {
            return new Outcome(Status.UNKNOWN, null, null, null);
        }
    }
}
