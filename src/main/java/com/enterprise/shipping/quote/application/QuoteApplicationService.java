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
 * Store completion returns the committed request/offers without normal-path rereads.
 * A response describes that snapshot, not all later concurrent changes. Conflicts alone
 * reload state. Provider, persistence and projection failures have separate boundaries;
 * rendering a committed result must never mark that execution as failed.
 *
 * API tradeoff: creation currently saves the input snapshot and retrieves rates in one
 * client call. A future split could save the request first, then fetch rates by request ID.
 * This would clarify responsibilities and let clients retry rating independently, but adds
 * an HTTP round trip, a SAVED/not-started state, an execution-claim update, and frontend
 * coordination for abandoned or interrupted steps. Rating would still need deadlines,
 * version fencing and atomic quote persistence. Keep the combined API for the current demo;
 * revisit the split when independent request saving becomes a product requirement.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class QuoteApplicationService implements QuoteSelectionPort {

    private static final Logger log = LoggerFactory.getLogger(QuoteApplicationService.class);
    private static final Duration PERSISTENCE_GRACE = Duration.ofSeconds(10);
    private static final Duration REUSE_MARGIN = Duration.ofSeconds(15);
    private static final Duration BOOKING_MARGIN = Duration.ofSeconds(5);

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
            throw ShippingException.invalidInput("INVALID_IDEMPOTENCY_KEY");
        }

        // 2. Insert a new request claim when the key has not been seen for this user
        var existing = store.byKey(userId, key);
        if (existing.isEmpty()) {
            long now = clock.millis();
            var intent = dated(input, businessDate(now));
            requireCurrentDate(intent, null, now);

            var claim =
                    new RateRequest(
                            userId,
                            TimeOrderedIds.next(),
                            key,
                            ShippingIntentFingerprint.of(intent),
                            intent,
                            RateRequest.State.RATING,
                            0,
                            deadline(now));
            if (store.insert(claim, now)) {
                return execute(claim, Disposition.CREATED);
            }
            // The winner owns execution. Read its identity/result, but never claim again here.
            var winner =
                    store.byKey(userId, key).orElseThrow(ShippingException::storageUnavailable);
            requireMatching(winner, input);
            return submission(winner, offersFor(winner), Disposition.EXISTING, clock.millis());
        }

        // 3. Verify that the replayed request has identical canonical content
        var row = existing.orElseThrow(() -> ShippingException.storageUnavailable());
        requireMatching(row, input);

        // 4. Only READY requests need stored offers to decide whether rating should refresh.
        long now = clock.millis();
        requireCurrentDate(row.intent(), row, now);
        var offers = offersFor(row);
        boolean expired = expired(row, offers, now);
        if (row.mayClaim(now, expired)) {
            var claimed = row.claimed(now, deadline(now), expired);
            if (store.claim(row, claimed, now)) {
                return execute(claimed, Disposition.EXISTING);
            }
            // A failed fence does not prove another execution is still running. Return only
            // the known identity; the client can inspect current state without redispatching.
            return new Submission(
                    new Result(
                            row.requestId(), "CONTENDED", row.intent().shippingDate(), List.of()),
                    Disposition.PENDING);
        }
        return submission(row, offers, Disposition.EXISTING, clock.millis());
    }

    private void requireMatching(RateRequest row, ShippingIntent input) {
        // Retain the saved default date across midnight; an explicit changed date still conflicts.
        String fingerprint =
                ShippingIntentFingerprint.of(dated(input, row.intent().shippingDate()));
        if (!row.matches(fingerprint)) {
            throw ShippingException.conflict("IDEMPOTENCY_CONFLICT")
                    .forQuoteRequest(row.requestId());
        }
    }

    /** Reads current state without invoking the external provider. */
    public Result get(String userId, String id) {
        var row = current(userId, id);
        return view(row, offersFor(row), clock.millis());
    }

    private RateRequest current(String userId, String id) {
        return current(store.request(userId, id).orElseThrow(ShippingException::missing));
    }

    private RateRequest current(RateRequest row) {
        long now = clock.millis();
        if (!row.overdue(now)) {
            return row;
        }
        if (store.fail(row, now)) {
            log.warn(
                    "Rate execution expired requestId={} version={}",
                    row.requestId(),
                    row.version());
            return row.failed();
        }
        return store.request(row.userId(), row.requestId()).orElseThrow(ShippingException::missing);
    }

    private Submission execute(RateRequest claim, Disposition completedDisposition) {
        long start = System.nanoTime();
        ShippingQuoteObserver.Outcome outcome = ShippingQuoteObserver.Outcome.FAILED;
        log.info(
                "Rate execution claimed requestId={} version={}",
                claim.requestId(),
                claim.version());
        try {
            // 1. Fetch and atomically persist, preserving newer executions on a lost fence.
            var completion = fetchAndComplete(claim);
            var row = completion.request();
            var offers = completion.quotes();
            if (completion.outcome() == QuoteStore.Completion.Outcome.EXPIRED) {
                // The store returns only after commit, so warn only for committed self-expiry.
                log.warn(
                        "Rate execution expired requestId={} version={}",
                        claim.requestId(),
                        claim.version());
            } else if (completion.outcome() == QuoteStore.Completion.Outcome.STALE) {
                log.info(
                        "Stale rate completion discarded requestId={} version={}",
                        claim.requestId(),
                        claim.version());
            }

            // 2. Projection is pure and outside failure-state updates. Use one observation time.
            long now = clock.millis();
            var result =
                    submission(
                            row,
                            offers,
                            completion.applied() ? completedDisposition : Disposition.EXISTING,
                            now);
            outcome = observedOutcome(row, offers, now);
            return result;
        } finally {
            observer.record(outcome, System.nanoTime() - start);
        }
    }

    private QuoteStore.Completion fetchAndComplete(RateRequest claim) {
        List<ShippingQuote> returned;
        try {
            returned = engine.compare(claim.intent(), System.nanoTime() + timeout.toNanos());
        } catch (RuntimeException error) {
            var failure =
                    error instanceof ShippingException shipping
                            ? shipping
                            : ShippingException.dependencyFailure("PROVIDER_UNAVAILABLE");
            return failedExecution(claim, failure, error);
        }

        // Local identity/projection errors are not reclassified as provider outages.
        var offers =
                returned.stream()
                        .map(q -> identified(q, claim.requestId()))
                        .sorted(
                                Comparator.comparingLong(ShippingQuote::totalMinor)
                                        .thenComparing(ShippingQuote::quoteId))
                        .toList();
        try {
            return store.complete(claim, offers, clock.millis());
        } catch (DataAccessException error) {
            return failedExecution(claim, ShippingException.storageUnavailable(), error);
        }
    }

    private QuoteStore.Completion failedExecution(
            RateRequest claim, ShippingException failure, RuntimeException cause) {
        log.warn(
                "Rate execution failed requestId={} version={} code={} type={}",
                claim.requestId(),
                claim.version(),
                failure.code(),
                cause.getClass().getSimpleName());
        try {
            if (!store.fail(claim, clock.millis())) {
                // An old provider failure owns only its original fence. Inspect a newer
                // execution without applying status-query expiry repair to it.
                var latest =
                        store.request(claim.userId(), claim.requestId())
                                .orElseThrow(ShippingException::missing);
                if (latest.version() > claim.version()
                        || latest.state() == RateRequest.State.READY
                        || latest.state() == RateRequest.State.NO_RATES) {
                    return new QuoteStore.Completion(
                            latest, offersFor(latest), QuoteStore.Completion.Outcome.STALE);
                }
            }
        } catch (RuntimeException storageFailure) {
            log.warn(
                    "Rate failure state not saved requestId={} version={} type={}",
                    claim.requestId(),
                    claim.version(),
                    storageFailure.getClass().getSimpleName());
        }
        throw failure.forQuoteRequest(claim.requestId());
    }

    private ShippingQuote identified(ShippingQuote q, String requestId) {
        return new ShippingQuote(
                TimeOrderedIds.next(),
                requestId,
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
                q.expiresAt());
    }

    private List<ShippingQuote> offersFor(RateRequest row) {
        return row.state() == RateRequest.State.READY
                ? store.quotes(row.userId(), row.requestId())
                : List.of();
    }

    private Submission submission(
            RateRequest row,
            List<ShippingQuote> offers,
            Disposition completedDisposition,
            long now) {
        return new Submission(
                view(row, offers, now),
                row.state() == RateRequest.State.RATING
                        ? Disposition.PENDING
                        : completedDisposition);
    }

    private Result view(RateRequest row, List<ShippingQuote> offers, long now) {
        boolean expired = expired(row, offers, now);
        var usable =
                expired
                        ? List.<ShippingQuote>of()
                        : offers.stream()
                                .filter(q -> q.reusable(now, REUSE_MARGIN.toMillis()))
                                .toList();
        return new Result(
                row.requestId(),
                expired ? "QUOTES_EXPIRED" : row.state().name(),
                row.intent().shippingDate(),
                usable.stream().map(QuoteView::from).toList());
    }

    private ShippingQuoteObserver.Outcome observedOutcome(
            RateRequest row, List<ShippingQuote> offers, long now) {
        return switch (row.state()) {
            case READY ->
                    expired(row, offers, now)
                            ? ShippingQuoteObserver.Outcome.FAILED
                            : ShippingQuoteObserver.Outcome.READY;
            case NO_RATES -> ShippingQuoteObserver.Outcome.NO_RATES;
            default -> ShippingQuoteObserver.Outcome.FAILED;
        };
    }

    private boolean expired(RateRequest row, List<ShippingQuote> offers, long now) {
        return row.state() == RateRequest.State.READY
                && (!row.intent().hasCurrentShippingDate(businessDate(now))
                        || offers.stream()
                                .noneMatch(q -> q.reusable(now, REUSE_MARGIN.toMillis())));
    }

    private long deadline(long now) {
        return now + timeout.toMillis() + PERSISTENCE_GRACE.toMillis();
    }

    private LocalDate businessDate(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    }

    private ShippingIntent dated(ShippingIntent input, LocalDate fallback) {
        return input.dated(input.shippingDate() == null ? fallback : input.shippingDate());
    }

    private void requireCurrentDate(ShippingIntent intent, RateRequest existing, long now) {
        if (!intent.hasCurrentShippingDate(businessDate(now))) {
            if (existing != null) {
                throw ShippingException.conflict("REQUEST_DATE_EXPIRED")
                        .forQuoteRequest(existing.requestId());
            }
            throw ShippingException.invalidInput("INVALID_SHIPPING_DATE");
        }
    }

    @Override
    public Selection resolve(String userId, String quoteId) {
        var q = store.find(userId, quoteId).orElseThrow(ShippingException::missing);

        var request = store.request(userId, q.requestId()).orElseThrow(ShippingException::missing);
        switch (request.bookingEligibility(businessDate(clock.millis()))) {
            case DATE_EXPIRED ->
                    throw ShippingException.conflict("REQUEST_DATE_EXPIRED")
                            .forQuoteRequest(request.requestId());
            case NOT_READY -> throw ShippingException.conflict("QUOTE_NOT_BOOKABLE");
            case ELIGIBLE -> {
                /* Offer-specific rules are checked by the quote below. */

            }
        }
        switch (q.eligibility(clock.millis(), BOOKING_MARGIN.toMillis())) {
            case NOT_BOOKABLE -> throw ShippingException.conflict("QUOTE_NOT_BOOKABLE");
            case EXPIRED -> throw ShippingException.conflict("QUOTE_EXPIRED");
            case BOOKABLE -> {
                /* The exact accepted quote is returned below. */

            }
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
                q.expiresAt());
    }

    public enum Disposition {
        CREATED,
        EXISTING,
        PENDING
    }

    public record Submission(Result result, Disposition disposition) {}

    public record Result(
            String requestId,
            String state,
            LocalDate shippingDate,
            List<QuoteView> quotes) {}

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
                    Instant.ofEpochMilli(q.expiresAt()));
        }
    }
}
