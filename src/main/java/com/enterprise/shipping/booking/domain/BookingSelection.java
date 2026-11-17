package com.enterprise.shipping.booking.domain;

import com.enterprise.shipping.shared.domain.ShippingIntent;

/** Immutable submitted facts, not proof of provider ownership or confirmed pricing. */
public record BookingSelection(
        String providerId,
        String accountAlias,
        String providerQuoteId,
        String carrierId,
        String serviceId,
        long acceptedMinor,
        String currency,
        Long expiresAt,
        ShippingIntent shippingInput,
        String carrierName,
        String serviceName,
        String expirySource) {
}
