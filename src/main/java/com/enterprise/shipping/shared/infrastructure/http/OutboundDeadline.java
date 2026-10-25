package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import java.time.Duration;
import reactor.core.publisher.Mono;

/** A monotonic budget includes response-body consumption; timeout cancels the subscription. */
public final class OutboundDeadline {
    private OutboundDeadline() {}
    public static long after(Duration budget) { return System.nanoTime() + budget.toNanos(); }
    public static <T> T await(Mono<T> response, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw ShippingException.gatewayTimeout("PROVIDER_TIMEOUT");
        return response.block(Duration.ofNanos(remaining));
    }
}
