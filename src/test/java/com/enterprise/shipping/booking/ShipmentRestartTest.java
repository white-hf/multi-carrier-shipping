package com.enterprise.shipping.booking;

import com.enterprise.shipping.booking.application.ShipmentApplicationService;
import com.enterprise.shipping.booking.application.ShipmentRecoveryService;
import com.enterprise.shipping.booking.contract.ManualReviewReason;
import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort;
import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.infrastructure.persistence.JdbcShipmentStore;
import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.booking.port.ShipmentStore;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.reconciliation.application.PendingShipmentDiscovery;
import com.enterprise.shipping.reconciliation.infrastructure.persistence.JdbcPendingTaskStore;
import com.enterprise.shipping.reconciliation.port.PendingTaskStore;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort;
import com.enterprise.shipping.shared.infrastructure.persistence.ShippingMigrations;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/** Reopen the actual shipping database and reconstruct services across simulated crashes. */
class ShipmentRestartTest {
    enum FailurePoint {
        AFTER_CLAIM_BEFORE_SEND,
        AFTER_ACCEPTANCE_LOST_RESPONSE,
        BEFORE_CONFIRMATION_SAVE
    }

    @TempDir
    Path directory;

    HikariDataSource source;
    JdbcShipmentStore store;
    final AtomicInteger accepted = new AtomicInteger();
    final Clock initialClock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    final QuoteSelectionPort.Selection selection =
            new QuoteSelectionPort.Selection(
                    "request-1",
                    "quote-1",
                    "sandbox",
                    "account",
                    "provider-quote",
                    "14",
                    "1",
                    2735,
                    "CAD",
                    Long.MAX_VALUE);
    final LabelProviderPort labels =
            new LabelProviderPort() {
                public Label fetch(Identity identity) {
                    return null;
                }

                public boolean allowedUrl(String url) {
                    return false;
                }
            };

    void open() {
        source =
                SqliteDataSources.open(
                        directory.resolve("shipping.db").toString(),
                        "restart-test",
                        2,
                        3000,
                        "db/shipping/bootstrap.sql");
        ShippingMigrations.migrate(source, "V001__shipping_schema.sql");
        store = new JdbcShipmentStore(new JdbcTemplate(source));
    }

    ShipmentApplicationService service(
            ShipmentStore storage, ShipmentProviderPort provider, Clock clock) {
        return new ShipmentApplicationService(
                storage,
                (user, id) -> selection,
                provider,
                labels,
                clock,
                Duration.ofMillis(500),
                (operation, outcome, elapsed) -> {
                });
    }

