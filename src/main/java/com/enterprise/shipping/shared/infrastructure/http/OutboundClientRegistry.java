package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Lifecycle owner of one transport pool per registered dependency. Templates share values,
 * never mutable circuit state. New integrations use this registry rather than private guards.
 * Registration is explicit and bounded; request input cannot create pools or metric labels.
 * Logical groups share transport; strict connection isolation requires a separate dependency.
 */
public final class OutboundClientRegistry implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OutboundClientRegistry.class);
    private final OutboundProperties properties;
    private final MeterRegistry metrics;
    private final Map<String, OutboundGuard> guards = new LinkedHashMap<>();
    private final Map<String, ConnectionProvider> pools = new LinkedHashMap<>();
    private boolean closed;

    public OutboundClientRegistry(OutboundProperties properties, MeterRegistry metrics) {
        properties.validate();
        this.properties = properties;
        this.metrics = metrics;
    }

    public synchronized OutboundGuard registerGuard(String id, Set<String> operations) {
        if (closed
                || guards.containsKey(id)
                || !id.matches("[a-zA-Z0-9_-]{1,64}")
                || operations.isEmpty()
                || operations.stream().anyMatch(v -> !v.matches("[a-zA-Z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Invalid or duplicate outbound dependency: " + id);
        }
        OutboundPolicy policy = properties.resolve(id);
        var guard =
                new OutboundGuard(
                        id,
                        policy,
                        operations,
                        metrics,
                        error ->
                                error instanceof ShippingException failure
                                        && failure.category().isCallerFault());
        guards.put(id, guard);
        // Only effective policy is logged: URLs, headers and credentials are deliberately excluded.
        log.info(
                "Outbound policy dependency={} concurrency={} timeout={} connectTimeout={} groups={} window={} minimumCalls={} failureRate={} openDuration={}",
                id,
                policy.concurrency(),
                policy.timeout(),
                policy.connectTimeout(),
                policy.groups(),
                policy.windowSize(),
                policy.minimumCalls(),
                policy.failureRate(),
                policy.openDuration());
        return guard;
    }

    public synchronized OutboundClient register(
            String id,
            String baseUrl,
            Set<String> operations,
            Consumer<WebClient.Builder> authentication) {
        OutboundGuard guard = registerGuard(id, operations);
        OutboundPolicy policy = properties.resolve(id);
        ConnectionProvider pool =
                CarrierHttpClientFactory.pool("outbound-" + id, policy.concurrency());
        try {
            // Both transport-level and application-level automatic retries are disabled.
            var builder =
                    CarrierHttpClientFactory.client(
                                    pool,
                                    baseUrl,
                                    policy.connectTimeout().toMillis(),
                                    policy.timeout().toMillis(),
                                    CarrierHttpClientFactory.TransportRetry.DISABLED)
                            .mutate()
                            .codecs(
                                    c ->
                                            c.defaultCodecs()
                                                    .maxInMemorySize(policy.maxResponseBytes()));
            authentication.accept(builder);
            WebClient client = builder.build();
            pools.put(id, pool);
            return new OutboundClient() {
                @Override
                public <T> T execute(
                        String group,
                        String operation,
                        long deadline,
                        Work<T> work) {
                    return guard.execute(
                            group,
                            operation,
                            deadline,
                            effective ->
                                    work.run(
                                            new Exchange() {
                                                @Override
                                                public ResponseEntity<byte[]> get(String path) {
                                                    requireRelativePath(path);
                                                    return OutboundDeadline.await(
                                                            client.get()
                                                                    .uri(path)
                                                                    .exchangeToMono(
                                                                            response ->
                                                                                    response
                                                                                            .toEntity(
                                                                                                    byte
                                                                                                                    []
                                                                                                            .class)),
                                                            effective);
                                                }

                                                @Override
                                                public ResponseEntity<byte[]> post(
                                                        String path, Object body) {
                                                    requireRelativePath(path);
                                                    return OutboundDeadline.await(
                                                            client.post()
                                                                    .uri(path)
                                                                    .contentType(
                                                                            MediaType
                                                                                    .APPLICATION_JSON)
                                                                    .bodyValue(body)
                                                                    .exchangeToMono(
                                                                            response ->
                                                                                    response
                                                                                            .toEntity(
                                                                                                    byte
                                                                                                                    []
                                                                                                            .class)),
                                                            effective);
                                                }
                                            }));
                }
            };
        } catch (RuntimeException failure) {
            pool.dispose();
            guards.remove(id);
            throw failure;
        }
    }

    private static void requireRelativePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")) {
            throw new IllegalArgumentException("Outbound paths must stay on the configured origin");
        }
    }

    public synchronized OutboundGuard guard(String id) {
        OutboundGuard guard = guards.get(id);
        if (guard == null) {
            throw new IllegalArgumentException("Unregistered outbound dependency: " + id);
        }
        return guard;
    }

    @Override
    public synchronized void close() {
        closed = true;
        pools.values().forEach(ConnectionProvider::dispose);
    }
}
