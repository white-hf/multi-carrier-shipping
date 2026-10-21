package com.enterprise.shipping.booking.config;

import jakarta.validation.constraints.*;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("shipping.booking")
public record BookingProperties(
        @DefaultValue("./data/bookings.db") @NotBlank String databasePath,
        @DefaultValue("500") @Min(50) @Max(5000) long carrierTimeoutMs,
        @DefaultValue("15000") @Min(1000) @Max(300000) long submissionDeadlineMs,
        @DefaultValue("1000") @Min(1) @Max(5000) int databaseBusyTimeoutMs,
        @DefaultValue("4") @Min(2) @Max(8) int databasePoolSize,
        @DefaultValue("local") @NotBlank String bookingLocator,
        @NotBlank String carrierContextReference,
        @NotEmpty Set<@NotBlank @Size(max = 32) String> enabledCarriers) {
    public BookingProperties {
        if (enabledCarriers == null || enabledCarriers.isEmpty()) {
            throw new IllegalArgumentException("At least one booking carrier must be configured");
        }
        // Match BookingCommand normalization and prevent callers from mutating startup policy.
        enabledCarriers = enabledCarriers.stream()
                .map(id -> id.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (submissionDeadlineMs <= carrierTimeoutMs) {
            throw new IllegalArgumentException("Submission deadline must exceed carrier timeout");
        }
    }
}
