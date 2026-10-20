package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.*;

/** Immutable database snapshot. Version and lease token fence stale request/worker updates. */
public record BookingRecord(String bookingId, BookingOwner owner, String idempotencyKey,
        String requestHash, String requestJson, String carrierId, String customerReference,
        BookingStatus status, String carrierBookingId, String trackingNumber, String labelUrl,
        String responseJson, String errorCode, long createdAt, long updatedAt,
        long nextInquiryAt, int inquiryAttempts, String leaseToken, long leaseExpiresAt, long version) {

    /** Keeps persistence metadata out of the aggregate and validates stored business state on restore. */
    public ShipmentBooking restoreBooking() {
        CarrierReceipt receipt = carrierBookingId == null && trackingNumber == null && labelUrl == null ? null
                : new CarrierReceipt(customerReference, carrierBookingId, trackingNumber, labelUrl);
        return ShipmentBooking.restore(new BookingId(bookingId), carrierId, customerReference, status, receipt, errorCode);
    }

    public static BookingRecord claim(ShipmentBooking booking, BookingOwner owner, String key, String hash,
            String requestJson, long now, String leaseToken, long leaseExpiresAt) {
        if (booking.status() != BookingStatus.PENDING) throw new IllegalArgumentException("Pending claim required");
        return new BookingRecord(booking.id().value(), owner, key, hash, requestJson,
                booking.carrierId(), booking.customerReference(), booking.status(),
                null, null, null, null, null, now, now, leaseExpiresAt, 0, leaseToken, leaseExpiresAt, 0);
    }

    /** Storage ownership must refer to the same business identity before a fenced update. */
    public void requireIdentityOf(ShipmentBooking booking) {
        if (!bookingId.equals(booking.id().value()) || !carrierId.equals(booking.carrierId())
                || !customerReference.equals(booking.customerReference()))
            throw new IllegalArgumentException("Booking identity does not match storage claim");
    }
}
