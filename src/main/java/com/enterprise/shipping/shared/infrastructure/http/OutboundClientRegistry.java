package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
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
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("Invalid or duplicate outbound dependency: " + id);
        }
        return createGuard(id, operations);
    }

    private OutboundGuard createGuard(String id, Set<String> operations) {
        if (closed
                || guards.containsKey(id)
                || !id.matches("[a-zA-Z0-9_-]{1,64}")
                || operations == null
                || operations.stream().anyMatch(v -> v == null || !v.matches("[a-zA-Z0-9_-]{1,64}"))) {
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

    public synchronized OutboundClient registerClient(
            String id,
            String baseUrl,
            Consumer<WebClient.Builder> authentication) {
        return createClient(id, baseUrl, Set.of(), true, authentication);
    }

    public synchronized OutboundClient registerEndpoints(
            String id,
            String baseUrl,
            Collection<? extends OutboundEndpoint<?, ?>> endpoints,
            Consumer<WebClient.Builder> authentication) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("Outbound endpoints required for: " + id);
        }
        // Validate null entries and duplicate operation names before creating the guard and its
        // gauges so failed registrations never leave orphaned meters in the registry.
        var validated = new LinkedHashMap<String, OutboundEndpoint<?, ?>>();
        for (OutboundEndpoint<?, ?> endpoint : endpoints) {
            if (endpoint == null
                    || validated.putIfAbsent(endpoint.operation(), endpoint) != null) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate outbound endpoint for: " + id);
            }
        }
        OutboundClient client = createClient(id, baseUrl, Set.of(), true, authentication);
        for (OutboundEndpoint<?, ?> endpoint : validated.values()) {
            client.register(endpoint);
        }
        return client;
    }

    public synchronized OutboundClient register(
            String id,
            String baseUrl,
            Set<String> operations,
            Consumer<WebClient.Builder> authentication) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("Invalid or duplicate outbound dependency: " + id);
        }
        return createClient(id, baseUrl, operations, false, authentication);
    }

    private OutboundClient createClient(
            String id,
            String baseUrl,
            Set<String> operations,
            boolean requireEndpointRegistration,
            Consumer<WebClient.Builder> authentication) {
        OutboundGuard guard = createGuard(id, operations);
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
            URI origin = URI.create(baseUrl);
            WebClient client = builder.build();
            pools.put(id, pool);
            var registeredEndpoints = new ConcurrentHashMap<String, OutboundEndpoint<?, ?>>();
            return new OutboundClient() {
                @Override
                public void register(OutboundEndpoint<?, ?> endpoint) {
                    if (endpoint == null) {
                        throw new IllegalArgumentException(
                                "Invalid or duplicate outbound endpoint for: " + id);
                    }
                    if (registeredEndpoints.putIfAbsent(endpoint.operation(), endpoint) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate outbound endpoint for "
                                        + id
                                        + ": "
                                        + endpoint.operation());
                    }
                    try {
                        guard.registerOperation(endpoint.operation());
                    } catch (RuntimeException duplicate) {
                        registeredEndpoints.remove(endpoint.operation(), endpoint);
                        throw duplicate;
                    }
                }

                @Override
                public <Req, Resp> Resp invoke(
                        OutboundEndpoint<Req, Resp> endpoint,
                        Map<String, ?> uriVariables,
                        Req request,
                        long deadline) {
                    Objects.requireNonNull(endpoint, "endpoint");
                    Objects.requireNonNull(uriVariables, "uriVariables");
                    if ((requireEndpointRegistration || !registeredEndpoints.isEmpty())
                            && registeredEndpoints.get(endpoint.operation()) != endpoint) {
                        throw new IllegalArgumentException(
                                "Unregistered outbound endpoint: " + endpoint.operation());
                    }
                    String requestId = MDC.get("requestId");
                    return guard.execute(
                            endpoint.group(),
                            endpoint.operation(),
                            deadline,
                            effective -> {
                                if (effective - System.nanoTime() <= 0) {
                                    throw ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
                                }
                                validateUriInputs(endpoint.pathTemplate(), uriVariables);
                                long started = System.nanoTime();
                                var spec =
                                        client.method(endpoint.method())
                                                .uri(
                                                        uriBuilder ->
                                                                buildSameOriginUri(
                                                                        origin,
                                                                        uriBuilder,
                                                                        endpoint.pathTemplate(),
                                                                        uriVariables));
                                var headersSpec =
                                        request == null
                                                ? spec
                                                : spec.contentType(MediaType.APPLICATION_JSON)
                                                        .bodyValue(request);
                                ResponseEntity<byte[]> response =
                                        OutboundDeadline.await(
                                                headersSpec.exchangeToMono(
                                                        raw ->
                                                                receive(
                                                                        raw,
                                                                        id,
                                                                        endpoint.operation(),
                                                                        started,
                                                                        requestId,
                                                                        endpoint.statusPolicy()
                                                                                .classify(
                                                                                        raw.statusCode()
                                                                                                .value()))),
                                                effective);
                                return endpoint.statusPolicy()
                                        .resolve(
                                                response,
                                                endpoint.decoder(),
                                                () ->
                                                        OutboundDiagnostics.response(
                                                                id,
                                                                endpoint.operation(),
                                                                response == null
                                                                        ? 0
                                                                        : response.getStatusCode()
                                                                                .value(),
                                                                started,
                                                                requestId,
                                                                response == null
                                                                        ? null
                                                                        : response.getBody(),
                                                                null,
                                                                OutboundDiagnostics.Classification
                                                                        .PROTOCOL_ERROR));
                            });
                }

                @Override
                public <T> T execute(
                        String group,
                        String operation,
                        long deadline,
                        Work<T> work) {
                    // Response callbacks run on Netty threads without the caller's MDC.
                    String requestId = MDC.get("requestId");
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
                                                    long started = System.nanoTime();
                                                    return OutboundDeadline.await(
                                                            client.get()
                                                                    .uri(path)
                                                                    .exchangeToMono(
                                                                            response ->
                                                                                    receive(
                                                                                            response,
                                                                                            id,
                                                                                            operation,
                                                                                            started,
                                                                                            requestId,
                                                                                            defaultClassification(
                                                                                                    response.statusCode()
                                                                                                            .value()))),
                                                            effective);
                                                }

                                                @Override
                                                public ResponseEntity<byte[]> post(
                                                        String path, Object body) {
                                                    requireRelativePath(path);
                                                    long started = System.nanoTime();
                                                    return OutboundDeadline.await(
                                                            client.post()
                                                                    .uri(path)
                                                                    .contentType(
                                                                            MediaType
                                                                                    .APPLICATION_JSON)
                                                                    .bodyValue(body)
                                                                    .exchangeToMono(
                                                                            response ->
                                                                                    receive(
                                                                                            response,
                                                                                            id,
                                                                                            operation,
                                                                                            started,
                                                                                            requestId,
                                                                                            defaultClassification(
                                                                                                    response.statusCode()
                                                                                                            .value()))),
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

    private static OutboundDiagnostics.Classification defaultClassification(int status) {
        return status >= 200 && status < 300
                ? OutboundDiagnostics.Classification.SUCCESS
                : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE;
    }

    private static Mono<ResponseEntity<byte[]>> receive(
            ClientResponse response,
            String dependency,
            String operation,
            long started,
            String requestId,
            OutboundDiagnostics.Classification classification) {
        int status = response.statusCode().value();
        var recorded = new java.util.concurrent.atomic.AtomicBoolean();
        return response.toEntity(byte[].class)
                .doOnNext(
                        received -> {
                            if (recorded.compareAndSet(false, true)) {
                                OutboundDiagnostics.response(
                                        dependency,
                                        operation,
                                        status,
                                        started,
                                        requestId,
                                        received.getBody(),
                                        null,
                                        classification);
                            }
                        })
                .doOnError(
                        error -> {
                            if (recorded.compareAndSet(false, true)) {
                                OutboundDiagnostics.response(
                                        dependency,
                                        operation,
                                        status,
                                        started,
                                        requestId,
                                        null,
                                        "[BODY_READ_FAILED:"
                                                + OutboundDiagnostics.reason(error)
                                                + "]",
                                        OutboundDiagnostics.Classification.BODY_READ_FAILURE);
                            }
                        })
                .doOnCancel(
                        () -> {
                            // Overall timeout cancels body consumption after headers have arrived.
                            if (recorded.compareAndSet(false, true)) {
                                OutboundDiagnostics.response(
                                        dependency,
                                        operation,
                                        status,
                                        started,
                                        requestId,
                                        null,
                                        "[BODY_READ_CANCELLED]",
                                        OutboundDiagnostics.Classification.BODY_READ_FAILURE);
                            }
                        });
    }

    private static void validateUriInputs(String pathTemplate, Map<String, ?> uriVariables) {
        OutboundEndpoint.validatePathTemplate(pathTemplate);
        if (uriVariables.isEmpty()) {
            if (pathTemplate.contains("{") || pathTemplate.contains("}")) {
                throw new IllegalArgumentException(
                        "Missing URI variables for outbound template: " + pathTemplate);
            }
            requireRelativePath(pathTemplate);
            return;
        }
        for (Map.Entry<String, ?> entry : uriVariables.entrySet()) {
            Object raw = entry.getValue();
            if (raw == null) {
                throw new IllegalArgumentException(
                        "Null URI variable for: " + entry.getKey());
            }
            String value = raw.toString();
            if (value.isBlank()
                    || value.contains("/")
                    || value.contains("\\")
                    || value.contains("..")
                    || value.contains("?")
                    || value.contains("#")) {
                throw new IllegalArgumentException(
                        "Unsafe URI variable value for: " + entry.getKey());
            }
        }
    }

    private static URI buildSameOriginUri(
            URI origin,
            UriBuilder uriBuilder,
            String pathTemplate,
            Map<String, ?> uriVariables) {
        // Delegate expansion and encoding to WebClient's UriBuilder in a single pass
        // so path variables with spaces, non-ASCII characters, or '%' are never double-encoded.
        URI resolved = uriBuilder.path(pathTemplate).build(uriVariables);
        String rawPath = resolved.getRawPath();
        if (!Objects.equals(origin.getScheme(), resolved.getScheme())
                || !Objects.equals(origin.getHost(), resolved.getHost())
                || origin.getPort() != resolved.getPort()
                || resolved.getUserInfo() != null
                || resolved.getRawQuery() != null
                || resolved.getRawFragment() != null) {
            throw new IllegalArgumentException("Outbound URI must stay on the configured origin");
        }
        requireRelativePath(rawPath);
        return resolved;
    }

    private static void requireRelativePath(String path) {
        if (path == null
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.contains("\\")
                || path.contains("..")) {
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
