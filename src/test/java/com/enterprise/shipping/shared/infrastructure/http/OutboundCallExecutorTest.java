package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundCallExecutorTest {

    @Test
    void expiredDeadlinesNeverSubscribeAndTimeoutCancelsOutstandingWork() {
        var subscribed = new AtomicInteger();
        var response = reactor.core.publisher.Mono.fromSupplier(subscribed::incrementAndGet);
        assertThatThrownBy(() -> OutboundDeadline.await(response, System.nanoTime() - 1))
                .isInstanceOf(ShippingException.class);
        assertThat(subscribed).hasValue(0);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> OutboundDeadline.await(
                reactor.core.publisher.Mono.never().doOnCancel(() -> cancelled.set(true)),
                OutboundDeadline.after(java.time.Duration.ofMillis(20))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cancelled).isTrue();
    }

    @Test
    void localCapacityRejectionDoesNotReachTheBreakerOrExecuteWork() {
        var bulkhead = Bulkhead.of("one", BulkheadConfig.custom().maxConcurrentCalls(1).build());
        var breaker = CircuitBreaker.ofDefaults("one");
        bulkhead.acquirePermission();
        try {
            assertThatThrownBy(() -> OutboundCallExecutor.execute(bulkhead, breaker, () -> "unexpected"))
                    .isInstanceOfSatisfying(ShippingException.class,
                            e -> assertThat(e.code()).isEqualTo("PROVIDER_CAPACITY_EXCEEDED"));
            assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        } finally {
            bulkhead.releasePermission();
        }
    }

    @Test
    void financialFailureIsNotRetriedAndReleasesAdmission() {
        var bulkhead = Bulkhead.ofDefaults("create");
        var breaker = CircuitBreaker.ofDefaults("create");
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> OutboundCallExecutor.execute(bulkhead, breaker, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connection lost");
        })).isInstanceOfSatisfying(ShippingException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        assertThat(calls).hasValue(1);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls())
                .isEqualTo(bulkhead.getBulkheadConfig().getMaxConcurrentCalls());
    }

    @Test
    void timeoutCauseAndOpenCircuitAreClassifiedWithoutRetry() {
        var bulkhead = Bulkhead.ofDefaults("rates");
        var breaker = CircuitBreaker.ofDefaults("rates");
        assertThatThrownBy(() -> OutboundCallExecutor.execute(bulkhead, breaker, () -> {
            throw new IllegalStateException(new TimeoutException());
        })).isInstanceOfSatisfying(ShippingException.class,
                e -> assertThat(e.code()).isEqualTo("PROVIDER_TIMEOUT"));
        breaker.transitionToOpenState();
        assertThatThrownBy(() -> OutboundCallExecutor.execute(bulkhead, breaker, () -> "unexpected"))
                .isInstanceOfSatisfying(ShippingException.class,
                        e -> assertThat(e.code()).isEqualTo("PROVIDER_CAPACITY_EXCEEDED"));
    }
}
