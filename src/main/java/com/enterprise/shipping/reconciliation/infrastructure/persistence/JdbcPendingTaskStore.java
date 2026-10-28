package com.enterprise.shipping.reconciliation.infrastructure.persistence;

import com.enterprise.shipping.booking.contract.ManualReviewReason;
import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort.Candidate;
import com.enterprise.shipping.reconciliation.port.PendingTaskStore;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import com.enterprise.shipping.shared.infrastructure.persistence.UniqueConstraints;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers paused manual-review tasks using RDBMS-agnostic ANSI SQL and unique-constraint handling.
 */
public final class JdbcPendingTaskStore implements PendingTaskStore {

    private final JdbcTemplate jdbc;

    public JdbcPendingTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registerManualReview(Candidate c, ManualReviewReason reason, long now) {
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
                            c.userId(),
                            c.bookingId(),
                            c.userId(),
                            c.providerId(),
                            accountAlias(c),
                            c.customerReference(),
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

    private static String accountAlias(Candidate c) {
        return c.accountAlias();
    }
}
