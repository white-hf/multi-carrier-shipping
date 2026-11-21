package com.enterprise.shipping.reconciliation;

import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import com.enterprise.shipping.shared.infrastructure.persistence.UniqueConstraints;
import com.enterprise.shipping.shipping.contract.ManualReviewReason;
import com.enterprise.shipping.shipping.contract.ShipmentRecovery;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers paused manual-review tasks using RDBMS-agnostic ANSI SQL and unique-constraint
 * handling. Reconciliation exclusively owns {@code booking_reconciliation_tasks}.
 */
public final class ReviewTaskRepository {
    private final JdbcTemplate jdbc;

    public ReviewTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean registerManualReview(
            ShipmentRecovery.Candidate candidate,
            ManualReviewReason reason,
            long now) {
        try {
            return jdbc.update(
                            """
                    INSERT INTO booking_reconciliation_tasks (
                        task_id, user_id, booking_id, booking_locator, provider_id, account_alias,
                        customer_reference, state, available_at, last_error_code, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PAUSED', ?, ?, ?, ?)
                    """,
                            TimeOrderedIds.next(),
                            candidate.userId(),
                            candidate.bookingId(),
                            candidate.userId(),
                            candidate.providerId(),
                            candidate.accountAlias(),
                            "booking-" + candidate.bookingId(),
                            now,
                            reason.name(),
                            now,
                            now)
                    == 1;
        } catch (DataAccessException failure) {
            if (UniqueConstraints.isUniqueViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }
}
