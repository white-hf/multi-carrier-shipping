package com.enterprise.shipping.shipping.api;

import com.enterprise.shipping.shared.api.ShippingLinks;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shipping.application.BookingService;

/** HTTP response projection; business state and links remain separate. */
public record BookingResponse(
        String bookingId,
        String providerId,
        String providerQuoteId,
        String status,
        String shipmentId,
        String trackingNumber,
        String labelStatus,
        String labelUrl,
        String errorCode,
        String statusUrl) {
    static BookingResponse from(BookingService.View booking) {
        return new BookingResponse(
                booking.bookingId(),
                booking.providerId(),
                booking.providerQuoteId(),
                booking.status(),
                booking.shipmentId(),
                booking.trackingNumber(),
                booking.labelStatus(),
                "CONFIRMED".equals(booking.status())
                        ? ShippingLinks.label(booking.bookingId())
                        : null,
                booking.errorCode(),
                ShippingLinks.status(ShippingException.Resource.BOOKING, booking.bookingId()));
    }
}
