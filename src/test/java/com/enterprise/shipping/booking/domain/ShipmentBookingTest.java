package com.enterprise.shipping.booking.domain;

import com.enterprise.shipping.booking.contract.ManualReviewReason;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Domain rules run without Spring, SQLite, HTTP, mocks, or a clock. */
class ShipmentBookingTest {
    private static final BookingId ID = new BookingId("booking-1");
    private static final String REFERENCE = "customer-reference-1";

    private ShipmentBooking pending() { return ShipmentBooking.pending(ID, "fedex", REFERENCE); }

    private static CarrierReceipt receipt() {
        return new CarrierReceipt(REFERENCE, "carrier-1", "tracking-1", "/labels/1");
    }

    @Test void onlyProvenUnsentBookingsCanReenterPending() {
        var booking = pending();
        booking.markNotDispatched();
        assertThat(booking.status()).isEqualTo(BookingStatus.RETRYABLE);
        assertThat(booking.errorCode()).isEqualTo("SHIPMENT_SUBMISSION_NOT_DISPATCHED");
        var restored = ShipmentBooking.restore(ID, "fedex", REFERENCE,
                booking.status(), null, booking.errorCode());
        restored.prepareRetry();
        assertThat(restored.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(restored.errorCode()).isNull();
        restored.confirm(receipt());
        assertThat(restored.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test void uncertaintyNeverGrantsRetryPermission() {
        var booking = pending();
        booking.markOutcomeUnknown();
        assertThatThrownBy(booking::prepareRetry).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(booking::markNotDispatched).isInstanceOf(IllegalStateException.class);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.RETRYABLE, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void confirmsWithMatchingEvidenceAndPreservesIdentity() {
        var booking = pending();
        booking.confirm(receipt());
        assertThat(booking.id()).isEqualTo(ID);
        assertThat(booking.carrierId()).isEqualTo("fedex");
        assertThat(booking.customerReference()).isEqualTo(REFERENCE);
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.receipt()).isEqualTo(receipt());
        assertThat(booking.errorCode()).isNull();
    }

    @Test void confirmationDoesNotRequireTrackingOrLabel() {
        var booking=pending();
        booking.confirm(new CarrierReceipt(REFERENCE,"carrier-1",null,null));
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.receipt().labelUrl()).isNull();
    }

    @Test void inquiryCanConfirmUnknownOutcomeAndClearItsError() {
        var booking = pending();
        booking.markOutcomeUnknown();
        booking.markOutcomeUnknown(); // A further unresolved inquiry does not reopen submission.
        assertThat(booking.errorCode()).isEqualTo("CARRIER_OUTCOME_UNKNOWN");
        booking.confirm(receipt());
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.errorCode()).isNull();
    }

    @Test void definitiveRejectionFailsAnInitialSubmission() {
        var booking = pending();
        booking.reject(RejectionReason.CARRIER_REJECTED);
        assertThat(booking.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(booking.errorCode()).isEqualTo("CARRIER_REJECTED");
        assertThat(booking.receipt()).isNull();
    }

    @Test void unknownOutcomeCannotBecomeARejection() {
        var booking = pending();
        booking.markOutcomeUnknown();
        assertThatThrownBy(() -> booking.reject(RejectionReason.CARRIER_REJECTED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThat(booking.errorCode()).isEqualTo("CARRIER_OUTCOME_UNKNOWN");
    }

    @Test void manualReviewRequiresAnUnresolvedOutcome() {
        var booking = pending();
        assertThatThrownBy(booking::requireManualReview).isInstanceOf(IllegalStateException.class);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
        booking.markOutcomeUnknown();
        booking.requireManualReview();
        assertThat(booking.status()).isEqualTo(BookingStatus.MANUAL_REVIEW);
        assertThat(booking.errorCode()).isEqualTo("INQUIRY_EXHAUSTED");
    }

    @ParameterizedTest @EnumSource(ManualReviewReason.class)
    void manualReviewReasonSurvivesRestoreWithoutReopeningAutomation(ManualReviewReason reason) {
        var booking = pending();
        booking.markOutcomeUnknown();
        booking.requireManualReview(reason);
        var restored = ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.MANUAL_REVIEW, null, reason.name());
        assertThat(restored.errorCode()).isEqualTo(reason.name());
        assertThat(restored.status().isTerminal()).isFalse(); // Manual review is unresolved.
        assertThatThrownBy(restored::markOutcomeUnknown).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restored.confirm(receipt())).isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidManualReviewReasonCannotMutateOrRestoreABooking() {
        var booking = pending();
        booking.markOutcomeUnknown();
        assertThatThrownBy(() -> booking.requireManualReview(null)).isInstanceOf(NullPointerException.class);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThat(booking.errorCode()).isEqualTo("CARRIER_OUTCOME_UNKNOWN");
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.MANUAL_REVIEW, null, "UNKNOWN_REASON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "FAILED", "MANUAL_REVIEW"})
    void closedAutomationStatesRejectEveryFurtherTransition(BookingStatus status) {
        var booking = pending();
        switch (status) {
            case CONFIRMED -> booking.confirm(receipt());
            case FAILED -> booking.reject(RejectionReason.CARRIER_REJECTED);
            case MANUAL_REVIEW -> { booking.markOutcomeUnknown(); booking.requireManualReview(); }
            default -> throw new AssertionError("Unexpected test state");
        }
        var beforeStatus = booking.status();
        var beforeReceipt = booking.receipt();
        var beforeError = booking.errorCode();
        assertThatThrownBy(() -> booking.confirm(receipt())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> booking.reject(RejectionReason.CARRIER_REJECTED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(booking::markOutcomeUnknown).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(booking::requireManualReview).isInstanceOf(IllegalStateException.class);
        assertThat(booking.status()).isEqualTo(beforeStatus);
        assertThat(booking.receipt()).isEqualTo(beforeReceipt);
        assertThat(booking.errorCode()).isEqualTo(beforeError);
    }

    static Stream<CarrierReceipt> invalidReceipts() {
        return Stream.of(null,
                new CarrierReceipt("other-reference", "carrier-1", "tracking-1", "/labels/1"),
                new CarrierReceipt(REFERENCE, null, "tracking-1", "/labels/1"));
    }

    @ParameterizedTest @MethodSource("invalidReceipts")
    void invalidEvidenceCannotMutatePendingBooking(CarrierReceipt invalid) {
        var booking = pending();
        assertThatThrownBy(() -> booking.confirm(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.receipt()).isNull();
        assertThat(booking.errorCode()).isNull();
    }

    @ParameterizedTest @MethodSource("invalidReceipts")
    void invalidEvidenceCannotEraseUncertainty(CarrierReceipt invalid) {
        var booking = pending();
        booking.markOutcomeUnknown();
        assertThatThrownBy(() -> booking.confirm(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThat(booking.errorCode()).isEqualTo("CARRIER_OUTCOME_UNKNOWN");
    }

    @Test void restoringConfirmationValidatesEvidenceWithoutChangingItsState() {
        var restored = ShipmentBooking.restore(ID, "fedex", REFERENCE, BookingStatus.CONFIRMED, receipt(), null);
        assertThat(restored.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(restored.receipt()).isEqualTo(receipt());
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.CONFIRMED, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.CONFIRMED, receipt(), "CARRIER_OUTCOME_UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void restoringUnconfirmedStateRejectsReceiptsAndInconsistentErrors() {
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.PENDING, receipt(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.PENDING_INQUIRY, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShipmentBooking.restore(ID, "fedex", REFERENCE,
                BookingStatus.FAILED, null, "CARRIER_OUTCOME_UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = " ")
    void identityCannotBeMissing(String id) {
        assertThatThrownBy(() -> new BookingId(id)).isInstanceOf(IllegalArgumentException.class);
    }
}
