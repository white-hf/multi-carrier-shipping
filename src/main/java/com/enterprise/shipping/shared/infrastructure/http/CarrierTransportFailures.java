package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.CarrierFailure;
import java.util.concurrent.TimeoutException;

/** Classifies wrapped transport failures consistently without depending on a business feature. */
public final class CarrierTransportFailures {
    private CarrierTransportFailures() {
    }

    public static String classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OutboundAdmissionRejectedException rejected) {
                return rejected.reason().name();
            }
            if (cause instanceof CarrierFailure failure) {
                return failure.diagnosticCode();
            }
            if (cause instanceof InterruptedException) {
                return "INTERRUPTED";
            }
            if (cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return "TIMEOUT";
            }
        }
        return Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "UPSTREAM_ERROR";
    }
}
