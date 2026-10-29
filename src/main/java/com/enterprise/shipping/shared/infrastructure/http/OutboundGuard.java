package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Shared admission and circuit policy for both single-provider and fan-out calls.
 * One dependency owns total capacity; a few allowlisted groups own independent circuits
 * and optional smaller semaphore limits. Groups share transport and do not reserve connections.
 * Limits are per application instance, not a distributed account-wide quota.
 * This saves pools/configuration but permits contention and failure propagation within a group.
 *
 * Keep fixed, validated bounds until production measurements justify adaptive admission.
 * Adaptation belongs at permit acquisition, not pool resizing or automatic write retries.
 * Per-operation timings expose mixed workloads that could mask failures in a shared circuit.
 */
public final class OutboundGuard {
    @FunctionalInterface
    public interface Work<T> {
        T run(long deadlineNanos) throws Exception;
    }

    private record Group(Bulkhead capacity, CircuitBreaker breaker, Duration timeout) {}

    private final String dependency;
    private final Set<String> operations = ConcurrentHashMap.newKeySet();
    private final Bulkhead total;
    private final Map<String, Group> groups;
    private final MeterRegistry metrics;

    public OutboundGuard(
            String dependency,
            OutboundPolicy policy,
            Set<String> operations,
            MeterRegistry metrics,
            Predicate<Throwable> ignoredFailure) {
        this.dependency = dependency;
        for (String operation : operations) {
            registerOperation(operation);
        }
        this.metrics = metrics;
        total = capacity(dependency, policy.concurrency());
        var configured = new LinkedHashMap<String, Group>();
        policy.groups()
                .forEach(
                        (name, group) -> {
                            String id = dependency + "." + name;
                            var breaker =
                                    CircuitBreaker.of(
                                            id,
                                            CircuitBreakerConfig.custom()
                                                    .minimumNumberOfCalls(policy.minimumCalls())
                                                    .slidingWindowSize(policy.windowSize())
                                                    .failureRateThreshold(policy.failureRate())
                                                    .waitDurationInOpenState(policy.openDuration())
                                                    .ignoreException(
                                                            error ->
                                                                    OutboundFailures.isInterrupted(
                                                                                    error)
                                                                            || ignoredFailure.test(
                                                                                    error))
                                                    .build());
                            configured.put(
                                    name,
                                    new Group(
                                            group.concurrency() == null
                                                    ? null
                                                    : capacity(id, group.concurrency()),
                                            breaker,
                                            group.timeout()));
                            try {
                                metrics.gauge(
                                        "shipping.outbound.circuit.state",
                                        java.util.List.of(
                                                io.micrometer.core.instrument.Tag.of(
                                                        "dependency", dependency),
                                                io.micrometer.core.instrument.Tag.of(
                                                        "group", name)),
                                        breaker,
                                        value -> value.getState().getOrder());
                            } catch (RuntimeException ignored) {
                                /* Observability must never prevent startup. */

                              }
                        });
        groups = Map.copyOf(configured);
        try {
            var tags =
                    java.util.List.of(
                            io.micrometer.core.instrument.Tag.of("dependency", dependency));
            metrics.gauge(
                    "shipping.outbound.inflight",
                    tags,
                    total,
                    value ->
                            policy.concurrency()
                                    - value.getMetrics().getAvailableConcurrentCalls());
            metrics.gauge("shipping.outbound.limit", tags, policy, OutboundPolicy::concurrency);
        } catch (RuntimeException ignored) {
            /* Metrics must not change admission behavior. */

          }
    }

    void registerOperation(String operation) {
        if (operation == null
                || !operation.matches("[a-zA-Z0-9_-]{1,64}")
                || !operations.add(operation)) {
            throw new IllegalArgumentException(
                    "Invalid or duplicate outbound operation for " + dependency + ": " + operation);
        }
    }

    private static Bulkhead capacity(String name, int limit) {
        return Bulkhead.of(
                name,
                BulkheadConfig.custom()
                        .maxConcurrentCalls(limit)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }

    /** Checked variant preserves carrier-specific failures for the fan-out result contract. */
    public <T> T call(
            String groupName,
            String operation,
            long deadline,
            Work<T> work)
            throws Exception {
        Group group = groups.get(groupName);
        if (group == null || !operations.contains(operation)) {
            throw new IllegalArgumentException("Unregistered outbound group or operation");
        }
        long start = System.nanoTime();
        String outcome = "failure";
        boolean groupPermit = false, totalPermit = false;
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Outbound caller cancelled");
            }
            long effective = Math.min(deadline, OutboundDeadline.after(group.timeout()));
            if (effective <= System.nanoTime()) {
                throw new TimeoutException("Outbound deadline expired before admission");
            }
            if (group.capacity() != null) {
                if (!group.capacity().tryAcquirePermission()) {
                    throw rejected();
                }
                groupPermit = true;
            }
            if (!total.tryAcquirePermission()) {
                throw rejected();
            }
            totalPermit = true;
            T result;
            try {
                result = group.breaker().executeCallable(() -> work.run(effective));
            } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException closed) {
                throw new OutboundAdmissionRejectedException(
                        OutboundAdmissionRejectedException.Reason.CIRCUIT_OPEN);
            }
            outcome = "success";
            return result;
        } catch (OutboundAdmissionRejectedException rejected) {
            OutboundDiagnostics.failure(dependency, groupName, operation, rejected, start);
            outcome = rejected.reason().name().toLowerCase(java.util.Locale.ROOT);
            throw rejected;
        } catch (Exception failure) {
            OutboundDiagnostics.failure(dependency, groupName, operation, failure, start);
            if (OutboundFailures.isInterrupted(failure)) {
                Thread.currentThread().interrupt();
            }
            if (OutboundFailures.isTimeout(failure)) {
                outcome = "timeout";
            } else if (Thread.currentThread().isInterrupted()) {
                outcome = "interrupted";
            }
            throw failure;
        } finally {
            if (totalPermit) {
                total.releasePermission();
            }
            if (groupPermit) {
                group.capacity().releasePermission();
            }
            SafeMetrics.record(
                    metrics,
                    "shipping.outbound.duration",
                    System.nanoTime() - start,
                    "dependency",
                    dependency,
                    "group",
                    groupName,
                    "operation",
                    operation,
                    "outcome",
                    outcome);
        }
    }

    private static OutboundAdmissionRejectedException rejected() {
        return new OutboundAdmissionRejectedException(
                OutboundAdmissionRejectedException.Reason.BULKHEAD_FULL);
    }

    public <T> T execute(
            String group,
            String operation,
            long deadline,
            Work<T> work) {
        if (!groups.containsKey(group) || !operations.contains(operation)) {
            throw new IllegalArgumentException("Unregistered outbound group or operation");
        }
        try {
            return call(group, operation, deadline, work);
        } catch (Exception failure) {
            throw OutboundFailures.failure(failure);
        }
    }

    public void resetCircuitBreakers() {
        groups.values().forEach(group -> group.breaker().reset());
    }
}
