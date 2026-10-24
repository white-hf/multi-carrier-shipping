package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.shared.domain.*;
import com.enterprise.shipping.shared.api.WorkflowException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Provider payload and unit mapping; no business-module dependencies. */
final class SandboxPayloadMapper {
    private final int collectionSeconds;
    SandboxPayloadMapper(int collectionSeconds) { this.collectionSeconds = collectionSeconds; }
    private static WorkflowException protocol() {
        return new WorkflowException("PROVIDER_INVALID_RESPONSE", HttpStatus.BAD_GATEWAY);
    }
    static String shipmentId(JsonNode body) {
        JsonNode id = body.get("shipId");
        if (id != null && id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0) {
            return Long.toString(id.longValue());
        }
        if (id != null && id.isTextual() && id.textValue().matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.toString(Long.parseLong(id.textValue()));
            } catch (NumberFormatException invalid) {
                throw protocol();
            }
        }
        throw protocol();
    }

    static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw protocol();
        }
        return value.asText();
    }

    Map<String, Object> rateRequest(ShippingIntent intent) {
        var parcel = intent.parcel();
        boolean imperial = parcel.dimensionUnit() == DimensionUnit.IN;

        // Mixed input units are converted to one consistent provider system without floating-point errors
        var weight = imperial
                ? (parcel.weightUnit() == WeightUnit.LB ? parcel.weight() : parcel.weight().divide(new java.math.BigDecimal("0.45359237"), 12, java.math.RoundingMode.HALF_UP).stripTrailingZeros())
                : (parcel.weightUnit() == WeightUnit.KG ? parcel.weight() : parcel.weight().multiply(new java.math.BigDecimal("0.45359237")).stripTrailingZeros());

        return Map.of(
                "from", address(intent.sender()),
                "to", address(intent.recipient()),
                "packageType", parcel.packageType(),
                "lineItems", List.of(Map.of("length", parcel.length(), "width", parcel.width(), "height", parcel.height(), "weight", weight)),
                "unitOfMeasurement", imperial ? "IMPERIAL" : "METRIC",
                "shipDate", intent.shippingDate().toString(),
                "waitTimeLimit", collectionSeconds
        );
    }

    private Map<String, Object> address(ShippingAddress a) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attention", a.contactName());
        result.put("streetAddress", a.addressLine1());
        result.put("city", a.city());
        result.put("state", a.province());
        result.put("postalCode", a.postalCode());
        result.put("countryCode", a.countryCode());
        result.put("phone", a.phone());
        result.put("residential", a.residential());

        if (a.companyName() != null) {
            result.put("companyName", a.companyName());
        }
        if (a.addressLine2() != null) {
            result.put("streetAddress2", a.addressLine2());
        }
        if (a.email() != null) {
            result.put("email", a.email());
        }
        return result;
    }

}
