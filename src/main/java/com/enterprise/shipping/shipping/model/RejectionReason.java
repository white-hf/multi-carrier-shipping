package com.enterprise.shipping.shipping.model;

/** Only a definitive rejection can fail an initial submission; timeouts are not rejections. */
public enum RejectionReason {
    CARRIER_REJECTED
}
