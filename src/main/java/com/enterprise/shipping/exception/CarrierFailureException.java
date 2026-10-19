package com.enterprise.shipping.exception;

import com.enterprise.shipping.dto.ShipmentDTO.OutcomeCode;
import java.util.concurrent.TimeoutException;

/** Carries a stable outcome code while keeping diagnostic causes out of API responses. */
public class CarrierFailureException extends RuntimeException {
    private final OutcomeCode code;
    public CarrierFailureException(OutcomeCode code) { super(code.name()); this.code = code; }
    public CarrierFailureException(OutcomeCode code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public OutcomeCode code() { return code; }
    public static OutcomeCode classify(Throwable error) {
        // Reactor and Future failures can wrap the transport exception in several layers.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CarrierFailureException failure) return failure.code();
            if (cause instanceof InterruptedException) return OutcomeCode.INTERRUPTED;
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException)
                return OutcomeCode.TIMEOUT;
        }
        // A cancelled blocking client may preserve only the thread interruption flag.
        return Thread.currentThread().isInterrupted() ? OutcomeCode.INTERRUPTED : OutcomeCode.UPSTREAM_ERROR;
    }
}
