package com.enterprise.shipping.shipping.model;

/** Stable aggregate identity. Storage and HTTP retain its existing string representation. */
public record BookingId(String value) {
    public BookingId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Booking ID required");
        }
    }
}
