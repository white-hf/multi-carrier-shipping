package com.enterprise.shipping.shared.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable shipping intent combining sender, recipient, package measurements, and ship date.
 *
 * Enforces DECIMAL(12, 4) precision bounds at validation time so database writes never
 * silently round or truncate user-supplied measurements.
 */
public record ShippingIntent(
        @NotNull @Valid ShippingAddress sender,
        @NotNull @Valid ShippingAddress recipient,
        @NotNull @Valid Package parcel,
        LocalDate shippingDate) {

    public record Package(
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") @Digits(integer = 8, fraction = 4) BigDecimal length,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") @Digits(integer = 8, fraction = 4) BigDecimal width,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") @Digits(integer = 8, fraction = 4) BigDecimal height,
            DimensionUnit dimensionUnit,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") @Digits(integer = 8, fraction = 4) BigDecimal weight,
            WeightUnit weightUnit,
            @NotBlank @Size(max = 32) String packageType) {

        public Package {
            length = normalizeDecimal(length);
            width = normalizeDecimal(width);
            height = normalizeDecimal(height);
            weight = normalizeDecimal(weight);
            dimensionUnit = dimensionUnit == null ? DimensionUnit.IN : dimensionUnit;
            weightUnit = weightUnit == null ? WeightUnit.LB : weightUnit;
            packageType = packageType == null || packageType.isBlank() ? "PACKAGE" : packageType.strip();
        }

        private static BigDecimal normalizeDecimal(BigDecimal value) {
            if (value == null) {
                return null;
            }
            BigDecimal stripped = value.stripTrailingZeros();
            return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
        }
    }

    public ShippingIntent dated(LocalDate date) {
        return new ShippingIntent(sender, recipient, parcel, date);
    }
}
