package com.enterprise.shipping.booking.infrastructure.persistence;

import com.enterprise.shipping.booking.application.BookingRequestHasher;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.port.BookingRecord;
import com.enterprise.shipping.shared.domain.ShipmentParcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class BookingPersistenceTest {
    @TempDir Path directory;
    HikariDataSource source;
    JdbcTemplate jdbc;
    JdbcBookingStore store;
    final BookingOwner owner = new BookingOwner("MERCHANT", "storage-test");
    final BookingRequestHasher hasher = new BookingRequestHasher(new ObjectMapper());

    @BeforeEach void open() {
        source = SqliteDataSources.open(directory.resolve("bookings.db").toString(), "storage-test", 2, 50,
                "booking/schema-v1.sql");
        jdbc = new JdbcTemplate(source);
        store = new JdbcBookingStore(jdbc);
    }
    @AfterEach void close() { source.close(); }

    BookingCommand intent() {
        return new BookingCommand("order-precision", "fedex", "Ground", new ShipmentParcel(
                "L6K3V3", "10001", new BigDecimal("0.12345678901234567890123456789"),
                new BigDecimal("25.400000000000000000000000001"), BigDecimal.TEN, BigDecimal.ONE, "CA", "US"));
    }
    BookingRecord seed(String id, long due, long leaseExpiry) {
        BookingCommand command = intent();
        var record = BookingRecord.claim(ShipmentBooking.pending(new BookingId(id), "fedex", "reference-" + id),
                owner, id, hasher.hash(command), command, "local", "test-account", 0, due);
        assertThat(store.insertClaim(record)).isTrue();
        return store.findById(owner, id).orElseThrow();
    }

    @Test void businessColumnsPreserveExactIntentAndReconstructResponseAfterReopen() {
        var record = seed("precision", 0, 0);
        assertThat(record.intent()).isEqualTo(intent());
        assertThat(hasher.hash(record.intent())).isEqualTo(record.requestHash());
        assertThat(jdbc.queryForObject("SELECT typeof(weight_kg) FROM shipment_bookings", String.class)).isEqualTo("text");
        assertThat(jdbc.queryForList("PRAGMA table_info(shipment_bookings)").stream()
                .map(column -> column.get("name")).toList()).doesNotContain("request_json", "response_json");
        var booking = record.restoreBooking();
        booking.confirm(new CarrierReceipt(record.customerReference(), "carrier-booking", "tracking", "/label"));
        assertThat(store.saveOutcome(record, booking, 1)).isTrue();
        var expected = BookingResponse.from(booking);
        source.close();
        open();
        var restored = store.find(owner, "precision").orElseThrow();
        assertThat(restored.intent()).isEqualTo(intent());
        assertThat(BookingResponse.from(restored.restoreBooking())).isEqualTo(expected);
        assertThat(hasher.hash(restored.intent())).isEqualTo(restored.requestHash());
    }

    @Test void discoveryScanFiltersActiveSubmissionsAndClosedStatesWithoutAnExtraSort() {
        seed("b", 100, 0);
        seed("a", 100, 0);
        seed("leased", 1000, 1000);
        seed("future", 1000, 0);
        var closed = seed("closed", 10, 0);
        var confirmed = closed.restoreBooking();
        confirmed.confirm(new CarrierReceipt(closed.customerReference(), "carrier-id", "tracking", "/label"));
        assertThat(store.saveOutcome(closed, confirmed, 1)).isTrue();
        var inquiry = seed("inquiry", 80, 0);
        var uncertain = inquiry.restoreBooking();
        uncertain.markOutcomeUnknown();
        assertThat(store.saveOutcome(inquiry, uncertain, 1)).isTrue();
        assertThat(store.discoverUnresolved(500, com.enterprise.shipping.contracts.recovery.Recovery.Cursor.START, 2)).extracting(c -> c.reference().bookingId()).containsExactly("a", "b");
        assertThat(store.discoverUnresolved(500, com.enterprise.shipping.contracts.recovery.Recovery.Cursor.START, 10)).extracting(c -> c.reference().bookingId()).containsExactly("a", "b", "inquiry");
        List<String> plan = plan(JdbcBookingStore.DISCOVERY_SQL, 500, -1, "", 2);
        assertThat(plan).anyMatch(step -> step.contains("USING INDEX idx_booking_unresolved"));
        assertThat(plan).noneMatch(step -> step.contains("TEMP B-TREE") || step.startsWith("SCAN shipment_bookings"));
    }

    @Test void pointLookupsAndFencedWritesUseExistingKeyIndexes() {
        seed("indexed", 0, 0);
        assertThat(plan("SELECT * FROM shipment_bookings WHERE owner_type=? AND owner_id=? AND idempotency_key=?",
                owner.type(), owner.id(), "indexed")).anyMatch(step -> step.contains("owner_type=? AND owner_id=? AND idempotency_key=?"));
        assertThat(plan("SELECT * FROM shipment_bookings WHERE owner_type=? AND owner_id=? AND booking_id=?",
                owner.type(), owner.id(), "indexed")).anyMatch(step -> step.contains("USING INDEX") && step.contains("booking_id=?"));
        assertThat(plan("UPDATE shipment_bookings SET version=version+1 WHERE booking_id=? AND version=?",
                "indexed", 0)).anyMatch(step -> step.contains("USING INDEX") && step.contains("booking_id=?"));
    }

    List<String> plan(String sql, Object... args) {
        return jdbc.query("EXPLAIN QUERY PLAN " + sql, (row, index) -> row.getString("detail"), args);
    }
}
