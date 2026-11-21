package com.enterprise.shipping.shipping.persistence;

import com.enterprise.shipping.shipping.model.BookingId;
import com.enterprise.shipping.shipping.model.BookingStatus;
import com.enterprise.shipping.shipping.model.ShipmentBooking;
import com.enterprise.shipping.shipping.model.BookingSelection;
import com.enterprise.shipping.shipping.model.CarrierReceipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.shipping.shared.infrastructure.persistence.UniqueConstraints;
import com.enterprise.shipping.shared.domain.ShippingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Handles RDBMS-agnostic persistent database operations for shipment bookings using JdbcTemplate.
 *
 * Uses explicit column lists (never SELECT *) and standard ANSI SQL.
 */
public final class BookingRepository {
    private static final Logger log = LoggerFactory.getLogger(BookingRepository.class);

    /** Immutable observed/committed facts keep version fencing independent of model mutation. */
    public record Record(
            String userId,
            String id,
            BookingSelection selection,
            BookingStatus status,
            String providerShipmentId,
            String trackingNumber,
            String labelUrl,
            String labelStatus,
            String errorCode,
            long deadline,
            long version) {
        public static Record pending(
                String user,
                String id,
                BookingSelection selection,
                long deadline) {
            var booking = ShipmentBooking.pending(new BookingId(id), selection.carrierId(), "booking-" + id);
            return new Record(user, id, selection, booking.status(), null, null, null,
                    "PENDING", null, deadline, 0);
        }

        public ShipmentBooking restore() {
            var receipt = providerShipmentId == null ? null
                    : new CarrierReceipt("booking-" + id, providerShipmentId, trackingNumber, labelUrl);
            return ShipmentBooking.restore(new BookingId(id), selection.carrierId(), "booking-" + id,
                    status, receipt, errorCode);
        }

        public Record forClaimedRetry(ShipmentBooking booking, long newDeadline) {
            requireIdentity(booking);
            if (status != BookingStatus.RETRYABLE || booking.status() != BookingStatus.PENDING) {
                throw new IllegalArgumentException("Retry snapshot requires the prepared original booking");
            }
            return new Record(userId, id, selection, booking.status(), null, null, null,
                    labelStatus, null, newDeadline, version + 1);
        }

        public Record completed(ShipmentBooking booking) {
            requireIdentity(booking);
            var receipt = booking.receipt();
            return new Record(userId, id, selection, booking.status(),
                    receipt == null ? null : receipt.carrierBookingId(),
                    receipt == null ? null : receipt.trackingNumber(),
                    receipt == null ? null : receipt.labelUrl(),
                    receipt != null && receipt.labelUrl() != null ? "AVAILABLE" : "PENDING",
                    booking.errorCode(), deadline, version + 1);
        }

        private void requireIdentity(ShipmentBooking booking) {
            if (!id.equals(booking.id().value()) || !selection.carrierId().equals(booking.carrierId())
                    || !("booking-" + id).equals(booking.customerReference())) {
                throw new IllegalArgumentException("Booking identity must match its stored snapshot");
            }
        }
    }

    public record Completion(Record current, boolean applied) {}

    private static final String BOOKING_SELECT_COLUMNS =
            "user_id, booking_id, submission_snapshot, status, provider_shipment_id, "
                    + "tracking_number, label_url, label_status, error_code, submission_deadline_at, version";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public BookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Record> byQuote(String user, String providerId, String quoteId) {
        return find("user_id = ? AND provider_id = ? AND provider_quote_id = ?",
                user, providerId, quoteId);
    }

    public Optional<Record> byId(String user, String id) {
        return find("user_id = ? AND booking_id = ?", user, id);
    }

    private Optional<Record> find(String where, Object... args) {
        String sql = "SELECT " + BOOKING_SELECT_COLUMNS + " FROM shipment_bookings WHERE " + where;
        return jdbc.query(sql, this::map, args).stream().findFirst();
    }

