package com.enterprise.shipping.booking.domain;

import com.enterprise.shipping.booking.contract.ManualReviewReason;
import java.util.Objects;

/**
 * Aggregate root for one booking lifecycle. One request owns each in-memory instance;
 * database uniqueness and fenced writes provide protection across requests and processes.
 */
public final class ShipmentBooking {
    private static final String OUTCOME_UNKNOWN = "CARRIER_OUTCOME_UNKNOWN";

    private final BookingId id;
    private final String carrierId;
    private final String customerReference;
    private BookingStatus status;
    private CarrierReceipt receipt;
    private String errorCode;

    private ShipmentBooking(
            BookingId id,
            String carrierId,
            String customerReference,
            BookingStatus status,
            CarrierReceipt receipt,
            String errorCode) {
        this.id = Objects.requireNonNull(id, "Booking ID required");
        this.carrierId = required(carrierId, "Carrier ID required");
        this.customerReference = required(customerReference, "Carrier reference required");
        this.status = Objects.requireNonNull(status, "Booking status required");
        this.receipt = receipt;
        this.errorCode = errorCode;
        validateRestoredState();
    }

    /** Creating an aggregate does not authorize submission: the caller must win the durable claim. */
    public static ShipmentBooking pending(
            BookingId id, String carrierId, String customerReference) {
        return new ShipmentBooking(
                id, carrierId, customerReference, BookingStatus.PENDING, null, null);
    }

    /** Rehydrates trusted storage without replaying business actions or triggering carrier calls. */
    public static ShipmentBooking restore(
            BookingId id,
            String carrierId,
            String customerReference,
            BookingStatus status,
            CarrierReceipt receipt,
            String errorCode) {
        return new ShipmentBooking(id, carrierId, customerReference, status, receipt, errorCode);
    }

    public void confirm(CarrierReceipt receipt) {
        requireRecoverable();
        acceptReceipt(receipt);
    }

    /** Only unresolved recovery states may consume evidence from a late submission response. */
    public boolean canAcceptLateConfirmation() {
        return status == BookingStatus.PENDING_INQUIRY || status == BookingStatus.MANUAL_REVIEW;
    }

    public void confirmLate(CarrierReceipt receipt) {
        if (!canAcceptLateConfirmation()) {
            throw new IllegalStateException("Booking cannot accept late confirmation");
        }
        acceptReceipt(receipt);
    }

    private void acceptReceipt(CarrierReceipt receipt) {
        validateReceipt(
                receipt); // Validate before mutation: malformed evidence leaves state unchanged.
        this.receipt = receipt;
        this.errorCode = null;
        this.status = BookingStatus.CONFIRMED;
    }

    public boolean canRetry() {
        return status == BookingStatus.RETRYABLE;
    }

    public boolean canRetrieveLabel() {
        return status == BookingStatus.CONFIRMED;
    }

    public boolean canRequireManualReview() {
        return status == BookingStatus.PENDING_INQUIRY;
    }

    public void reject(RejectionReason reason) {
        requireStatus(BookingStatus.PENDING);
        this.errorCode = Objects.requireNonNull(reason, "Definitive rejection required").name();
        this.status = BookingStatus.FAILED;
    }

    /** Unknown outcomes require inquiry; this transition never grants permission to resubmit. */
    public void markOutcomeUnknown() {
        requireRecoverable();
        this.errorCode = OUTCOME_UNKNOWN;
        this.status = BookingStatus.PENDING_INQUIRY;
    }

    /** Local admission refused dispatch; RETRYABLE also accepts documented no-creation evidence. */
    public void markNotDispatched() {
        requireStatus(BookingStatus.PENDING);
        this.status = BookingStatus.RETRYABLE;
        this.errorCode = "SHIPMENT_SUBMISSION_NOT_DISPATCHED";
    }

    /** The documented authentication rejection proves no shipment was created, not that no HTTP was sent. */
    public void markAuthenticationFailed() {
        requireStatus(BookingStatus.PENDING);
        this.status = BookingStatus.RETRYABLE;
        this.errorCode = "PROVIDER_AUTHENTICATION_FAILED";
    }

    /** The caller must still win the durable version-fenced retry claim before dispatch. */
    public void prepareRetry() {
        requireStatus(BookingStatus.RETRYABLE);
        this.status = BookingStatus.PENDING;
        this.errorCode = null;
    }

    public void requireManualReview() {
        requireManualReview(ManualReviewReason.INQUIRY_EXHAUSTED);
    }

    public void requireManualReview(ManualReviewReason reason) {
        requireStatus(BookingStatus.PENDING_INQUIRY);
        this.errorCode = Objects.requireNonNull(reason, "Manual review reason required").name();
        this.status = BookingStatus.MANUAL_REVIEW;
    }

    private void requireRecoverable() {
        if (status != BookingStatus.PENDING && status != BookingStatus.PENDING_INQUIRY) {
            throw new IllegalStateException("Booking is not open for automated recovery");
        }
    }

    private void requireStatus(BookingStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Invalid booking state transition");
        }
    }

    private void validateReceipt(CarrierReceipt candidate) {
        if (candidate == null || !customerReference.equals(candidate.customerReference())) {
            throw new IllegalArgumentException("Receipt must match the booking reference");
        }
        required(candidate.carrierBookingId(), "Carrier booking ID required");
    }

    private void validateRestoredState() {
        if (status == BookingStatus.CONFIRMED) {
            validateReceipt(receipt);
            if (errorCode != null) {
                throw new IllegalArgumentException("Confirmed booking cannot have an error");
            }
            return;
        }
        if (receipt != null) {
            throw new IllegalArgumentException("Unconfirmed booking cannot have a receipt");
        }
        if (status == BookingStatus.MANUAL_REVIEW) {
            if (!ManualReviewReason.accepts(errorCode)) {
                throw new IllegalArgumentException("Manual review reason required");
            }
            return;
        }
        String expectedError =
                switch (status) {
                    case PENDING -> null;
                    case RETRYABLE ->
                            "PROVIDER_AUTHENTICATION_FAILED".equals(errorCode)
                                    ? "PROVIDER_AUTHENTICATION_FAILED"
                                    : "SHIPMENT_SUBMISSION_NOT_DISPATCHED";
                    case FAILED -> RejectionReason.CARRIER_REJECTED.name();
                    case PENDING_INQUIRY -> OUTCOME_UNKNOWN;
                    case MANUAL_REVIEW ->
                            throw new IllegalStateException("Manual review already checked");
                    case CONFIRMED ->
                            throw new IllegalStateException("Confirmation already checked");
                };
        if (!Objects.equals(expectedError, errorCode)) {
            throw new IllegalArgumentException("Booking status and error are inconsistent");
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public BookingId id() {
        return id;
    }

    public String carrierId() {
        return carrierId;
    }

    public String customerReference() {
        return customerReference;
    }

    public BookingStatus status() {
        return status;
    }

    public CarrierReceipt receipt() {
        return receipt;
    }

    public String errorCode() {
        return errorCode;
    }
}
