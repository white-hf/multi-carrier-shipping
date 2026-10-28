package com.enterprise.shipping.quote.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Fan-out scheduling only; transport, deadline caps and resilience belong to shipping.outbound.
 * Worker overrides are optional. An unconfigured carrier uses the bounded scheduling default.
 */
@ConfigurationProperties(prefix = "shipping.engine")
@Validated
public class QuoteProperties {

    @Min(1)
    @Max(256)
    private int maxConcurrentRequests = 32;

    @Valid
    private Map<String, Carrier> carriers = new LinkedHashMap<>();

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int value) {
        maxConcurrentRequests = value;
    }

    public Map<String, Carrier> getCarriers() {
        return carriers;
    }

    public void setCarriers(Map<String, Carrier> value) {
        carriers = value;
    }

    public Carrier carrier(String id) {
        Carrier value = carriers.get(id);
        if (value == null) {
            return new Carrier(); // Safe scheduling default; outbound policy is resolved
                                  // separately.
        }
        return value;
    }

    public static class Carrier {
        @Min(1)
        @Max(64)
        private int concurrency = 8;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int value) {
            concurrency = value;
        }
    }
}
