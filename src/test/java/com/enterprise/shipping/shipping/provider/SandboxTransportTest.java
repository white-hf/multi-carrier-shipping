package com.enterprise.shipping.shipping.provider;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import com.enterprise.shipping.support.ProviderFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxTransportTest {
    interface TrackingService {
        @PostExchange("/shipments/{shipmentId}/events")
        Mono<ResponseEntity<List<TrackEvent>>> track(
                @PathVariable("shipmentId") String id,
                @RequestBody TrackRequest request);

        @PostExchange("https://other.example/resource")
        Mono<ResponseEntity<byte[]>> foreign();
    }

    record TrackRequest(String reference) {}

    record TrackEvent(String code, Long timestamp) {
        public TrackEvent {
            if (code == null || code.isBlank() || timestamp == null || timestamp <= 0) {
                throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
            }
        }
    }

    @Test
    void timeoutCancelsBodyWaitNeverRetriesCreateAndReleasesPermit() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/rest/shipments", exchange -> {
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
        var properties = ProviderFixture.properties(
                "http://127.0.0.1:" + server.getAddress().getPort());
        try (var fixture = new ProviderFixture(properties, Duration.ofSeconds(90),
                Duration.ofMillis(200), Duration.ofSeconds(3))) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> fixture.shipments.create(
                            new ShipmentProvider.ShipmentSubmission("sandbox", "account", "q")))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            error -> assertThat(error.code()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(1500);
            assertThat(calls).hasValue(1);
            assertThat(fixture.metrics.get("shipping.outbound.duration")
                    .tag("operation", "shipments").tag("outcome", "timeout").timer().count())
                    .isEqualTo(1);
            assertThat(fixture.metrics.get("shipping.outbound.inflight").gauge().value())
                    .isZero();
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test
    void nativeInterfacesBindGenericResponsesEncodeOnceAndRejectForeignOrigins() throws Exception {
        var status = new AtomicInteger(200);
        var payload = new AtomicReference<>("[{\"code\":\"DELIVERED\",\"timestamp\":1700000000}]");
        var rawPath = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shipments/", exchange -> {
            calls.incrementAndGet();
            rawPath.set(exchange.getRequestURI().getRawPath());
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var fixture = new ProviderFixture("http://127.0.0.1:" + server.getAddress().getPort())) {
            var http = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(fixture.web))
                    .build()
                    .createClient(TrackingService.class);
            assertThat(http.track("ship-42", new TrackRequest("ref-1"))
                    .block(Duration.ofSeconds(3)).getBody())
                    .containsExactly(new TrackEvent("DELIVERED", 1700000000L));
            assertThat(requestBody.get()).isEqualTo("{\"reference\":\"ref-1\"}");
            assertThat(rawPath.get()).isEqualTo("/shipments/ship-42/events");
            for (var sample : Map.of("A B", "A%20B", "包裹", "%E5%8C%85%E8%A3%B9", "100%", "100%25")
                    .entrySet()) {
                http.track(sample.getKey(), new TrackRequest("ref")).block(Duration.ofSeconds(3));
                assertThat(rawPath.get()).isEqualTo("/shipments/" + sample.getValue() + "/events");
            }
            int before = calls.get();
            for (String unsafe : List.of("../admin", "a/b", "a\\b")) {
                assertThatThrownBy(() -> http.track(unsafe, new TrackRequest("ref"))
                        .block(Duration.ofSeconds(3)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> http.foreign().block(Duration.ofSeconds(3)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).hasValue(before);
            payload.set("[{\"code\":\"DELIVERED\",\"timestamp\":\"1700000000\"}]");
            assertThatThrownBy(() -> http.track("ship-42", new TrackRequest("ref"))
                    .block(Duration.ofSeconds(3)))
                    .isInstanceOf(org.springframework.core.codec.DecodingException.class);
            status.set(404);
            assertThatThrownBy(() -> http.track("ship-42", new TrackRequest("ref"))
                    .block(Duration.ofSeconds(3)))
                    .isInstanceOf(
                            org.springframework.web.reactive.function.client
                                    .WebClientResponseException.NotFound.class);
        } finally {
            server.stop(0);
        }
    }
}
