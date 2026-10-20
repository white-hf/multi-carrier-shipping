package com.enterprise.shipping.shared.domain;

import java.math.BigDecimal;

/** Exact conversion to the canonical domain unit; no rounding is applied during normalization. */
public enum DimensionUnit {
    CM(BigDecimal.ONE), IN(new BigDecimal("2.54"));

    private final BigDecimal centimetersPerUnit;
    DimensionUnit(BigDecimal centimetersPerUnit) { this.centimetersPerUnit = centimetersPerUnit; }
    public BigDecimal toCentimeters(BigDecimal value) { return value.multiply(centimetersPerUnit); }
}
