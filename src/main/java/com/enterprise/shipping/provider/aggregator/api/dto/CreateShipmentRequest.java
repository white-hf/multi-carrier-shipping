package com.enterprise.shipping.provider.aggregator.api.dto;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateShipmentRequest(
        @JsonProperty("quoteId") String quoteId) {

    public CreateShipmentRequest {
        if (quoteId == null || quoteId.isBlank()) {
            throw ShippingException.invalidInput("INVALID_QUOTE_ID");
        }
    }
}
