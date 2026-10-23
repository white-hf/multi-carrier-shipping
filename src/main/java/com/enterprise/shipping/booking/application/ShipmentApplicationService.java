package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.port.*;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import com.enterprise.shipping.shared.api.WorkflowException;
import org.springframework.http.HttpStatus;
import org.slf4j.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

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

    private final ShipmentWorkflowObserver observer;
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
            ShipmentWorkflowObserver observer) {
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
            throw new WorkflowException("INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST);
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
                    .orElseThrow(() -> new WorkflowException("STORE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE));
            return new Submission(view(existing), HttpStatus.CONFLICT, "SHIPMENT_ALREADY_SUBMITTED");
        }

        // 5. Winners proceed to invoke the external logistics provider
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
        } catch (RuntimeException uncertain) {
            // Ambiguous network outcome. The booking is marked as PENDING_INQUIRY, preventing duplicate POSTs.
            booking.markOutcomeUnknown();
            log.warn("Shipment outcome unknown bookingId={} type={}", id, uncertain.getClass().getSimpleName());
        }

        // 6. Persist the outcome with a Compare-And-Set (CAS) version check.
        // This defends against late-arriving responses clashing with out-of-order discovery runs.
        if (!store.outcome(claim, booking, clock.millis())) {
            var latest = store.byId(user, id)
                    .orElseThrow(WorkflowException::missing);
            if (latest.status() == BookingStatus.PENDING_INQUIRY && booking.status() == BookingStatus.CONFIRMED) {
                store.outcome(latest, booking, clock.millis());
            }
        }

        // 7. Deliver results and record diagnostics metrics
        var result = get(user, id);
        log.info("Shipment result bookingId={} status={}", id, result.status());

        ShipmentWorkflowObserver.Outcome outcomeTag = result.status().equals("CONFIRMED")
                ? ShipmentWorkflowObserver.Outcome.CONFIRMED
                : result.status().equals("REJECTED")
                        ? ShipmentWorkflowObserver.Outcome.REJECTED
                        : ShipmentWorkflowObserver.Outcome.UNKNOWN;
        observer.record(ShipmentWorkflowObserver.Operation.SUBMIT, outcomeTag, System.nanoTime() - started);

        HttpStatus httpStatus = result.status().equals("CONFIRMED")
                ? HttpStatus.CREATED
                : result.status().equals("REJECTED")
                        ? HttpStatus.UNPROCESSABLE_ENTITY
                        : HttpStatus.ACCEPTED;
        return new Submission(result, httpStatus, null);
    }

    private Submission replay(ShipmentStore.Record row, String hash) {
        if (!hash.equals(row.hash())) {
            observer.record(ShipmentWorkflowObserver.Operation.CONFLICT, ShipmentWorkflowObserver.Outcome.ERROR, 0);
            throw new WorkflowException("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT);
        }

        ShipmentWorkflowObserver.Outcome outcomeTag = row.status() == BookingStatus.CONFIRMED
                ? ShipmentWorkflowObserver.Outcome.CONFIRMED
                : row.status() == BookingStatus.FAILED
                        ? ShipmentWorkflowObserver.Outcome.REJECTED
                        : ShipmentWorkflowObserver.Outcome.UNKNOWN;
        observer.record(ShipmentWorkflowObserver.Operation.REPLAY, outcomeTag, 0);

        HttpStatus httpStatus = row.status().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return new Submission(view(row), httpStatus, null);
    }

    public View get(String user, String id) {
        return view(store.byId(user, id).orElseThrow(WorkflowException::missing));
    }

    /**
     * Resolves the shipping label for an already confirmed booking.
     * Prompts an on-demand pull from the provider if the label is not yet locally cached.
     */
    public LabelProviderPort.Label label(String user, String id) {
        var row = store.byId(user, id)
                .orElseThrow(WorkflowException::missing);
        if (row.status() != BookingStatus.CONFIRMED) {
            throw new WorkflowException("SHIPMENT_NOT_CONFIRMED", HttpStatus.CONFLICT);
        }

        // Return the cached label URL immediately if it exists and matches safety hosts
        if (row.labelUrl() != null && labels.allowedUrl(row.labelUrl())) {
            observer.record(ShipmentWorkflowObserver.Operation.LABEL, ShipmentWorkflowObserver.Outcome.AVAILABLE, 0);
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

        ShipmentWorkflowObserver.Outcome outcomeTag = label == null
                ? ShipmentWorkflowObserver.Outcome.PENDING
                : ShipmentWorkflowObserver.Outcome.AVAILABLE;
        observer.record(ShipmentWorkflowObserver.Operation.LABEL, outcomeTag, System.nanoTime() - started);

        if (label != null) {
            store.label(user, id, label.url(), clock.millis());
        }
        return label;
    }

    private View view(ShipmentStore.Record row) {
        String state = switch (row.status()) {
            case CONFIRMED -> "CONFIRMED";
            case FAILED -> "REJECTED";
            case PENDING -> "PENDING";
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

    public record Submission(View booking, HttpStatus httpStatus, String code) {}
}