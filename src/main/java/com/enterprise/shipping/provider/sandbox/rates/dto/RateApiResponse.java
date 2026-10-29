package com.enterprise.shipping.provider.sandbox.rates.dto;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record RateApiResponse(
        @JsonProperty("availableRates") List<RateItem> availableRates) {

    public RateApiResponse {
        if (availableRates == null || availableRates.stream().anyMatch(Objects::isNull)) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        availableRates = List.copyOf(availableRates);
    }

    public static RateApiResponse empty() {
        return new RateApiResponse(List.of());
    }
}
