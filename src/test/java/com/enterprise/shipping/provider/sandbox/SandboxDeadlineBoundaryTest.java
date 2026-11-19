package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.labels.LabelIdentity;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelOperation;
import com.enterprise.shipping.provider.sandbox.rates.SandboxRateOperation;
import com.enterprise.shipping.provider.sandbox.rates.dto.RateApiResponse;
import com.enterprise.shipping.provider.sandbox.shipments.CreateSubmission;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxCreateOperation;
import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import com.enterprise.shipping.shared.infrastructure.http.OutboundProperties;
import com.enterprise.shipping.support.BookingFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class SandboxDeadlineBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"rates", "shipments", "labels"})
    void expiryAfterAdmissionBeforeSubscriptionStaysTimeout(
            String operation,
            CapturedOutput output) {
        var metrics = new SimpleMeterRegistry();
        var actualGuard = new OutboundGuard("sandbox", new OutboundProperties().resolve("sandbox"),
                Set.of("rates", "shipments", "labels"), metrics, failure -> false);
        var delayedGuard = mock(OutboundGuard.class);
        // Actual admission, circuit and metrics execute normally; only the work's remaining
        // budget is exhausted, deterministically reproducing a scheduling/GC pause after admission.
        doAnswer(invocation -> {
            OutboundGuard.Work<?> work = invocation.getArgument(3);
            return actualGuard.execute(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), effective -> work.run(System.nanoTime() - 1));
        }).when(delayedGuard).execute(anyString(), anyString(), anyLong(), any());

        var subscribed = new AtomicInteger();
        var context = new ProviderAccountContext("sandbox", "account", Set.of());
        Runnable invoke = switch (operation) {
            case "rates" -> () -> new SandboxRateOperation(request -> response(RateApiResponse.empty(), subscribed),
                    delayedGuard, context, 10, 300, Clock.systemUTC()).fetchRates(
                            BookingFixtures.command("q").shippingInput().dated(LocalDate.now()),
                            OutboundDeadline.after(Duration.ofSeconds(10)));
            case "shipments" -> () -> new SandboxCreateOperation(request -> response(
                    new CreateShipmentResponse("123", List.of(), null), subscribed), delayedGuard, context,
                    Duration.ofSeconds(10)).create(new CreateSubmission("sandbox", "account", "q"));
            case "labels" -> () -> new SandboxLabelOperation(id -> response(new byte[0], subscribed),
                    delayedGuard, context, Duration.ofSeconds(10)).fetch(new LabelIdentity("sandbox", "account", "123"));
            default -> throw new IllegalArgumentException(operation);
        };

        var failure = catchThrowableOfType(invoke::run, ShippingException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(failure.category()).isEqualTo(ShippingException.Category.DEPENDENCY_TIMEOUT);
        assertThat(subscribed).hasValue(0);
        assertThat(output.getOut()).doesNotContain("classification=PROTOCOL_ERROR", "httpStatus=0");
        assertThat(metrics.get("shipping.outbound.duration").tag("operation", operation)
                .tag("outcome", "timeout").timer().count()).isEqualTo(1);
        assertThat(metrics.get("shipping.outbound.inflight").gauge().value()).isZero();
    }

    private static <T> Mono<ResponseEntity<T>> response(T body, AtomicInteger subscribed) {
        return Mono.fromSupplier(() -> {
            subscribed.incrementAndGet();
            return ResponseEntity.ok(body);
        });
    }
}
