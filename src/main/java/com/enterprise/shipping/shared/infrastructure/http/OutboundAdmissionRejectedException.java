package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;

/** Proof that a local guard refused execution before the outbound work began. */
public final class OutboundAdmissionRejectedException extends ShippingException {
    public enum Reason { BULKHEAD_FULL, CIRCUIT_OPEN }
    private final Reason reason;

    public OutboundAdmissionRejectedException(Reason reason) {
        super("PROVIDER_CAPACITY_EXCEEDED", 503);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
