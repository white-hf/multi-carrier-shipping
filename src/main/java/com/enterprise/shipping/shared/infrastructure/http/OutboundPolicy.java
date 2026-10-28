package com.enterprise.shipping.shared.infrastructure.http;

import java.time.Duration;
import java.util.Map;

/** Immutable, validated runtime snapshot; configuration inheritance never shares circuit state. */
public record OutboundPolicy(
        Duration timeout,
        Duration connectTimeout,
        int concurrency,
        int maxResponseBytes,
        int windowSize,
        int minimumCalls,
        float failureRate,
        Duration openDuration,
        Map<String, Group> groups) {
    public record Group(Duration timeout, Integer concurrency) {}

    public OutboundPolicy {
        groups = Map.copyOf(groups);
        if (timeout.isZero()
                || timeout.isNegative()
                || connectTimeout.isZero()
                || connectTimeout.isNegative()
                || connectTimeout.compareTo(timeout) > 0
                || connectTimeout.toMillis() > Integer.MAX_VALUE
                || connectTimeout.toMillis() < 1
                || concurrency < 1
                || concurrency > 1024
                || maxResponseBytes < 1
                || windowSize < 1
                || minimumCalls < 1
                || minimumCalls > windowSize
                || !Float.isFinite(failureRate)
                || failureRate <= 0
                || failureRate > 100
                || openDuration.isZero()
                || openDuration.isNegative()) {
            throw new IllegalArgumentException("Invalid outbound policy bounds");
        }
        if (!groups.containsKey("default")) {
            throw new IllegalArgumentException("Default outbound group required");
        }
        groups.forEach(
                (name, group) -> {
                    if (!name.matches("[a-zA-Z0-9_-]{1,64}")
                            || group.timeout().isZero()
                            || group.timeout().isNegative()
                            || group.timeout().compareTo(timeout) > 0
                            || group.concurrency() != null
                                    && (group.concurrency() < 1
                                            || group.concurrency() > concurrency)) {
                        throw new IllegalArgumentException("Invalid outbound group: " + name);
                    }
                });
    }
}
