package com.enterprise.shipping.booking.domain;

/** Read projection reconstructed from persisted business state for reads and idempotent replay. */
public record BookingResponse(String bookingId, BookingStatus status, String carrierId,
                              String customerReference, String carrierBookingId,
                              String trackingNumber, String labelUrl, String errorCode) {
    /** Read-only projection; lifecycle decisions remain on the aggregate. */
    public static BookingResponse from(ShipmentBooking booking) {
        CarrierReceipt receipt = booking.receipt();
        return new BookingResponse(booking.id().value(), booking.status(), booking.carrierId(),
                booking.customerReference(), receipt == null ? null : receipt.carrierBookingId(),
                receipt == null ? null : receipt.trackingNumber(), receipt == null ? null : receipt.labelUrl(),
                booking.errorCode());
    }
}
