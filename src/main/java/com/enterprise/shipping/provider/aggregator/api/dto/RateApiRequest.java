package com.enterprise.shipping.provider.aggregator.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record RateApiRequest(
        @JsonProperty("from") AddressPayload from,
        @JsonProperty("to") AddressPayload to,
        @JsonProperty("packageType") String packageType,
        @JsonProperty("lineItems") List<LineItemPayload> lineItems,
        @JsonProperty("unitOfMeasurement") String unitOfMeasurement,
        @JsonProperty("shipDate") String shipDate,
        @JsonProperty("waitTimeLimit") int waitTimeLimit) {

    public RateApiRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(packageType, "packageType");
        Objects.requireNonNull(lineItems, "lineItems");
        Objects.requireNonNull(unitOfMeasurement, "unitOfMeasurement");
        Objects.requireNonNull(shipDate, "shipDate");
        lineItems = List.copyOf(lineItems);
    }
}
