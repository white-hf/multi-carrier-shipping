package com.enterprise.shipping.quote.domain;

import java.math.BigDecimal;

/** Canonical service offer; the comparison engine verifies currency and carrier identity. */
public record CarrierQuote(String carrierId, String carrierName, String serviceLevel,
        BigDecimal rate, String currency, int estimatedDeliveryDays) {}
