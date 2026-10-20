package com.enterprise.shipping.booking.api;

import com.enterprise.shipping.booking.domain.BookingCommand;
import com.enterprise.shipping.booking.domain.BookingCommand.DemoScenario;
import com.enterprise.shipping.shared.api.ParcelRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CreateBookingRequest(
        @NotBlank @Size(max = 64) String orderReference,
        @NotBlank @Pattern(regexp = "(?i)(fedex|ups)") String carrierId,
        @NotBlank @Size(max = 64) String serviceLevel,
        @NotNull @Valid ParcelRequest parcel,
        DemoScenario demoScenario) {
    public BookingCommand toCommand() {
        return new BookingCommand(orderReference, carrierId, serviceLevel,
                parcel.toDomain(), demoScenario);
    }
}
