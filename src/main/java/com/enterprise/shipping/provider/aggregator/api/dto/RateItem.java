package com.enterprise.shipping.provider.aggregator.api.dto;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RateItem(
        @JsonProperty("quoteId") String quoteId,
        @JsonProperty("carrierId") String carrierId,
        @JsonProperty("carrierName") String carrierName,
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("totalCharge") MoneyPayload totalCharge,
        @JsonProperty("transitDays") Integer transitDays,
        @JsonProperty("transitUnit") String transitUnit) {

    public RateItem {
        if (isBlank(quoteId)
                || isBlank(carrierId)
                || isBlank(serviceId)
                || totalCharge == null) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        if (transitDays != null
                && (transitUnit == null || "Days".equalsIgnoreCase(transitUnit))
                && transitDays < 0) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
