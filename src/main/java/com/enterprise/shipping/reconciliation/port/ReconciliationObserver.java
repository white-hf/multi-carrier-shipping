package com.enterprise.shipping.reconciliation.port;

/** Implementations must isolate instrumentation failures from recovery. Labels are bounded enums. */
public interface ReconciliationObserver {
    enum Operation { DISCOVERY, INQUIRY, DELIVERY }
    enum Outcome { CREATED, EXISTING, OBSERVED, RESOLVED, RETRY, PAUSED, FENCED }
    void record(Operation operation, Outcome outcome, long elapsedNanos);
}
