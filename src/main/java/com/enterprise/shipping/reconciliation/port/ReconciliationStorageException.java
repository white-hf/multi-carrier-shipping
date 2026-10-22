package com.enterprise.shipping.reconciliation.port;

/** Storage failure exposed by the task persistence port, independent of the database implementation. */
public final class ReconciliationStorageException extends RuntimeException {
    public ReconciliationStorageException(Throwable cause) { super("Reconciliation storage unavailable", cause); }
}
