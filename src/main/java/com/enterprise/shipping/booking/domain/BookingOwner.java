package com.enterprise.shipping.booking.domain;

/** Ownership comes from server context, never from an idempotency key or request body. */
public record BookingOwner(String type, String id) {}
