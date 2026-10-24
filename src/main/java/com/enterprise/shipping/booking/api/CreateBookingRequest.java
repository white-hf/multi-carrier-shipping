package com.enterprise.shipping.booking.api;

import com.enterprise.shipping.booking.application.BookingCommand;
import com.enterprise.shipping.shared.api.ParcelRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CreateBookingRequest(
        @NotBlank @Size(max = 64) String orderReference,
        @NotBlank @Size(max = 32) String carrierId,
        @NotBlank @Size(max = 64) String serviceLevel,
        @NotNull @Valid ParcelRequest parcel) {
    public BookingCommand toCommand(String defaultCountry) {
        return new BookingCommand(orderReference, carrierId, serviceLevel,
                parcel.toDomain(defaultCountry));
    }
}
