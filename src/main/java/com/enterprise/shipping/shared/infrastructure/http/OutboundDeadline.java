package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.api.WorkflowException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/** A monotonic budget includes response-body consumption; timeout cancels the subscription. */
public final class OutboundDeadline {
    private OutboundDeadline() {}
    public static long after(Duration budget) { return System.nanoTime() + budget.toNanos(); }
    public static <T> T await(Mono<T> response, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new WorkflowException("PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        return response.block(Duration.ofNanos(remaining));
    }
}
