package com.enterprise.shipping.shared.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** Shared immutable parcel intent. Normalization is also part of the persisted booking fingerprint. */
public record ShipmentParcel(String originPostalCode, String destinationPostalCode, BigDecimal weightKg,
                     BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {
    public ShipmentParcel {
        originPostalCode = normalizePostalCode(originPostalCode);
        destinationPostalCode = normalizePostalCode(destinationPostalCode);
        weightKg = Objects.requireNonNull(weightKg).stripTrailingZeros();
        lengthCm = Objects.requireNonNull(lengthCm).stripTrailingZeros();
        widthCm = Objects.requireNonNull(widthCm).stripTrailingZeros();
        heightCm = Objects.requireNonNull(heightCm).stripTrailingZeros();
    }

    private static String normalizePostalCode(String value) {
        return Objects.requireNonNull(value).replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