    public boolean claim(Record b, long now) {
        var q = b.selection();

        // The database identity constraint, not the optimistic read, arbitrates dispatch rights.
        try {
            return jdbc.update(
                            """
                    INSERT INTO shipment_bookings (
                        booking_id, user_id, provider_id, account_alias, provider_quote_id,
                        submission_snapshot, quote_expires_at, status, label_status,
                        submission_deadline_at, created_at, updated_at, version
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'PENDING', ?, ?, ?, 0)
                    """,
                            b.id(),
                            b.userId(),
                            q.providerId(),
                            q.accountAlias(),
                            q.providerQuoteId(),
                            encode(q),
                            q.expiresAt(),
                            b.deadline(),
                            now,
                            now)
                    == 1;
        } catch (DataAccessException failure) {
            if (UniqueConstraints.isUniqueViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }

    public boolean claimRetry(Record expected, long now, long deadline) {
        return jdbc.update(
                        """
                UPDATE shipment_bookings
                SET status = 'PENDING', error_code = NULL, submission_deadline_at = ?,
                    updated_at = ?, version = version + 1
                WHERE user_id = ? AND booking_id = ? AND status = 'RETRYABLE'
                  AND version = ? AND (quote_expires_at IS NULL OR quote_expires_at > ?)
                """,
                        deadline,
                        now,
                        expected.userId(),
                        expected.id(),
                        expected.version(),
                        now)
                == 1;
    }

    public Completion complete(Record expected, ShipmentBooking booking, long now) {
        var completed = expected.completed(booking);
        if (writeOutcome(expected, booking, now)) {
            return new Completion(completed, true);
        }

        var current =
                byId(expected.userId(), expected.id()).orElseThrow(ShippingException::missing);
        var latest = current.restore();
        if (booking.status() == BookingStatus.CONFIRMED && latest.canAcceptLateConfirmation()) {
            // The domain owns admissible transitions; SQL still fences the observed version.
            latest.confirmLate(booking.receipt());
            completed = current.completed(latest);
            if (writeOutcome(current, latest, now)) {
                return new Completion(completed, true);
            }
            // No unbounded CAS loop or second provider call. Without a durable evidence record,
            // repeated contention can still leave a late receipt unresolved for manual review.
            log.warn("Late confirmation save conflicted bookingId={}", expected.id());
            current =
                    byId(expected.userId(), expected.id()).orElseThrow(ShippingException::missing);
        }
        return new Completion(current, false);
    }

    private boolean writeOutcome(Record expected, ShipmentBooking booking, long now) {
        var receipt = booking.receipt();

        // Employs strict Compare-And-Set (CAS) on version and status to protect state transitions
        return jdbc.update(
                        """
                UPDATE shipment_bookings
                SET status = ?, provider_shipment_id = ?, tracking_number = ?, label_url = ?,
                    label_status = ?, error_code = ?, updated_at = ?, version = version + 1
                WHERE user_id = ? AND booking_id = ? AND version = ?
                  AND (status IN ('PENDING', 'PENDING_INQUIRY')
                       OR (status = 'MANUAL_REVIEW' AND ? = 'CONFIRMED'))
                """,
                        booking.status().name(),
                        receipt == null ? null : receipt.carrierBookingId(),
                        receipt == null ? null : receipt.trackingNumber(),
                        receipt == null ? null : receipt.labelUrl(),
                        receipt != null && receipt.labelUrl() != null ? "AVAILABLE" : "PENDING",
                        booking.errorCode(),
                        now,
                        expected.userId(),
                        expected.id(),
                        expected.version(),
                        booking.status().name())
                == 1;
    }

    public void label(
            String user,
            String id,
            String url,
            long now) {
        // Repeated PDF reads publish the same availability fact; avoid another SQLite write.
        jdbc.update(
                """
                UPDATE shipment_bookings
                SET label_status = 'AVAILABLE', label_url = ?, updated_at = ?, version = version + 1
                WHERE user_id = ? AND booking_id = ? AND status = 'CONFIRMED'
                  AND (label_status <> 'AVAILABLE'
                       OR (label_url IS NULL AND ? IS NOT NULL)
                       OR (label_url IS NOT NULL AND ? IS NULL)
                       OR label_url <> ?)
                """,
                url,
                now,
                user,
                id,
                url,
                url,
                url);
    }

    public List<Record> overdue(
            long now,
            long afterDeadline,
            String afterId,
            int limit) {
        // Portable ANSI keyset pagination using composite index idx_shipping_unresolved
        String sql =
                "SELECT "
                        + BOOKING_SELECT_COLUMNS
                        + """
                 FROM shipment_bookings
                WHERE status IN ('PENDING', 'PENDING_INQUIRY')
                  AND submission_deadline_at <= ?
                  AND (submission_deadline_at > ? OR (submission_deadline_at = ? AND booking_id > ?))
                ORDER BY submission_deadline_at, booking_id
                LIMIT ?
                """;
        return jdbc.query(
                sql, this::map, now, afterDeadline, afterDeadline, afterId, limit);
    }

    public boolean markUnknown(
            String user,
            String id,
            long expectedVersion,
            long now) {
        return jdbc.update(
                        "UPDATE shipment_bookings SET status = 'PENDING_INQUIRY', error_code = 'CARRIER_OUTCOME_UNKNOWN', "
                                + "version = version + 1, updated_at = ? WHERE user_id = ? AND booking_id = ? AND status = 'PENDING' AND version = ?",
                        now,
                        user,
                        id,
                        expectedVersion)
                == 1;
    }

    private String encode(BookingSelection selection) {
        try {
            return json.writeValueAsString(selection);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode booking snapshot", error);
        }
    }

    private Record map(ResultSet r, int ignored) throws SQLException {
        BookingSelection q;
        try {
            q = json.readValue(r.getString("submission_snapshot"), BookingSelection.class);
        } catch (JsonProcessingException error) {
            throw new SQLException("Invalid stored booking snapshot", error);
        }
        return new Record(
                r.getString("user_id"),
                r.getString("booking_id"),
                q,
                BookingStatus.valueOf(r.getString("status")),
                r.getString("provider_shipment_id"),
                r.getString("tracking_number"),
                r.getString("label_url"),
                r.getString("label_status"),
                r.getString("error_code"),
                r.getLong("submission_deadline_at"),
                r.getLong("version"));
    }
}
