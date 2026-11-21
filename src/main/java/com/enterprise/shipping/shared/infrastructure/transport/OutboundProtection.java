package com.enterprise.shipping.shared.infrastructure.transport;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

/**
 * Admission, circuit and remaining-budget protection shared by outbound operations.
 *
 * <p>This is a mechanism only. Operations are metric tags, not a registry: adding a provider API
 * never requires editing this class. Admission rejection is rethrown as the framework exception so
 * the owning operation decides whether it proves "not dispatched" or local capacity exhaustion.
 */
public final class OutboundProtection {
    private final String providerId;
    private final Bulkhead capacity;
    private final CircuitBreaker circuit;
    private final MeterRegistry metrics;

    public OutboundProtection(
            String providerId,
            Bulkhead capacity,
            CircuitBreaker circuit,
            MeterRegistry metrics) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.circuit = Objects.requireNonNull(circuit, "circuit");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public <T> T await(String operation, long deadlineNanos, Mono<T> response) {
        long started = System.nanoTime();
        String outcome = "failure";
        String transport = "SUCCESS";
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new ShippingException(
                        "PROVIDER_UNAVAILABLE",
                        ShippingException.Category.DEPENDENCY_FAILURE,
                        new InterruptedException("Outbound call cancelled"));
            }
            // The deferred check runs after admission and before the HTTP source subscribes.
            T result = Mono.defer(() -> {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return Mono.<T>error(
                            ShippingException.dependencyTimeout("PROVIDER_TIMEOUT"));
                }
                return response.timeout(Duration.ofNanos(remaining));
            })
                    .transformDeferred(CircuitBreakerOperator.of(circuit))
                    .transformDeferred(BulkheadOperator.of(capacity))
                    .block();
            outcome = "success";
            return result;
        } catch (BulkheadFullException | CallNotPermittedException unsent) {
            outcome = unsent instanceof BulkheadFullException ? "bulkhead_full" : "circuit_open";
            transport = unsent instanceof BulkheadFullException ? "BULKHEAD_FULL" : "CIRCUIT_OPEN";
            OutboundDiagnostics.failure(providerId, "default", operation, unsent, started);
            throw unsent;
        } catch (RuntimeException failure) {
            boolean interrupted = OutboundDiagnostics.isInterrupted(failure);
            outcome = OutboundDiagnostics.isTimeout(failure)
                    ? "timeout" : interrupted ? "interrupted" : "failure";
            transport = interrupted ? "INTERRUPTED" : transportStatus(failure);
            OutboundDiagnostics.failure(providerId, "default", operation, failure, started);
            throw OutboundDiagnostics.failure(failure);
        } finally {
            SafeMetrics.record(
                    metrics,
                    "shipping.outbound.duration",
                    System.nanoTime() - started,
                    "dependency",
                    providerId,
                    "group",
                    "default",
                    "operation",
                    operation,
                    "outcome",
                    outcome);
            SafeMetrics.record(
                    metrics,
                    "shipping.carrier.transport.latency",
                    System.nanoTime() - started,
                    "carrier",
                    "shipping-provider",
                    "transportStatus",
                    transport);
        }
    }

    private static String transportStatus(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return "TIMEOUT";
            }
        }
        return "UPSTREAM_ERROR";
    }
}
