package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.domain.RejectionReason;
import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.booking.port.ShipmentNotDispatchedException;
import com.enterprise.shipping.booking.port.ShipmentStore;
import com.enterprise.shipping.booking.port.ShippingBookingObserver;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final Validator validator;
    private final String providerId;
    private final String accountAlias;
    private final ZoneId zone;
    private final ShipmentProviderPort provider;
    private final LabelProviderPort labels;
    private final Clock clock;
    private final Duration timeout;

    public ShipmentApplicationService(
            ShipmentStore store,
            Validator validator,
            ShipmentProviderPort provider,
            LabelProviderPort labels,
            Clock clock,
            Duration timeout,
            ShippingBookingObserver observer,
            String providerId,
            String accountAlias,
            ZoneId zone) {
        this.store = store;
        this.validator = validator;
        this.providerId = providerId;
        this.accountAlias = accountAlias;
        this.zone = zone;
        this.provider = provider;
        this.labels = labels;
        this.clock = clock;
        this.timeout = timeout;
        this.observer = observer;
    }

    /**
     * Attempts to book a shipment under its user-scoped provider quote identity.
     * Replays prior attempts identically and prevents concurrent duplicate dispatches.
     */
    public Submission create(String user, BookingCommand command) {
        // 1. Validate only identity before returning the original immutable booking.
        validateIdentity(command.providerId(), command.providerQuoteId());
        var prior = store.byQuote(user, command.providerId(), command.providerQuoteId());
        if (prior.isPresent()) {
            return submission(prior.get(), ShippingBookingObserver.Operation.REPLAY, 0);
        }

        // 2. New submissions alone require the full snapshot and current basic admission.
        var violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        var input = command.shippingInput();
        input = input.dated(input.shippingDate() == null ? today : input.shippingDate());
        if (!input.hasCurrentShippingDate(today)) {
            throw ShippingException.invalidInput("INVALID_SHIPPING_DATE");
        }
        var selection = command.selection(accountAlias, input);
        String id = TimeOrderedIds.next();
        long now = clock.millis();
        if (selection.expiresAt() != null && selection.expiresAt() <= now) {
            throw ShippingException.conflict("QUOTE_EXPIRED");
        }

        // 4. Commit a unique booking claim in its own short database transaction.
        // The unique provider quote identity chooses one sender across concurrent requests.
        var claim =
                ShipmentStore.Record.pending(
                        user, id, selection, submissionDeadline(now));

        if (!store.claim(claim, now)) {
            // Uniqueness conflict detected. Retrieve the winner to resolve whether it is a replay
            // or conflict.
            var winner = store.byQuote(user, command.providerId(), command.providerQuoteId())
                    .orElseThrow(ShippingException::storageUnavailable);
            return submission(winner, ShippingBookingObserver.Operation.REPLAY, 0);
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

    private void validateIdentity(String provider, String quote) {
        if (!providerId.equals(provider) || quote == null || quote.isBlank()
                || quote.length() > 255 || quote.chars().anyMatch(Character::isISOControl)) {
            throw ShippingException.invalidInput("INVALID_PROVIDER_QUOTE");
        }
    }

    public Submission retry(String user, String id) {
        // 1. Only a proven-unsent persisted booking may obtain sending rights.
        var row = store.byId(user, id).orElseThrow(ShippingException::missing);
        if (!row.restore().canRetry()) {
            throw ShippingException.conflict("BOOKING_NOT_RETRYABLE").forBooking(id);
        }
        var booking = row.restore();
        booking.prepareRetry();
        long now = clock.millis();
        if (row.selection().expiresAt() != null && row.selection().expiresAt() <= now) {
            throw ShippingException.conflict("QUOTE_EXPIRED").forBooking(id);
        }
        if (!row.selection().shippingInput().hasCurrentShippingDate(LocalDate.now(clock.withZone(zone)))) {
            throw ShippingException.conflict("INVALID_SHIPPING_DATE").forBooking(id);
        }
        // 2. The version/state fence, not this read, chooses the single sender.
        long deadline = submissionDeadline(now);
        if (!store.claimRetry(row, now, deadline)) {
            log.info("Shipment retry claim lost bookingId={} version={}", row.id(), row.version());
            // Read the committed winner once; a lost fence never authorizes another claim.
            return submission(store.byId(user, id).orElseThrow(ShippingException::missing),
                    ShippingBookingObserver.Operation.REPLAY, 0);
        }
        log.info("Shipment retry claimed bookingId={} version={}", row.id(), row.version() + 1);
        var submitted = submit(
                row.forClaimedRetry(booking, deadline), ShippingBookingObserver.Operation.RETRY);
        return submitted.disposition() == Disposition.CREATED
                ? new Submission(submitted.booking(), Disposition.EXISTING) : submitted;
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
        if (!replay && row.restore().canRetry()) {
            throw ShippingException.localCapacity("SHIPMENT_SUBMISSION_NOT_DISPATCHED")
                    .forBooking(row.id());
        }
        Disposition disposition =
                replay
                        ? (row.status().isTerminal() || row.status() == BookingStatus.MANUAL_REVIEW
                                || row.status() == BookingStatus.RETRYABLE
                                ? Disposition.EXISTING
                                : Disposition.PENDING)
                        : switch (row.status()) {
                            case CONFIRMED -> Disposition.CREATED;
                            case FAILED -> Disposition.REJECTED;
                            default -> Disposition.PENDING;
                        };
        return new Submission(result, disposition);
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

    public View lookup(String user, String provider, String quote) {
        validateIdentity(provider, quote);
        return view(store.byQuote(user, provider, quote).orElseThrow(ShippingException::missing));
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
                row.selection().providerId(),
                row.selection().providerQuoteId(),
                state,
                row.providerShipmentId(),
                row.trackingNumber(),
                row.labelStatus(),
                row.errorCode());
    }

    /** Business projection; API routes and response metadata are added by the controller. */
    public record View(
            String bookingId,
            String providerId,
            String providerQuoteId,
            String status,
            String shipmentId,
            String trackingNumber,
            String labelStatus,
            String errorCode) {}

    public enum Disposition {
        CREATED,
        EXISTING,
        PENDING,
        REJECTED
    }

    public record Submission(View booking, Disposition disposition) {}
}
