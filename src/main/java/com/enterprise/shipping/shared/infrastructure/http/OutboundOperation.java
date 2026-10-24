package com.enterprise.shipping.shared.infrastructure.http;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/** Reusable outbound resources. Each provider operation owns a separate instance.
 * Application retries are disabled; transport retry eligibility is an explicit construction policy.
 */
public final class OutboundOperation implements AutoCloseable {
    private final ConnectionProvider pool;
    private final Bulkhead bulkhead;
    private final CircuitBreaker breaker;
    private final WebClient client;

    public OutboundOperation(String name, String baseUrl, int concurrency, Duration timeout,
            int maxResponseBytes, CarrierHttpClientFactory.TransportRetry retry,
            Predicate<Throwable> ignoredFailure) {
        pool = CarrierHttpClientFactory.pool(name, concurrency);
        bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(concurrency).maxWaitDuration(Duration.ZERO).build());
        breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(5).slidingWindowSize(10).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10)).ignoreException(ignoredFailure).build());
        try {
            client = CarrierHttpClientFactory.client(pool, baseUrl, timeout.toMillis(), retry).mutate()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(maxResponseBytes)).build();
        } catch (RuntimeException failure) {
            pool.dispose();
            throw failure;
        }
    }

    public WebClient client() { return client; }
    public <T> T execute(Supplier<T> work) { return OutboundCallExecutor.execute(bulkhead, breaker, work); }
    public void resetCircuitBreaker() { breaker.reset(); }
    @Override public void close() { pool.dispose(); }
}
