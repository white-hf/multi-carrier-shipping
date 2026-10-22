package com.enterprise.shipping.reconciliation.infrastructure.persistence;

import com.enterprise.shipping.contracts.recovery.Recovery.*;
import com.enterprise.shipping.reconciliation.domain.ReconciliationTask;
import com.enterprise.shipping.reconciliation.port.ReconciliationStorageException;
import com.enterprise.shipping.reconciliation.domain.ReconciliationTask.State;
import com.enterprise.shipping.reconciliation.port.ReconciliationTaskStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Single-statement task writes; task ownership never grants access to booking rows. */
public final class JdbcReconciliationTaskStore implements ReconciliationTaskStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcReconciliationTaskStore.class);
    public static final String DUE_SQL = """
            SELECT * FROM booking_reconciliation_tasks
            WHERE state IN ('WAITING','RUNNING','RESULT_PENDING') AND available_at<=?
            ORDER BY available_at, task_id LIMIT ?
            """;
    private final JdbcTemplate jdbc;
    public JdbcReconciliationTaskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean ensure(Reference ref, long now) {
        return db(() -> jdbc.update("""
                INSERT INTO booking_reconciliation_tasks(task_id,booking_id,booking_locator,carrier_id,
                    carrier_context_reference,customer_reference,state,available_at,attempts,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'WAITING',?,0,0,?,?)
                ON CONFLICT(booking_locator,booking_id) DO NOTHING
                """, TimeOrderedIds.next(), ref.bookingId(), ref.bookingLocator(), ref.carrierId(),
                ref.carrierContextReference(), ref.customerReference(), now, now, now)) == 1;
    }
    @Override public List<ReconciliationTask> findDue(long now, int limit) {
        return db(() -> jdbc.query(DUE_SQL, JdbcReconciliationTaskStore::map, now, limit));
    }
    @Override public Optional<ReconciliationTask> find(String id) {
        return db(() -> jdbc.query("SELECT * FROM booking_reconciliation_tasks WHERE task_id=?",
                JdbcReconciliationTaskStore::map, id)).stream().findFirst();
    }
    @Override public Optional<ReconciliationTask> claim(ReconciliationTask task, String token, long now, long expires) {
        int changed = db(() -> jdbc.update("""
                UPDATE booking_reconciliation_tasks SET state=CASE WHEN result_type IS NULL THEN 'RUNNING' ELSE 'RESULT_PENDING' END,
                    lease_token=?, available_at=?, attempts=attempts+CASE WHEN result_type IS NULL THEN 1 ELSE 0 END,
                    version=version+1,updated_at=?
                WHERE task_id=? AND version=? AND available_at<=? AND state IN ('WAITING','RUNNING','RESULT_PENDING')
                """, token, expires, now, task.taskId(), task.version(), now));
        return changed == 1 ? find(task.taskId()).filter(current -> current.version() == task.version() + 1
                && java.util.Objects.equals(current.leaseToken(), token)) : Optional.empty();
    }
    @Override public Optional<ReconciliationTask> saveObservation(ReconciliationTask task, Observation observation, String error, long now) {
        if (!task.reference().equals(observation.reference())) throw new IllegalArgumentException("Task reference mismatch");
        Evidence receipt = observation.evidence();
        int changed = db(() -> jdbc.update("""
                UPDATE booking_reconciliation_tasks SET state='RESULT_PENDING',result_type=?,carrier_booking_id=?,
                    tracking_number=?,label_url=?,last_error_code=?,updated_at=?,version=version+1
                WHERE task_id=? AND version=? AND lease_token=? AND state='RUNNING'
                """, observation.type().name(), receipt == null ? null : receipt.carrierBookingId(),
                receipt == null ? null : receipt.trackingNumber(), receipt == null ? null : receipt.labelUrl(),
                error, now, task.taskId(), task.version(), task.leaseToken()));
        return changed == 1 ? find(task.taskId()).filter(current -> current.version() == task.version() + 1
                && java.util.Objects.equals(current.leaseToken(), task.leaseToken())) : Optional.empty();
    }
    @Override public boolean retry(ReconciliationTask task, long nextAt, String error, boolean retain, long now) {
        String result = retain && task.observation() != null ? task.observation().type().name() : null;
        Evidence evidence = retain && task.observation() != null ? task.observation().evidence() : null;
        return db(() -> jdbc.update("""
                UPDATE booking_reconciliation_tasks SET state=?,available_at=?,lease_token=NULL,version=version+1,
                    last_error_code=?,result_type=?,carrier_booking_id=?,tracking_number=?,label_url=?,updated_at=?
                WHERE task_id=? AND version=? AND lease_token=? AND state IN ('RUNNING','RESULT_PENDING')
                """, retain ? "RESULT_PENDING" : "WAITING", nextAt, error, result,
                evidence == null ? null : evidence.carrierBookingId(), evidence == null ? null : evidence.trackingNumber(),
                evidence == null ? null : evidence.labelUrl(), now, task.taskId(), task.version(), task.leaseToken())) == 1;
    }
    @Override public boolean pause(ReconciliationTask task, String reason, long now) {
        return db(() -> jdbc.update("""
                UPDATE booking_reconciliation_tasks SET state='PAUSED',lease_token=NULL,version=version+1,last_error_code=?,updated_at=?
                WHERE task_id=? AND version=? AND lease_token=?
                """, reason, now, task.taskId(), task.version(), task.leaseToken())) == 1;
    }
    @Override public boolean delete(ReconciliationTask task) {
        return db(() -> jdbc.update("DELETE FROM booking_reconciliation_tasks WHERE task_id=? AND version=? AND lease_token=?",
                task.taskId(), task.version(), task.leaseToken())) == 1;
    }
    private <T> T db(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException error) {
            log.warn("Reconciliation persistence unavailable type={}", error.getClass().getSimpleName());
            throw new ReconciliationStorageException(error);
        }
    }
    private static ReconciliationTask map(ResultSet row, int ignored) throws SQLException {
        Reference ref = new Reference(row.getString("booking_id"), row.getString("booking_locator"), row.getString("carrier_id"),
                row.getString("carrier_context_reference"), row.getString("customer_reference"));
        String type = row.getString("result_type");
        Observation observation = type == null ? null : new Observation(ref, ObservationType.valueOf(type),
                ObservationType.FOUND.name().equals(type) ? new Evidence(ref.customerReference(), row.getString("carrier_booking_id"),
                        row.getString("tracking_number"), row.getString("label_url")) : null);
        return new ReconciliationTask(row.getString("task_id"), ref, State.valueOf(row.getString("state")), row.getLong("available_at"),
                row.getInt("attempts"), row.getString("lease_token"), row.getLong("version"), row.getString("last_error_code"),
                observation, row.getLong("created_at"), row.getLong("updated_at"));
    }
}
