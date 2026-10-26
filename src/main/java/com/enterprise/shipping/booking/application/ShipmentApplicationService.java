package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.domain.RejectionReason;
import com.enterprise.shipping.booking.domain.ShipmentBooking;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates shipment booking creations and replays under strict idempotency.
 *
 * Enforces a claim-before-call pattern where a unique database claim is committed
 * prior to making any external HTTP call. This safeguards against duplicate submissions
 * and handles ambiguous external outcomes with eventual-consistency recovery states.
 */
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class ShipmentApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentApplicationService.class);

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
            throw ShippingException.badRequest("INVALID_IDEMPOTENCY_KEY");
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
        // the unique index constraints will abort the insertion, protecting against race conditions.
        var claim = new ShipmentStore.Record(
                user, id, key, hash, selection, BookingStatus.PENDING,
                null, null, null, "PENDING", null,
                now + timeout.toMillis() + 10000, 0
        );

        if (!store.claim(claim, now)) {
            // Uniqueness conflict detected. Retrieve the winner to resolve whether it is a replay or conflict.
            var winner = store.byKey(user, key);
            if (winner.isPresent()) {
                return replay(winner.get(), hash);
            }

            // Same request_id booked by a different key or quote is a terminal conflict.
            var existing = store.byRequest(user, selection.requestId())
                    .orElseThrow(() -> ShippingException.serviceUnavailable("STORE_UNAVAILABLE"));
            return new Submission(view(existing), 409, "SHIPMENT_ALREADY_SUBMITTED");
        }

        return submit(claim, ShippingBookingObserver.Operation.SUBMIT);
    }

    private Submission submit(ShipmentStore.Record claim, ShippingBookingObserver.Operation operation) {
        String user = claim.userId();
        String id = claim.id();
        var selection = claim.selection();
        // Only a committed initial claim or retry claim authorizes an external submission.
        long started = System.nanoTime();
        log.info("Shipment claimed bookingId={} provider={}", id, selection.providerId());
        ShipmentBooking booking = claim.restore();

        try {
            var submission = new ShipmentProviderPort.Submission(
                    selection.providerId(),
                    selection.accountAlias(),
                    selection.providerQuoteId()
            );
            var outcome = provider.create(submission);

            if (outcome.rejected()) {
                booking.reject(RejectionReason.CARRIER_REJECTED);
            } else {
                var receipt = new CarrierReceipt(
                        "booking-" + id,
                        outcome.shipmentId(),
                        outcome.trackingNumber(),
                        outcome.labelUrl()
                );
                booking.confirm(receipt);
            }
        } catch (ShipmentNotDispatchedException notDispatched) {
            booking.markNotDispatched();
            log.info("Shipment dispatch refused bookingId={} reason={}", id, notDispatched.reason());
        } catch (RuntimeException uncertain) {
            // Ambiguous network outcome. The booking is marked as PENDING_INQUIRY, preventing duplicate POSTs.
            booking.markOutcomeUnknown();
            log.warn("Shipment outcome unknown bookingId={} type={}", id, uncertain.getClass().getSimpleName());
        }

        // 6. Persist the outcome with a Compare-And-Set (CAS) version check.
        // This defends against late-arriving responses clashing with out-of-order discovery runs.
        if (!store.outcome(claim, booking, clock.millis())) {
            var latest = store.byId(user, id)
                    .orElseThrow(ShippingException::missing);
            if ((latest.status() == BookingStatus.PENDING_INQUIRY || latest.status() == BookingStatus.MANUAL_REVIEW)
                    && booking.status() == BookingStatus.CONFIRMED) {
                store.outcome(latest, booking, clock.millis());
            }
        }

        // 7. Deliver results and record diagnostics metrics
        var result = get(user, id);
        log.info("Shipment result bookingId={} status={}", id, result.status());

        ShippingBookingObserver.Outcome outcomeTag = result.status().equals("CONFIRMED")
                ? ShippingBookingObserver.Outcome.CONFIRMED
                : result.status().equals("REJECTED")
                        ? ShippingBookingObserver.Outcome.REJECTED
                        : result.status().equals("RETRYABLE")
                                ? ShippingBookingObserver.Outcome.RETRYABLE
                                : ShippingBookingObserver.Outcome.UNKNOWN;
        observer.record(operation, outcomeTag, System.nanoTime() - started);

        if (result.status().equals("RETRYABLE")) {
            throw ShippingException.serviceUnavailable("SHIPMENT_SUBMISSION_NOT_DISPATCHED")
                    .forOperation(id, result.statusUrl());
        }

        int statusCode = result.status().equals("CONFIRMED")
                ? 201
                : result.status().equals("REJECTED")
                        ? 422
                        : 202;
        return new Submission(result, statusCode, null);
    }

    private Submission replay(ShipmentStore.Record row, String hash) {
        if (!hash.equals(row.hash())) {
            observer.record(ShippingBookingObserver.Operation.CONFLICT, ShippingBookingObserver.Outcome.ERROR, 0);
            throw ShippingException.conflict("IDEMPOTENCY_CONFLICT");
        }

        if (row.status() == BookingStatus.RETRYABLE) {
            return retry(row);
        }

        ShippingBookingObserver.Outcome outcomeTag = row.status() == BookingStatus.CONFIRMED
                ? ShippingBookingObserver.Outcome.CONFIRMED
                : row.status() == BookingStatus.FAILED
                        ? ShippingBookingObserver.Outcome.REJECTED
                        : ShippingBookingObserver.Outcome.UNKNOWN;
        observer.record(ShippingBookingObserver.Operation.REPLAY, outcomeTag, 0);

        int statusCode = row.status().isTerminal() || row.status() == BookingStatus.MANUAL_REVIEW
                ? 200 : 202;
        return new Submission(view(row), statusCode, null);
    }

    private Submission retry(ShipmentStore.Record row) {
        long now = clock.millis();
        if (row.selection().expiresAt() <= now) {
            throw ShippingException.conflict("QUOTE_EXPIRED").forOperation(row.id(), view(row).statusUrl());
        }
        // Revalidate the original quote, including ownership and current request eligibility.
        try {
            quotes.resolve(row.userId(), row.selection().quoteId());
        } catch (ShippingException unavailable) {
            throw unavailable.forOperation(row.id(), view(row).statusUrl());
        }
        now = clock.millis();
        var booking = row.restore();
        booking.prepareRetry();
        long deadline = now + timeout.toMillis() + 10000;
        if (!store.claimRetry(row, now, deadline)) {
            log.info("Shipment retry claim lost bookingId={} version={}", row.id(), row.version());
            var latest = store.byId(row.userId(), row.id()).orElseThrow(ShippingException::missing);
            // Do not loop into another submission attempt after losing this version.
            int statusCode = latest.status().isTerminal() || latest.status() == BookingStatus.MANUAL_REVIEW ? 200 : 202;
            if (latest.status() == BookingStatus.RETRYABLE) {
                throw ShippingException.serviceUnavailable("SHIPMENT_SUBMISSION_NOT_DISPATCHED")
                        .forOperation(row.id(), view(latest).statusUrl());
            }
            return new Submission(view(latest), statusCode, null);
        }
        log.info("Shipment retry claimed bookingId={} version={}", row.id(), row.version() + 1);
        var claimed = new ShipmentStore.Record(row.userId(), row.id(), row.key(), row.hash(), row.selection(),
                booking.status(), null, null, null, row.labelStatus(), null, deadline, row.version() + 1);
        return submit(claimed, ShippingBookingObserver.Operation.RETRY);
    }

    public View get(String user, String id) {
        return view(store.byId(user, id).orElseThrow(ShippingException::missing));
    }

    /**
     * Resolves the shipping label for an already confirmed booking.
     * Prompts an on-demand pull from the provider if the label is not yet locally cached.
     */
    public LabelProviderPort.Label label(String user, String id) {
        var row = store.byId(user, id)
                .orElseThrow(ShippingException::missing);
        if (row.status() != BookingStatus.CONFIRMED) {
            throw ShippingException.conflict("SHIPMENT_NOT_CONFIRMED");
        }

        // Return the cached label URL immediately if it exists and matches safety hosts
        if (row.labelUrl() != null && labels.allowedUrl(row.labelUrl())) {
            observer.record(ShippingBookingObserver.Operation.LABEL, ShippingBookingObserver.Outcome.AVAILABLE, 0);
            return new LabelProviderPort.Label(null, null, row.labelUrl());
        }

        // Fetch from the external provider on-demand without recreating the shipment
        long started = System.nanoTime();
        var identity = new LabelProviderPort.Identity(
                row.selection().providerId(),
                row.selection().accountAlias(),
                row.providerShipmentId()
        );
        var label = labels.fetch(identity);

        ShippingBookingObserver.Outcome outcomeTag = label == null
                ? ShippingBookingObserver.Outcome.PENDING
                : ShippingBookingObserver.Outcome.AVAILABLE;
        observer.record(ShippingBookingObserver.Operation.LABEL, outcomeTag, System.nanoTime() - started);

        if (label != null) {
            store.label(user, id, label.url(), clock.millis());
        }
        return label;
    }

    private View view(ShipmentStore.Record row) {
        String state = switch (row.status()) {
            case CONFIRMED -> "CONFIRMED";
            case RETRYABLE -> "RETRYABLE";
            case FAILED -> "REJECTED";
            case PENDING -> "PENDING";
            case MANUAL_REVIEW -> "MANUAL_REVIEW";
            default -> "PENDING_CONFIRMATION";
        };

        String labelUrl = row.status() == BookingStatus.CONFIRMED
                ? "/api/bookings/" + row.id() + "/label"
                : null;

        return new View(
                row.id(),
                state,
                row.providerShipmentId(),
                row.trackingNumber(),
                row.labelStatus(),
                labelUrl,
                row.errorCode(),
                "/api/bookings/" + row.id()
        );
    }

    private static String fingerprint(String id) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record View(
            String bookingId,
            String status,
            String shipmentId,
            String trackingNumber,
            String labelStatus,
            String labelUrl,
            String errorCode,
            String statusUrl) {}

    public record Submission(View booking, int statusCode, String code) {}
}
