package com.enterprise.shipping.demo.booking;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("demo.booking-carrier")
public record DemoBookingProperties(
        @DefaultValue("NORMAL") Mode mode,
        @NotBlank String baseUrl,
        @DefaultValue("./data/demo-carrier.db") @NotBlank String databasePath,
        @DefaultValue("demo-merchant") @NotBlank String ownerId) {
    public enum Mode { NORMAL, TIMEOUT_AFTER_ACCEPT, REJECTED, UNAVAILABLE }
}
