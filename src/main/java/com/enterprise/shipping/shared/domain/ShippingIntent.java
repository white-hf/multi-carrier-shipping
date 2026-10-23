package com.enterprise.shipping.shared.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public record ShippingIntent(@NotNull @Valid ShippingAddress sender,
        @NotNull @Valid ShippingAddress recipient, @NotNull @Valid Package parcel, LocalDate shippingDate) {
    public record Package(@NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("10000") BigDecimal length,
            @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("10000") BigDecimal width,
            @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("10000") BigDecimal height,
            DimensionUnit dimensionUnit,
            @NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("10000") BigDecimal weight,
            WeightUnit weightUnit, @Size(max=32) String packageType) {
        public Package {
            dimensionUnit = dimensionUnit == null ? DimensionUnit.IN : dimensionUnit;
            weightUnit = weightUnit == null ? WeightUnit.LB : weightUnit;
            packageType = packageType == null ? "PACKAGE" : packageType;
        }
    }
    public ShippingIntent dated(LocalDate date) { return new ShippingIntent(sender, recipient, parcel, date); }
}
