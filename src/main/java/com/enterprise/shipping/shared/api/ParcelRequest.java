package com.enterprise.shipping.shared.api;

import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.InvalidParcelException;
import com.enterprise.shipping.shared.domain.ShipmentParcel;
import com.enterprise.shipping.shared.domain.WeightUnit;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Explicit transport units; the domain and carrier ports receive normalized kg/cm values. */
public record ParcelRequest(
        @NotBlank @Size(max = 32) String originPostalCode,
        @NotBlank @Size(max = 32) String destinationPostalCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal weight,
        @NotNull WeightUnit weightUnit,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal length,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal width,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal height,
        @NotNull DimensionUnit dimensionUnit,
        @Pattern(regexp = "(?i)[a-z]{2}") String originCountryCode,
        @Pattern(regexp = "(?i)[a-z]{2}") String destinationCountryCode) {
    // Transport guardrails are physical bounds, independent of the submitted measurement unit.
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("1000");
    private static final BigDecimal MAX_DIMENSION_CM = new BigDecimal("1000");

    public ShipmentParcel toDomain(String defaultCountry) {
        BigDecimal kg = bounded(weightUnit.toKilograms(weight), MAX_WEIGHT_KG);
        BigDecimal lengthCm = bounded(dimensionUnit.toCentimeters(length), MAX_DIMENSION_CM);
        BigDecimal widthCm = bounded(dimensionUnit.toCentimeters(width), MAX_DIMENSION_CM);
        BigDecimal heightCm = bounded(dimensionUnit.toCentimeters(height), MAX_DIMENSION_CM);
        return new ShipmentParcel(originPostalCode, destinationPostalCode, kg, lengthCm, widthCm, heightCm,
                originCountryCode == null ? defaultCountry : originCountryCode,
                destinationCountryCode == null ? defaultCountry : destinationCountryCode);
    }

    private static BigDecimal bounded(BigDecimal value, BigDecimal maximum) {
        if (value.signum() <= 0 || value.compareTo(maximum) > 0) throw new InvalidParcelException();
        return value;
    }
}
