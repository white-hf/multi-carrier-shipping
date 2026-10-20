package com.enterprise.shipping.booking.infrastructure.persistence;

import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.port.BookingStore;
import com.enterprise.shipping.booking.port.BookingRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** All writes are single-statement, auto-committed operations; no remote call holds a DB lock. */
public final class JdbcBookingStore implements BookingStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcBookingStore.class);
    private final JdbcTemplate jdbc;

    public JdbcBookingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<BookingRecord> find(BookingOwner owner, String key) {
        return query("SELECT * FROM shipment_bookings WHERE owner_type=? AND owner_id=? AND idempotency_key=?",
                owner.type(), owner.id(), key).stream().findFirst();
    }

    @Override public Optional<BookingRecord> findById(BookingOwner owner, String id) {
        return query("SELECT * FROM shipment_bookings WHERE owner_type=? AND owner_id=? AND booking_id=?",
                owner.type(), owner.id(), id).stream().findFirst();
    }

    @Override public boolean insertClaim(BookingRecord booking) {
        // Target only the owner/key conflict. Other constraints and SQLITE_BUSY remain real errors.
        return database(() -> jdbc.update("""
                INSERT INTO shipment_bookings (booking_id,owner_type,owner_id,idempotency_key,
                    request_hash,request_json,carrier_id,customer_reference,status,created_at,updated_at,
                    next_inquiry_at,lease_token,lease_expires_at,version,inquiry_attempts)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0)
                ON CONFLICT(owner_type,owner_id,idempotency_key) DO NOTHING
                """, booking.bookingId(), booking.owner().type(), booking.owner().id(), booking.idempotencyKey(),
                booking.requestHash(), booking.requestJson(), booking.carrierId(), booking.customerReference(),
                booking.status().name(), booking.createdAt(), booking.updatedAt(), booking.nextInquiryAt(),
                booking.leaseToken(), booking.leaseExpiresAt())) == 1;
    }

    @Override public boolean complete(BookingRecord owned, ShipmentBooking booking, String responseJson, long now) {
        owned.requireIdentityOf(booking);
        BookingStatus status = booking.status();
        CarrierReceipt receipt = booking.receipt();
        if (!status.isTerminal()) throw new IllegalArgumentException("Terminal status required");
        // Outcome and replay snapshot become visible atomically; stale owners cannot overwrite either.
        return database(() -> jdbc.update("""
                UPDATE shipment_bookings SET status=?, carrier_booking_id=?, tracking_number=?, label_url=?,
                    response_json=?, error_code=?, updated_at=?, lease_token=NULL, lease_expires_at=0, version=version+1
                WHERE booking_id=? AND version=? AND lease_token=? AND status IN ('PENDING','PENDING_INQUIRY')
                """, status.name(), receipt == null ? null : receipt.carrierBookingId(),
                receipt == null ? null : receipt.trackingNumber(), receipt == null ? null : receipt.labelUrl(),
                responseJson, booking.errorCode(), now, owned.bookingId(), owned.version(), owned.leaseToken())) == 1;
    }

    @Override public boolean defer(BookingRecord owned, ShipmentBooking booking, long due, long now) {
        owned.requireIdentityOf(booking);
        BookingStatus status = booking.status();
        if (status != BookingStatus.PENDING_INQUIRY && status != BookingStatus.MANUAL_REVIEW)
            throw new IllegalArgumentException("Recovery status required");
        return database(() -> jdbc.update("""
                UPDATE shipment_bookings SET status=?, error_code=?, next_inquiry_at=?, updated_at=?,
                    lease_token=NULL, lease_expires_at=0, version=version+1
                WHERE booking_id=? AND version=? AND lease_token=? AND status IN ('PENDING','PENDING_INQUIRY')
                """, status.name(), booking.errorCode(), due, now, owned.bookingId(), owned.version(), owned.leaseToken())) == 1;
    }

    @Override public List<BookingRecord> findDue(long now, int limit) {
        return query("""
                SELECT * FROM shipment_bookings WHERE status IN ('PENDING','PENDING_INQUIRY')
                    AND next_inquiry_at<=? AND lease_expires_at<=?
                ORDER BY next_inquiry_at, booking_id LIMIT ?
                """, now, now, limit);
    }

    @Override public Optional<BookingRecord> acquireInquiryLease(BookingRecord candidate, String token, long now, long expires) {
        int updated = database(() -> jdbc.update("""
                UPDATE shipment_bookings SET lease_token=?, lease_expires_at=?, updated_at=?,
                    inquiry_attempts=inquiry_attempts+1, version=version+1
                WHERE booking_id=? AND version=? AND lease_expires_at<=? AND next_inquiry_at<=?
                    AND status IN ('PENDING','PENDING_INQUIRY')
                """, token, expires, now, candidate.bookingId(), candidate.version(), now, now));
        return updated == 1 ? findById(candidate.owner(), candidate.bookingId()) : Optional.empty();
    }

    private List<BookingRecord> query(String sql, Object... args) {
        return database(() -> jdbc.query(sql, JdbcBookingStore::map, args));
    }

    private <T> T database(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException error) {
            // Do not log SQL parameters: they can contain addresses, raw keys, or saved responses.
            log.warn("Booking persistence unavailable type={}", error.getClass().getSimpleName());
            throw new BookingException(BookingException.Code.STORE_UNAVAILABLE, error);
        }
    }

    private static BookingRecord map(ResultSet row, int ignored) throws SQLException {
        return new BookingRecord(row.getString("booking_id"),
                new BookingOwner(row.getString("owner_type"), row.getString("owner_id")), row.getString("idempotency_key"),
                row.getString("request_hash"), row.getString("request_json"), row.getString("carrier_id"),
                row.getString("customer_reference"), BookingStatus.valueOf(row.getString("status")),
                row.getString("carrier_booking_id"), row.getString("tracking_number"), row.getString("label_url"),
                row.getString("response_json"), row.getString("error_code"), row.getLong("created_at"),
                row.getLong("updated_at"), row.getLong("next_inquiry_at"), row.getInt("inquiry_attempts"),
                row.getString("lease_token"), row.getLong("lease_expires_at"), row.getLong("version"));
    }
}
