package com.enterprise.shipping.provider.sandbox.label;

/** Provider routing context plus the shipment identifier used to resolve a label. */
public record LabelIdentity(
        String providerId,
        String accountAlias,
        String shipmentId) {}
