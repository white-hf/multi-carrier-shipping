package com.enterprise.shipping.quote.application;

import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.quote.port.*;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.shared.api.WorkflowException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/**
 * Orchestrates multi-carrier rate querying and quote selection checks under the 'workflow' profile.
 * 
 * Enforces transaction-safety rules where long HTTP/provider waits are never executed 
 * inside transactional database locks.
 */
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class QuoteApplicationService implements QuoteSelectionPort {

    private final QuoteWorkflowObserver observer;
    private final QuoteStore store;
    private final RateComparisonPort engine;
    private final Clock clock;
    private final Duration timeout;
    private final ZoneId zone;

    public QuoteApplicationService(
            QuoteStore store,
            RateComparisonPort engine,
            Clock clock,
            Duration timeout,
            ZoneId zone,
            QuoteWorkflowObserver observer) {
        this.store = store;
        this.engine = engine;
        this.clock = clock;
        this.timeout = timeout;
        this.zone = zone;
        this.observer = observer;
    }

    /**
     * Initializes a rating request snapshot, fetches quotes from the engine, 
     * and persists the resulting offers associated with the original request.
     */
    public Result create(String userId, ShippingIntent input) {
        long start = System.nanoTime();
        QuoteWorkflowObserver.Outcome outcome = QuoteWorkflowObserver.Outcome.FAILED;

        try {
            Result result = execute(userId, input);
            outcome = result.quotes().isEmpty() 
                    ? QuoteWorkflowObserver.Outcome.NO_RATES 
                    : QuoteWorkflowObserver.Outcome.READY;
            return result;
        } finally {
            observer.record(outcome, System.nanoTime() - start);
        }
    }

    private Result execute(String userId, ShippingIntent input) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        ShippingIntent intent = input.dated(input.shippingDate() == null ? today : input.shippingDate());

        if (intent.shippingDate().isBefore(today)) {
            throw new WorkflowException("INVALID_SHIPPING_DATE", HttpStatus.BAD_REQUEST);
        }

        String id = TimeOrderedIds.next();
        store.begin(userId, id, intent, clock.millis());

        List<ShippingQuote> quotes;
        try {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            quotes = engine.compare(intent, deadlineNanos).stream()
                    .map(q -> new ShippingQuote(
                            TimeOrderedIds.next(), id, q.providerId(), q.accountAlias(),
                            q.providerQuoteId(), q.carrierId(), q.carrierName(), q.serviceId(),
                            q.serviceName(), q.totalMinor(), q.currency(), q.transitDays(), q.expiresAt()
                    ))
                    .sorted(Comparator.comparingLong(ShippingQuote::totalMinor).thenComparing(ShippingQuote::quoteId))
                    .toList();
        } catch (RuntimeException failure) {
            store.fail(userId, id, clock.millis());
            throw failure;
        }

        store.complete(userId, id, quotes, clock.millis());
        return new Result(id, quotes.isEmpty() ? "NO_RATES" : "READY", intent.shippingDate(), quotes.stream().map(QuoteView::from).toList());
    }

    @Override
    public Selection resolve(String userId, String quoteId) {
        var q = store.find(userId, quoteId)
                .orElseThrow(WorkflowException::missing);

        if (q.providerQuoteId() == null || q.providerQuoteId().isBlank()) {
            throw new WorkflowException("QUOTE_NOT_BOOKABLE", HttpStatus.CONFLICT);
        }

        // Add a 5-second buffer margin before quote expiration to prevent race conditions during submission
        if (q.expiresAt() <= clock.millis() + 5000) {
            throw new WorkflowException("QUOTE_EXPIRED", HttpStatus.CONFLICT);
        }

        return new Selection(
                q.requestId(), q.quoteId(), q.providerId(), q.accountAlias(),
                q.providerQuoteId(), q.carrierId(), q.serviceId(), q.totalMinor(),
                q.currency(), q.expiresAt()
        );
    }

    public record Result(String requestId, String state, LocalDate shippingDate, List<QuoteView> quotes) {}

    public record QuoteView(
            String quoteId,
            String carrierId,
            String carrierName,
            String serviceId,
            String serviceName,
            ShippingQuote.Money total,
            Integer transitDays,
            Instant expiresAt) {
        
        static QuoteView from(ShippingQuote q) {
            return new QuoteView(
                    q.quoteId(), q.carrierId(), q.carrierName(), q.serviceId(), q.serviceName(),
                    q.total(), q.transitDays(), Instant.ofEpochMilli(q.expiresAt())
            );
        }
    }
}