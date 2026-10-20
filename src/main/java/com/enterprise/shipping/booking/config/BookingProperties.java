package com.enterprise.shipping.booking.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("shipping.booking")
public record BookingProperties(
        @DefaultValue("./data/bookings.db") @NotBlank String databasePath,
        @DefaultValue("./data/demo-carrier.db") @NotBlank String carrierDatabasePath,
        @DefaultValue("http://127.0.0.1:8080/api/mock/bookings") @NotBlank String carrierBaseUrl,
        @DefaultValue("demo-merchant") @NotBlank String demoOwnerId,
        @DefaultValue("500") @Min(50) @Max(5000) long carrierTimeoutMs,
        @DefaultValue("15000") @Min(1000) @Max(300000) long leaseMs,
        @DefaultValue("1000") @Min(1) @Max(5000) int databaseBusyTimeoutMs,
        @DefaultValue("4") @Min(2) @Max(8) int databasePoolSize,
        @DefaultValue("1000") @Min(10) @Max(60000) long inquiryBaseDelayMs,
        @DefaultValue("30000") @Min(10) @Max(300000) long inquiryMaxDelayMs,
        @DefaultValue("8") @Min(1) @Max(100) int inquiryMaxAttempts,
        @DefaultValue("16") @Min(1) @Max(100) int inquiryBatchSize) {
    public BookingProperties {
        if (leaseMs <= carrierTimeoutMs) {
            throw new IllegalArgumentException("Booking lease must exceed carrier timeout");
        }
        if (inquiryMaxDelayMs < inquiryBaseDelayMs) {
            throw new IllegalArgumentException("Maximum inquiry delay must not be below base delay");
        }
    }
}
