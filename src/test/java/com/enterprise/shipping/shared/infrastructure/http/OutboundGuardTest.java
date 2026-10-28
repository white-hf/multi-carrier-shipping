package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OutboundGuardTest {
    private static final long DEADLINE = Long.MAX_VALUE;

    static OutboundProperties properties() {
        var properties = new OutboundProperties();
        properties.getDefaults().setConcurrency(1);
        properties.getDefaults().setMinimumCalls(2);
        properties.getDefaults().setWindowSize(2);
        return properties;
    }

    @Test
    void inheritsTemplatesAndOverridesWithoutSharingMutableSettings() {
        var p = properties();
        var template = new OutboundProperties.Settings();
        template.setTimeout(Duration.ofSeconds(90));
        template.setConcurrency(4);
        p.getTemplates().put("slow", template);
        var dependency = new OutboundProperties.Settings();
        dependency.setTemplate("slow");
        dependency.setConcurrency(2);
        var background = new OutboundProperties.Group();
        background.setConcurrency(1);
        dependency.getGroups().put("background", background);
        p.getDependencies().put("carrier", dependency);
        p.validate();
        assertThat(p.resolve("carrier").timeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(p.resolve("carrier").concurrency()).isEqualTo(2);
        assertThat(p.resolve("other").concurrency()).isEqualTo(1);
        assertThat(p.resolve("carrier").groups().get("background").timeout())
                .isEqualTo(Duration.ofSeconds(90));
        assertThatThrownBy(() -> p.resolve("carrier").groups().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidPolicyAndUnknownTemplatesBeforeServingRequests() {
        var p = properties();
        p.getDefaults().setMinimumCalls(3);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class);
        p = properties();
        var invalid = new OutboundProperties.Settings();
        invalid.setTemplate("missing");
        p.getDependencies().put("bad", invalid);
        assertThatThrownBy(p::validate).hasMessageContaining("Unknown outbound template");
        p = properties();
        var oversized = new OutboundProperties.Group();
        oversized.setConcurrency(2);
        p.getDefaults().getGroups().put("auxiliary", oversized);
        assertThatThrownBy(p::validate).hasMessageContaining("Invalid outbound group");
    }

    @Test
    void concurrentOperationsShareTotalAdmissionButDoNotHoldRejectedPermits() throws Exception {
        var p = properties();
        var group = new OutboundProperties.Group();
        group.setConcurrency(1);
        p.getDefaults().getGroups().put("auxiliary", group);
        var metrics = new SimpleMeterRegistry();
        var registry = new OutboundClientRegistry(p, metrics);
        var guard = registry.registerGuard("carrier", Set.of("rates", "create"));
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> running =
                    executor.submit(
                            () ->
                                    guard.execute(
                                            "default",
                                            "rates",
                                            DEADLINE,
                                            deadline -> {
                                                entered.countDown();
                                                release.await();
                                                return "done";
                                            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(
                            () ->
                                    guard.execute(
                                            "auxiliary",
                                            "create",
                                            DEADLINE,
                                            deadline -> "unexpected"))
                    .isInstanceOfSatisfying(
                            OutboundAdmissionRejectedException.class,
                            e ->
                                    assertThat(e.reason())
                                            .isEqualTo(
                                                    OutboundAdmissionRejectedException.Reason
                                                            .BULKHEAD_FULL));
            release.countDown();
            running.get(1, TimeUnit.SECONDS);
            assertThat(guard.<String>execute("auxiliary", "create", DEADLINE, deadline -> "ok"))
                    .isEqualTo("ok");
            assertThat(
                            metrics.get("shipping.outbound.duration")
                                    .tag("outcome", "bulkhead_full")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            registry.close();
        }
    }

    @Test
    void smallerGroupLimitLeavesTotalCapacityForCoreWork() throws Exception {
        var p = properties();
        p.getDefaults().setConcurrency(2);
        var auxiliary = new OutboundProperties.Group();
        auxiliary.setConcurrency(1);
        p.getDefaults().getGroups().put("auxiliary", auxiliary);
        var guard =
                new OutboundClientRegistry(p, new SimpleMeterRegistry())
                        .registerGuard("one", Set.of("rates"));
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var running =
                    executor.submit(
                            () ->
                                    guard.execute(
                                            "auxiliary",
                                            "rates",
                                            DEADLINE,
                                            deadline -> {
                                                entered.countDown();
                                                release.await();
                                                return "done";
                                            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(
                            () ->
                                    guard.execute(
                                            "auxiliary",
                                            "rates",
                                            DEADLINE,
                                            deadline -> "unexpected"))
                    .isInstanceOf(OutboundAdmissionRejectedException.class);
            assertThat(guard.<String>execute("default", "rates", DEADLINE, deadline -> "core"))
                    .isEqualTo("core");
            release.countDown();
            running.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void groupCircuitFailureDoesNotOpenAnotherGroupOrDependency() {
        var p = properties();
        p.getDefaults().getGroups().put("auxiliary", new OutboundProperties.Group());
        var registry = new OutboundClientRegistry(p, new SimpleMeterRegistry());
        var guard = registry.registerGuard("one", Set.of("rates", "create"));
        var other = registry.registerGuard("two", Set.of("rates"));
        var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(
                            () ->
                                    guard.execute(
                                            "auxiliary",
                                            "rates",
                                            DEADLINE,
                                            deadline -> {
                                                calls.incrementAndGet();
                                                throw new TimeoutException();
                                            }))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            e -> assertThat(e.code()).isEqualTo("PROVIDER_TIMEOUT"));
        }
        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        "auxiliary",
                                        "rates",
                                        DEADLINE,
                                        deadline -> calls.incrementAndGet()))
                .isInstanceOfSatisfying(
                        OutboundAdmissionRejectedException.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(
                                                OutboundAdmissionRejectedException.Reason
                                                        .CIRCUIT_OPEN));
        assertThat(calls).hasValue(2); // Failed financial calls are never automatically retried.
        assertThat(guard.<String>execute("default", "create", DEADLINE, deadline -> "ok"))
                .isEqualTo("ok");
        assertThat(other.<String>execute("default", "rates", DEADLINE, deadline -> "ok"))
                .isEqualTo("ok");
    }

    @Test
    void sharedDefaultCircuitProtectsAllCoreOperationsAndIgnoresBusinessErrors() {
        var registry = new OutboundClientRegistry(properties(), new SimpleMeterRegistry());
        var guard = registry.registerGuard("one", Set.of("rates", "create"));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(
                            () ->
                                    guard.execute(
                                            "default",
                                            "rates",
                                            DEADLINE,
                                            deadline -> {
                                                throw ShippingException.invalidInput("REJECTED");
                                            }))
                    .isInstanceOf(ShippingException.class);
        }
        assertThat(guard.<String>execute("default", "create", DEADLINE, deadline -> "ok"))
                .isEqualTo("ok");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(
                            () ->
                                    guard.execute(
                                            "default",
                                            "rates",
                                            DEADLINE,
                                            deadline -> {
                                                throw new IllegalStateException();
                                            }))
                    .isInstanceOf(ShippingException.class);
        }
        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        "default", "create", DEADLINE, deadline -> "unexpected"))
                .isInstanceOf(OutboundAdmissionRejectedException.class);
    }

    @Test
    void boundsDeadlineAndRejectsUnknownLabelsWithoutCreatingMetrics() {
        var p = properties();
        p.getDefaults().setConnectTimeout(Duration.ofMillis(10));
        p.getDefaults().setTimeout(Duration.ofMillis(100));
        var metrics = new SimpleMeterRegistry();
        var registry = new OutboundClientRegistry(p, metrics);
        var guard = registry.registerGuard("one", Set.of("rates"));
        long callerDeadline = OutboundDeadline.after(Duration.ofMillis(20));
        assertThat(guard.<Long>execute("default", "rates", callerDeadline, deadline -> deadline))
                .isEqualTo(callerDeadline);
        long start = System.nanoTime();
        assertThat(guard.<Long>execute("default", "rates", DEADLINE, deadline -> deadline))
                .isBetween(start, start + TimeUnit.MILLISECONDS.toNanos(150));
        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        "default",
                                        "rates",
                                        System.nanoTime() - 1,
                                        deadline -> "unexpected"))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        e -> assertThat(e.code()).isEqualTo("PROVIDER_TIMEOUT"));
        int meters = metrics.getMeters().size();
        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        "unbounded-input",
                                        "rates",
                                        DEADLINE,
                                        deadline -> "unexpected"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                guard.execute(
                                        "default",
                                        "arbitrary-path",
                                        DEADLINE,
                                        deadline -> "unexpected"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(metrics.getMeters()).hasSize(meters);
    }

    @Test
    void metricFailuresCannotChangeSuccessfulChargeResult() {
        var broken = org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        var registry = new OutboundClientRegistry(properties(), broken);
        var guard = registry.registerGuard("one", Set.of("create"));
        assertThat(guard.<String>execute("default", "create", DEADLINE, deadline -> "receipt"))
                .isEqualTo("receipt");
    }

    @Test
    void expiredAwaitNeverSubscribesAndTimeoutCancelsOutstandingWork() {
        var subscribed = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                OutboundDeadline.await(
                                        reactor.core.publisher.Mono.fromSupplier(
                                                subscribed::incrementAndGet),
                                        System.nanoTime() - 1))
                .isInstanceOf(ShippingException.class);
        assertThat(subscribed).hasValue(0);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(
                        () ->
                                OutboundDeadline.await(
                                        reactor.core.publisher.Mono.never()
                                                .doOnCancel(() -> cancelled.set(true)),
                                        OutboundDeadline.after(Duration.ofMillis(20))))
                .isInstanceOf(RuntimeException.class);
        assertThat(cancelled).isTrue();
    }
}
