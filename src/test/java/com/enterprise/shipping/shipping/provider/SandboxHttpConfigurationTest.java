package com.enterprise.shipping.shipping.provider;

import com.enterprise.shipping.shipping.model.Label;
import com.enterprise.shipping.shipping.port.LabelProvider;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import com.enterprise.shipping.support.BookingFixtures;
import com.enterprise.shipping.support.ProviderFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxHttpConfigurationTest {
    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"/rest/shipments", "/custom/shipments"})
    void nativeProxiesUseNormalizedRoutesAndSharedAuthentication(String rawPath)
            throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        var authorization = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            boolean label = path.endsWith("/label");
            byte[] body = (label ? "%PDF-test" : path.equals("/rest/rates")
                    ? "{\"availableRates\":[]}"
                    : "{\"shipId\":123,\"trackingNumbers\":[\"TRACK\"]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .set("Content-Type", label ? "application/pdf" : "application/json");
            exchange.sendResponseHeaders(
                    label || path.equals("/rest/rates") ? 200 : 201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = ProviderFixture.properties(
                "http://127.0.0.1:" + server.getAddress().getPort());
        var fixture = new ProviderFixture(
                properties,
                rawPath,
                Duration.ofSeconds(90),
                Duration.ofSeconds(3),
                Duration.ofSeconds(3));
        try (fixture) {
            assertThat(fixture.rates.fetchRates(
                            BookingFixtures.command("q").shippingInput().dated(LocalDate.now()),
                            System.nanoTime() + Duration.ofSeconds(3).toNanos()))
                    .isEmpty();
            assertThat(fixture.shipments.create(
                            new ShipmentProvider.ShipmentSubmission("sandbox", "account", "q"))
                    .shipmentId())
                    .isEqualTo("123");
            assertThat(fixture.labels.fetch(
                            new LabelProvider.LabelIdentity("sandbox", "account", "123"))
                    .bytes())
                    .isEqualTo("%PDF-test".getBytes(StandardCharsets.UTF_8));
            String expected = rawPath == null || rawPath.isBlank()
                    ? "/rest/shipments" : rawPath;
            assertThat(paths).containsExactly("/rest/rates", expected, expected + "/123/label");
            String basic = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    (properties.username() + ":" + properties.password())
                            .getBytes(StandardCharsets.UTF_8));
            assertThat(authorization).containsExactly(basic, basic, basic);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../shipments", "/unsafe/../shipments", "/shipments?redirect=elsewhere"})
    void invalidConfiguredRoutesStillFailBeforeSending(String path) {
        var properties = ProviderFixture.properties("http://127.0.0.1");
        assertThatThrownBy(() -> new ProviderFixture(properties, path, Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
