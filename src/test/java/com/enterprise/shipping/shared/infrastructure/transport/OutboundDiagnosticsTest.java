package com.enterprise.shipping.shared.infrastructure.transport;

import com.enterprise.shipping.shared.domain.ShippingException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.buffer.DataBufferLimitException;

import static org.assertj.core.api.Assertions.assertThat;

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
                        BulkheadFullException.createBulkheadFullException(
                                Bulkhead.ofDefaults("test")),
                        "CAPACITY_REJECTED"),
                Arguments.of(
                        CallNotPermittedException.createCallNotPermittedException(
                                CircuitBreaker.ofDefaults("test")),
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
        var translated = OutboundDiagnostics.failure(new CompletionException(failure));
        assertThat(translated.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(translated.category()).isEqualTo(ShippingException.Category.DEPENDENCY_TIMEOUT);
    }

    @Test
    void sanitizesNestedErrorsAndBoundsResponseOutput() {
        String json =
                "{\"errors\":[{\"field\":\"parcel.weight\",\"message\":\"Invalid weight; contact a@example.com; token=private-token\"}],\"request\":{\"address\":\"private-address\"}}";
        assertThat(OutboundDiagnostics.safeResponse(json.getBytes(StandardCharsets.UTF_8)))
                .contains("parcel.weight", "Invalid weight", "[EMAIL]", "[REDACTED]")
                .doesNotContain("a@example.com", "private-token", "private-address");
        assertThat(OutboundDiagnostics.safeResponse(
                        "Invalid weight; password=private-value; contact user@example.com"
                                .getBytes(StandardCharsets.UTF_8)))
                .contains("Invalid weight", "[REDACTED]", "[EMAIL]")
                .doesNotContain("private-value", "user@example.com");
        assertThat(OutboundDiagnostics.safeResponse(null)).isEqualTo("[EMPTY_BODY]");
        assertThat(OutboundDiagnostics.safeResponse(new byte[17 * 1024]))
                .isEqualTo("[OMITTED_OVERSIZED_BODY]");
        assertThat(OutboundDiagnostics.safeResponse(
                        "<html>private-value</html>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("[OMITTED_NON_JSON_BODY]");
    }

    @Test
    void retainsStatusButRedactsBodyForFailureLogs() {
        assertThat(OutboundDiagnostics.reason(new IllegalStateException("secret")))
                .isEqualTo("UNCLASSIFIED_FAILURE");
    }
}
