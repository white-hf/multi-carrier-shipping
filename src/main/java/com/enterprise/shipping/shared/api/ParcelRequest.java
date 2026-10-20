package com.enterprise.shipping.shared.api;

import com.enterprise.shipping.shared.domain.ShipmentParcel;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Demo input guardrails for one domestic Canadian parcel; these are not carrier service limits. */
public record ParcelRequest(
        @NotBlank @Pattern(regexp = "(?i)[A-Z]\\d[A-Z] ?\\d[A-Z]\\d") String originPostalCode,
        @NotBlank @Pattern(regexp = "(?i)[A-Z]\\d[A-Z] ?\\d[A-Z]\\d") String destinationPostalCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal weightKg,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal lengthCm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal widthCm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal heightCm) {
    public ShipmentParcel toDomain() {
        return new ShipmentParcel(originPostalCode, destinationPostalCode, weightKg, lengthCm, widthCm, heightCm);
    }
}
