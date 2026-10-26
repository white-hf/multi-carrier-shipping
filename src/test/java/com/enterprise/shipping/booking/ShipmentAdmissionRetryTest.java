package com.enterprise.shipping.booking;

import com.enterprise.shipping.booking.application.ShipmentApplicationService;
import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.infrastructure.persistence.JdbcShipmentStore;
import com.enterprise.shipping.booking.port.*;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.persistence.ShippingMigrations;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real SQLite constraints/CAS across concurrent services and reopened connections. */
class ShipmentAdmissionRetryTest {
    @TempDir Path directory;
    HikariDataSource source;
    JdbcShipmentStore store;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
    final AtomicInteger dispatches = new AtomicInteger();
    final QuoteSelectionPort.Selection selection = new QuoteSelectionPort.Selection(
            "request-1", "quote-1", "sandbox", "account", "provider-quote", "14", "ground",
            2735, "CAD", clock.millis() + 60000);
    final LabelProviderPort labels = new LabelProviderPort() {
        public Label fetch(Identity identity) { return null; }
        public boolean allowedUrl(String url) { return false; }
    };

    @BeforeEach void open() {
        source = SqliteDataSources.open(directory.resolve("retry.db").toString(), "retry-test", 4, 3000,
                "db/shipping/bootstrap.sql");
        ShippingMigrations.migrate(source, "V001__shipping_schema.sql");
        store = new JdbcShipmentStore(new JdbcTemplate(source));
    }

    @AfterEach void close() { if (source != null) source.close(); }

    ShipmentApplicationService service(ShipmentStore storage, ShipmentProviderPort provider, Clock time) {
        return new ShipmentApplicationService(storage, (user, quote) -> selection, provider, labels, time,
                Duration.ofSeconds(1), (operation, outcome, elapsed) -> {});
    }

    ShipmentProviderPort.Outcome accept() {
        dispatches.incrementAndGet();
        return new ShipmentProviderPort.Outcome("shipment-1", "tracking-1", null, false);
    }

    ShipmentStore.Record unsent() {
        assertThatThrownBy(() -> service(store, submission -> {
            throw new ShipmentNotDispatchedException(ShipmentNotDispatchedException.Reason.BULKHEAD_FULL);
        }, clock).create("user", "same-key", "quote-1"))
                .isInstanceOfSatisfying(ShippingException.class,
                        e -> assertThat(e.code()).isEqualTo("SHIPMENT_SUBMISSION_NOT_DISPATCHED"));
        var row = store.byKey("user", "same-key").orElseThrow();
        assertThat(row.status()).isEqualTo(BookingStatus.RETRYABLE);
        assertThat(store.overdue(Long.MAX_VALUE, -1, "", 100)).isEmpty();
        assertThat(dispatches).hasValue(0);
        return row;
    }

