package com.enterprise.shipping.shipping.model;

/** The carrier's identifiers are separate from our pre-generated customer reference. */
public record CarrierReceipt(
        String customerReference,
        String carrierBookingId,
        String trackingNumber,
        String labelUrl) {}
