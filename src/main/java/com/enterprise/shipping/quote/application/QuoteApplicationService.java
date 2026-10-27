package com.enterprise.shipping.quote.application;

import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.quote.domain.RateRequest;
import com.enterprise.shipping.quote.domain.ShippingIntentFingerprint;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.QuoteStore;
import com.enterprise.shipping.quote.port.RateComparisonPort;
import com.enterprise.shipping.quote.port.ShippingQuoteObserver;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Orchestrates idempotent shipment-request persistence, retryable multi-carrier rate execution,
 * and quote selection checks.
 *
 * Enforces transaction-safety rules where long HTTP/provider waits are never executed
 * inside transactional database locks.
 *
 * API tradeoff: creation currently saves the input snapshot and retrieves rates in one
 * client call. A future split could save the request first, then fetch rates by request ID.
 * This would clarify responsibilities and let clients retry rating independently, but adds
 * an HTTP round trip, a SAVED/not-started state, an execution-claim update, and frontend
 * coordination for abandoned or interrupted steps. Rating would still need deadlines,
 * version fencing and atomic quote persistence. Keep the combined API for the current demo;
 * revisit the split when independent request saving becomes a product requirement.
 */
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class QuoteApplicationService implements QuoteSelectionPort {

    private static final Logger log = LoggerFactory.getLogger(QuoteApplicationService.class);
    private static final Duration PERSISTENCE_GRACE = Duration.ofSeconds(10);
    private static final Duration REUSE_MARGIN = Duration.ofSeconds(15);

    private final ShippingQuoteObserver observer;
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
            ShippingQuoteObserver observer) {
        this.store = store;
        this.engine = engine;
        this.clock = clock;
        this.timeout = timeout;
        this.zone = zone;
        this.observer = observer;
    }

    /**
     * Saving customer intent is idempotent; retrying a read-only rate query is a separate decision.
     * The backend keeps the business snapshot independently of browser drafts and the provider.
     */
    public Submission create(String userId, String key, ShippingIntent input) {
        // 1. Validate the idempotency key format
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw ShippingException.badRequest("INVALID_IDEMPOTENCY_KEY");
        }

        // 2. Insert a new request claim when the key has not been seen for this user
        var existing = store.byKey(userId, key);
        if (existing.isEmpty()) {
            var intent = dated(input, LocalDate.now(clock.withZone(zone)));
            requireCurrentDate(intent, null);

            var claim = new RateRequest(
                    userId,
                    TimeOrderedIds.next(),
                    key,
                    ShippingIntentFingerprint.of(intent),
                    intent,
                    RateRequest.State.RATING,
                    0,
                    deadline()
            );
            if (store.insert(claim, clock.millis())) {
                return execute(claim, 201);
            }
            existing = store.byKey(userId, key);
        }

        // 3. Verify that the replayed request has identical canonical content
        var row = existing.orElseThrow(
                () -> ShippingException.serviceUnavailable("STORE_UNAVAILABLE")
        );
        // Reuse the stored default date so crossing midnight does not turn a matching replay into a conflict
        String candidateFingerprint = ShippingIntentFingerprint.of(dated(input, row.intent().shippingDate()));
        if (!row.matches(candidateFingerprint)) {
            throw failure(row, "IDEMPOTENCY_CONFLICT", 409);
        }

        // 4. Re-claim and re-execute only when prior execution failed, timed out, or all quotes expired
        var offers = store.quotes(userId, row.requestId());
        boolean expired = expired(row, offers);
        if (row.mayClaim(clock.millis(), expired)) {
            requireCurrentDate(row.intent(), row);
            var claimed = row.claimed(clock.millis(), deadline(), expired);
            if (store.claim(row, claimed, clock.millis())) {
                return execute(claimed, 200);
            }
        }

        return submission(current(userId, row.requestId()), 200);
    }

    /**
     * Reads current rate-request state and quotes without invoking the external provider.
     */
    public Result get(String userId, String id) {
        return view(current(userId, id));
    }

    private RateRequest current(String userId, String id) {
        var row = store.request(userId, id)
                .orElseThrow(ShippingException::missing);

        if (row.overdue(clock.millis())) {
            if (store.fail(row, clock.millis())) {
                log.warn("Rate execution expired requestId={} version={}", id, row.version());
            }
            row = store.request(userId, id)
                    .orElseThrow(ShippingException::missing);
        }
        return row;
    }

    private Submission execute(RateRequest claim, int completedStatusCode) {
        long start = System.nanoTime();
        ShippingQuoteObserver.Outcome outcome = ShippingQuoteObserver.Outcome.FAILED;
        log.info("Rate execution claimed requestId={} version={}", claim.requestId(), claim.version());

        try {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            var offers = engine.compare(claim.intent(), deadlineNanos).stream()
                    .map(q -> new ShippingQuote(
                            TimeOrderedIds.next(),
                            claim.requestId(),
                            q.providerId(),
                            q.accountAlias(),
                            q.providerQuoteId(),
                            q.carrierId(),
                            q.carrierName(),
                            q.serviceId(),
                            q.serviceName(),
                            q.totalMinor(),
                            q.currency(),
                            q.transitDays(),
                            q.expiresAt()
                    ))
                    .sorted(Comparator.comparingLong(ShippingQuote::totalMinor).thenComparing(ShippingQuote::quoteId))
                    .toList();

            boolean applied = store.complete(claim, offers, clock.millis());
            if (!applied) {
                log.info("Stale rate completion discarded requestId={} version={}", claim.requestId(), claim.version());
            }

            var result = submission(
                    current(claim.userId(), claim.requestId()),
                    applied ? completedStatusCode : 200
            );
            if ("READY".equals(result.result().state())) {
                outcome = ShippingQuoteObserver.Outcome.READY;
            } else if ("NO_RATES".equals(result.result().state())) {
                outcome = ShippingQuoteObserver.Outcome.NO_RATES;
            }
            return result;
        } catch (RuntimeException error) {
            String code = error instanceof ShippingException shipping
                    ? shipping.code()
                    : error instanceof DataAccessException ? "STORE_UNAVAILABLE" : "PROVIDER_UNAVAILABLE";
            int statusCode = error instanceof ShippingException shipping
                    ? shipping.statusCode()
                    : error instanceof DataAccessException ? 503 : 502;

            log.warn(
                    "Rate execution failed requestId={} version={} code={} type={}",
                    claim.requestId(),
                    claim.version(),
                    code,
                    error.getClass().getSimpleName()
            );

            try {
                store.fail(claim, clock.millis());
            } catch (RuntimeException storageFailure) {
                log.warn(
                        "Rate failure state not saved requestId={} version={} type={}",
                        claim.requestId(),
                        claim.version(),
                        storageFailure.getClass().getSimpleName()
                );
            }
            throw failure(claim, code, statusCode);
        } finally {
            observer.record(outcome, System.nanoTime() - start);
        }
    }

    private Submission submission(RateRequest row, int completedStatusCode) {
        var result = view(row);
        int statusCode = row.state() == RateRequest.State.RATING
                ? 202
                : completedStatusCode;
        return new Submission(result, statusCode);
    }

    private Result view(RateRequest row) {
        var offers = row.state() == RateRequest.State.READY
                ? store.quotes(row.userId(), row.requestId())
                : List.<ShippingQuote>of();
        boolean expired = expired(row, offers);
        String state = expired ? "QUOTES_EXPIRED" : row.state().name();
        var usable = offers.stream()
                .filter(q -> q.expiresAt() > clock.millis() + REUSE_MARGIN.toMillis())
                .toList();

        return new Result(
                row.requestId(),
                state,
                row.intent().shippingDate(),
                usable.stream().map(QuoteView::from).toList()
        );
    }

    private boolean expired(RateRequest row, List<ShippingQuote> offers) {
        return row.state() == RateRequest.State.READY
                && offers.stream().noneMatch(q -> q.expiresAt() > clock.millis() + REUSE_MARGIN.toMillis());
    }

    private long deadline() {
        return clock.millis() + timeout.toMillis() + PERSISTENCE_GRACE.toMillis();
    }

    private ShippingIntent dated(ShippingIntent input, LocalDate fallback) {
        return input.dated(input.shippingDate() == null ? fallback : input.shippingDate());
    }

    private void requireCurrentDate(ShippingIntent intent, RateRequest existing) {
        if (intent.shippingDate().isBefore(LocalDate.now(clock.withZone(zone)))) {
            if (existing != null) {
                throw failure(existing, "REQUEST_DATE_EXPIRED", 409);
            }
            throw ShippingException.badRequest("INVALID_SHIPPING_DATE");
        }
    }

    private ShippingException failure(RateRequest row, String code, int statusCode) {
        return new ShippingException(code, statusCode)
                .forOperation(row.requestId(), "/api/quotes/" + row.requestId());
    }

    @Override
    public Selection resolve(String userId, String quoteId) {
        var q = store.find(userId, quoteId)
                .orElseThrow(ShippingException::missing);

        // Fetch only the request state column rather than deserializing the full address/parcel snapshot
        var requestState = store.requestState(userId, q.requestId())
                .orElseThrow(ShippingException::missing);
        if (requestState != RateRequest.State.READY) {
            throw ShippingException.conflict("QUOTE_NOT_BOOKABLE");
        }
        switch (q.eligibility(clock.millis(), 5000)) {
            case NOT_BOOKABLE -> throw ShippingException.conflict("QUOTE_NOT_BOOKABLE");
            case EXPIRED -> throw ShippingException.conflict("QUOTE_EXPIRED");
            case BOOKABLE -> { /* The exact accepted quote is returned below. */ }
        }

        return new Selection(
                q.requestId(),
                q.quoteId(),
                q.providerId(),
                q.accountAlias(),
                q.providerQuoteId(),
                q.carrierId(),
                q.serviceId(),
                q.totalMinor(),
                q.currency(),
                q.expiresAt()
        );
    }

    public record Submission(Result result, int statusCode) {}

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
                    q.quoteId(),
                    q.carrierId(),
                    q.carrierName(),
                    q.serviceId(),
                    q.serviceName(),
                    q.total(),
                    q.transitDays(),
                    Instant.ofEpochMilli(q.expiresAt())
            );
        }
    }
}
