package com.enterprise.shipping.reconciliation.domain;

import com.enterprise.shipping.contracts.recovery.Recovery.*;

/** Immutable task state. A stored observation survives booking-port failures and is delivered before another inquiry. */
public record ReconciliationTask(String taskId, Reference reference, State state, long availableAt,
        int attempts, String leaseToken, long version, String lastErrorCode, Observation observation,
        long createdAt, long updatedAt) {
    public enum State { WAITING, RUNNING, RESULT_PENDING, PAUSED }
    public long retryDelay(long base, long maximum) {
        return Math.min(maximum, base * (1L << Math.min(Math.max(0, attempts - 1), 20)));
    }
}
