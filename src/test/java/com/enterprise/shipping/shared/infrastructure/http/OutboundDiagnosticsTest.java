package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.api.ShippingExceptionHandler;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.buffer.DataBufferLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class OutboundDiagnosticsTest {
    static Stream<Arguments> reasons() {
        return Stream.of(
                Arguments.of(new ConnectTimeoutException(), "CONNECT_TIMEOUT"),
                Arguments.of(ReadTimeoutException.INSTANCE, "RESPONSE_TIMEOUT"),
                Arguments.of(new TimeoutException(), "DEADLINE_EXCEEDED"),
                Arguments.of(new ConnectException(), "CONNECT_FAILED"),
                Arguments.of(new UnknownHostException(), "DNS_FAILED"),
                Arguments.of(new SSLException("secret"), "TLS_FAILED"),
                Arguments.of(new DataBufferLimitException("secret"), "RESPONSE_TOO_LARGE"),
                Arguments.of(
                        ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE"),
                        "INVALID_RESPONSE"),
                Arguments.of(
                        new OutboundAdmissionRejectedException(
                                OutboundAdmissionRejectedException.Reason.BULKHEAD_FULL),
                        "CAPACITY_REJECTED"),
                Arguments.of(
                        new OutboundAdmissionRejectedException(
                                OutboundAdmissionRejectedException.Reason.CIRCUIT_OPEN),
                        "CIRCUIT_OPEN"));
    }

    @ParameterizedTest
    @MethodSource("reasons")
    void classifiesTypedCauses(Throwable failure, String expected) {
        assertThat(OutboundDiagnostics.reason(failure)).isEqualTo(expected);
        assertThat(OutboundDiagnostics.reason(new CompletionException(failure)))
                .isEqualTo(expected);
    }

    static Stream<Throwable> timeoutReasons() {
        return Stream.of(
                new ConnectTimeoutException(),
                ReadTimeoutException.INSTANCE,
                new TimeoutException(),
                new java.net.SocketTimeoutException());
    }

    @ParameterizedTest
    @MethodSource("timeoutReasons")
    void timeoutSemanticsAgreeWithDiagnostics(Throwable failure) {
        var translated = OutboundFailures.failure(new CompletionException(failure));
        assertThat(translated.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(translated.category()).isEqualTo(ShippingException.Category.DEPENDENCY_TIMEOUT);
    }

    @Test
    void guardLogsTheRootCauseButDoesNotExposeDetailsInApiResponse(CapturedOutput output) {
        String secret = "password-and-private-address";
        var original = new CompletionException(new ConnectException(secret));
        try (var registry =
                new OutboundClientRegistry(new OutboundProperties(), new SimpleMeterRegistry())) {
            var guard = registry.registerGuard("test", Set.of("rates"));
            ShippingException failure =
                    catchThrowableOfType(
                            () ->
                                    guard.execute(
                                            "default",
                                            "rates",
                                            OutboundDeadline.after(Duration.ofSeconds(3)),
                                            deadline -> {
                                                throw original;
                                            }),
                            ShippingException.class);
            assertThat(failure.code()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(failure.getCause()).isSameAs(original);
            var enriched = failure.forQuoteRequest("request-1");
            assertThat(enriched.getCause()).isSameAs(original);
            var response = new ShippingExceptionHandler().shipping(enriched);
            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(response.getBody().toString())
                    .contains("PROVIDER_UNAVAILABLE")
                    .doesNotContain(secret, "ConnectException", "CONNECT_FAILED");
            assertThat(output.getOut())
                    .contains(
                            "dependency=test",
                            "operation=rates",
                            "reason=CONNECT_FAILED",
                            "rootCauseType=ConnectException",
                            "elapsedMs=")
                    .doesNotContain(secret);
        }
    }

    @Test
    void logsFilteredErrorResponseWithoutExposingItToTheClient(CapturedOutput output)
            throws Exception {
        String secret = "private-provider-response";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/rates",
                exchange -> {
                    byte[] bytes =
                            ("{\"code\":\"INVALID_PACKAGE\",\"message\":\"Package weight must be positive\",\"password\":\""
                                            + secret
                                            + "\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        MDC.put("requestId", "diagnostics-test-request");
        try (var registry =
                new OutboundClientRegistry(new OutboundProperties(), new SimpleMeterRegistry())) {
            var client =
                    registry.register(
                            "test",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            Set.of("rates"),
                            builder ->
                                    builder.defaultHeaders(
                                            headers ->
                                                    headers.setBasicAuth(
                                                            "private-user", "private-password")));
            var failure =
                    catchThrowableOfType(
                            () ->
                                    client.execute(
                                            "rates",
                                            OutboundDeadline.after(Duration.ofSeconds(3)),
                                            exchange -> {
                                                var response = exchange.get("/rates");
                                                assertThat(response.getStatusCode().value())
                                                        .isEqualTo(429);
                                                throw ShippingException.dependencyFailure(
                                                        "PROVIDER_UNAVAILABLE");
                                            }),
                            ShippingException.class);
            var frontend = new ShippingExceptionHandler().shipping(failure);
            assertThat(frontend.getBody().toString())
                    .doesNotContain("INVALID_PACKAGE", "Package weight", secret);
            assertThat(output.getOut())
                    .contains(
                            "dependency=test",
                            "operation=rates",
                            "httpStatus=429",
                            "elapsedMs=",
                            "requestId=diagnostics-test-request",
                            "INVALID_PACKAGE",
                            "Package weight must be positive")
                    .doesNotContain(
                            secret,
                            "private-user",
                            "private-password",
                            "/rates",
                            "httpStatus=null");
        } finally {
            MDC.remove("requestId");
            server.stop(0);
        }
    }

    @Test
    void sanitizesNestedErrorsAndLimitsResponseOutput() {
        String json =
                "{\"errors\":[{\"field\":\"parcel.weight\",\"message\":\"Invalid weight; contact a@example.com; token=private-token\"}],\"request\":{\"address\":\"private-address\"}}";
        assertThat(OutboundDiagnostics.safeResponse(json.getBytes(StandardCharsets.UTF_8)))
                .contains("parcel.weight", "Invalid weight", "[EMAIL]", "[REDACTED]")
                .doesNotContain("a@example.com", "private-token", "private-address");
        assertThat(
                        OutboundDiagnostics.safeResponse(
                                "Invalid weight; password=private-value; contact user@example.com"
                                        .getBytes(StandardCharsets.UTF_8)))
                .contains("Invalid weight", "[REDACTED]", "[EMAIL]")
                .doesNotContain("private-value", "user@example.com");
        assertThat(
                        OutboundDiagnostics.safeResponse(
                                "400 Bad Request: weight must be positive"
                                        .getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("400 Bad Request: weight must be positive");
        assertThat(
                        OutboundDiagnostics.safeResponse(
                                "<html>private-value</html>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("[OMITTED_NON_JSON_BODY]");
        assertThat(OutboundDiagnostics.safeResponse(null)).isEqualTo("[EMPTY_BODY]");
        assertThat(OutboundDiagnostics.safeResponse(new byte[17 * 1024]))
                .isEqualTo("[OMITTED_OVERSIZED_BODY]");
        String large =
                "{\"messages\":["
                        + String.join(
                                ",",
                                java.util.Collections.nCopies(10, "\"" + "x".repeat(600) + "\""))
                        + "]}";
        assertThat(OutboundDiagnostics.safeResponse(large.getBytes(StandardCharsets.UTF_8)))
                .endsWith("[TRUNCATED]")
                .hasSizeLessThan(2100);
    }

    @Test
    void retainsReceivedHttpStatusWhenBodyCannotBeRead(CapturedOutput output) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/rates",
                exchange -> {
                    byte[] body = "private-body".repeat(100).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        var properties = new OutboundProperties();
        properties.getDefaults().setMaxResponseBytes(64);
        try (var registry = new OutboundClientRegistry(properties, new SimpleMeterRegistry())) {
            var client =
                    registry.register(
                            "test",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            Set.of("rates"),
                            builder -> {
                            });
            assertThat(
                            catchThrowableOfType(
                                    () ->
                                            client.execute(
                                                    "rates",
                                                    OutboundDeadline.after(Duration.ofSeconds(3)),
                                                    exchange -> exchange.get("/rates")),
                                    ShippingException.class))
                    .isNotNull();
            assertThat(output.getOut())
                    .contains("httpStatus=400", "BODY_READ_FAILED:RESPONSE_TOO_LARGE")
                    .doesNotContain("httpStatus=null", "private-body");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesExpectedBranchesProtocolErrorsAndBodyReadFailuresWithoutOperationNameCoupling(
            CapturedOutput output) {
        OutboundDiagnostics.response(
                "sandbox",
                "custom-operation",
                404,
                System.nanoTime(),
                "req-expected-404",
                "{}".getBytes(StandardCharsets.UTF_8),
                null,
                OutboundDiagnostics.Classification.EXPECTED_BRANCH);
        assertThat(output.getOut()).doesNotContain("req-expected-404");

        // Expected status must never suppress a failed or cancelled body read.
        OutboundDiagnostics.response(
                "sandbox",
                "custom-operation",
                202,
                System.nanoTime(),
                "req-body-read-failed",
                null,
                "[BODY_READ_CANCELLED]",
                OutboundDiagnostics.Classification.EXPECTED_BRANCH);
        assertThat(output.getOut())
                .contains(
                        "req-body-read-failed",
                        "classification=BODY_READ_FAILURE",
                        "httpStatus=202",
                        "[BODY_READ_CANCELLED]");

        // Malformed 200 payload is logged as PROTOCOL_ERROR.
        OutboundDiagnostics.response(
                "sandbox",
                "custom-operation",
                200,
                System.nanoTime(),
                "req-protocol-error",
                "{\"message\":\"corrupted payload\"}".getBytes(StandardCharsets.UTF_8),
                null,
                OutboundDiagnostics.Classification.PROTOCOL_ERROR);
        assertThat(output.getOut())
                .contains(
                        "req-protocol-error",
                        "classification=PROTOCOL_ERROR",
                        "reason=PROTOCOL_ERROR",
                        "httpStatus=200",
                        "corrupted payload");
    }
}
