package com.enterprise.shipping.quote.application;

import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.RateComparisonPort;
import com.enterprise.shipping.quote.port.ShippingQuoteObserver;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, read-only provider query. No durable quote identity or execution lifecycle. */
@Transactional(propagation = Propagation.NEVER)
public class QuoteApplicationService {
    private final RateComparisonPort engine;
    private final Clock clock;
    private final Duration timeout;
    private final ZoneId zone;
    private final ShippingQuoteObserver observer;

    public QuoteApplicationService(
            RateComparisonPort engine,
            Clock clock,
            Duration timeout,
            ZoneId zone,
            ShippingQuoteObserver observer) {
        this.engine = engine;
        this.clock = clock;
        this.timeout = timeout;
        this.zone = zone;
        this.observer = observer;
    }

    public Result compare(ShippingIntent input) {
        // 1. Resolve the business date before crossing the provider boundary.
        LocalDate today = LocalDate.now(clock.withZone(zone));
        var intent = input.dated(input.shippingDate() == null ? today : input.shippingDate());
        if (!intent.hasCurrentShippingDate(today)) {
            throw ShippingException.invalidInput("INVALID_SHIPPING_DATE");
        }

        // 2. Keep provider waiting bounded without acquiring database resources.
        long started = System.nanoTime();
        var outcome = ShippingQuoteObserver.Outcome.FAILED;
        try {
            var quotes = engine.compare(intent, System.nanoTime() + timeout.toNanos()).stream()
                    .sorted(Comparator.comparingLong(ShippingQuote::totalMinor))
                    .map(QuoteView::from)
                    .toList();
            outcome = quotes.isEmpty() ? ShippingQuoteObserver.Outcome.NO_RATES
                    : ShippingQuoteObserver.Outcome.READY;
            return new Result(intent.shippingDate(), quotes);
        } finally {
            observer.record(outcome, System.nanoTime() - started);
        }
    }

    public record Result(LocalDate shippingDate, List<QuoteView> quotes) {}

    public record QuoteView(
            String providerId,
            String providerQuoteId,
            String carrierId,
            String carrierName,
            String serviceId,
            String serviceName,
            ShippingQuote.Money total,
            Integer transitDays,
            Instant expiresAt,
            String expirySource) {
        static QuoteView from(ShippingQuote quote) {
            return new QuoteView(
                    quote.providerId(), quote.providerQuoteId(), quote.carrierId(), quote.carrierName(),
                    quote.serviceId(), quote.serviceName(), quote.total(), quote.transitDays(),
                    quote.expiresAt() > 0 ? Instant.ofEpochMilli(quote.expiresAt()) : null,
                    quote.expiresAt() > 0 ? "LOCAL_ESTIMATE" : null);
        }
    }
}
