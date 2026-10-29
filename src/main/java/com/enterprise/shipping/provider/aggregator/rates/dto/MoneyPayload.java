package com.enterprise.shipping.provider.aggregator.rates.dto;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MoneyPayload(
        @JsonProperty("amount") Long amount,
        @JsonProperty("currency") String currency) {

    public MoneyPayload {
        if (amount == null || amount < 0 || currency == null || currency.isBlank()) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
    }
}
