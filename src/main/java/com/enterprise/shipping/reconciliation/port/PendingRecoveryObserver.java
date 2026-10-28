package com.enterprise.shipping.reconciliation.port;

/** Bounded discovery metrics; no owner, booking or reference values become metric labels. */
public interface PendingRecoveryObserver {
    void scan(
            int discovered,
            int tasksCreated,
            long oldestCandidateOverdueMs,
            long elapsedNanos,
            boolean failed);
}
