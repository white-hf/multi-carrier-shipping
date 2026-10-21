package com.enterprise.shipping.reconciliation.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("shipping.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("15000") @Min(1000) @Max(300000) long leaseMs,
        @DefaultValue("500") @Min(50) @Max(5000) long inquiryTimeoutMs,
        @DefaultValue("1000") @Min(10) @Max(60000) long baseDelayMs,
        @DefaultValue("30000") @Min(10) @Max(300000) long maxDelayMs,
        @DefaultValue("8") @Min(1) @Max(100) int maxAttempts,
        @DefaultValue("16") @Min(1) @Max(500) int batchSize,
        @DefaultValue("100") @Min(1) @Max(500) int discoveryBatchSize) {
    public ReconciliationProperties {
        if (leaseMs <= inquiryTimeoutMs) throw new IllegalArgumentException("Task lease must exceed inquiry budget");
        if (maxDelayMs < baseDelayMs) throw new IllegalArgumentException("Maximum delay must not be below base delay");
    }
}
