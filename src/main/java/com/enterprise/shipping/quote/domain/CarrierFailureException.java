package com.enterprise.shipping.quote.domain;

import com.enterprise.shipping.quote.domain.OutcomeCode;
import com.enterprise.shipping.shared.domain.CarrierFailure;

/** Carries a stable outcome code while keeping diagnostic causes out of API responses. */
public class CarrierFailureException extends RuntimeException implements CarrierFailure {
    private final OutcomeCode code;
    public CarrierFailureException(OutcomeCode code) { super(code.name()); this.code = code; }
    public CarrierFailureException(OutcomeCode code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public OutcomeCode code() { return code; }
    /** Framework-independent fallback for worker interruption and wrapped adapter failures. */
    public static OutcomeCode classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CarrierFailureException failure) return failure.code();
            if (cause instanceof InterruptedException) return OutcomeCode.INTERRUPTED;
            if (cause instanceof java.util.concurrent.TimeoutException) return OutcomeCode.TIMEOUT;
        }
        return Thread.currentThread().isInterrupted() ? OutcomeCode.INTERRUPTED : OutcomeCode.UPSTREAM_ERROR;
    }

    @Override public String diagnosticCode() { return code.name(); }
}
