package com.enterprise.shipping.quote.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated per-carrier runtime settings supplied by the active deployment configuration. */
@Configuration
@ConfigurationProperties(prefix = "shipping.engine")
@Validated
@ConditionalOnProperty(name = "shipping.quote.enabled", havingValue = "true")
public class QuoteProperties {
    @Min(1) @Max(10000)
    private long globalTimeoutMs = 600;
    @Min(1) @Max(256)
    private int maxConcurrentRequests = 32;
    @Valid @NotEmpty
    private Map<String, Carrier> carriers = new LinkedHashMap<>();
    public long getGlobalTimeoutMs() { return globalTimeoutMs; }
    public void setGlobalTimeoutMs(long value) { globalTimeoutMs = value; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = value; }
    public Map<String, Carrier> getCarriers() { return carriers; }
    public void setCarriers(Map<String, Carrier> value) { carriers = value; }
    public Carrier carrier(String id) {
        Carrier value = carriers.get(id);
        if (value == null) throw new IllegalArgumentException("Missing carrier configuration: " + id);
        return value;
    }
    public static class Carrier {
        @NotBlank private String baseUrl;
        @Min(1) @Max(10000) private long timeoutMs = 300;
        @Min(1) @Max(64) private int concurrency = 8;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long value) { timeoutMs = value; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int value) { concurrency = value; }
    }
}
