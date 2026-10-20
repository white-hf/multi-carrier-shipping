package com.enterprise.shipping.booking.domain;

/** Immutable database snapshot. Version and lease token fence stale request/worker updates. */
public record BookingRecord(String bookingId, BookingOwner owner, String idempotencyKey,
        String requestHash, String requestJson, String carrierId, String customerReference,
        BookingStatus status, String carrierBookingId, String trackingNumber, String labelUrl,
        String responseJson, String errorCode, long createdAt, long updatedAt,
        long nextInquiryAt, int inquiryAttempts, String leaseToken, long leaseExpiresAt, long version) {

    public BookingResponse currentResponse() {
        return new BookingResponse(bookingId, status, carrierId, customerReference,
                carrierBookingId, trackingNumber, labelUrl, errorCode);
    }
}
