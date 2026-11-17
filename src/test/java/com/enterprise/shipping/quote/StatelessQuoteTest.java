package com.enterprise.shipping.quote;

import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.RateComparisonPort;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.support.BookingFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatelessQuoteTest {
    QuoteApplicationService service(RateComparisonPort provider) {
        return new QuoteApplicationService(provider, Clock.systemUTC(), Duration.ofSeconds(5),
                ZoneOffset.UTC, (outcome, elapsed) -> {
                });
    }

    @Test
    void repeatedQueriesAlwaysCallProviderAndReturnExactProviderIdentity() {
        var calls = new AtomicInteger();
        var service = service((input, deadline) -> {
            calls.incrementAndGet();
            assertThat(input.shippingDate()).isNotNull();
            assertThat(deadline).isGreaterThan(System.nanoTime());
            return List.of(new ShippingQuote("sandbox", "account", "Case-Sensitive/ID", "14", "Carrier",
                    "1", "Ground", 2735, "CAD", null, 0));
        });
        var input = BookingFixtures.command("q").shippingInput();
        var result = service.compare(input);
        service.compare(input);
        assertThat(calls).hasValue(2);
        assertThat(result.quotes().get(0).providerQuoteId()).isEqualTo("Case-Sensitive/ID");
        assertThat(result.quotes().get(0).total().amount()).isEqualTo("27.35");
        assertThat(result.quotes().get(0).expiresAt()).isNull();
    }

    @Test
    void failuresRemainFailuresAndEmptyQueriesAreNotDurableTasks() {
        var input = BookingFixtures.command("q").shippingInput();
        assertThat(service((intent, deadline) -> List.of()).compare(input).quotes()).isEmpty();
        assertThatThrownBy(() -> service((intent, deadline) -> {
            throw ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
        }).compare(input)).isInstanceOf(ShippingException.class);
        assertThatThrownBy(() -> service((intent, deadline) -> List.of())
                .compare(input.dated(java.time.LocalDate.of(2020, 1, 1))))
                .isInstanceOf(ShippingException.class);
    }
}
