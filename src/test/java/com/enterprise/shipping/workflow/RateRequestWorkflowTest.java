package com.enterprise.shipping.workflow;

import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.quote.domain.RateRequest;
import com.enterprise.shipping.quote.domain.ShippingIntentFingerprint;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.infrastructure.persistence.JdbcQuoteStore;
import com.enterprise.shipping.quote.port.RateComparisonPort;
import com.enterprise.shipping.shared.api.WorkflowException;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.enterprise.shipping.shared.infrastructure.persistence.WorkflowMigrations;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies real SQLite uniqueness, version-fenced rate claims, atomic quote persistence,
 * and schema migration preservation for idempotent rate requests.
 */
class RateRequestWorkflowTest {

    @TempDir
    Path directory;

    HikariDataSource source;
    JdbcQuoteStore store;
    JdbcTemplate jdbc;

    final MutableClock clock = new MutableClock();
    final AtomicInteger calls = new AtomicInteger();

    RateComparisonPort rates = (intent, deadline) -> {
        calls.incrementAndGet();
        return offers("provider-quote", 2735);
    };

    final ShippingAddress address = new ShippingAddress(
            "Test User", null, "100 Test St", null, "Toronto", "ON",
            "M5V 3L9", "CA", "4165550100", null, false
    );

    final ShippingIntent intent = new ShippingIntent(
            address,
            address,
            new ShippingIntent.Package(
                    new BigDecimal("12"),
                    new BigDecimal("10"),
                    new BigDecimal("8"),
                    DimensionUnit.IN,
                    new BigDecimal("5"),
                    WeightUnit.LB,
                    "PACKAGE"
            ),
            null
    );

