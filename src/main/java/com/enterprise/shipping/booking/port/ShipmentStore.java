package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.BookingId;
import com.enterprise.shipping.booking.domain.BookingSelection;
import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.domain.ShipmentBooking;
import java.util.Optional;

/** Each mutation commits before returning; this store owns no quote or recovery tables. */
public interface ShipmentStore {
    record Record(
            String userId,
            String id,
            BookingSelection selection,
            BookingStatus status,
            String providerShipmentId,
            String trackingNumber,
            String labelUrl,
            String labelStatus,
            String errorCode,
            long deadline,
            long version) {
        public static Record pending(
                String user,
                String id,
                BookingSelection selection,
                long deadline) {
            var booking =
                    ShipmentBooking.pending(
                            new BookingId(id), selection.carrierId(), "booking-" + id);
            return new Record(
                    user,
                    id,
                    selection,
                    booking.status(),
                    null,
                    null,
                    null,
                    "PENDING",
                    null,
                    deadline,
                    0);
        }

        public ShipmentBooking restore() {
            var receipt =
                    providerShipmentId == null
                            ? null
                            : new CarrierReceipt(
                                    "booking-" + id, providerShipmentId, trackingNumber, labelUrl);
            return ShipmentBooking.restore(
                    new BookingId(id),
                    selection.carrierId(),
                    "booking-" + id,
                    status,
                    receipt,
                    errorCode);
        }

        /** Mirrors a committed retry CAS; constructing a snapshot alone never authorizes dispatch. */
        public Record forClaimedRetry(ShipmentBooking booking, long newDeadline) {
            requireIdentity(booking);
            if (status != BookingStatus.RETRYABLE || booking.status() != BookingStatus.PENDING) {
                throw new IllegalArgumentException(
                        "Retry snapshot requires the prepared original booking");
            }
            return new Record(
                    userId,
                    id,
                    selection,
                    booking.status(),
                    null,
                    null,
                    null,
                    labelStatus,
                    null,
                    newDeadline,
                    version + 1);
        }

        /** Snapshot of this exact committed update, not a claim to observe all later concurrent changes. */
        public Record completed(ShipmentBooking booking) {
            requireIdentity(booking);
            var receipt = booking.receipt();
            return new Record(
                    userId,
                    id,
                    selection,
                    booking.status(),
                    receipt == null ? null : receipt.carrierBookingId(),
                    receipt == null ? null : receipt.trackingNumber(),
                    receipt == null ? null : receipt.labelUrl(),
                    receipt != null && receipt.labelUrl() != null ? "AVAILABLE" : "PENDING",
                    booking.errorCode(),
                    deadline,
                    version + 1);
        }

        private void requireIdentity(ShipmentBooking booking) {
            if (!id.equals(booking.id().value())
                    || !selection.carrierId().equals(booking.carrierId())
                    || !("booking-" + id).equals(booking.customerReference())) {
                throw new IllegalArgumentException(
                        "Booking identity must match its stored snapshot");
            }
        }
    }

    record Completion(Record current, boolean applied) {}

    Optional<Record> byQuote(String userId, String providerId, String providerQuoteId);

    Optional<Record> byId(String userId, String id);

    boolean claim(Record record, long now);

    /** Acquires the next submission version only for a known-unsent, unexpired booking. */
    boolean claimRetry(Record expected, long now, long deadline);

    /** Success returns the committed snapshot without a reread; conflicts return current storage state. */
    Completion complete(Record expected, ShipmentBooking booking, long now);

    void label(
            String userId,
            String id,
            String url,
            long now);

    java.util.List<Record> overdue(
            long now,
            long afterDeadline,
            String afterId,
            int limit);

    boolean markUnknown(Record expected, long now);
}
