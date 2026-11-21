package com.enterprise.shipping.shipping.provider;

import com.enterprise.shipping.provider.sandbox.ProviderJson;
import com.enterprise.shipping.provider.sandbox.label.LabelResponseDecoder;
import com.enterprise.shipping.provider.sandbox.shipment.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shipping.model.CreateResult;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import com.enterprise.shipping.support.BookingFixtures;
import com.enterprise.shipping.support.ProviderFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.json.Jackson2JsonDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxProviderProtocolTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "{}",
        "{\"availableRates\":null}",
        "{\"availableRates\":\"invalid\"}",
        "{\"availableRates\":[null]}",
        "{\"availableRates\":[{\"quoteId\":\"\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"CAD\"}}]}",
        "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":-1,\"currency\":\"CAD\"}}]}",
        "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":12.5,\"currency\":\"CAD\"}}]}",
        "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":\"100\",\"currency\":\"CAD\"}}]}",
        "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"USD\"}}]}",
        "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"CAD\"},\"transitDays\":-1,\"transitUnit\":\"Days\"}]}"
    })
    void rejectsMalformedOrInvalidRateResponses(String invalidPayload) throws Exception {
        var body = new AtomicReference<>(invalidPayload);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/rates", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var fixture = new ProviderFixture("http://127.0.0.1:" + server.getAddress().getPort())) {
            var intent = BookingFixtures.command("q").shippingInput().dated(LocalDate.now());
            assertThatThrownBy(
                            () -> fixture.rates.fetchRates(
                                    intent, System.nanoTime() + Duration.ofSeconds(3).toNanos()))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            error -> assertThat(error.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void labelDecoderHandlesEmptyPdfAllowlistedUrlAndRejectsInvalidPayloads() {
        var decoder = new LabelResponseDecoder(
                url -> url != null && url.startsWith("https://labels.example.com/"));

        assertThat(decoder.decode(ResponseEntity.ok().body(new byte[0]))).isNull();

        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        var pdfLabel = decoder.decode(
                ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf));
        assertThat(pdfLabel.bytes()).isEqualTo(pdf);
        assertThat(pdfLabel.contentType()).isEqualTo("application/pdf");

        var urlLabel = decoder.decode(ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"labelUrl\":\"https://labels.example.com/1.pdf\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(urlLabel.url()).isEqualTo("https://labels.example.com/1.pdf");

        // Disallowed host or wrong content-type fails closed.
        assertThatThrownBy(() -> decoder.decode(ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"labelUrl\":\"https://evil.example.com/1.pdf\"}"
                                .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));

        assertThatThrownBy(() -> decoder.decode(ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, "text/html")
                        .body("<html></html>".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "{}", " ", "{\"labelUrl\":null}", "{\"labelUrl\":123}", "{\"labelUrl\":true}",
        "{\"labelUrl\":\"https://labels.example.com/a.pdf\"} {}",
        "{\"labelUrl\":\"https://evil.example.com/a.pdf\"}",
        "{\"labelUrl\":\"https://user@labels.example.com/a.pdf\"}"
    })
    void strictLabelJsonStillRejectsInvalidBodies(String body) {
        var properties = new com.enterprise.shipping.provider.sandbox.SandboxProviderProperties(
                "http://127.0.0.1",
                "stub",
                java.util.UUID.randomUUID().toString(),
                "sandbox",
                "account",
                Set.of("labels.example.com"),
                Duration.ofSeconds(3),
                4 * 1024 * 1024);
        var decoder = new LabelResponseDecoder(properties::allowedUrl);
        var response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> decoder.decode(response))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rates", "shipments"})
    void expectedRejectionDoesNotOpenAvailabilityCircuit(String api) throws Exception {
        var status = new AtomicInteger(400);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String body = api.equals("rates")
                    ? "{\"availableRates\":[]}" : "{\"shipId\":123}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var fixture = new ProviderFixture("http://127.0.0.1:" + server.getAddress().getPort())) {
            var intent = BookingFixtures.command("q").shippingInput().dated(LocalDate.now());
            for (int attempt = 0; attempt < 8; attempt++) {
                if (api.equals("rates")) {
                    assertThatThrownBy(() -> fixture.rates.fetchRates(
                                    intent, System.nanoTime() + Duration.ofSeconds(3).toNanos()))
                            .isInstanceOfSatisfying(
                                    ShippingException.class,
                                    error -> assertThat(error.code())
                                            .isEqualTo("PROVIDER_REQUEST_REJECTED"));
                } else {
                    assertThat(fixture.shipments.create(
                                    new ShipmentProvider.ShipmentSubmission(
                                            "sandbox", "account", "q"))
                            .status())
                            .isEqualTo(CreateResult.Status.REJECTED);
                }
            }
            status.set(200);
            if (api.equals("rates")) {
                assertThat(fixture.rates.fetchRates(
                                intent, System.nanoTime() + Duration.ofSeconds(3).toNanos()))
                        .isEmpty();
            } else {
                assertThat(fixture.shipments.create(
                                new ShipmentProvider.ShipmentSubmission(
                                        "sandbox", "account", "q"))
                        .shipmentId())
                        .isEqualTo("123");
            }
            assertThat(calls).hasValue(9);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createResponseCannotSpoofRejectedFlagFromJson() {
        var decoder = new Jackson2JsonDecoder(ProviderJson.mapper());
        var parsed = (CreateShipmentResponse) decoder.decode(
                DefaultDataBufferFactory.sharedInstance.wrap(
                        "{\"shipId\":123,\"rejected\":true}".getBytes(StandardCharsets.UTF_8)),
                ResolvableType.forClass(CreateShipmentResponse.class),
                MediaType.APPLICATION_JSON,
                Map.of());
        assertThat(parsed.rejected()).isFalse();
        assertThat(parsed.shipmentId()).isEqualTo("123");
    }
}
