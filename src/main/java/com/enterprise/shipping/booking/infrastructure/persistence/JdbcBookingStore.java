package com.enterprise.shipping.booking.infrastructure.persistence;

import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.contracts.recovery.Recovery.*;
import com.enterprise.shipping.booking.port.BookingStore;
import com.enterprise.shipping.booking.port.BookingRecord;
import java.sql.ResultSet;
import java.math.BigDecimal;
import com.enterprise.shipping.shared.domain.ShipmentParcel;
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
    // Keyset paging repeatedly wraps; an older booking can become uncertain after an earlier sweep.
    static final String DISCOVERY_SQL = """
            SELECT booking_id, booking_locator, carrier_id, carrier_context_reference, customer_reference, created_at
            FROM shipment_bookings WHERE status IN ('PENDING','PENDING_INQUIRY')
                AND (status='PENDING_INQUIRY' OR submission_deadline_at<=?)
                AND (created_at, booking_id)>(?,?)
            ORDER BY created_at, booking_id LIMIT ?
            """;
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

    @Override public Optional<BookingRecord> findForRecovery(Reference reference) {
        return query("SELECT * FROM shipment_bookings WHERE booking_id=? AND booking_locator=?",
                reference.bookingId(), reference.bookingLocator()).stream().findFirst();
    }

    @Override public boolean insertClaim(BookingRecord booking) {
        // Target only the owner/key conflict. Other constraints and SQLITE_BUSY remain real errors.
        return database(() -> jdbc.update("""
                INSERT INTO shipment_bookings (booking_id,owner_type,owner_id,idempotency_key,
                    request_hash,order_reference,carrier_id,service_level,origin_postal_code,destination_postal_code,
                    origin_country_code,destination_country_code,weight_kg,length_cm,width_cm,height_cm,
                    booking_locator,carrier_context_reference,customer_reference,status,created_at,updated_at,submission_deadline_at,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                ON CONFLICT(owner_type,owner_id,idempotency_key) DO NOTHING
                """, booking.bookingId(), booking.owner().type(), booking.owner().id(), booking.idempotencyKey(),
                booking.requestHash(), booking.intent().orderReference(), booking.carrierId(), booking.intent().serviceLevel(),
                booking.intent().parcel().originPostalCode(), booking.intent().parcel().destinationPostalCode(),
                booking.intent().parcel().originCountryCode(), booking.intent().parcel().destinationCountryCode(),
                booking.intent().parcel().weightKg().toPlainString(), booking.intent().parcel().lengthCm().toPlainString(),
                booking.intent().parcel().widthCm().toPlainString(), booking.intent().parcel().heightCm().toPlainString(),
                booking.bookingLocator(), booking.carrierContextReference(), booking.customerReference(), booking.status().name(),
                booking.createdAt(), booking.updatedAt(), booking.submissionDeadlineAt())) == 1;
    }

    @Override public boolean saveOutcome(BookingRecord expected, ShipmentBooking booking, long now) {
        expected.requireIdentityOf(booking);
        CarrierReceipt receipt = booking.receipt();
        // Only a business transition changes this version; task leases never touch the booking row.
        return database(() -> jdbc.update("""
                UPDATE shipment_bookings SET status=?, carrier_booking_id=?, tracking_number=?, label_url=?,
                    error_code=?, updated_at=?, version=version+1
                WHERE booking_id=? AND version=? AND status IN ('PENDING','PENDING_INQUIRY')
                """, booking.status().name(), receipt == null ? null : receipt.carrierBookingId(),
                receipt == null ? null : receipt.trackingNumber(), receipt == null ? null : receipt.labelUrl(),
                booking.errorCode(), now, expected.bookingId(), expected.version())) == 1;
    }

    @Override public List<Candidate> discoverUnresolved(long now, Cursor after, int limit) {
        return database(() -> jdbc.query(DISCOVERY_SQL, (row, index) -> new Candidate(
                new Reference(row.getString("booking_id"), row.getString("booking_locator"), row.getString("carrier_id"),
                        row.getString("carrier_context_reference"), row.getString("customer_reference")), row.getLong("created_at")),
                now, after.createdAt(), after.bookingId(), limit));
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
        // Decimal TEXT avoids SQLite REAL rounding; restore the canonical domain values exactly.
        ShipmentParcel parcel = new ShipmentParcel(row.getString("origin_postal_code"),
                row.getString("destination_postal_code"), new BigDecimal(row.getString("weight_kg")),
                new BigDecimal(row.getString("length_cm")), new BigDecimal(row.getString("width_cm")),
                new BigDecimal(row.getString("height_cm")), row.getString("origin_country_code"),
                row.getString("destination_country_code"));
        BookingCommand intent = new BookingCommand(row.getString("order_reference"), row.getString("carrier_id"),
                row.getString("service_level"), parcel);
        return new BookingRecord(row.getString("booking_id"),
                new BookingOwner(row.getString("owner_type"), row.getString("owner_id")), row.getString("idempotency_key"),
                row.getString("request_hash"), intent, row.getString("booking_locator"), row.getString("carrier_context_reference"),
                row.getString("customer_reference"),
                BookingStatus.valueOf(row.getString("status")), row.getString("carrier_booking_id"),
                row.getString("tracking_number"), row.getString("label_url"), row.getString("error_code"),
                row.getLong("created_at"), row.getLong("updated_at"), row.getLong("submission_deadline_at"), row.getLong("version"));
    }
}
