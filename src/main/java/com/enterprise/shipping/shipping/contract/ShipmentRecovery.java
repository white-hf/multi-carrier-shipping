package com.enterprise.shipping.shipping.contract;

import java.util.List;

/**
 * Published recovery contract owned by shipping. Reconciliation depends on this interface and on
 * immutable values only; persistence records and aggregates never cross the module boundary.
 */
public interface ShipmentRecovery {
    List<Candidate> overdue(
            long now,
            long afterDeadline,
            String afterId,
            int limit);

    boolean markUnknown(Candidate candidate, long now);

    boolean requireManualReview(
            Candidate candidate,
            ManualReviewReason reason,
            long now);

    record Candidate(
            String userId,
            String bookingId,
            String providerId,
            String accountAlias,
            long deadline,
            long version) {}
}
