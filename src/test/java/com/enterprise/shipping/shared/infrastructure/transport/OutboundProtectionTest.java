package com.enterprise.shipping.shared.infrastructure.transport;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundProtectionTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Bulkhead capacity = Bulkhead.of(
            "shipping-provider",
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    private final CircuitBreaker circuit = CircuitBreaker.of(
            "shipping-provider",
            CircuitBreakerConfig.custom()
                    .minimumNumberOfCalls(2)
                    .slidingWindowSize(2)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(10))
                    .build());
    private final OutboundProtection protection =
            new OutboundProtection("sandbox", capacity, circuit, metrics);

    @Test
    void saturationRejectsWithoutSubscriptionAndDoesNotLeakPermits() {
        var subscribed = new AtomicInteger();
        assertThat(capacity.tryAcquirePermission()).isTrue();
        try {
            assertThatThrownBy(() -> protection.await(
                    "rates", Long.MAX_VALUE, Mono.fromSupplier(subscribed::incrementAndGet)))
                    .isInstanceOf(BulkheadFullException.class);
            assertThat(subscribed).hasValue(0);
            assertThat(capacity.getMetrics().getAvailableConcurrentCalls()).isZero();
            assertThat(metrics.get("shipping.outbound.duration")
                    .tag("operation", "rates").tag("outcome", "bulkhead_full").timer().count())
                    .isEqualTo(1);
        } finally {
            capacity.releasePermission();
        }
        assertThat(capacity.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void failuresOpenTheSharedCircuitAndOpenAdmissionNeverSubscribes() {
        var calls = new AtomicInteger();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> protection.await(
                    "shipments", Long.MAX_VALUE, Mono.fromRunnable(calls::incrementAndGet)
                            .then(Mono.error(new IllegalStateException("transport failed")))))
                    .isInstanceOf(ShippingException.class);
        }
        assertThatThrownBy(() -> protection.await(
                "shipments", Long.MAX_VALUE, Mono.fromRunnable(calls::incrementAndGet)))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(2);
        assertThat(capacity.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
        assertThat(metrics.get("shipping.outbound.duration")
                .tag("operation", "shipments").tag("outcome", "circuit_open").timer().count())
                .isEqualTo(1);
    }

    @Test
    void expiredBudgetNeverSubscribesAndReleasesThePermit() {
        var subscribed = new AtomicInteger();
        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> protection.await(
                        "labels",
                        System.nanoTime() - 1,
                        Mono.fromSupplier(subscribed::incrementAndGet)),
                ShippingException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(failure.category()).isEqualTo(ShippingException.Category.DEPENDENCY_TIMEOUT);
        assertThat(subscribed).hasValue(0);
        assertThat(capacity.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
        assertThat(metrics.get("shipping.outbound.duration")
                .tag("operation", "labels").tag("outcome", "timeout").timer().count())
                .isEqualTo(1);
    }

    @Test
    void interruptionCancelsTheSourceSetsTheFlagAndRestoresCapacity() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var interrupted = new AtomicBoolean();
        var thread = new Thread(() -> {
            try {
                protection.await(
                        "shipments",
                        Long.MAX_VALUE,
                        Mono.<String>never()
                                .doOnSubscribe(subscription -> entered.countDown())
                                .doOnCancel(() -> cancelled.set(true)));
            } catch (ShippingException failure) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            thread.interrupt();
            thread.join(3000);
        }
        assertThat(thread.isAlive()).isFalse();
        assertThat(cancelled).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(capacity.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }
}
