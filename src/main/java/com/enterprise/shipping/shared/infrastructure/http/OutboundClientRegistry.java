package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

/** Dependency policy, transport lifecycle and instrumentation; Spring owns HTTP service dispatch. */
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
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("Outbound operations required");
        }
        return createGuard(id, operations);
    }

    private OutboundGuard createGuard(String id, Set<String> operations) {
        if (closed || id == null || guards.containsKey(id) || !id.matches("[a-zA-Z0-9_-]{1,64}")
                || operations.stream().anyMatch(value -> value == null || !value.matches("[a-zA-Z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("Invalid or duplicate outbound dependency: " + id);
        }
        var policy = properties.resolve(id);
        var guard = new OutboundGuard(id, policy, operations, metrics,
                error -> error instanceof ShippingException shipping && shipping.category().isCallerFault());
        guards.put(id, guard);
        log.info("Outbound policy dependency={} concurrency={} timeout={} connectTimeout={} groups={} window={} minimumCalls={} failureRate={} openDuration={}",
                id, policy.concurrency(), policy.timeout(), policy.connectTimeout(), policy.groups(),
                policy.windowSize(), policy.minimumCalls(), policy.failureRate(), policy.openDuration());
        return guard;
    }

    public synchronized WebClient registerClient(
            String id,
            String baseUrl,
            Consumer<WebClient.Builder> authentication) {
        createGuard(id, Set.of());
        var policy = properties.resolve(id);
        var pool = CarrierHttpClientFactory.pool("outbound-" + id, policy.concurrency());
        try {
            var origin = URI.create(baseUrl);
            var builder = CarrierHttpClientFactory.client(pool, baseUrl,
                    policy.connectTimeout().toMillis(), policy.timeout().toMillis(),
                    CarrierHttpClientFactory.TransportRetry.DISABLED).mutate()
                    .codecs(codecs -> {
                        codecs.defaultCodecs().maxInMemorySize(policy.maxResponseBytes());
                        // Preserve body-based JSON validation, including providers omitting Content-Type.
                        codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(OutboundJson.mapper(), MediaType.ALL));
                        codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(OutboundJson.mapper()));
                    })
                    .filter((request, next) -> Mono.deferContextual(context -> {
                        requireSameOrigin(origin, request.url());
                        var call = context.getOrDefault(OutboundDiagnostics.HttpCall.class,
                                OutboundDiagnostics.call(id, "http", status -> status >= 200 && status < 300
                                        ? OutboundDiagnostics.Classification.SUCCESS : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE));
                        return next.exchange(request).flatMap(response -> receive(response, call));
                    }));
            authentication.accept(builder);
            var client = builder.build();
            pools.put(id, pool);
            return client;
        } catch (RuntimeException failure) {
            pool.dispose();
            guards.remove(id);
            throw failure;
        }
    }

    private static Mono<ClientResponse> receive(
            ClientResponse response,
            OutboundDiagnostics.HttpCall call) {
        int status = response.statusCode().value();
        var recorded = new AtomicBoolean();
        return response.toEntity(byte[].class)
                .doOnNext(entity -> {
                    if (recorded.compareAndSet(false, true)) {
                        call.received(entity);
                    }
                })
                .doOnError(error -> {
                    if (recorded.compareAndSet(false, true)) {
                        call.bodyFailure(status, "[BODY_READ_FAILED:" + OutboundDiagnostics.reason(error) + "]");
                    }
                })
                .doOnCancel(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        call.bodyFailure(status, "[BODY_READ_CANCELLED]");
                    }
                })
                .map(entity -> {
                    byte[] bytes = entity.getBody();
                    return response.mutate().body(bytes == null ? Flux.empty()
                            : Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))).build();
                });
    }

    private static void requireSameOrigin(URI origin, URI resolved) {
        String path = resolved.getRawPath();
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (!Objects.equals(origin.getScheme(), resolved.getScheme())
                || !Objects.equals(origin.getHost(), resolved.getHost()) || origin.getPort() != resolved.getPort()
                || resolved.getUserInfo() != null || resolved.getRawQuery() != null || resolved.getRawFragment() != null
                || path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.contains("..")
                || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")) {
            throw new IllegalArgumentException("Outbound URI must stay on the configured origin without traversal");
        }
    }

    public synchronized OutboundGuard guard(String id) {
        var guard = guards.get(id);
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
