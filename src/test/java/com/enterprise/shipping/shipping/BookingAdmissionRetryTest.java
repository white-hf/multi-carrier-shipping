package com.enterprise.shipping.shipping;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.persistence.ShippingMigrations;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.enterprise.shipping.shipping.application.BookingCommand;
import com.enterprise.shipping.shipping.model.BookingSelection;
import com.enterprise.shipping.shipping.model.BookingStatus;
import com.enterprise.shipping.shipping.model.CreateResult;
import com.enterprise.shipping.shipping.persistence.BookingRepository;
import com.enterprise.shipping.support.BookingFixtures;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class BookingAdmissionRetryTest {
    @TempDir
    Path directory;
    HikariDataSource source;
    BookingRepository store;
    final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void open() {
        source = SqliteDataSources.open(directory.resolve("test.db").toString(), "booking", 4, 3000,
                "db/shipping/bootstrap.sql");
        ShippingMigrations.migrate(source, "V001__shipping_schema.sql");
        store = new BookingRepository(new JdbcTemplate(source));
    }

    @AfterEach
    void close() {
        source.close();
    }

    CreateResult confirm() {
        calls.incrementAndGet();
        return CreateResult.confirmed("123", "TRACK", null);
    }

    @Test
    void concurrentInitialSubmissionsCommitOneBookingAndOneDispatch() throws Exception {
        var service = BookingFixtures.service(store, submission -> confirm(), Clock.systemUTC());
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int n = 0; n < 8; n++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.create("user", BookingFixtures.command("Same/Quote"))
                            .booking().bookingId();
                }));
            }
            start.countDown();
            var ids = new java.util.HashSet<String>();
            for (var future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
            assertThat(calls).hasValue(1);
            assertThat(new JdbcTemplate(source)
                    .queryForObject("SELECT count(*) FROM shipment_bookings", Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicatePayloadIsIgnoredAndUserQuoteAndCaseScopesAreIndependent() {
        var service = BookingFixtures.service(store, submission -> confirm(), Clock.systemUTC());
        var original = service.create("user", BookingFixtures.command("Case"));
        var row = store.byQuote("user", "sandbox", "Case").orElseThrow();
        var replay = service.create("user", new BookingCommand("sandbox", "Case", null, null));
        assertThat(replay.booking().bookingId()).isEqualTo(original.booking().bookingId());
        assertThat(store.byQuote("user", "sandbox", "Case").orElseThrow()).isEqualTo(row);
        service.create("user", BookingFixtures.command("case"));
        service.create("other-user", BookingFixtures.command("Case"));
        assertThat(calls).hasValue(3);
        assertThatThrownBy(() -> service.get("foreign", row.id())).isInstanceOf(ShippingException.class);
    }

    @Test
    void providerScopeIsPartOfDatabaseUniqueness() {
        var command = BookingFixtures.command("q");
        var input = command.shippingInput().dated(java.time.LocalDate.now());
        var selection = command.selection("account", input);
        var first = BookingRepository.Record.pending("user", "a", selection, Long.MAX_VALUE);
        var other = new BookingSelection(
                "other-provider",
                "account",
                "q",
                selection.carrierId(),
                selection.serviceId(),
                selection.acceptedMinor(),
                selection.currency(),
                null,
                input,
                null,
                null,
                null);
        assertThat(store.claim(first, 1)).isTrue();
        assertThat(store.claim(BookingRepository.Record.pending("user", "b", other, Long.MAX_VALUE), 1))
                .isTrue();
        assertThat(store.byQuote("user", "sandbox", "q")).isPresent();
        assertThat(store.byQuote("user", "other-provider", "q")).isPresent();
    }

    @Test
    void storageFailureAndInvalidProviderNeverDispatch() {
        var broken = spy(store);
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(broken).claim(any(), anyLong());
        var service = BookingFixtures.service(broken, submission -> confirm(), Clock.systemUTC());
        assertThatThrownBy(() -> service.create("user", BookingFixtures.command("q")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> service.create("user", new BookingCommand("forbidden", "q", null, null)))
                .isInstanceOf(ShippingException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void ordinaryCreateAndRecoveryNeverResendRetryableBooking() {
        var service = BookingFixtures.service(store, submission -> {
            calls.incrementAndGet();
            return CreateResult.notDispatched();
        }, Clock.systemUTC());
        assertThatThrownBy(() -> service.create("user", BookingFixtures.command("q")))
                .isInstanceOf(ShippingException.class);
        var row = store.byQuote("user", "sandbox", "q").orElseThrow();
        for (int n = 0; n < 3; n++) {
            assertThat(service.create("user", BookingFixtures.command("q")).booking().status())
                    .isEqualTo("RETRYABLE");
            assertThat(service.lookup("user", "sandbox", "q").status()).isEqualTo("RETRYABLE");
            service.get("user", row.id());
        }
        assertThat(store.byId("user", row.id()).orElseThrow()).isEqualTo(row);
        assertThat(calls).hasValue(1);
    }

    @Test
    void explicitConcurrentRetryUsesOneVersionClaimAndSavedSnapshot() throws Exception {
        var unsent = BookingFixtures.service(
                store, submission -> CreateResult.notDispatched(), Clock.systemUTC());
        assertThatThrownBy(() -> unsent.create("user", BookingFixtures.command("Original")))
                .isInstanceOf(ShippingException.class);
        var original = store.byQuote("user", "sandbox", "Original").orElseThrow();
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var service = BookingFixtures.service(store, submission -> {
            assertThat(submission.providerQuoteId()).isEqualTo("Original");
            assertThat(submission.accountAlias()).isEqualTo("account");
            calls.incrementAndGet();
            entered.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            }
            return CreateResult.confirmed("123", null, null);
        }, Clock.systemUTC());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> service.retry("user", original.id()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.retry("user", original.id()))
                    .isInstanceOf(ShippingException.class);
            gate.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS).booking().status()).isEqualTo("CONFIRMED");
            assertThat(calls).hasValue(1);
            assertThat(store.byId("user", original.id()).orElseThrow().selection())
                    .isEqualTo(original.selection());
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void unknownOutcomeNeverBecomesRetryPermission() {
        var service = BookingFixtures.service(store, submission -> {
            calls.incrementAndGet();
            throw new IllegalStateException("lost response");
        }, Clock.systemUTC());
        var result = service.create("user", BookingFixtures.command("q"));
        assertThat(result.booking().status()).isEqualTo("PENDING_CONFIRMATION");
        assertThatThrownBy(() -> service.retry("user", result.booking().bookingId()))
                .isInstanceOf(ShippingException.class);
        service.create("user", BookingFixtures.command("q"));
        assertThat(calls).hasValue(1);
        assertThat(store.byQuote("user", "sandbox", "q").orElseThrow().status())
                .isEqualTo(BookingStatus.PENDING_INQUIRY);
    }

    @Test
    void twoObservedRetryableVersionsStillHaveOnlyOneCasWinner() throws Exception {
        var unsent = BookingFixtures.service(
                store, submission -> CreateResult.notDispatched(), Clock.systemUTC());
        assertThatThrownBy(() -> unsent.create("user", BookingFixtures.command("race")))
                .isInstanceOf(ShippingException.class);
        var row = store.byQuote("user", "sandbox", "race").orElseThrow();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var competing = spy(store);
        org.mockito.Mockito.doAnswer(invocation -> {
            barrier.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(competing).claimRetry(any(), anyLong(), anyLong());
        var service = BookingFixtures.service(competing, submission -> confirm(), Clock.systemUTC());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.retry("user", row.id()));
            var second = pool.submit(() -> service.retry("user", row.id()));
            assertThat(first.get(10, TimeUnit.SECONDS).booking().bookingId()).isEqualTo(row.id());
            assertThat(second.get(10, TimeUnit.SECONDS).booking().bookingId()).isEqualTo(row.id());
            assertThat(calls).hasValue(1);
            assertThat(store.byId("user", row.id()).orElseThrow().status())
                    .isEqualTo(BookingStatus.CONFIRMED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anExpiredQuoteCannotEraseOrHideItsExistingBooking() {
        var command = BookingFixtures.command("expires");
        var clock = Clock.fixed(
                java.time.Instant.parse("2026-11-16T12:00:00Z"), java.time.ZoneOffset.UTC);
        var display = command.displaySnapshot();
        var dated = new BookingCommand(
                command.providerId(),
                command.providerQuoteId(),
                command.shippingInput(),
                new BookingCommand.DisplaySnapshot(
                        display.carrierId(),
                        display.carrierName(),
                        display.serviceId(),
                        display.serviceName(),
                        display.total(),
                        clock.instant().plusSeconds(30),
                        "LOCAL_ESTIMATE"));
        var service = BookingFixtures.service(store, submission -> confirm(), clock);
        String id = service.create("user", dated).booking().bookingId();
        var expired = BookingFixtures.service(store, submission -> {
            throw new AssertionError("Existing expired selection must not dispatch");
        }, Clock.offset(clock, java.time.Duration.ofDays(1)));
        var row = store.byId("user", id).orElseThrow();
        assertThat(expired.create("user", dated).booking().bookingId()).isEqualTo(id);
        assertThat(expired.lookup("user", "sandbox", "expires").status()).isEqualTo("CONFIRMED");
        assertThat(expired.get("user", id).status()).isEqualTo("CONFIRMED");
        assertThat(store.byId("user", id).orElseThrow()).isEqualTo(row);
        assertThat(calls).hasValue(1);
    }
}
