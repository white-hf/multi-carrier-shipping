package com.enterprise.shipping.reconciliation.domain;

public final class ReconciliationStorageException extends RuntimeException {
    public ReconciliationStorageException(Throwable cause) { super("Reconciliation storage unavailable", cause); }
}
