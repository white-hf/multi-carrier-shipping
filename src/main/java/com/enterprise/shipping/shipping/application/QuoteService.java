package com.enterprise.shipping.shipping.application;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import com.enterprise.shipping.shipping.model.ShippingQuote;
import com.enterprise.shipping.shipping.port.RateProvider;
import io.micrometer.core.instrument.MeterRegistry;
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
public class QuoteService {
    private final RateProvider provider;
    private final Clock clock;
    private final Duration timeout;
    private final ZoneId zone;
    private final MeterRegistry metrics;

    public QuoteService(
            RateProvider provider,
            Clock clock,
            Duration timeout,
            ZoneId zone,
            MeterRegistry metrics) {
        this.provider = provider;
        this.clock = clock;
        this.timeout = timeout;
        this.zone = zone;
        this.metrics = metrics;
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
        String outcome = "FAILED";
        try {
            var quotes = provider.fetchRates(intent, System.nanoTime() + timeout.toNanos())
                    .stream()
                    .sorted(Comparator.comparingLong(ShippingQuote::totalMinor))
                    .map(QuoteView::from)
                    .toList();
            outcome = quotes.isEmpty() ? "NO_RATES" : "READY";
            return new Result(intent.shippingDate(), quotes);
        } finally {
            SafeMetrics.record(metrics, "shipping.quote", System.nanoTime() - started, "outcome", outcome);
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
                    quote.providerId(),
                    quote.providerQuoteId(),
                    quote.carrierId(),
                    quote.carrierName(),
                    quote.serviceId(),
                    quote.serviceName(),
                    quote.total(),
                    quote.transitDays(),
                    quote.expiresAt() > 0 ? Instant.ofEpochMilli(quote.expiresAt()) : null,
                    quote.expiresAt() > 0 ? "LOCAL_ESTIMATE" : null);
        }
    }
}
