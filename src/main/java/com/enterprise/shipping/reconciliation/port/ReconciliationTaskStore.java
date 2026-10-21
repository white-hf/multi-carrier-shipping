package com.enterprise.shipping.reconciliation.port;

import com.enterprise.shipping.contracts.recovery.Recovery.*;
import com.enterprise.shipping.reconciliation.domain.ReconciliationTask;
import java.util.List;
import java.util.Optional;

/** Independent task persistence. No operation joins or updates booking tables. */
public interface ReconciliationTaskStore {
    boolean ensure(Reference reference, long now);
    List<ReconciliationTask> findDue(long now, int limit);
    Optional<ReconciliationTask> find(String taskId);
    Optional<ReconciliationTask> claim(ReconciliationTask candidate, String token, long now, long expiresAt);
    Optional<ReconciliationTask> saveObservation(ReconciliationTask owned, Observation observation, String error, long now);
    boolean retry(ReconciliationTask owned, long nextAt, String error, boolean retainObservation, long now);
    boolean pause(ReconciliationTask owned, String reason, long now);
    boolean delete(ReconciliationTask owned);
}
