package com.enterprise.shipping.provider.aggregator.api.dto;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LabelUrlResponse(
        @JsonProperty("labelUrl") String labelUrl) {

    public LabelUrlResponse {
        if (labelUrl == null || labelUrl.isBlank()) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
    }
}
