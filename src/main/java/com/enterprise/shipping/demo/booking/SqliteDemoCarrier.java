package com.enterprise.shipping.demo.booking;

import com.enterprise.shipping.booking.application.BookingRequestHasher;
import com.enterprise.shipping.booking.domain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A durable simulated external system, backed by a different database from shipment_bookings. */
public final class SqliteDemoCarrier {
    private final JdbcTemplate jdbc;
    private final BookingRequestHasher canonical;
    private final ObjectMapper mapper;
    public SqliteDemoCarrier(JdbcTemplate jdbc, BookingRequestHasher canonical, ObjectMapper mapper) {
        this.jdbc = jdbc; this.canonical = canonical; this.mapper = mapper;
    }

    public CarrierReceipt accept(String reference, BookingCommand command) {
        String id = UUID.randomUUID().toString();
        CarrierReceipt candidate = new CarrierReceipt(reference, id,
                "DEMO-" + id.substring(0, 8), "/api/mock/bookings/" + reference + "/label");
        int updated = jdbc.update("""
                INSERT INTO carrier_bookings(customer_reference,request_hash,receipt_json,submission_count)
                VALUES (?,?,?,1)
                ON CONFLICT(customer_reference) DO UPDATE SET submission_count=submission_count+1
                    WHERE carrier_bookings.request_hash=excluded.request_hash
                """, reference, canonical.hash(command), write(candidate));
        if (updated == 0) throw new IllegalArgumentException("Carrier reference has a different request");
        return find(reference).orElseThrow();
    }

    public Optional<CarrierReceipt> find(String reference) {
        return jdbc.query("SELECT receipt_json FROM carrier_bookings WHERE customer_reference=?",
                (row, index) -> read(row.getString(1)), reference).stream().findFirst();
    }

    public int submissionCount(String reference) {
        return jdbc.query("SELECT submission_count FROM carrier_bookings WHERE customer_reference=?",
                (row, index) -> row.getInt(1), reference).stream().findFirst().orElse(0);
    }

    private String write(CarrierReceipt receipt) {
        try { return mapper.writeValueAsString(receipt); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot serialize simulated carrier receipt", error); }
    }

    private CarrierReceipt read(String value) {
        try { return mapper.readValue(value, CarrierReceipt.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid saved carrier receipt", error); }
    }
}
