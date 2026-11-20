package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundClientTest {
    interface ResourceService {
        @GetExchange("/resource")
        Mono<ResponseEntity<byte[]>> resource();

        @GetExchange("/warmup")
        Mono<ResponseEntity<byte[]>> warmup();

        @PostExchange("/create")
        Mono<ResponseEntity<byte[]>> create(@RequestBody TrackRequest request);

        @PostExchange("https://other.example/resource")
        Mono<ResponseEntity<byte[]>> foreign();
    }

    interface TrackingService {
        @PostExchange("/shipments/{shipmentId}/events")
        Mono<ResponseEntity<List<TrackEvent>>> track(@PathVariable("shipmentId") String id, @RequestBody TrackRequest request);
    }

    record TrackRequest(String reference) {}

    record TrackEvent(String code, Long timestamp) {
        public TrackEvent {
            if (code == null || code.isBlank() || timestamp == null || timestamp <= 0) {
                throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
            }
        }
    }

    static <T> T service(WebClient client, Class<T> type) {
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client)).build().createClient(type);
    }

    @Test
    void sharesOnePoolAcrossNativeServicesAndPreservesAuthentication() throws Exception {
        var calls = new AtomicInteger();
        String password = java.util.UUID.randomUUID().toString();
        String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(("user:" + password).getBytes(StandardCharsets.UTF_8));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resource", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(authorization);
            calls.incrementAndGet();
            byte[] body = "receipt".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var registry = new OutboundClientRegistry(new OutboundProperties(), new SimpleMeterRegistry())) {
            var client = registry.registerClient("test", "http://127.0.0.1:" + server.getAddress().getPort(),
                    builder -> builder.defaultHeaders(headers -> headers.setBasicAuth("user", password)));
            var http = service(client, ResourceService.class);
            var guard = registry.guard("test");
            for (String operation : Set.of("rates", "create", "labels")) {
                guard.registerOperation(operation);
                byte[] body = guard.execute("default", operation, OutboundDeadline.after(Duration.ofSeconds(3)),
                        effective -> OutboundDeadline.await(http.resource(), effective).getBody());
                assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("receipt");
            }
            assertThat(calls).hasValue(3);
            assertThat((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(registry, "pools")).hasSize(1);
            assertThatThrownBy(() -> guard.execute("default", "create", OutboundDeadline.after(Duration.ofSeconds(3)),
                    effective -> OutboundDeadline.await(http.foreign(), effective))).isInstanceOf(ShippingException.class);
            assertThatThrownBy(() -> guard.execute("default", "rates", System.nanoTime() - 1,
                    effective -> OutboundDeadline.await(http.resource(), effective))).isInstanceOf(ShippingException.class);
            assertThatThrownBy(() -> guard.execute("default", "unregistered", OutboundDeadline.after(Duration.ofSeconds(3)),
                    effective -> OutboundDeadline.await(http.resource(), effective))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.registerClient("test", "http://127.0.0.1", builder -> {
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).hasValue(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeHttpTimeoutCancelsWaitAndNeverRetriesCreation() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/warmup", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/create", exchange -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var metrics = new SimpleMeterRegistry();
        try (var registry = new OutboundClientRegistry(new OutboundProperties(), metrics)) {
            var http = service(registry.registerClient("test", "http://127.0.0.1:" + server.getAddress().getPort(), builder -> {
            }), ResourceService.class);
            var guard = registry.guard("test");
            guard.registerOperation("create");
            guard.registerOperation("rates");
            guard.execute("default", "rates", OutboundDeadline.after(Duration.ofSeconds(3)),
                    effective -> OutboundDeadline.await(http.warmup(), effective));
            long started = System.nanoTime();
            assertThatThrownBy(() -> guard.execute("default", "create", OutboundDeadline.after(Duration.ofMillis(200)),
                    effective -> OutboundDeadline.await(http.create(new TrackRequest("q")), effective)))
                    .isInstanceOfSatisfying(ShippingException.class, failure -> assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
            assertThat(calls).hasValue(1);
            assertThat(metrics.get("shipping.outbound.duration").tag("operation", "create").tag("outcome", "timeout").timer().count()).isEqualTo(1);
            assertThat(metrics.get("shipping.outbound.inflight").gauge().value()).isZero();
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test
    void nativeInterfaceBindsGenericResponsesAndEncodesSafePathVariablesOnce() throws Exception {
        var status = new AtomicInteger(200);
        var payload = new AtomicReference<>("[{\"code\":\"DELIVERED\",\"timestamp\":1700000000}]");
        var rawPath = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shipments/", exchange -> {
            calls.incrementAndGet();
            rawPath.set(exchange.getRequestURI().getRawPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var registry = new OutboundClientRegistry(new OutboundProperties(), new SimpleMeterRegistry())) {
            var http = service(registry.registerClient("track-provider", "http://127.0.0.1:" + server.getAddress().getPort(), builder -> {
            }), TrackingService.class);
            var events = http.track("ship-42", new TrackRequest("ref-1")).block(Duration.ofSeconds(3));
            assertThat(events.getBody()).containsExactly(new TrackEvent("DELIVERED", 1700000000L));
            assertThat(requestBody.get()).isEqualTo("{\"reference\":\"ref-1\"}");
            assertThat(rawPath.get()).isEqualTo("/shipments/ship-42/events");
            for (var sample : java.util.Map.of("A B", "A%20B", "包裹", "%E5%8C%85%E8%A3%B9", "100%", "100%25").entrySet()) {
                http.track(sample.getKey(), new TrackRequest("ref")).block(Duration.ofSeconds(3));
                assertThat(rawPath.get()).isEqualTo("/shipments/" + sample.getValue() + "/events");
            }
            int before = calls.get();
            for (String unsafe : List.of("../admin", "a/b", "a\\b")) {
                assertThatThrownBy(() -> http.track(unsafe, new TrackRequest("ref")).block(Duration.ofSeconds(3)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(calls).hasValue(before);
            payload.set("[{\"code\":\"DELIVERED\",\"timestamp\":\"1700000000\"}]");
            assertThatThrownBy(() -> http.track("ship-42", new TrackRequest("ref")).block(Duration.ofSeconds(3)))
                    .isInstanceOf(org.springframework.core.codec.DecodingException.class);
            status.set(404);
            assertThatThrownBy(() -> http.track("ship-42", new TrackRequest("ref")).block(Duration.ofSeconds(3)))
                    .isInstanceOf(org.springframework.web.reactive.function.client.WebClientResponseException.NotFound.class);
            var noEvents = http.track("ship-42", new TrackRequest("ref"))
                    .onErrorResume(org.springframework.web.reactive.function.client.WebClientResponseException.NotFound.class,
                            missing -> Mono.just(ResponseEntity.status(404).body(List.of())))
                    .block(Duration.ofSeconds(3));
            assertThat(noEvents.getBody()).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
