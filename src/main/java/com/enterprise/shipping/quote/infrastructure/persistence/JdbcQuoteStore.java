package com.enterprise.shipping.quote.infrastructure.persistence;

import com.enterprise.shipping.quote.domain.RateRequest;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.QuoteStore;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
    public boolean insert(RateRequest request, long now) {
        String user = request.userId();
        String id = request.requestId();
        ShippingIntent intent = request.intent();
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

        Collections.addAll(values, request.key(), request.fingerprint(), request.version(), request.deadline());

        String columns = "request_id,user_id,sender_contact_name,sender_company_name,sender_address_line1,sender_address_line2,"
                + "sender_city,sender_province,sender_postal_code,sender_country_code,sender_phone,sender_email,sender_residential,"
                + "recipient_contact_name,recipient_company_name,recipient_address_line1,recipient_address_line2,recipient_city,"
                + "recipient_province,recipient_postal_code,recipient_country_code,recipient_phone,recipient_email,recipient_residential,"
                + "length,width,height,weight,dimension_unit,weight_unit,package_type,shipping_date,state,created_at,updated_at,"
                + "idempotency_key,content_fingerprint,rate_version,rate_deadline_at";
        String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));

        // Persist the customer's shipping intent independently of browser drafts and external providers
        return jdbc.update(
                "INSERT INTO shipment_requests (" + columns + ") VALUES (" + placeholders
                        + ") ON CONFLICT(user_id, idempotency_key) DO NOTHING",
                values.toArray()
        ) == 1;
    }

    @Override
    public Optional<RateRequest> byKey(String user, String key) {
        return jdbc.query(
                "SELECT * FROM shipment_requests WHERE user_id = ? AND idempotency_key = ?",
                JdbcQuoteStore::request,
                user,
                key
        ).stream().findFirst();
    }

    @Override
    public Optional<RateRequest> request(String user, String id) {
        return jdbc.query(
                "SELECT * FROM shipment_requests WHERE user_id = ? AND request_id = ?",
                JdbcQuoteStore::request,
                user,
                id
        ).stream().findFirst();
    }

    @Override
    public boolean claim(RateRequest expected, RateRequest claimed, long now) {
        return jdbc.update(
                "UPDATE shipment_requests SET state = 'RATING', rate_version = ?, rate_deadline_at = ?, updated_at = ? "
                        + "WHERE user_id = ? AND request_id = ? AND rate_version = ? AND state = ?",
                claimed.version(),
                claimed.deadline(),
                now,
                expected.userId(),
                expected.requestId(),
                expected.version(),
                expected.state().name()
        ) == 1;
    }

    @Override
    public boolean complete(RateRequest expected, List<ShippingQuote> quotes, long now) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            // Fence first, then save offers in the same transaction; any insert failure rolls back both
            int applied = jdbc.update(
                    "UPDATE shipment_requests SET state = ?, updated_at = ? WHERE user_id = ? AND request_id = ? "
                            + "AND state = 'RATING' AND rate_version = ? AND rate_deadline_at > ?",
                    quotes.isEmpty() ? "NO_RATES" : "READY",
                    now,
                    expected.userId(),
                    expected.requestId(),
                    expected.version(),
                    now
            );
            if (applied == 0) {
                return false;
            }

            jdbc.update(
                    "DELETE FROM shipment_quotes WHERE user_id = ? AND request_id = ?",
                    expected.userId(),
                    expected.requestId()
            );

            for (var q : quotes) {
                jdbc.update(
                        "INSERT INTO shipment_quotes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        q.quoteId(),
                        expected.requestId(),
                        expected.userId(),
                        q.providerId(),
                        q.accountAlias(),
                        q.providerQuoteId(),
                        q.carrierId(),
                        q.carrierName(),
                        q.serviceId(),
                        q.serviceName(),
                        q.totalMinor(),
                        q.currency(),
                        q.transitDays(),
                        q.expiresAt()
                );
            }
            return true;
        }));
    }

    @Override
    public boolean fail(RateRequest expected, long now) {
        return jdbc.update(
                "UPDATE shipment_requests SET state = 'RATE_FAILED', updated_at = ? WHERE user_id = ? AND request_id = ? "
                        + "AND state = 'RATING' AND rate_version = ?",
                now,
                expected.userId(),
                expected.requestId(),
                expected.version()
        ) == 1;
    }

    @Override
    public List<ShippingQuote> quotes(String user, String id) {
        return jdbc.query(
                "SELECT * FROM shipment_quotes WHERE user_id = ? AND request_id = ? ORDER BY total_minor, quote_id",
                JdbcQuoteStore::quote,
                user,
                id
        );
    }

    @Override
    public Optional<ShippingQuote> find(String user, String id) {
        return jdbc.query(
                "SELECT * FROM shipment_quotes WHERE user_id = ? AND quote_id = ?",
                JdbcQuoteStore::quote,
                user,
                id
        ).stream().findFirst();
    }

    private static RateRequest request(ResultSet row, int ignored) throws SQLException {
        var intent = new ShippingIntent(
                address(row, "sender"),
                address(row, "recipient"),
                new ShippingIntent.Package(
                        new BigDecimal(row.getString("length")),
                        new BigDecimal(row.getString("width")),
                        new BigDecimal(row.getString("height")),
                        DimensionUnit.valueOf(row.getString("dimension_unit")),
                        new BigDecimal(row.getString("weight")),
                        WeightUnit.valueOf(row.getString("weight_unit")),
                        row.getString("package_type")
                ),
                LocalDate.parse(row.getString("shipping_date"))
        );

        return new RateRequest(
                row.getString("user_id"),
                row.getString("request_id"),
                row.getString("idempotency_key"),
                row.getString("content_fingerprint"),
                intent,
                RateRequest.State.valueOf(row.getString("state")),
                row.getLong("rate_version"),
                row.getLong("rate_deadline_at")
        );
    }

    private static ShippingAddress address(ResultSet row, String prefix) throws SQLException {
        return new ShippingAddress(
                row.getString(prefix + "_contact_name"),
                row.getString(prefix + "_company_name"),
                row.getString(prefix + "_address_line1"),
                row.getString(prefix + "_address_line2"),
                row.getString(prefix + "_city"),
                row.getString(prefix + "_province"),
                row.getString(prefix + "_postal_code"),
                row.getString(prefix + "_country_code"),
                row.getString(prefix + "_phone"),
                row.getString(prefix + "_email"),
                "1".equals(row.getString(prefix + "_residential"))
        );
    }

    private static ShippingQuote quote(ResultSet rs, int ignored) throws SQLException {
        return new ShippingQuote(
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
        );
    }
}
