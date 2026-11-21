package com.enterprise.shipping.shipping.application;

import com.enterprise.shipping.shipping.contract.ManualReviewReason;
import com.enterprise.shipping.shipping.contract.ShipmentRecovery;
import com.enterprise.shipping.shipping.persistence.BookingRepository;
import java.util.List;

/** Published recovery boundary implementation; persistence records do not cross this seam. */
public final class RecoveryOperations implements ShipmentRecovery {
    private final BookingRepository repository;
    private final BookingService bookings;

    public RecoveryOperations(
            BookingRepository repository,
            BookingService bookings) {
        this.repository = repository;
        this.bookings = bookings;
    }

    @Override
    public List<Candidate> overdue(
            long now,
            long afterDeadline,
            String afterId,
            int limit) {
        return repository.overdue(now, afterDeadline, afterId, limit).stream()
                .map(row -> new Candidate(
                        row.userId(),
                        row.id(),
                        row.selection().providerId(),
                        row.selection().accountAlias(),
                        row.deadline(),
                        row.version()))
                .toList();
    }

    @Override
    public boolean markUnknown(Candidate candidate, long now) {
        return repository.markUnknown(
                candidate.userId(),
                candidate.bookingId(),
                candidate.version(),
                now);
    }

    @Override
    public boolean requireManualReview(
            Candidate candidate,
            ManualReviewReason reason,
            long now) {
        return bookings.requireManualReview(candidate, reason, now);
    }
}
