package com.enterprise.shipping.quote.infrastructure.persistence;

import com.enterprise.shipping.quote.domain.RateRequest;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.QuoteStore;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import com.enterprise.shipping.shared.infrastructure.persistence.UniqueConstraints;
import com.enterprise.shipping.shared.domain.ShippingException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles RDBMS-agnostic persistent database operations for shipping requests and quotes.
 *
 * Uses explicit column projections (never SELECT *) and standard ANSI SQL with
 * Spring DuplicateKeyException translation.
 */
public final class JdbcQuoteStore implements QuoteStore {

    private static final String REQUEST_INSERT_COLUMNS =
            "request_id, user_id, sender_contact_name, sender_company_name, sender_address_line1, sender_address_line2, "
                    + "sender_city, sender_province, sender_postal_code, sender_country_code, sender_phone, sender_email, sender_residential, "
                    + "recipient_contact_name, recipient_company_name, recipient_address_line1, recipient_address_line2, recipient_city, "
                    + "recipient_province, recipient_postal_code, recipient_country_code, recipient_phone, recipient_email, recipient_residential, "
                    + "parcel_length, parcel_width, parcel_height, parcel_weight, dimension_unit, weight_unit, package_type, shipping_date, "
                    + "state, created_at, updated_at, idempotency_key, content_fingerprint, rate_version, rate_deadline_at";

    private static final String REQUEST_SELECT_COLUMNS =
            "request_id, user_id, sender_contact_name, sender_company_name, sender_address_line1, sender_address_line2, "
                    + "sender_city, sender_province, sender_postal_code, sender_country_code, sender_phone, sender_email, sender_residential, "
                    + "recipient_contact_name, recipient_company_name, recipient_address_line1, recipient_address_line2, recipient_city, "
                    + "recipient_province, recipient_postal_code, recipient_country_code, recipient_phone, recipient_email, recipient_residential, "
                    + "parcel_length, parcel_width, parcel_height, parcel_weight, dimension_unit, weight_unit, package_type, shipping_date, "
                    + "state, idempotency_key, content_fingerprint, rate_version, rate_deadline_at";

    private static final String QUOTE_SELECT_COLUMNS =
            "quote_id, request_id, provider_id, account_alias, provider_quote_id, carrier_id, carrier_name, "
                    + "service_id, service_name, total_minor, currency, transit_days, expires_at";

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

        // Flatten sender and recipient address snapshots using integer boolean flags (0/1)
        for (var a : List.of(intent.sender(), intent.recipient())) {
            Collections.addAll(
                    values,
                    a.contactName(),
                    a.companyName(),
                    a.addressLine1(),
                    a.addressLine2(),
                    a.city(),
                    a.province(),
                    a.postalCode(),
                    a.countryCode(),
                    a.phone(),
                    a.email(),
                    a.residential() ? 1 : 0);
        }

        // Flatten normalized package specifications and rating metadata
        var p = intent.parcel();
        Collections.addAll(
                values,
                p.length().toPlainString(),
                p.width().toPlainString(),
                p.height().toPlainString(),
                p.weight().toPlainString(),
                p.dimensionUnit().name(),
                p.weightUnit().name(),
                p.packageType(),
                intent.shippingDate().toString(),
                "RATING",
                now,
                now);

        Collections.addAll(
                values,
                request.key(),
                request.fingerprint(),
                request.version(),
                request.deadline());