    @Test void retrySurvivesRestartAndKeepsTheOriginalIdentity() {
        var original = unsent();
        source.close();
        open();
        var result = service(store, submission -> accept(), clock).create("user", "same-key", "quote-1");
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.booking().bookingId()).isEqualTo(original.id());
        var row = store.byKey("user", "same-key").orElseThrow();
        assertThat(row.version()).isEqualTo(original.version() + 2);
        assertThat(row.errorCode()).isNull();
        assertThat(dispatches).hasValue(1);
    }

    @Test void concurrentRetriesHaveExactlyOneDispatchWinnerAndFenceStaleWrites() throws Exception {
        var original = unsent();
        var raced = spy(store);
        var barrier = new CyclicBarrier(8);
        doAnswer(call -> {
            barrier.await(5, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(raced).claimRetry(any(), anyLong(), anyLong());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service(raced, submission -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release not signalled");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return accept();
        }, clock);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<ShipmentApplicationService.Submission>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> service.create("user", "same-key", "quote-1")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // A late outcome from the previous version cannot overwrite the new attempt.
            var stale = original.restore();
            stale.prepareRetry();
            stale.markNotDispatched();
            assertThat(store.outcome(original, stale, clock.millis())).isFalse();
            release.countDown();
            for (var future : futures) {
                var result = future.get(10, TimeUnit.SECONDS);
                assertThat(result.booking().bookingId()).isEqualTo(original.id());
                assertThat(result.statusCode()).isIn(200, 201, 202);
            }
            assertThat(dispatches).hasValue(1);
            assertThat(store.byKey("user", "same-key").orElseThrow().status()).isEqualTo(BookingStatus.CONFIRMED);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void conflictingKeysPayloadsAndExpiredQuotesNeverDispatch() {
        var row = unsent();
        var service = service(store, submission -> accept(), clock);
        assertThatThrownBy(() -> service.create("user", "same-key", "other-quote"))
                .isInstanceOfSatisfying(ShippingException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(service.create("user", "other-key", "quote-1").statusCode()).isEqualTo(409);
        var expired = service(store, submission -> accept(), Clock.offset(clock, Duration.ofMinutes(2)));
        assertThatThrownBy(() -> expired.create("user", "same-key", "quote-1"))
                .isInstanceOfSatisfying(ShippingException.class, e -> assertThat(e.code()).isEqualTo("QUOTE_EXPIRED"));
        assertThat(store.claimRetry(row, row.selection().expiresAt(), Long.MAX_VALUE)).isFalse();
        assertThat(dispatches).hasValue(0);
    }

    @Test void retryThatLosesItsResponseCannotRetryAgain() {
        unsent();
        var service = service(store, submission -> {
            accept();
            throw new IllegalStateException("Injected lost response after acceptance");
        }, clock);
        assertThat(service.create("user", "same-key", "quote-1").statusCode()).isEqualTo(202);
        assertThat(service.create("user", "same-key", "quote-1").statusCode()).isEqualTo(202);
        assertThat(store.byKey("user", "same-key").orElseThrow().status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThat(dispatches).hasValue(1);
    }

    @Test void crashAfterRetryClaimStaysUnknownRatherThanRedispatching() {
        var original = unsent();
        var interrupted = spy(store);
        doAnswer(call -> { call.callRealMethod(); throw new AssertionError("Injected crash after retry claim"); })
                .when(interrupted).claimRetry(any(), anyLong(), anyLong());
        assertThatThrownBy(() -> service(interrupted, submission -> accept(), clock)
                .create("user", "same-key", "quote-1")).isInstanceOf(AssertionError.class);
        source.close();
        open();
        var replay = service(store, submission -> accept(), clock).create("user", "same-key", "quote-1");
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.booking().bookingId()).isEqualTo(original.id());
        assertThat(store.byKey("user", "same-key").orElseThrow().status()).isEqualTo(BookingStatus.PENDING);
        assertThat(dispatches).hasValue(0);
    }

    @Test void baselineReapplicationPreservesExistingOutcomesAndUniqueIndexes() {
        try (var legacy = SqliteDataSources.open(directory.resolve("baseline.db").toString(), "baseline-test", 2, 3000,
                "db/shipping/bootstrap.sql")) {
            ShippingMigrations.migrate(legacy, "V001__shipping_schema.sql");
            var storage = new JdbcShipmentStore(new JdbcTemplate(legacy));
            var confirmed = service(storage, submission -> accept(), clock).create("user-a", "key", "quote-1");
            var unknown = service(storage, submission -> { throw new IllegalStateException("Response lost"); }, clock)
                    .create("user-b", "key", "quote-1");
            var prior = storage.byKey("user-a", "key").orElseThrow();
            ShippingMigrations.migrate(legacy, "V001__shipping_schema.sql");
            ShippingMigrations.migrate(legacy, "V001__shipping_schema.sql");
            assertThat(storage.byKey("user-a", "key").orElseThrow()).isEqualTo(prior);
            assertThat(storage.byId("user-b", unknown.booking().bookingId()).orElseThrow().status())
                    .isEqualTo(BookingStatus.PENDING_INQUIRY);
            assertThat(storage.claim(prior, clock.millis())).isFalse();
            assertThat(service(storage, submission -> accept(), clock).create("user-a", "different-key", "quote-1").statusCode())
                    .isEqualTo(409);
            var jdbc = new JdbcTemplate(legacy);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_bookings", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='index'", String.class))
                    .contains("idx_shipping_unresolved");
            assertThat(confirmed.booking().status()).isEqualTo("CONFIRMED");
        }
    }

    @Test void unsupportedDevelopmentHistoryFailsWithoutDeletingRows() {
        var original = unsent();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE schema_migrations SET version='pre-release-development'");
        try {
            assertThatThrownBy(() -> ShippingMigrations.migrate(source, "V001__shipping_schema.sql"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("fresh database is required");
            assertThat(store.byKey("user", "same-key").orElseThrow()).isEqualTo(original);
        } finally {
            jdbc.update("UPDATE schema_migrations SET version='V001__shipping_schema.sql'");
        }
    }

    @Test void repeatedLocalRejectionKeepsTheSameRecordAndDoesNotCreateRecoveryTasks() {
        var original = unsent();
        var next = unsent();
        assertThat(next.id()).isEqualTo(original.id());
        assertThat(next.version()).isEqualTo(original.version() + 2);
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_bookings", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_reconciliation_tasks", Integer.class)).isZero();
    }
}
