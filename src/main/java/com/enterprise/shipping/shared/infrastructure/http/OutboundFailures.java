package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import java.util.concurrent.TimeoutException;

/** Common failure classification; no retry and no business-module dependencies. */
public final class OutboundFailures {
    private OutboundFailures() {
    }

    public static ShippingException failure(Exception failure) {
        if (failure instanceof ShippingException shipping) {
            return shipping;
        }
        if (isInterrupted(failure)) {
            Thread.currentThread().interrupt();
        }
        return isTimeout(failure)
                ? ShippingException.dependencyTimeout("PROVIDER_TIMEOUT")
                : ShippingException.dependencyFailure("PROVIDER_UNAVAILABLE");
    }

    public static boolean isInterrupted(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted();
    }

    public static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.ReadTimeoutException
                    || cause instanceof ShippingException shipping
                            && shipping.category() == ShippingException.Category.DEPENDENCY_TIMEOUT
                    || cause instanceof com.enterprise.shipping.shared.domain.CarrierFailure carrier
                            && "TIMEOUT".equals(carrier.diagnosticCode())) {
                return true;
            }
        }
        return false;
    }
}
