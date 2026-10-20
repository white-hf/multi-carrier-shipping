package com.enterprise.shipping.booking.domain;

/** Terminal instances are persisted as response snapshots for stable idempotent replay. */
public record BookingResponse(String bookingId, BookingStatus status, String carrierId,
                              String customerReference, String carrierBookingId,
                              String trackingNumber, String labelUrl, String errorCode) {}
