package com.enterprise.shipping.provider.aggregator.api.dto;

import com.enterprise.shipping.provider.aggregator.api.codec.ShipmentIdDeserializer;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.Objects;

public record CreateShipmentResponse(
        String shipmentId,
        List<String> trackingNumbers,
        String labelUrl,
        boolean rejected) {

    @JsonCreator
    public CreateShipmentResponse(
            @JsonProperty("shipId")
            @JsonDeserialize(using = ShipmentIdDeserializer.class)
            String shipmentId,
            @JsonProperty("trackingNumbers")
            List<String> trackingNumbers,
            @JsonProperty("labelUrl")
            String labelUrl) {
        this(shipmentId, trackingNumbers, labelUrl, false);
    }

    public CreateShipmentResponse {
        if (!rejected && (shipmentId == null || shipmentId.isBlank())) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        trackingNumbers =
                trackingNumbers == null
                        ? List.of()
                        : trackingNumbers.stream()
                                .filter(Objects::nonNull)
                                .toList();
    }

    public static CreateShipmentResponse rejection() {
        return new CreateShipmentResponse(null, List.of(), null, true);
    }
}
