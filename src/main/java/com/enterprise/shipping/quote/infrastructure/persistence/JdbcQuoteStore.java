package com.enterprise.shipping.quote.infrastructure.persistence;

import com.enterprise.shipping.quote.port.QuoteStore;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.shared.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles persistent database operations for shipping requests and quotes using JdbcTemplate.
 */
public final class JdbcQuoteStore implements QuoteStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcQuoteStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void begin(String user, String id, ShippingIntent intent, long now) {
        List<Object> values = new ArrayList<>();
        values.add(id);
        values.add(user);

        // Flatten sender and recipient address snapshots
        for (var a : List.of(intent.sender(), intent.recipient())) {
            Collections.addAll(
                    values,
                    a.contactName(), a.companyName(), a.addressLine1(), a.addressLine2(),
                    a.city(), a.province(), a.postalCode(), a.countryCode(),
                    a.phone(), a.email(), a.residential() ? "1" : "0"
            );
        }

        // Flatten package specifications and rating metadata
        var p = intent.parcel();
        Collections.addAll(
                values,
                p.length().toPlainString(), p.width().toPlainString(), p.height().toPlainString(),
                p.weight().toPlainString(), p.dimensionUnit().name(), p.weightUnit().name(),
                p.packageType(), intent.shippingDate().toString(), "RATING", now, now
        );

        String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
        jdbc.update("INSERT INTO shipment_requests VALUES (" + placeholders + ")", values.toArray());
    }

    @Override
    public void complete(String user, String id, List<ShippingQuote> quotes, long now) {
        tx.executeWithoutResult(status -> {
            // 1. Insert quotes associated with this request
            for (var q : quotes) {
                jdbc.update(
                        "INSERT INTO shipment_quotes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        q.quoteId(), id, user, q.providerId(), q.accountAlias(), q.providerQuoteId(),
                        q.carrierId(), q.carrierName(), q.serviceId(), q.serviceName(),
                        q.totalMinor(), q.currency(), q.transitDays(), q.expiresAt()
                );
            }

            // 2. Transition rating status to READY or NO_RATES
            jdbc.update(
                    "UPDATE shipment_requests SET state = ?, updated_at = ? WHERE request_id = ? AND user_id = ? AND state = 'RATING'",
                    quotes.isEmpty() ? "NO_RATES" : "READY",
                    now, id, user
            );
        });
    }

    @Override
    public void fail(String user, String id, long now) {
        jdbc.update(
                "UPDATE shipment_requests SET state = 'RATE_FAILED', updated_at = ? WHERE user_id = ? AND request_id = ?",
                now, user, id
        );
    }

    @Override
    public Optional<ShippingQuote> find(String user, String id) {
        String sql = "SELECT * FROM shipment_quotes WHERE user_id = ? AND quote_id = ?";
        return jdbc.query(sql, (rs, n) -> new ShippingQuote(
                rs.getString("quote_id"),
                rs.getString("request_id"),
                rs.getString("provider_id"),
                rs.getString("account_alias"),
                rs.getString("provider_quote_id"),
                rs.getString("carrier_id"),
                rs.getString("carrier_name"),
                rs.getString("service_id"),
                rs.getString("service_name"),
                rs.getLong("total_minor"),
                rs.getString("currency"),
                (Integer) rs.getObject("transit_days"),
                rs.getLong("expires_at")
        ), user, id).stream().findFirst();
    }
}