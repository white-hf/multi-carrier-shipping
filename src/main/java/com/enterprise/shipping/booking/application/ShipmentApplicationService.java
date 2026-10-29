package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.domain.RejectionReason;
import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.booking.port.ShipmentNotDispatchedException;
import com.enterprise.shipping.booking.port.ShipmentStore;
import com.enterprise.shipping.booking.port.ShippingBookingObserver;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates shipment booking creations and replays under strict idempotency.
 *
 * Enforces a claim-before-call pattern where a unique database claim is committed
 * prior to making any external HTTP call. This safeguards against duplicate submissions
 * and handles ambiguous external outcomes with eventual-consistency recovery states.
 *
 * Keep orchestration here, business transitions in ShipmentBooking, and fenced completion
 * in ShipmentStore. A successful completion returns its committed snapshot without another
 * SELECT; conflicts alone require rereading. This is an as-of-commit response, not a guarantee
 * of observing later label/recovery updates. Unknown external outcomes still require recovery.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class ShipmentApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentApplicationService.class);
    private static final Duration PERSISTENCE_GRACE = Duration.ofSeconds(10);

    private final ShippingBookingObserver observer;
    private final ShipmentStore store;
    private final QuoteSelectionPort quotes;
    private final ShipmentProviderPort provider;
    private final LabelProviderPort labels;
    private final Clock clock;
    private final Duration timeout;

    public ShipmentApplicationService(
            ShipmentStore store,
            QuoteSelectionPort quotes,
            ShipmentProviderPort provider,
            LabelProviderPort labels,
            Clock clock,
            Duration timeout,
            ShippingBookingObserver observer) {
        this.store = store;
        this.quotes = quotes;
        this.provider = provider;
        this.labels = labels;
        this.clock = clock;
        this.timeout = timeout;
        this.observer = observer;
    }

    /**
     * Attempts to book a shipment under a unique user-scoped idempotency key.
     * Replays prior attempts identically and prevents concurrent duplicate dispatches.
     */
    public Submission create(String user, String key, String quoteId) {
        // 1. Enforce strict syntactic format on the incoming idempotency key
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw ShippingException.invalidInput("INVALID_IDEMPOTENCY_KEY");
        }

        // 2. Perform a fast read optimized for common replay cases
        String hash = fingerprint(quoteId);
        Optional<ShipmentStore.Record> prior = store.byKey(user, key);
        if (prior.isPresent()) {
            return replay(prior.get(), hash);
        }

        // 3. Resolve and validate the quote selection owned by this user
        var selection = quotes.resolve(user, quoteId);
        String id = TimeOrderedIds.next();
        long now = clock.millis();

        // 4. Commit a unique booking claim in its own short database transaction.
        // If a concurrent thread or alternative key has already submitted this request_id,
        // the unique index constraints will abort the insertion, protecting against race
        // conditions.
        var claim =
                ShipmentStore.Record.pending(
                        user, id, key, hash, selection, submissionDeadline(now));

        if (!store.claim(claim, now)) {
            // Uniqueness conflict detected. Retrieve the winner to resolve whether it is a replay
            // or conflict.
            var winner = store.byKey(user, key);
            if (winner.isPresent()) {
                requireMatching(winner.get(), hash);
                return submission(winner.get(), ShippingBookingObserver.Operation.REPLAY, 0);
            }

            // Same request_id booked by a different key or quote is a terminal conflict.
            var existing =
                    store.byRequest(user, selection.requestId())
                            .orElseThrow(() -> ShippingException.storageUnavailable());
            return new Submission(
                    view(existing), Disposition.CONFLICT, "SHIPMENT_ALREADY_SUBMITTED");
        }

        return submit(claim, ShippingBookingObserver.Operation.SUBMIT);
    }

    private Submission submit(
            ShipmentStore.Record claim, ShippingBookingObserver.Operation operation) {
        long started = System.nanoTime();
        log.info(
                "Shipment claimed bookingId={} provider={}",
                claim.id(),
                claim.selection().providerId());
        var booking = claim.restore();
        var outcome = invokeProvider(claim);

        // Domain mutations are outside the provider catch: invalid transitions are programming
        // or data errors, not evidence that the provider timed out.
        switch (outcome.status()) {
            case CONFIRMED ->
                    booking.confirm(
                            new CarrierReceipt(
                                    booking.customerReference(),
                                    outcome.shipmentId(),
                                    outcome.trackingNumber(),
                                    outcome.labelUrl()));
            case REJECTED -> booking.reject(RejectionReason.CARRIER_REJECTED);
            case NOT_DISPATCHED -> booking.markNotDispatched();
            case AUTHENTICATION_FAILED -> booking.markAuthenticationFailed();
            case UNKNOWN -> booking.markOutcomeUnknown();
        }
        var completion = store.complete(claim, booking, clock.millis());
        log.info(
                "Shipment result bookingId={} status={} applied={}",
                claim.id(),
                completion.current().status(),
                completion.applied());
        return submission(completion.current(), operation, started);
    }

    private ShipmentProviderPort.Outcome invokeProvider(ShipmentStore.Record claim) {
        var selection = claim.selection();
        var request =
                new ShipmentProviderPort.Submission(
                        selection.providerId(),
                        selection.accountAlias(),
                        selection.providerQuoteId());
        try {
            return Objects.requireNonNull(provider.create(request), "Provider outcome required");
        } catch (ShipmentNotDispatchedException notDispatched) {
            log.info(
                    "Shipment dispatch refused bookingId={} reason={}",
                    claim.id(),
                    notDispatched.reason());
            return ShipmentProviderPort.Outcome.notDispatched();
        } catch (RuntimeException uncertain) {
            // Limit the conservative fallback to the provider boundary. Unexpected adapter errors
            // still cannot prove that a chargeable request was never accepted.
            log.warn(
                    "Shipment outcome unknown bookingId={} type={}",
                    claim.id(),
                    uncertain.getClass().getSimpleName());
            return ShipmentProviderPort.Outcome.unknown();
        }
    }

    private Submission replay(ShipmentStore.Record row, String hash) {
        requireMatching(row, hash);

        if (row.restore().canRetry()) {
            return retry(row);
        }
        return submission(row, ShippingBookingObserver.Operation.REPLAY, 0);
    }

    private void requireMatching(ShipmentStore.Record row, String hash) {
        if (!hash.equals(row.hash())) {
            observer.record(
                    ShippingBookingObserver.Operation.CONFLICT,
                    ShippingBookingObserver.Outcome.ERROR,
                    0);
            throw ShippingException.conflict("IDEMPOTENCY_CONFLICT");
        }
    }

    private Submission retry(ShipmentStore.Record row) {
        var booking = row.restore();
        booking.prepareRetry();
        // Quote owns current request/date/offer eligibility. Do not duplicate its expiry
        // policy here; the retry SQL independently fences the persisted expiry at claim time.
        try {
            quotes.resolve(row.userId(), row.selection().quoteId());
        } catch (ShippingException unavailable) {
            throw unavailable.forBooking(row.id());
        }
        long now = clock.millis();
        long deadline = submissionDeadline(now);
        if (!store.claimRetry(row, now, deadline)) {
            log.info("Shipment retry claim lost bookingId={} version={}", row.id(), row.version());
            // No reread or second claim. Expiry can also reject this CAS, so do not assert
            // a persisted PENDING state or imply that a provider call is running.
            observer.record(
                    ShippingBookingObserver.Operation.REPLAY,
                    ShippingBookingObserver.Outcome.UNKNOWN,
                    0);
            return new Submission(
                    new View(row.id(), "CONTENDED", null, null, null, null),
                    Disposition.PENDING,
                    null);
        }
        log.info("Shipment retry claimed bookingId={} version={}", row.id(), row.version() + 1);
        return submit(
                row.forClaimedRetry(booking, deadline), ShippingBookingObserver.Operation.RETRY);
    }

    private long submissionDeadline(long now) {
        return now + timeout.toMillis() + PERSISTENCE_GRACE.toMillis();
    }

    private Submission submission(
            ShipmentStore.Record row, ShippingBookingObserver.Operation operation, long started) {
        boolean replay = operation == ShippingBookingObserver.Operation.REPLAY;
        observer.record(
                operation, observedOutcome(row.status()), replay ? 0 : System.nanoTime() - started);
        var result = view(row);
        if (row.restore().canRetry()) {
            if ("PROVIDER_AUTHENTICATION_FAILED".equals(row.errorCode())) {
                throw ShippingException.integrationUnavailable("PROVIDER_AUTHENTICATION_FAILED")
                        .forBooking(row.id());
            }
            throw ShippingException.localCapacity("SHIPMENT_SUBMISSION_NOT_DISPATCHED")
                    .forBooking(row.id());
        }
        Disposition disposition =
                replay
                        ? (row.status().isTerminal() || row.status() == BookingStatus.MANUAL_REVIEW
                                ? Disposition.EXISTING
                                : Disposition.PENDING)
                        : switch (row.status()) {
                            case CONFIRMED -> Disposition.CREATED;
                            case FAILED -> Disposition.REJECTED;
                            default -> Disposition.PENDING;
                        };
        return new Submission(result, disposition, null);
    }

    private ShippingBookingObserver.Outcome observedOutcome(BookingStatus status) {
        return switch (status) {
            case CONFIRMED -> ShippingBookingObserver.Outcome.CONFIRMED;
            case FAILED -> ShippingBookingObserver.Outcome.REJECTED;
            case RETRYABLE -> ShippingBookingObserver.Outcome.RETRYABLE;
            default -> ShippingBookingObserver.Outcome.UNKNOWN;
        };
    }

    public View get(String user, String id) {
        return view(store.byId(user, id).orElseThrow(ShippingException::missing));
    }

    /**
     * Resolves the shipping label for an already confirmed booking.
     * Prompts an on-demand pull from the provider if the label is not yet locally cached.
     */
    public LabelProviderPort.Label label(String user, String id) {
        var row = store.byId(user, id).orElseThrow(ShippingException::missing);
        if (!row.restore().canRetrieveLabel()) {
            throw ShippingException.conflict("SHIPMENT_NOT_CONFIRMED");
        }

        // Return the cached label URL immediately if it exists and matches safety hosts
        if (row.labelUrl() != null && labels.allowedUrl(row.labelUrl())) {
            observer.record(
                    ShippingBookingObserver.Operation.LABEL,
                    ShippingBookingObserver.Outcome.AVAILABLE,
                    0);
            return new LabelProviderPort.Label(null, null, row.labelUrl());
        }

        // Fetch from the external provider on-demand without recreating the shipment
        long started = System.nanoTime();
        var identity =
                new LabelProviderPort.Identity(
                        row.selection().providerId(),
                        row.selection().accountAlias(),
                        row.providerShipmentId());
        var label = labels.fetch(identity);

        ShippingBookingObserver.Outcome outcomeTag =
                label == null
                        ? ShippingBookingObserver.Outcome.PENDING
                        : ShippingBookingObserver.Outcome.AVAILABLE;
        observer.record(
                ShippingBookingObserver.Operation.LABEL, outcomeTag, System.nanoTime() - started);

        if (label != null) {
            store.label(user, id, label.url(), clock.millis());
        }
        return label;
    }

    private View view(ShipmentStore.Record row) {
        String state =
                switch (row.status()) {
                    case CONFIRMED -> "CONFIRMED";
                    case RETRYABLE -> "RETRYABLE";
                    case FAILED -> "REJECTED";
                    case PENDING -> "PENDING";
                    case MANUAL_REVIEW -> "MANUAL_REVIEW";
                    default -> "PENDING_CONFIRMATION";
                };

        return new View(
                row.id(),
                state,
                row.providerShipmentId(),
                row.trackingNumber(),
                row.labelStatus(),
                row.errorCode());
    }

    private static String fingerprint(String id) {
        try {
            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Business projection; API routes and response metadata are added by the controller. */
    public record View(
            String bookingId,
            String status,
            String shipmentId,
            String trackingNumber,
            String labelStatus,
            String errorCode) {}

    public enum Disposition {
        CREATED,
        EXISTING,
        PENDING,
        REJECTED,
        CONFLICT
    }

    public record Submission(View booking, Disposition disposition, String code) {}
}
