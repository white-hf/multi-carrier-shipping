package com.enterprise.shipping.booking.infrastructure.persistence;

import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.ShipmentBooking;
import com.enterprise.shipping.booking.port.ShipmentStore;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort.Selection;
import com.enterprise.shipping.shared.infrastructure.persistence.UniqueConstraints;
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
public final class JdbcShipmentStore implements ShipmentStore {

    private static final String BOOKING_SELECT_COLUMNS =
            "user_id, booking_id, idempotency_key, request_hash, request_id, quote_id, "
            + "provider_id, account_alias, provider_quote_id, carrier_id, service_id, "
            + "accepted_minor, currency, quote_expires_at, status, provider_shipment_id, "
            + "tracking_number, label_url, label_status, error_code, submission_deadline_at, version";

    private final JdbcTemplate jdbc;

    public JdbcShipmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Record> byKey(String user, String key) {
        // Enforce exact case-sensitive key matching in Java to guard against case-insensitive DB collations
        return find("user_id = ? AND idempotency_key = ?", user, key)
                .filter(r -> key.equals(r.key()));
    }

    @Override
    public Optional<Record> byRequest(String user, String id) {
        return find("user_id = ? AND request_id = ?", user, id);
    }

    @Override
    public Optional<Record> byId(String user, String id) {
        return find("user_id = ? AND booking_id = ?", user, id);
    }

    private Optional<Record> find(String where, Object... args) {
        String sql = "SELECT " + BOOKING_SELECT_COLUMNS + " FROM shipment_bookings WHERE " + where;
        return jdbc.query(sql, JdbcShipmentStore::map, args).stream().findFirst();
    }

    @Override
    public boolean claim(Record b, long now) {
        var q = b.selection();

        // Standard ANSI INSERT guarded by UNIQUE(user_id, idempotency_key) and UNIQUE(user_id, request_id).
        // Catching DuplicateKeyException avoids dialect-specific ON CONFLICT / INSERT IGNORE clauses.
        try {
            return jdbc.update("""
                    INSERT INTO shipment_bookings (
                        booking_id, user_id, idempotency_key, request_hash, request_id, quote_id,
                        provider_id, account_alias, provider_quote_id, carrier_id, service_id,
                        accepted_minor, currency, quote_expires_at, status, label_status,
                        submission_deadline_at, created_at, updated_at, version
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'PENDING', ?, ?, ?, 0)
                    """,
                    b.id(), b.userId(), b.key(), b.hash(), q.requestId(), q.quoteId(),
                    q.providerId(), q.accountAlias(), q.providerQuoteId(), q.carrierId(), q.serviceId(),
                    q.acceptedMinor(), q.currency(), q.expiresAt(), b.deadline(), now, now) == 1;
        } catch (DataAccessException failure) {
            if (UniqueConstraints.isUniqueViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }

    @Override
    public boolean claimRetry(Record expected, long now, long deadline) {
        return jdbc.update("""
                UPDATE shipment_bookings
                SET status = 'PENDING', error_code = NULL, submission_deadline_at = ?,
                    updated_at = ?, version = version + 1
                WHERE user_id = ? AND booking_id = ? AND status = 'RETRYABLE'
                  AND version = ? AND quote_expires_at > ?
                """, deadline, now, expected.userId(), expected.id(), expected.version(), now) == 1;
    }

    @Override
    public boolean outcome(Record expected, ShipmentBooking booking, long now) {
        var receipt = booking.receipt();

        // Employs strict Compare-And-Set (CAS) on version and status to protect state transitions
        return jdbc.update("""
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
                booking.status().name()) == 1;
    }

    @Override
    public void label(String user, String id, String url, long now) {
        jdbc.update(
                "UPDATE shipment_bookings SET label_status = 'AVAILABLE', label_url = ?, updated_at = ?, version = version + 1 "
                        + "WHERE user_id = ? AND booking_id = ? AND status = 'CONFIRMED'",
                url, now, user, id
        );
    }

    @Override
    public List<Record> overdue(long now, long afterDeadline, String afterId, int limit) {
        // Portable ANSI keyset pagination using composite index idx_shipping_unresolved
        String sql = "SELECT " + BOOKING_SELECT_COLUMNS + """
                 FROM shipment_bookings
                WHERE status IN ('PENDING', 'PENDING_INQUIRY')
                  AND submission_deadline_at <= ?
                  AND (submission_deadline_at > ? OR (submission_deadline_at = ? AND booking_id > ?))
                ORDER BY submission_deadline_at, booking_id
                LIMIT ?
                """;
        return jdbc.query(sql, JdbcShipmentStore::map, now, afterDeadline, afterDeadline, afterId, limit);
    }

    @Override
    public boolean markUnknown(Record row, long now) {
        return jdbc.update(
                "UPDATE shipment_bookings SET status = 'PENDING_INQUIRY', error_code = 'CARRIER_OUTCOME_UNKNOWN', "
                        + "version = version + 1, updated_at = ? WHERE user_id = ? AND booking_id = ? AND status = 'PENDING' AND version = ?",
                now, row.userId(), row.id(), row.version()
        ) == 1;
    }

    private static Record map(ResultSet r, int ignored) throws SQLException {
        var q = new Selection(
                r.getString("request_id"),
                r.getString("quote_id"),
                r.getString("provider_id"),
                r.getString("account_alias"),
                r.getString("provider_quote_id"),
                r.getString("carrier_id"),
                r.getString("service_id"),
                r.getLong("accepted_minor"),
                r.getString("currency"),
                r.getLong("quote_expires_at")
        );
        return new Record(
                r.getString("user_id"),
                r.getString("booking_id"),
                r.getString("idempotency_key"),
                r.getString("request_hash"),
                q,
                BookingStatus.valueOf(r.getString("status")),
                r.getString("provider_shipment_id"),
                r.getString("tracking_number"),
                r.getString("label_url"),
                r.getString("label_status"),
                r.getString("error_code"),
                r.getLong("submission_deadline_at"),
                r.getLong("version")
        );
    }
}
