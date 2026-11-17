package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.domain.BookingSelection;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** Validated fully only when creating a new booking, after the identity replay fast path. */
public record BookingCommand(
        @NotBlank @Size(max = 64) String providerId,
        @NotBlank @Size(max = 255) String providerQuoteId,
        @NotNull @Valid ShippingIntent shippingInput,
        @NotNull @Valid DisplaySnapshot displaySnapshot) {

    public BookingSelection selection(String accountAlias, ShippingIntent dated) {
        var display = displaySnapshot;
        return new BookingSelection(
                providerId, accountAlias, providerQuoteId, display.carrierId, display.serviceId,
                display.total.amount.movePointRight(2).longValueExact(), display.total.currency,
                display.expiresAt == null ? null : display.expiresAt.toEpochMilli(), dated,
                display.carrierName, display.serviceName, display.expirySource);
    }

    public record DisplaySnapshot(
            @NotBlank @Size(max = 128) String carrierId,
            @Size(max = 200) String carrierName,
            @NotBlank @Size(max = 128) String serviceId,
            @Size(max = 200) String serviceName,
            @NotNull @Valid Money total,
            Instant expiresAt,
            @Pattern(regexp = "PROVIDER|LOCAL_ESTIMATE") String expirySource) {}

    public record Money(
            @NotNull @DecimalMin("0") @DecimalMax("999999999999.99")
            @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "CAD") String currency) {}
}
