package com.enterprise.shipping.booking.domain;

/** Only a definitive rejection can fail an initial submission; timeouts are not rejections. */
public enum RejectionReason {
    CARRIER_REJECTED
}
