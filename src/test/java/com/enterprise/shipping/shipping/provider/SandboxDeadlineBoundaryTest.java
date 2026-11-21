package com.enterprise.shipping.shipping.provider;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.support.BookingFixtures;
import com.enterprise.shipping.support.ProviderFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class SandboxDeadlineBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"rates", "shipments", "labels"})
    void expiryAfterAdmissionBeforeSubscriptionStaysTimeout(
            String operation,
            CapturedOutput output) {
        try (var fixture = new ProviderFixture("http://127.0.0.1")) {
            var subscribed = new AtomicInteger();
            var failure = catchThrowableOfType(
                    () -> fixture.protection.await(
                            operation,
                            System.nanoTime() - 1,
                            Mono.fromSupplier(subscribed::incrementAndGet)),
                    ShippingException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
            assertThat(failure.category()).isEqualTo(ShippingException.Category.DEPENDENCY_TIMEOUT);
            assertThat(subscribed).hasValue(0);
            assertThat(output.getOut())
                    .doesNotContain("classification=PROTOCOL_ERROR", "httpStatus=0");
            assertThat(fixture.metrics.get("shipping.outbound.duration")
                    .tag("operation", operation).tag("outcome", "timeout").timer().count())
                    .isEqualTo(1);
            assertThat(fixture.metrics.get("shipping.outbound.inflight").gauge().value())
                    .isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rates"})
    void expiredCallerBudgetNeverReachesTheTransport(String operation) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/rates", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"availableRates\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var fixture = new ProviderFixture(
                "http://127.0.0.1:" + server.getAddress().getPort())) {
            var intent = BookingFixtures.command("q").shippingInput()
                    .dated(LocalDate.now());
            var failure = catchThrowableOfType(
                    () -> fixture.rates.fetchRates(intent, System.nanoTime() - 1),
                    ShippingException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }
}
