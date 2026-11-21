package com.enterprise.shipping.shipping.model;

public enum BookingStatus {
    PENDING,
    RETRYABLE,
    CONFIRMED,
    FAILED,
    PENDING_INQUIRY,
    MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED;
    }
}
