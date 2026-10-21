package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.*;

/** Business state plus storage coordination metadata; no serialized request/response snapshots. */
public record BookingRecord(String bookingId, BookingOwner owner, String idempotencyKey,
        String requestHash, BookingCommand intent, String bookingLocator, String carrierContextReference, String customerReference,
        BookingStatus status, String carrierBookingId, String trackingNumber, String labelUrl,
        String errorCode, long createdAt, long updatedAt,
        long submissionDeadlineAt, long version) {

    public BookingRecord {
        // Lookup identity must survive retries and configuration changes; never persist raw credentials.
        new com.enterprise.shipping.contracts.recovery.Recovery.Reference(bookingId, bookingLocator,
                intent.carrierId(), carrierContextReference, customerReference);
    }

    public String carrierId() { return intent.carrierId(); }

    /** Keeps persistence metadata out of the aggregate and validates stored business state on restore. */
    public ShipmentBooking restoreBooking() {
        CarrierReceipt receipt = carrierBookingId == null && trackingNumber == null && labelUrl == null ? null
                : new CarrierReceipt(customerReference, carrierBookingId, trackingNumber, labelUrl);
        return ShipmentBooking.restore(new BookingId(bookingId), carrierId(), customerReference, status, receipt, errorCode);
    }

    public static BookingRecord claim(ShipmentBooking booking, BookingOwner owner, String key, String hash,
            BookingCommand intent, String bookingLocator, String carrierContextReference, long now, long submissionDeadlineAt) {
        if (booking.status() != BookingStatus.PENDING) throw new IllegalArgumentException("Pending claim required");
        if (!booking.carrierId().equals(intent.carrierId())) throw new IllegalArgumentException("Intent carrier mismatch");
        return new BookingRecord(booking.id().value(), owner, key, hash, intent, bookingLocator, carrierContextReference,
                booking.customerReference(), booking.status(), null, null, null, null,
                now, now, submissionDeadlineAt, 0);
    }

    public void requireIdentityOf(ShipmentBooking booking) {
        if (!bookingId.equals(booking.id().value()) || !carrierId().equals(booking.carrierId())
                || !customerReference.equals(booking.customerReference()))
            throw new IllegalArgumentException("Booking identity does not match storage claim");
    }
}
