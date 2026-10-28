package com.enterprise.shipping.shared.domain;

import java.math.BigDecimal;

/** International avoirdupois pound conversion, kept exact for stable intent fingerprints. */
public enum WeightUnit {
    KG(BigDecimal.ONE),
    LB(new BigDecimal("0.45359237"));

    private final BigDecimal kilogramsPerUnit;

    WeightUnit(BigDecimal kilogramsPerUnit) {
        this.kilogramsPerUnit = kilogramsPerUnit;
    }

    public BigDecimal toKilograms(BigDecimal value) {
        return value.multiply(kilogramsPerUnit);
    }
}
