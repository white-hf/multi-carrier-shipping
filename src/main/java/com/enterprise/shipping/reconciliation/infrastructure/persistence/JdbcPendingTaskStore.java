package com.enterprise.shipping.reconciliation.infrastructure.persistence;

import com.enterprise.shipping.reconciliation.port.PendingTaskStore;
import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort.Candidate;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import org.springframework.jdbc.core.JdbcTemplate;

/** NO_LOOKUP tasks are deliberately paused, not repeatedly sent to an unsupported provider endpoint. */
public final class JdbcPendingTaskStore implements PendingTaskStore {
    private final JdbcTemplate jdbc;
    public JdbcPendingTaskStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public boolean registerManualReview(Candidate c,long now){
        return jdbc.update("""
                INSERT INTO booking_reconciliation_tasks (task_id,user_id,booking_id,booking_locator,provider_id,account_alias,
                    customer_reference,state,available_at,last_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'PAUSED',?,'AUTHORITATIVE_LOOKUP_UNSUPPORTED',?,?)
                ON CONFLICT(user_id,booking_locator,booking_id) DO NOTHING
                """,TimeOrderedIds.next(),c.userId(),c.bookingId(),c.userId(),c.providerId(),c.accountAlias(),c.customerReference(),now,now,now)==1;
    }
}
