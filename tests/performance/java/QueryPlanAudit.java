import com.enterprise.shipping.booking.infrastructure.persistence.JdbcShipmentStore;
import com.enterprise.shipping.quote.infrastructure.persistence.JdbcQuoteStore;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;

/** Supplementary read-only EXPLAIN audit; UPDATE statements are explained, never executed. */
public class QueryPlanAudit {
    record Probe(String name, String sql, Object[] parameters) {}

    static String columns(Class<?> type, String field) throws Exception {
        var f = type.getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]).toAbsolutePath();
        if (!database.toString().startsWith("/tmp/")
                && !database.toString().startsWith("/private/tmp/")) {
            throw new IllegalArgumentException("Temporary test database only");
        }
        try (var ds =
                SqliteDataSources.open(
                        database.toString(), "plan-audit", 1, 3000, "db/shipping/bootstrap.sql")) {
            var jdbc = new JdbcTemplate(ds);
            var r =
                    jdbc.queryForMap(
                            "SELECT request_id,user_id,idempotency_key FROM shipment_requests LIMIT 1");
            var b =
                    jdbc.queryForMap(
                            "SELECT booking_id,user_id,request_id,idempotency_key,version FROM shipment_bookings LIMIT 1");
            var q =
                    jdbc.queryForMap(
                            "SELECT quote_id,user_id,request_id FROM shipment_quotes LIMIT 1");
            var t =
                    jdbc.queryForMap(
                            "SELECT task_id,user_id,booking_id FROM booking_reconciliation_tasks LIMIT 1");
            String rc = columns(JdbcQuoteStore.class, "REQUEST_SELECT_COLUMNS"),
                    qc = columns(JdbcQuoteStore.class, "QUOTE_SELECT_COLUMNS"),
                    bc = columns(JdbcShipmentStore.class, "BOOKING_SELECT_COLUMNS");
            List<Probe> probes =
                    List.of(
                            new Probe(
                                    "request_idempotency",
                                    "SELECT "
                                            + rc
                                            + " FROM shipment_requests WHERE user_id=? AND idempotency_key=?",
                                    new Object[] {r.get("user_id"), r.get("idempotency_key")}),
                            new Probe(
                                    "request_by_id",
                                    "SELECT "
                                            + rc
                                            + " FROM shipment_requests WHERE user_id=? AND request_id=?",
                                    new Object[] {r.get("user_id"), r.get("request_id")}),
                            new Probe(
                                    "request_state",
                                    "SELECT state FROM shipment_requests WHERE user_id=? AND request_id=?",
                                    new Object[] {r.get("user_id"), r.get("request_id")}),
                            new Probe(
                                    "booking_idempotency",
                                    "SELECT "
                                            + bc
                                            + " FROM shipment_bookings WHERE user_id=? AND idempotency_key=?",
                                    new Object[] {b.get("user_id"), b.get("idempotency_key")}),
                            new Probe(
                                    "booking_by_request",
                                    "SELECT "
                                            + bc
                                            + " FROM shipment_bookings WHERE user_id=? AND request_id=?",
                                    new Object[] {b.get("user_id"), b.get("request_id")}),
                            new Probe(
                                    "booking_by_id",
                                    "SELECT "
                                            + bc
                                            + " FROM shipment_bookings WHERE user_id=? AND booking_id=?",
                                    new Object[] {b.get("user_id"), b.get("booking_id")}),
                            new Probe(
                                    "quote_by_id_and_expiry_read",
                                    "SELECT "
                                            + qc
                                            + " FROM shipment_quotes WHERE user_id=? AND quote_id=?",
                                    new Object[] {q.get("user_id"), q.get("quote_id")}),
                            new Probe(
                                    "request_quotes",
                                    "SELECT "
                                            + qc
                                            + " FROM shipment_quotes WHERE user_id=? AND request_id=? ORDER BY total_minor,quote_id",
                                    new Object[] {q.get("user_id"), q.get("request_id")}),
                            new Probe(
                                    "task_dedup",
                                    "SELECT task_id FROM booking_reconciliation_tasks WHERE user_id=? AND booking_id=?",
                                    new Object[] {t.get("user_id"), t.get("booking_id")}),
                            new Probe(
                                    "booking_cas",
                                    "UPDATE shipment_bookings SET version=version+1 WHERE user_id=? AND booking_id=? AND version=? AND status IN ('PENDING','PENDING_INQUIRY')",
                                    new Object[] {
                                        b.get("user_id"), b.get("booking_id"), b.get("version")
                                    }),
                            new Probe(
                                    "request_claim_cas",
                                    "UPDATE shipment_requests SET state='RATING',rate_version=rate_version+1 WHERE user_id=? AND request_id=? AND rate_version=? AND state=?",
                                    new Object[] {
                                        r.get("user_id"), r.get("request_id"), 0, "RATE_FAILED"
                                    }),
                            new Probe(
                                    "synthetic_task_due",
                                    "SELECT task_id,user_id,booking_id FROM booking_reconciliation_tasks WHERE state='WAITING' AND available_at<=? ORDER BY available_at,task_id LIMIT ?",
                                    new Object[] {Long.MAX_VALUE, 100}));
            Map<String, Object> results = new LinkedHashMap<>();
            boolean indexed = true;
            for (var probe : probes) {
                var plan = jdbc.queryForList("EXPLAIN QUERY PLAN " + probe.sql, probe.parameters);
                boolean search =
                        plan.stream()
                                .anyMatch(row -> row.get("detail").toString().startsWith("SEARCH"));
                indexed &= search;
                results.put(
                        probe.name,
                        Map.of("sql", probe.sql, "plan", plan, "indexed_search", search));
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("queries", results);
            report.put("all_indexed", indexed);
            report.put("sqlite", jdbc.queryForObject("SELECT sqlite_version()", String.class));
            report.put(
                    "booking_states",
                    jdbc.queryForList(
                            "SELECT status,count(*) AS rows FROM shipment_bookings GROUP BY status"));
            report.put(
                    "request_states",
                    jdbc.queryForList(
                            "SELECT state,count(*) AS rows FROM shipment_requests GROUP BY state"));
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(Path.of(args[1]).toFile(), report);
            if (!indexed) {
                throw new IllegalStateException(
                        "Unexplained non-indexed core query; inspect report");
            }
        }
    }
}
