package com.enterprise.shipping.shipping.application;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.identity.TimeOrderedIds;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import com.enterprise.shipping.shipping.contract.ManualReviewReason;
import com.enterprise.shipping.shipping.contract.ShipmentRecovery;
import com.enterprise.shipping.shipping.model.BookingStatus;
import com.enterprise.shipping.shipping.model.CarrierReceipt;
import com.enterprise.shipping.shipping.model.CreateResult;
import com.enterprise.shipping.shipping.model.Label;
import com.enterprise.shipping.shipping.model.RejectionReason;
import com.enterprise.shipping.shipping.persistence.BookingRepository;
import com.enterprise.shipping.shipping.port.LabelProvider;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
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
 * <p>Enforces a claim-before-call pattern where a unique database claim is committed prior to
 * making any external HTTP call. This safeguards against duplicate submissions and handles
 * ambiguous external outcomes with eventual-consistency recovery states.
 *
 * <p>Keep orchestration here, business transitions in ShipmentBooking, and fenced completion in
 * BookingRepository. A successful completion returns its committed snapshot without another SELECT;
 * conflicts alone require rereading. This is an as-of-commit response, not a guarantee of observing
 * later label/recovery updates.
 */
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NEVER)
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final Duration PERSISTENCE_GRACE = Duration.ofSeconds(10);

    private final MeterRegistry metrics;
    private final BookingRepository store;
    private final Validator validator;
    private final String providerId;
    private final String accountAlias;
    private final ZoneId zone;
    private final ShipmentProvider shipments;
    private final LabelProvider labels;
    private final Clock clock;
    private final Duration timeout;

    public BookingService(
            BookingRepository store,
            Validator validator,
            ShipmentProvider shipments,
            LabelProvider labels,
            Clock clock,
            Duration timeout,
            MeterRegistry metrics,
            String providerId,
            String accountAlias,
            ZoneId zone) {
        this.store = store;
        this.validator = validator;
        this.providerId = providerId;
        this.accountAlias = accountAlias;
        this.zone = zone;
        this.shipments = shipments;
        this.labels = labels;
        this.clock = clock;
        this.timeout = timeout;
        this.metrics = metrics;
    }

    public Submission create(String user, BookingCommand command) {
        // 1. Validate only identity before returning the original immutable booking.
        validateIdentity(command.providerId(), command.providerQuoteId());
        var prior = store.byQuote(user, command.providerId(), command.providerQuoteId());
        if (prior.isPresent()) {
            return submission(prior.get(), Operation.REPLAY, 0);
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
        var claim = BookingRepository.Record.pending(user, id, selection, submissionDeadline(now));

        if (!store.claim(claim, now)) {
            // Uniqueness conflict detected. Retrieve the winner to resolve whether it is a replay
            // or conflict.
            var winner = store.byQuote(user, command.providerId(), command.providerQuoteId())
                    .orElseThrow(ShippingException::storageUnavailable);
            return submission(winner, Operation.REPLAY, 0);
        }

        return submit(claim, Operation.SUBMIT);
    }

    private Submission submit(BookingRepository.Record claim, Operation operation) {
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
            case CONFIRMED -> booking.confirm(
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

    private CreateResult invokeProvider(BookingRepository.Record claim) {
        var selection = claim.selection();
        var request = new ShipmentProvider.ShipmentSubmission(
                selection.providerId(),
                selection.accountAlias(),
                selection.providerQuoteId());
        try {
            return Objects.requireNonNull(shipments.create(request), "Provider outcome required");
        } catch (RuntimeException uncertain) {
            // Limit the conservative fallback to the provider boundary. Unexpected adapter errors
            // still cannot prove that a chargeable request was never accepted.
            log.warn(
                    "Shipment outcome unknown bookingId={} type={}",
                    claim.id(),
                    uncertain.getClass().getSimpleName());
            return CreateResult.unknown();
        }
    }

    private void validateIdentity(String provider, String quote) {
        if (!providerId.equals(provider)
                || quote == null
                || quote.isBlank()
                || quote.length() > 255
                || quote.chars().anyMatch(Character::isISOControl)) {
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
        if (!row.selection()
                .shippingInput()
                .hasCurrentShippingDate(LocalDate.now(clock.withZone(zone)))) {
            throw ShippingException.conflict("INVALID_SHIPPING_DATE").forBooking(id);
        }
        // 2. The version/state fence, not this read, chooses the single sender.
        long deadline = submissionDeadline(now);
        if (!store.claimRetry(row, now, deadline)) {
            log.info("Shipment retry claim lost bookingId={} version={}", row.id(), row.version());
            // Read the committed winner once; a lost fence never authorizes another claim.
            return submission(
                    store.byId(user, id).orElseThrow(ShippingException::missing),
                    Operation.REPLAY,
                    0);
        }
        log.info("Shipment retry claimed bookingId={} version={}", row.id(), row.version() + 1);
        var submitted = submit(row.forClaimedRetry(booking, deadline), Operation.RETRY);
        return submitted.disposition() == Disposition.CREATED
                ? new Submission(submitted.booking(), Disposition.EXISTING) : submitted;
    }

    private long submissionDeadline(long now) {
        return now + timeout.toMillis() + PERSISTENCE_GRACE.toMillis();
    }

    private Submission submission(
            BookingRepository.Record row,
            Operation operation,
            long started) {
        boolean replay = operation == Operation.REPLAY;
        record(
                operation,
                observedOutcome(row.status()),
                replay ? 0 : System.nanoTime() - started);
        var result = view(row);
        if (!replay && row.restore().canRetry()) {
            throw ShippingException.localCapacity("SHIPMENT_SUBMISSION_NOT_DISPATCHED")
                    .forBooking(row.id());
        }
        boolean resolved = row.status().isTerminal()
                || row.status() == BookingStatus.MANUAL_REVIEW
                || row.status() == BookingStatus.RETRYABLE;
        Disposition disposition;
        if (replay) {
            disposition = resolved ? Disposition.EXISTING : Disposition.PENDING;
        } else {
            disposition = switch (row.status()) {
                case CONFIRMED -> Disposition.CREATED;
                case FAILED -> Disposition.REJECTED;
                default -> Disposition.PENDING;
            };
        }
        return new Submission(result, disposition);
    }

    private String observedOutcome(BookingStatus status) {
        return switch (status) {
            case CONFIRMED -> "CONFIRMED";
            case FAILED -> "REJECTED";
            case RETRYABLE -> "RETRYABLE";
            default -> "UNKNOWN";
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
     * Resolves the shipping label for an already confirmed booking. Prompts an on-demand pull from
     * the provider if the label is not yet locally cached.
     */
    public Label label(String user, String id) {
        var row = store.byId(user, id).orElseThrow(ShippingException::missing);
        if (!row.restore().canRetrieveLabel()) {
            throw ShippingException.conflict("SHIPMENT_NOT_CONFIRMED");
        }

        // Return the cached label URL immediately if it exists and matches safety hosts.
        if (row.labelUrl() != null && labels.allowedUrl(row.labelUrl())) {
            record(Operation.LABEL, "AVAILABLE", 0);
            return new Label(null, null, row.labelUrl());
        }

        // Fetch from the external provider on-demand without recreating the shipment.
        long started = System.nanoTime();
        var identity = new LabelProvider.LabelIdentity(
                row.selection().providerId(),
                row.selection().accountAlias(),
                row.providerShipmentId());
        var label = labels.fetch(identity);

        String outcomeTag = label == null ? "PENDING" : "AVAILABLE";
        record(Operation.LABEL, outcomeTag, System.nanoTime() - started);

        if (label != null) {
            store.label(user, id, label.url(), clock.millis());
        }
        return label;
    }

    private View view(BookingRepository.Record row) {
        String state = switch (row.status()) {
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

    private enum Operation {
        SUBMIT, RETRY, REPLAY, LABEL
    }

    private void record(Operation operation, String outcome, long nanos) {
        SafeMetrics.record(
                metrics,
                "shipping.booking",
                nanos,
                "operation",
                operation.name(),
                "outcome",
                outcome);
    }

    boolean requireManualReview(
            ShipmentRecovery.Candidate candidate,
            ManualReviewReason reason,
            long now) {
        var current = store.byId(candidate.userId(), candidate.bookingId());
        if (current.isEmpty()) {
            return false;
        }
        var row = current.get();
        var booking = row.restore();
        if (!booking.canRequireManualReview()) {
            return false;
        }
        if (!row.selection().providerId().equals(candidate.providerId())
                || !row.selection().accountAlias().equals(candidate.accountAlias())) {
            throw new IllegalArgumentException("Recovery provider context changed");
        }
        booking.requireManualReview(reason);
        boolean applied = store.complete(row, booking, now).applied();
        if (applied) {
            log.info("Shipment requires manual review bookingId={} reason={}", row.id(), reason);
        }
        return applied;
    }
}