        String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));
        try {
            return jdbc.update(
                            "INSERT INTO shipment_requests ("
                                    + REQUEST_INSERT_COLUMNS
                                    + ") VALUES ("
                                    + placeholders
                                    + ")",
                            values.toArray())
                    == 1;
        } catch (DataAccessException failure) {
            if (UniqueConstraints.isUniqueViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }

    @Override
    public Optional<RateRequest> byKey(String user, String key) {
        // Enforce exact case-sensitive key comparison in Java to guard against case-insensitive DB
        // collations
        return jdbc
                .query(
                        "SELECT "
                                + REQUEST_SELECT_COLUMNS
                                + " FROM shipment_requests WHERE user_id = ? AND idempotency_key = ?",
                        JdbcQuoteStore::request,
                        user,
                        key)
                .stream()
                .filter(r -> key.equals(r.key()))
                .findFirst();
    }

    @Override
    public Optional<RateRequest> request(String user, String id) {
        return jdbc
                .query(
                        "SELECT "
                                + REQUEST_SELECT_COLUMNS
                                + " FROM shipment_requests WHERE user_id = ? AND request_id = ?",
                        JdbcQuoteStore::request,
                        user,
                        id)
                .stream()
                .findFirst();
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
                        expected.state().name())
                == 1;
    }

    @Override
    public Completion complete(RateRequest expected, List<ShippingQuote> quotes, long now) {
        List<ShippingQuote> saved = List.copyOf(quotes);
        return Objects.requireNonNull(
                tx.execute(
                        status -> {
                            // An old execution may arrive after a newer claim. Do not apply its
                            // quotes.
                            if (expected.overdue(now)) {
                                // End only this expired execution. The original version fence
                                // prevents a late completion from failing a newer execution.
                                if (fail(expected, now)) {
                                    return new Completion(expected.failed(), List.of(), false);
                                }
                                return currentCompletion(expected);
                            }
                            var completed = expected.completed(!saved.isEmpty(), now);
                            // Fence first and clear rate_deadline_at to NULL; any quote insert
                            // failure rolls back both
                            int applied =
                                    jdbc.update(
                                            """
                    UPDATE shipment_requests
                    SET state = ?, rate_deadline_at = NULL, updated_at = ?
                    WHERE user_id = ? AND request_id = ? AND state = 'RATING'
                      AND rate_version = ? AND rate_deadline_at > ?
                    """,
                                            completed.state().name(),
                                            now,
                                            expected.userId(),
                                            expected.requestId(),
                                            expected.version(),
                                            now);
                            if (applied == 0) {
                                return currentCompletion(expected);
                            }

                            jdbc.update(
                                    """
                    DELETE FROM shipment_quotes WHERE user_id = ? AND request_id = ?
                    """,
                                    expected.userId(),
                                    expected.requestId());

                            if (!saved.isEmpty()) {
                                // Reuse one prepared statement; batch failure rolls back the entire
                                // replacement.
                                jdbc.batchUpdate(
                                        """
                        INSERT INTO shipment_quotes (
                            quote_id, request_id, user_id, provider_id, account_alias, provider_quote_id,
                            carrier_id, carrier_name, service_id, service_name, total_minor, currency, transit_days, expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                                        saved,
                                        saved.size(),
                                        (statement, q) -> {
                                            statement.setString(1, q.quoteId());
                                            statement.setString(2, expected.requestId());
                                            statement.setString(3, expected.userId());
                                            statement.setString(4, q.providerId());
                                            statement.setString(5, q.accountAlias());
                                            statement.setString(6, q.providerQuoteId());
                                            statement.setString(7, q.carrierId());
                                            statement.setString(8, q.carrierName());
                                            statement.setString(9, q.serviceId());
                                            statement.setString(10, q.serviceName());
                                            statement.setLong(11, q.totalMinor());
                                            statement.setString(12, q.currency());
                                            statement.setObject(
                                                    13, q.transitDays(), Types.SMALLINT);
                                            statement.setLong(14, q.expiresAt());
                                        });
                            }
                            return new Completion(completed, saved, true);
                        }),
                "Completion transaction returned no result");
    }

    private Completion currentCompletion(RateRequest expected) {
        // Called inside complete's short transaction. SQLite gives these two reads one
        // snapshot; other database engines need equivalent isolation before deployment.
        var current =
                request(expected.userId(), expected.requestId())
                        .orElseThrow(ShippingException::missing);
        var offers =
                current.state() == RateRequest.State.READY
                        ? quotes(current.userId(), current.requestId())
                        : List.<ShippingQuote>of();
        return new Completion(current, offers, false);
    }

    @Override
    public boolean fail(RateRequest expected, long now) {
        var failed = expected.failed();
        return jdbc.update(
                        """
                UPDATE shipment_requests
                SET state = ?, rate_deadline_at = NULL, updated_at = ?
                WHERE user_id = ? AND request_id = ? AND state = 'RATING' AND rate_version = ?
                """,
                        failed.state().name(),
                        now,
                        expected.userId(),
                        expected.requestId(),
                        expected.version())
                == 1;
    }

    @Override
    public List<ShippingQuote> quotes(String user, String id) {
        return jdbc.query(
                "SELECT "
                        + QUOTE_SELECT_COLUMNS
                        + " FROM shipment_quotes WHERE user_id = ? AND request_id = ? ORDER BY total_minor, quote_id",
                JdbcQuoteStore::quote,
                user,
                id);
    }

    @Override
    public Optional<ShippingQuote> find(String user, String id) {
        return jdbc
                .query(
                        "SELECT "
                                + QUOTE_SELECT_COLUMNS
                                + " FROM shipment_quotes WHERE user_id = ? AND quote_id = ?",
                        JdbcQuoteStore::quote,
                        user,
                        id)
                .stream()
                .findFirst();
    }

    private static RateRequest request(ResultSet row, int ignored) throws SQLException {
        var intent =
                new ShippingIntent(
                        address(row, "sender"),
                        address(row, "recipient"),
                        new ShippingIntent.Package(
                                new BigDecimal(row.getString("parcel_length")),
                                new BigDecimal(row.getString("parcel_width")),
                                new BigDecimal(row.getString("parcel_height")),
                                DimensionUnit.valueOf(row.getString("dimension_unit")),
                                new BigDecimal(row.getString("parcel_weight")),
                                WeightUnit.valueOf(row.getString("weight_unit")),
                                row.getString("package_type")),
                        LocalDate.parse(row.getString("shipping_date")));

        long rawDeadline = row.getLong("rate_deadline_at");
        Long deadline = row.wasNull() ? null : rawDeadline;

        return new RateRequest(
                row.getString("user_id"),
                row.getString("request_id"),
                row.getString("idempotency_key"),
                row.getString("content_fingerprint"),
                intent,
                RateRequest.State.valueOf(row.getString("state")),
                row.getLong("rate_version"),
                deadline);
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
                row.getInt(prefix + "_residential") == 1);
    }

    private static ShippingQuote quote(ResultSet rs, int ignored) throws SQLException {
        Number rawTransit = (Number) rs.getObject("transit_days");
        Integer transitDays = rawTransit == null ? null : rawTransit.intValue();
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
                transitDays,
                rs.getLong("expires_at"));
    }
}