    @BeforeEach
    void open() {
        source = SqliteDataSources.open(
                directory.resolve("rates.db").toString(),
                "rates-test",
                4,
                3000,
                "db/workflow/bootstrap.sql"
        );
        WorkflowMigrations.migrate(
                source,
                "V001__quotes.sql",
                "V002__bookings.sql",
                "V003__rate_request_idempotency.sql"
        );
        jdbc = new JdbcTemplate(source);
        store = new JdbcQuoteStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @AfterEach
    void close() {
        if (source != null) {
            source.close();
        }
    }

    QuoteApplicationService service() {
        return new QuoteApplicationService(store, rates, clock, Duration.ofSeconds(1), ZoneOffset.UTC, (a, b) -> {});
    }

    List<ShippingQuote> offers(String token, long amount) {
        return List.of(new ShippingQuote(
                null, null, "sandbox", "account", token, "14", "Carrier", "1", "Ground",
                amount, "CAD", 2, clock.millis() + 600000
        ));
    }

    @Test
    void sameKeyReplaysCanonicalContentAndRetainsOwnBusinessSnapshot() {
        var first = service().create("user", "same-key", intent);
        var equivalent = new ShippingIntent(
                address,
                address,
                new ShippingIntent.Package(
                        new BigDecimal("12.00"),
                        new BigDecimal("10.0"),
                        new BigDecimal("8"),
                        DimensionUnit.IN,
                        new BigDecimal("5.000"),
                        WeightUnit.LB,
                        "PACKAGE"
                ),
                null
        );

        var replay = service().create("user", "same-key", equivalent);
        assertThat(replay.status()).isEqualTo(HttpStatus.OK);
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(calls).hasValue(1);
        assertThat(count("shipment_requests")).isEqualTo(1);
        assertThat(store.request("user", first.result().requestId()).orElseThrow().intent().sender().addressLine1())
                .isEqualTo("100 Test St");

        assertThatThrownBy(() -> service().create("user", "same-key", new ShippingIntent(
                address,
                address,
                new ShippingIntent.Package(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE, null, null),
                null
        ))).isInstanceOfSatisfying(
                WorkflowException.class,
                error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT")
        );
        assertThat(calls).hasValue(1);
    }

    @Test
    void failureAndExplicitRetryReuseTheRequestWithoutAnErrorColumn() {
        rates = (input, deadline) -> {
            calls.incrementAndGet();
            throw new WorkflowException("PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
        };

        AtomicReference<String> id = new AtomicReference<>();
        assertThatThrownBy(() -> service().create("user", "retry-key", intent))
                .isInstanceOfSatisfying(WorkflowException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    id.set(error.operation().id());
                });

        assertThat(service().get("user", id.get()).state()).isEqualTo("RATE_FAILED");
        assertThat(calls).hasValue(1);

        rates = (input, deadline) -> {
            calls.incrementAndGet();
            return offers("retry-success", 2735);
        };

        var retry = service().create("user", "retry-key", intent);
        assertThat(retry.result().requestId()).isEqualTo(id.get());
        assertThat(retry.result().state()).isEqualTo("READY");
        assertThat(store.byKey("user", "retry-key").orElseThrow().version()).isEqualTo(1);
        assertThat(count("shipment_requests")).isEqualTo(1);
        assertThat(jdbc.queryForList("PRAGMA table_info(shipment_requests)").stream().map(row -> row.get("name")))
                .doesNotContain("last_error_code");
    }

    @Test
    void concurrentMatchingRequestsHaveOneLiveProviderExecution() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rates = (input, deadline) -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Provider not released");
                }
            } catch (InterruptedException error) {
                throw new IllegalStateException(error);
            }
            return offers("winner", 2735);
        };

        var workers = Executors.newFixedThreadPool(8);
        try {
            var winner = workers.submit(() -> service().create("user", "race-key", intent));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            var followers = new ArrayList<Future<QuoteApplicationService.Submission>>();
            for (int i = 0; i < 7; i++) {
                followers.add(workers.submit(() -> service().create("user", "race-key", intent)));
            }

            Set<String> ids = new HashSet<>();
            for (var follower : followers) {
                var result = follower.get(5, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(HttpStatus.ACCEPTED);
                ids.add(result.result().requestId());
            }
            assertThat(ids).hasSize(1);
            assertThat(calls).hasValue(1);

            release.countDown();
            assertThat(winner.get(5, TimeUnit.SECONDS).result().requestId()).isIn(ids);
            assertThat(count("shipment_requests")).isEqualTo(1);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void overdueExecutionCannotPublishAfterANewerRetryWins() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rates = (input, deadline) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Old execution not released");
                    }
                } catch (InterruptedException error) {
                    throw new IllegalStateException(error);
                }
            }
            return offers(call == 1 ? "stale-token" : "current-token", 2735);
        };

        var worker = Executors.newSingleThreadExecutor();
        try {
            var old = worker.submit(() -> service().create("user", "lease-key", intent));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            String id = store.byKey("user", "lease-key").orElseThrow().requestId();
            clock.advance(Duration.ofSeconds(12));
            assertThat(service().get("user", id).state()).isEqualTo("RATE_FAILED");

            var retry = service().create("user", "lease-key", intent);
            release.countDown();
            old.get(5, TimeUnit.SECONDS);

            assertThat(retry.result().requestId()).isEqualTo(id);
            assertThat(store.quotes("user", id))
                    .extracting(ShippingQuote::providerQuoteId)
                    .containsExactly("current-token");
            assertThat(count("shipment_requests")).isEqualTo(1);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void failedQuoteInsertRollsBackOffersAndSuccessfulState() {
        rates = (input, deadline) -> offers("invalid-price", -1);
        assertThatThrownBy(() -> service().create("user", "storage-key", intent))
                .isInstanceOfSatisfying(
                        WorkflowException.class,
                        error -> assertThat(error.code()).isEqualTo("STORE_UNAVAILABLE")
                );

        assertThat(store.byKey("user", "storage-key").orElseThrow().state())
                .isEqualTo(RateRequest.State.RATE_FAILED);
        assertThat(count("shipment_quotes")).isZero();
    }

    @Test
    void reopeningPreservesAbandonedClaimAndAllowsAnExplicitRetry() {
        var dated = intent.dated(LocalDate.of(2026, 10, 23));
        var claim = new RateRequest(
                "user",
                "abandoned",
                "restart-key",
                ShippingIntentFingerprint.of(dated),
                dated,
                RateRequest.State.RATING,
                0,
                clock.millis() + 11000
        );
        assertThat(store.insert(claim, clock.millis())).isTrue();

        source.close();
        open();
        clock.advance(Duration.ofSeconds(12));

        assertThat(service().get("user", "abandoned").state()).isEqualTo("RATE_FAILED");
        var retry = service().create("user", "restart-key", intent);
        assertThat(retry.result().requestId()).isEqualTo("abandoned");
        assertThat(calls).hasValue(1);
    }

    @Test
    void ownershipAndDefaultDateRemainStableAcrossReplays() {
        var first = service().create("user", "owner-key", intent);
        clock.advance(Duration.ofDays(1));

        assertThatThrownBy(() -> service().create("user", "owner-key", intent))
                .isInstanceOfSatisfying(
                        WorkflowException.class,
                        error -> assertThat(error.code()).isEqualTo("REQUEST_DATE_EXPIRED")
                );
        assertThatThrownBy(() -> service().get("other", first.result().requestId()))
                .isInstanceOfSatisfying(
                        WorkflowException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND")
                );
        assertThat(count("shipment_requests")).isEqualTo(1);
    }

    @Test
    void expiredQuotesRefreshUnderTheSameRequest() {
        var first = service().create("user", "expiry-key", intent);
        clock.advance(Duration.ofMinutes(11));

        assertThat(service().get("user", first.result().requestId()).state()).isEqualTo("QUOTES_EXPIRED");
        var refreshed = service().create("user", "expiry-key", intent);
        assertThat(refreshed.result().requestId()).isEqualTo(first.result().requestId());
        assertThat(refreshed.result().quotes()).hasSize(1);
        assertThat(calls).hasValue(2);
        assertThat(count("shipment_requests")).isEqualTo(1);
    }

    @Test
    void migrationPreservesLegacyRowsAndMaintainsFourApplicationTablesAndIndexes() {
        try (var legacySource = SqliteDataSources.open(
                directory.resolve("legacy-upgrade.db").toString(),
                "legacy-upgrade",
                2,
                3000,
                "db/workflow/bootstrap.sql")) {

            WorkflowMigrations.migrate(legacySource, "V001__quotes.sql", "V002__bookings.sql");
            var legacyJdbc = new JdbcTemplate(legacySource);
            legacyJdbc.update("""
                    INSERT INTO shipment_requests (
                        request_id, user_id, sender_contact_name, sender_company_name, sender_address_line1, sender_address_line2,
                        sender_city, sender_province, sender_postal_code, sender_country_code, sender_phone, sender_email, sender_residential,
                        recipient_contact_name, recipient_company_name, recipient_address_line1, recipient_address_line2, recipient_city,
                        recipient_province, recipient_postal_code, recipient_country_code, recipient_phone, recipient_email, recipient_residential,
                        length, width, height, weight, dimension_unit, weight_unit, package_type, shipping_date, state, created_at, updated_at
                    ) VALUES (
                        'legacy-req-1', 'user', 'Sender', null, '100 King St', null, 'Toronto', 'ON', 'M5V 3L9', 'CA', '4165550100', null, '0',
                        'Recipient', null, '200 Queen St', null, 'Halifax', 'NS', 'B3J 3K1', 'CA', '9025550100', null, '0',
                        '12', '10', '8', '5', 'IN', 'LB', 'PACKAGE', '2026-10-23', 'READY', 1000, 1000
                    )
                    """);

            // Apply V003 migration onto the populated V001/V002 database
            WorkflowMigrations.migrate(
                    legacySource,
                    "V001__quotes.sql",
                    "V002__bookings.sql",
                    "V003__rate_request_idempotency.sql"
            );

            var upgradedStore = new JdbcQuoteStore(
                    legacyJdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(legacySource))
            );
            var legacyRow = upgradedStore.request("user", "legacy-req-1").orElseThrow();
            assertThat(legacyRow.key()).isNull();
            assertThat(legacyRow.version()).isZero();
            assertThat(legacyRow.state()).isEqualTo(RateRequest.State.READY);

            // Exactly 4 application tables plus schema_migrations metadata
            var tables = legacyJdbc.queryForList(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                    String.class
            );
            assertThat(tables).containsExactlyInAnyOrder(
                    "shipment_requests",
                    "shipment_quotes",
                    "shipment_bookings",
                    "booking_reconciliation_tasks",
                    "schema_migrations"
            );

            var keyPlan = String.join(" ", legacyJdbc.query(
                    "EXPLAIN QUERY PLAN SELECT * FROM shipment_requests WHERE user_id = ? AND idempotency_key = ?",
                    (rs, n) -> rs.getString("detail"),
                    "user",
                    "key-1"
            ));
            assertThat(keyPlan).contains("uk_shipment_request_key");

            var quotesPlan = String.join(" ", legacyJdbc.query(
                    "EXPLAIN QUERY PLAN SELECT * FROM shipment_quotes WHERE user_id = ? AND request_id = ?",
                    (rs, n) -> rs.getString("detail"),
                    "user",
                    "legacy-req-1"
            ));
            assertThat(quotesPlan).contains("idx_request_quotes");
        }
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static final class MutableClock extends Clock {
        final AtomicLong time = new AtomicLong(Instant.parse("2026-10-23T12:00:00Z").toEpochMilli());

        void advance(Duration value) {
            time.addAndGet(value.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(time.get());
        }

        @Override
        public long millis() {
            return time.get();
        }
    }
}