    ShipmentProviderPort.Outcome accept() {
        accepted.incrementAndGet();
        return ShipmentProviderPort.Outcome.confirmed("123", "TRACK123", null);
    }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void restartPreservesUnresolvedIdentityAndNeverRedispatches(FailurePoint failure) {
        open();
        JdbcShipmentStore interrupted = spy(store);
        if (failure == FailurePoint.AFTER_CLAIM_BEFORE_SEND) {
            doAnswer(
                            call -> {
                                call.callRealMethod();
                                throw new SimulatedCrash();
                            })
                    .when(interrupted)
                    .claim(any(), anyLong());
        } else if (failure == FailurePoint.BEFORE_CONFIRMATION_SAVE) {
            doThrow(new DataAccessResourceFailureException("Injected outcome-write failure"))
                    .when(interrupted)
                    .complete(any(), any(), anyLong());
        }
        ShipmentProviderPort provider =
                submission -> {
                    var result = accept();
                    if (failure == FailurePoint.AFTER_ACCEPTANCE_LOST_RESPONSE) {
                        throw new IllegalStateException("Injected loss after external acceptance");
                    }
                    return result;
                };
        var firstService = service(interrupted, provider, initialClock);
        if (failure == FailurePoint.AFTER_CLAIM_BEFORE_SEND) {
            assertThatThrownBy(() -> firstService.create("user", "restart-key", "quote-1"))
                    .isInstanceOf(SimulatedCrash.class);
        } else if (failure == FailurePoint.BEFORE_CONFIRMATION_SAVE) {
            assertThatThrownBy(() -> firstService.create("user", "restart-key", "quote-1"))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        } else {
            assertThat(firstService.create("user", "restart-key", "quote-1").disposition())
                    .isEqualTo(ShipmentApplicationService.Disposition.PENDING);
        }

        var before = store.byKey("user", "restart-key").orElseThrow();
        assertThat(before.status()).isIn(BookingStatus.PENDING, BookingStatus.PENDING_INQUIRY);
        int acceptedBeforeRestart = accepted.get();
        source.close();
        open();
        Clock restartedClock = Clock.offset(initialClock, Duration.ofMinutes(1));
        var restarted =
                service(
                        store,
                        submission -> {
                            throw new AssertionError("Replay must never dispatch");
                        },
                        restartedClock);
        var replay = restarted.create("user", "restart-key", "quote-1");
        assertThat(replay.disposition()).isEqualTo(ShipmentApplicationService.Disposition.PENDING);
        assertThat(replay.booking().bookingId()).isEqualTo(before.id());
        assertThat(replay.booking().shipmentId()).isNull();
        var competing = restarted.create("user", "another-key", "quote-1");
        assertThat(competing.disposition())
                .isEqualTo(ShipmentApplicationService.Disposition.CONFLICT);
        assertThat(competing.booking().bookingId()).isEqualTo(before.id());

        var discovery =
                new PendingShipmentDiscovery(
                        new ShipmentRecoveryService(store),
                        new JdbcPendingTaskStore(new JdbcTemplate(source)),
                        restartedClock,
                        (
                                a,
                                b,
                                c,
                                d,
                                e) -> {
                        },
                        (providerId, accountAlias) ->
                                new RecoveryCapabilityPort.Capabilities(
                                        RecoveryCapabilityPort.Support.UNSUPPORTED,
                                        RecoveryCapabilityPort.Support.UNVERIFIED));
        discovery.discover();
        discovery.discover();
        assertThat(store.byKey("user", "restart-key").orElseThrow().status())
                .isEqualTo(BookingStatus.MANUAL_REVIEW);
        var jdbc = new JdbcTemplate(source);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM booking_reconciliation_tasks", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM booking_reconciliation_tasks", String.class))
                .isEqualTo("PAUSED");
        assertThat(accepted.get()).isEqualTo(acceptedBeforeRestart);
    }

    @Test
    void restartReplaysConfirmedReceiptWithoutCallingProvider() {
        open();
        var first =
                service(store, submission -> accept(), initialClock)
                        .create("user", "confirmed-key", "quote-1");
        assertThat(first.disposition()).isEqualTo(ShipmentApplicationService.Disposition.CREATED);
        source.close();
        open();
        var replay =
                service(
                                store,
                                submission -> {
                                    throw new AssertionError("Confirmed replay must not dispatch");
                                },
                                initialClock)
                        .create("user", "confirmed-key", "quote-1");
        assertThat(replay.disposition()).isEqualTo(ShipmentApplicationService.Disposition.EXISTING);
        assertThat(replay.booking()).isEqualTo(first.booking());
        assertThat(accepted).hasValue(1);
    }

    @Test
    void interruptedManualReviewResumesAfterReopeningWithAnExistingTask() {
        open();
        var first =
                service(
                                store,
                                submission -> {
                                    accept();
                                    throw new IllegalStateException("Response lost");
                                },
                                initialClock)
                        .create("user", "manual-key", "quote-1");
        Clock recoveryClock = Clock.offset(initialClock, Duration.ofMinutes(1));
        var recovery = spy(new ShipmentRecoveryService(store));
        doThrow(new DataAccessResourceFailureException("Interrupted after task commit"))
                .when(recovery)
                .requireManualReview(any(), any(), anyLong());
        var discovery =
                manualDiscovery(
                        recovery,
                        new JdbcPendingTaskStore(new JdbcTemplate(source)),
                        recoveryClock);
        discovery.discover();
        var jdbc = new JdbcTemplate(source);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM booking_reconciliation_tasks", Integer.class))
                .isEqualTo(1);
        assertThat(store.byKey("user", "manual-key").orElseThrow().status())
                .isEqualTo(BookingStatus.PENDING_INQUIRY);
        source.close();
        open();
        discovery =
                manualDiscovery(
                        new ShipmentRecoveryService(store),
                        new JdbcPendingTaskStore(new JdbcTemplate(source)),
                        recoveryClock);
        discovery.discover();
        discovery.discover();
        var row = store.byKey("user", "manual-key").orElseThrow();
        assertThat(row.status()).isEqualTo(BookingStatus.MANUAL_REVIEW);
        assertThat(row.restore().errorCode()).isEqualTo("AUTHORITATIVE_LOOKUP_UNSUPPORTED");
        assertThat(store.overdue(recoveryClock.millis(), -1, "", 100)).isEmpty();
        assertThat(
                        new JdbcTemplate(source)
                                .queryForObject(
                                        "SELECT count(*) FROM booking_reconciliation_tasks",
                                        Integer.class))
                .isEqualTo(1);
        var replay =
                service(
                                store,
                                submission -> {
                                    throw new AssertionError("Manual replay must not dispatch");
                                },
                                recoveryClock)
                        .create("user", "manual-key", "quote-1");
        assertThat(replay.disposition()).isEqualTo(ShipmentApplicationService.Disposition.EXISTING);
        assertThat(replay.booking().bookingId()).isEqualTo(first.booking().bookingId());
        assertThat(replay.booking().status()).isEqualTo("MANUAL_REVIEW");
        assertThat(accepted).hasValue(1);
    }

    @Test
    void confirmationWinningTheCompareAndSetCannotBeDowngradedToManualReview() {
        open();
        service(
                        store,
                        submission -> {
                            accept();
                            throw new IllegalStateException("Response lost");
                        },
                        initialClock)
                .create("user", "race-key", "quote-1");
        Clock recoveryClock = Clock.offset(initialClock, Duration.ofMinutes(1));
        var original = new ShipmentRecoveryService(store);
        var candidate =
                original.discover(recoveryClock.millis(), ShipmentRecoveryPort.Cursor.START, 100)
                        .candidates()
                        .get(0);
        var racingStore = spy(store);
        doAnswer(
                        call -> {
                            ShipmentStore.Record expected = call.getArgument(0);
                            var confirmed = expected.restore();
                            confirmed.confirm(
                                    new CarrierReceipt(
                                            "booking-" + expected.id(), "123", "TRACK123", null));
                            assertThat(
                                            store.complete(
                                                            expected,
                                                            confirmed,
                                                            recoveryClock.millis())
                                                    .applied())
                                    .isTrue();
                            return call.callRealMethod();
                        })
                .when(racingStore)
                .complete(any(), any(), anyLong());
        assertThat(
                        new ShipmentRecoveryService(racingStore)
                                .requireManualReview(
                                        candidate,
                                        ManualReviewReason.AUTHORITATIVE_LOOKUP_UNSUPPORTED,
                                        recoveryClock.millis()))
                .isFalse();
        assertThat(store.byKey("user", "race-key").orElseThrow().status())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(store.byKey("user", "race-key").orElseThrow().providerShipmentId())
                .isEqualTo("123");
        assertThat(accepted).hasValue(1);
    }

    private PendingShipmentDiscovery manualDiscovery(
            ShipmentRecoveryPort recovery, PendingTaskStore tasks, Clock clock) {
        return new PendingShipmentDiscovery(
                recovery,
                tasks,
                clock,
                (
                        a,
                        b,
                        c,
                        d,
                        e) -> {
                },
                (providerId, accountAlias) ->
                        new RecoveryCapabilityPort.Capabilities(
                                RecoveryCapabilityPort.Support.UNSUPPORTED,
                                RecoveryCapabilityPort.Support.UNVERIFIED));
    }

    @AfterEach
    void close() {
        if (source != null) {
            source.close();
        }
    }

    static final class SimulatedCrash extends Error {}
}
