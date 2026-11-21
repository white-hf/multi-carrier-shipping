package com.enterprise.shipping.shared.infrastructure.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Transport construction and mechanics only: pools, origin safety, strict JSON codecs, body
 * buffering and diagnostics hooks. Provider-specific paths, status meaning and resilience limits
 * stay with the provider.
 */
public final class OutboundTransportFactory {
    private static final Duration CLIENT_WARMUP_TIMEOUT = Duration.ofSeconds(10);

    private OutboundTransportFactory() {
    }

    public static ConnectionProvider pool(
            String name,
            int concurrency,
            Duration pendingAcquireTimeout) {
        return ConnectionProvider.builder(name)
                .maxConnections(concurrency)
                .pendingAcquireMaxCount(concurrency)
                .pendingAcquireTimeout(pendingAcquireTimeout)
                .metrics(true)
                .build();
    }

    public static WebClient client(
            ConnectionProvider pool,
            String baseUrl,
            String username,
            String password,
            Duration connectTimeout,
            Duration responseTimeout,
            int maxResponseBytes,
            String providerId,
            ObjectMapper mapper) {
        HttpClient http = HttpClient.create(pool)
                .disableRetry(true)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);
        // Initialize event loops without making a provider request inside a short first-call budget.
        http.warmup().block(CLIENT_WARMUP_TIMEOUT);

        URI origin = URI.create(baseUrl);
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(maxResponseBytes);
                    // Preserve body-based JSON validation, including providers omitting Content-Type.
                    codecs.defaultCodecs()
                            .jackson2JsonDecoder(new Jackson2JsonDecoder(mapper, MediaType.ALL));
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
                })
                .filter((request, next) -> Mono.deferContextual(context -> {
                    requireSameOrigin(origin, request.url());
                    OutboundDiagnostics.HttpCall call =
                            context.getOrDefault(
                                    OutboundDiagnostics.HttpCall.class,
                                    OutboundDiagnostics.call(
                                            providerId,
                                            "http",
                                            status -> status >= 200 && status < 300
                                                    ? OutboundDiagnostics.Classification.SUCCESS
                                                    : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE));
                    return next.exchange(request).flatMap(response -> receive(response, call));
                }))
                .build();
    }

    static void requireSameOrigin(URI origin, URI resolved) {
        String path = resolved.getRawPath();
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (!Objects.equals(origin.getScheme(), resolved.getScheme())
                || !Objects.equals(origin.getHost(), resolved.getHost())
                || origin.getPort() != resolved.getPort()
                || resolved.getUserInfo() != null
                || resolved.getRawQuery() != null
                || resolved.getRawFragment() != null
                || path == null
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.contains("\\")
                || path.contains("..")
                || lower.contains("%2f")
                || lower.contains("%5c")
                || lower.contains("%2e")) {
            throw new IllegalArgumentException(
                    "Outbound URI must stay on the configured origin without traversal");
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
                        call.bodyFailure(
                                status,
                                "[BODY_READ_FAILED:" + OutboundDiagnostics.reason(error) + "]");
                    }
                })
                .doOnCancel(() -> {
                    if (recorded.compareAndSet(false, true)) {
                        call.bodyFailure(status, "[BODY_READ_CANCELLED]");
                    }
                })
                .map(entity -> {
                    byte[] bytes = entity.getBody();
                    return response.mutate()
                            .body(
                                    bytes == null
                                            ? Flux.empty()
                                            : Flux.just(
                                                    DefaultDataBufferFactory.sharedInstance.wrap(
                                                            bytes)))
                            .build();
                });
    }
}
