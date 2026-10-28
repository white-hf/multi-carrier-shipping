package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OutboundClientTest {
    @Test
    void sharesOnePoolAcrossOperationsAndPreservesAuthentication() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/resource",
                exchange -> {
                    if (!"Basic dXNlcjpwYXNz"
                            .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                    } else {
                        calls.incrementAndGet();
                        byte[] bytes = "receipt".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
        server.start();
        var registry =
                new OutboundClientRegistry(new OutboundProperties(), new SimpleMeterRegistry());
        try {
            var client =
                    registry.register(
                            "test",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            Set.of("rates", "create", "labels"),
                            builder -> builder.defaultHeaders(h -> h.setBasicAuth("user", "pass")));
            for (String operation : Set.of("rates", "create", "labels")) {
                String receipt =
                        client.execute(
                                operation,
                                OutboundDeadline.after(Duration.ofSeconds(3)),
                                exchange ->
                                        new String(
                                                exchange.get("/resource").getBody(),
                                                StandardCharsets.UTF_8));
                assertThat(receipt).isEqualTo("receipt");
            }
            assertThat(calls).hasValue(3);
            var pools =
                    (java.util.Map<?, ?>)
                            org.springframework.test.util.ReflectionTestUtils.getField(
                                    registry, "pools");
            assertThat(pools).hasSize(1);
            assertThatThrownBy(
                            () ->
                                    client.execute(
                                            "create",
                                            OutboundDeadline.after(Duration.ofSeconds(3)),
                                            exchange ->
                                                    exchange.post(
                                                            "https://other.example/resource",
                                                            java.util.Map.of())))
                    .isInstanceOf(ShippingException.class);
            assertThatThrownBy(
                            () ->
                                    client.execute(
                                            "rates",
                                            System.nanoTime() - 1,
                                            exchange -> exchange.get("/resource")))
                    .isInstanceOf(ShippingException.class);
            assertThat(calls).hasValue(3);
            assertThatThrownBy(() -> registry.registerGuard("test", Set.of("rates")))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            registry.close();
            server.stop(0);
        }
    }

    @Test
    void httpTimeoutCancelsWaitAndNeverRetriesCreation() throws Exception {
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext(
                "/warmup",
                exchange -> {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.createContext(
                "/create",
                exchange -> {
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
        var registry = new OutboundClientRegistry(new OutboundProperties(), metrics);
        try {
            var client =
                    registry.register(
                            "test",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            Set.of("create", "rates"),
                            builder -> {
                            });
            client.execute(
                    "rates",
                    OutboundDeadline.after(Duration.ofSeconds(3)),
                    exchange -> exchange.get("/warmup"));
            long start = System.nanoTime();
            assertThatThrownBy(
                            () ->
                                    client.execute(
                                            "create",
                                            OutboundDeadline.after(Duration.ofMillis(200)),
                                            exchange ->
                                                    exchange.post(
                                                            "/create",
                                                            java.util.Map.of("quoteId", "q"))))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            e -> assertThat(e.code()).isEqualTo("PROVIDER_TIMEOUT"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
            assertThat(calls).hasValue(1);
            assertThat(
                            metrics.get("shipping.outbound.duration")
                                    .tag("operation", "create")
                                    .tag("outcome", "timeout")
                                    .timer()
                                    .count())
                    .isEqualTo(1);
            assertThat(metrics.get("shipping.outbound.inflight").gauge().value()).isZero();
        } finally {
            release.countDown();
            registry.close();
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
