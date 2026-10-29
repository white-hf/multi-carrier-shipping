package com.enterprise.shipping.provider.aggregator;

import com.enterprise.shipping.provider.aggregator.config.AggregatorContext;
import com.enterprise.shipping.provider.aggregator.config.ProviderProperties;
import com.enterprise.shipping.provider.aggregator.labels.AggregatorLabelApi;
import com.enterprise.shipping.provider.aggregator.labels.LabelIdentity;
import com.enterprise.shipping.provider.aggregator.labels.LabelResponseDecoder;
import com.enterprise.shipping.provider.aggregator.rates.AggregatorRateApi;
import com.enterprise.shipping.provider.aggregator.rates.dto.RateApiResponse;
import com.enterprise.shipping.provider.aggregator.shipments.AggregatorShipmentApi;
import com.enterprise.shipping.provider.aggregator.shipments.CreateSubmission;
import com.enterprise.shipping.provider.aggregator.shipments.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.OutboundProperties;
import com.enterprise.shipping.shared.infrastructure.http.ResponseDecoder;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AggregatorProviderProtocolTest {

    private static final ShippingAddress ADDRESS =
            new ShippingAddress(
                    "Alice",
                    null,
                    "100 King St",
                    null,
                    "Toronto",
                    "ON",
                    "M5V 3L9",
                    "CA",
                    "4165550100",
                    null,
                    true);

    private static final ShippingIntent INTENT =
            new ShippingIntent(
                    ADDRESS,
                    ADDRESS,
                    new ShippingIntent.Package(
                            new BigDecimal("10"),
                            new BigDecimal("8"),
                            new BigDecimal("6"),
                            DimensionUnit.IN,
                            new BigDecimal("5"),
                            WeightUnit.LB,
                            "PACKAGE"),
                    LocalDate.of(2026, 10, 29));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{}",
                "{\"availableRates\":null}",
                "{\"availableRates\":\"invalid\"}",
                "{\"availableRates\":[null]}",
                "{\"availableRates\":[{\"quoteId\":\"\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"CAD\"}}]}",
                "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":-1,\"currency\":\"CAD\"}}]}",
                "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":12.5,\"currency\":\"CAD\"}}]}",
                "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":\"100\",\"currency\":\"CAD\"}}]}",
                "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":9223372036854775808,\"currency\":\"CAD\"}}]}",
                "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"USD\"}}]}",
                "{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"CAD\"},\"transitDays\":-1,\"transitUnit\":\"Days\"}]}"
            })
    void rejectsMalformedOrInvalidRateResponses(String invalidPayload) {
        var decoder =
                ResponseDecoder.json(RateApiResponse.class)
                        .validate(AggregatorRateApi::validateCurrency);
        var response =
                ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(invalidPayload.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> decoder.decode(response))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        e -> assertThat(e.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));
    }

    @Test
    void labelDecoderHandlesEmptyPdfAllowlistedUrlAndRejectsInvalidPayloads() {
        var decoder =
                new LabelResponseDecoder(
                        url -> url != null && url.startsWith("https://labels.example.com/"));

        assertThat(decoder.decode(ResponseEntity.ok().body(new byte[0]))).isNull();

        byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        var pdfLabel =
                decoder.decode(
                        ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf));
        assertThat(pdfLabel.bytes()).isEqualTo(pdf);
        assertThat(pdfLabel.contentType()).isEqualTo("application/pdf");

        var urlLabel =
                decoder.decode(
                        ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"labelUrl\":\"https://labels.example.com/1.pdf\"}"
                                                .getBytes(StandardCharsets.UTF_8)));
        assertThat(urlLabel.url()).isEqualTo("https://labels.example.com/1.pdf");

        // Disallowed host or wrong content-type fails closed.
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        ResponseEntity.ok()
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .body(
                                                        "{\"labelUrl\":\"https://evil.example.com/1.pdf\"}"
                                                                .getBytes(
                                                                        StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        e -> assertThat(e.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));

        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        ResponseEntity.ok()
                                                .header(HttpHeaders.CONTENT_TYPE, "text/html")
                                                .body(
                                                        "<html></html>"
                                                                .getBytes(
                                                                        StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        e -> assertThat(e.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));
    }

    @Test
    void createResponseCannotSpoofRejectedFlagFromJson() {
        var decoder = ResponseDecoder.json(CreateShipmentResponse.class);
        var parsed =
                decoder.decode(
                        ResponseEntity.status(201)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"shipId\":123,\"rejected\":true}"
                                                .getBytes(StandardCharsets.UTF_8)));
        assertThat(parsed.rejected()).isFalse();
        assertThat(parsed.shipmentId()).isEqualTo("123");
    }

    record UpsTrackResponse(String trackingNumber, String status) {}

    @Test
    void independentApiSlicesAndSecondProviderCoexistWithoutCentralClientCoupling(
            CapturedOutput output) throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>("{\"availableRates\":\"corrupted\"}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/rest/rates",
                exchange -> {
                    byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/rest/shipments",
                exchange -> {
                    byte[] bytes = "{\"shipId\":0}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/rest/shipments/123/label",
                exchange -> {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                });
        server.createContext(
                "/ups/track/1Z999",
                exchange -> {
                    byte[] bytes =
                            "{\"trackingNumber\":\"1Z999\",\"status\":\"IN_TRANSIT\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var metrics = new SimpleMeterRegistry();
        var registry = new OutboundClientRegistry(new OutboundProperties(), metrics);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var properties =
                    new ProviderProperties(
                            baseUrl,
                            "user",
                            "pass",
                            "sandbox",
                            "local-account",
                            "/rest/shipments",
                            Duration.ofSeconds(30),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(10),
                            10,
                            300,
                            Set.of("labels.example.com"));
            var context = new AggregatorContext(properties);
            var sandboxClient =
                    registry.registerClient(
                            properties.providerId(),
                            properties.baseUrl(),
                            builder -> {
                            });

            var rateApi =
                    new AggregatorRateApi(
                            sandboxClient,
                            context,
                            Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC));
            var shipmentApi = new AggregatorShipmentApi(sandboxClient, context);
            var labelApi = new AggregatorLabelApi(sandboxClient, context);

            assertThatThrownBy(
                            () ->
                                    rateApi.fetchRates(
                                            INTENT, OutboundDeadline.after(Duration.ofSeconds(3))))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            e -> assertThat(e.code()).isEqualTo("PROVIDER_INVALID_RESPONSE"));
            assertThat(output.getOut())
                    .contains(
                            "operation=rates",
                            "classification=PROTOCOL_ERROR",
                            "reason=PROTOCOL_ERROR",
                            "httpStatus=200");
            assertThat(
                            metrics.get("shipping.outbound.duration")
                                    .tag("operation", "rates")
                                    .tag("outcome", "failure")
                                    .timer()
                                    .count())
                    .isEqualTo(1);

            assertThatThrownBy(
                            () ->
                                    shipmentApi.create(
                                            new CreateSubmission(
                                                    "sandbox", "local-account", "quote-1")))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            e -> assertThat(e.code()).isEqualTo("SHIPMENT_OUTCOME_UNKNOWN"));

            assertThatThrownBy(
                            () ->
                                    labelApi.fetch(
                                            new LabelIdentity("sandbox", "local-account", "123")))
                    .isInstanceOfSatisfying(
                            ShippingException.class,
                            e -> assertThat(e.code()).isEqualTo("PROVIDER_UNEXPECTED_RESPONSE"));

            // A second independent provider (e.g. UPS tracking) registers its own client and slice
            // without touching aggregator slices.
            var upsClient =
                    registry.registerClient(
                            "ups",
                            baseUrl,
                            builder -> {
                            });
            var upsTrackEndpoint =
                    OutboundEndpoint.<Void, UpsTrackResponse>get(
                            "track",
                            "/ups/track/{trackingNumber}",
                            UpsTrackResponse.class);
            upsClient.register(upsTrackEndpoint);

            UpsTrackResponse tracked =
                    upsClient.invoke(
                            upsTrackEndpoint,
                            Map.of("trackingNumber", "1Z999"),
                            null,
                            OutboundDeadline.after(Duration.ofSeconds(3)));
            assertThat(tracked).isEqualTo(new UpsTrackResponse("1Z999", "IN_TRANSIT"));
        } finally {
            registry.close();
            server.stop(0);
        }
    }
}
