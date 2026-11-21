package com.enterprise.shipping.shipping;

import com.enterprise.shipping.shared.infrastructure.persistence.ShippingMigrations;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.enterprise.shipping.shipping.model.CreateResult;
import com.enterprise.shipping.shipping.persistence.BookingRepository;
import com.enterprise.shipping.support.BookingFixtures;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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

class BookingRestartTest {
    enum FailurePoint {
        AFTER_CLAIM_BEFORE_SEND, AFTER_ACCEPTANCE_LOST_RESPONSE, BEFORE_CONFIRMATION_SAVE, CONFIRMED
    }

    @TempDir
    Path directory;
    HikariDataSource source;

    BookingRepository open() {
        source = SqliteDataSources.open(directory.resolve("restart.db").toString(), "restart", 2, 3000,
                "db/shipping/bootstrap.sql");
        ShippingMigrations.migrate(source, "V001__shipping_schema.sql");
        return new BookingRepository(new JdbcTemplate(source));
    }

    @AfterEach
    void close() {
        source.close();
    }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void reopenPreservesSnapshotAndNeverRedispatches(FailurePoint failure) {
        var store = open();
        var interrupted = spy(store);
        var calls = new AtomicInteger();
        if (failure == FailurePoint.AFTER_CLAIM_BEFORE_SEND) {
            doAnswer(call -> {
                call.callRealMethod();
                throw new SimulatedCrash();
            }).when(interrupted).claim(any(), anyLong());
        } else if (failure == FailurePoint.BEFORE_CONFIRMATION_SAVE) {
            doThrow(new DataAccessResourceFailureException("save failed"))
                    .when(interrupted).complete(any(), any(), anyLong());
        }
        var first = BookingFixtures.service(interrupted, submission -> {
            calls.incrementAndGet();
            if (failure == FailurePoint.AFTER_ACCEPTANCE_LOST_RESPONSE) {
                throw new IllegalStateException("lost response");
            }
            return CreateResult.confirmed("123", "TRACK", null);
        }, Clock.systemUTC());
        if (failure == FailurePoint.AFTER_CLAIM_BEFORE_SEND
                || failure == FailurePoint.BEFORE_CONFIRMATION_SAVE) {
            assertThatThrownBy(() -> first.create("user", BookingFixtures.command("original")))
                    .isInstanceOfAny(
                            SimulatedCrash.class,
                            DataAccessResourceFailureException.class);
        } else {
            first.create("user", BookingFixtures.command("original"));
        }
        var original = store.byQuote("user", "sandbox", "original").orElseThrow();
        source.close();
        var reopened = open();
        var next = BookingFixtures.service(reopened, submission -> {
            throw new AssertionError("Restart must never dispatch an existing identity");
        }, Clock.systemUTC());
        var replay = next.create("user", BookingFixtures.command("original"));
        assertThat(replay.booking().bookingId()).isEqualTo(original.id());
        assertThat(next.lookup("user", "sandbox", "original").bookingId()).isEqualTo(original.id());
        assertThat(reopened.byId("user", original.id()).orElseThrow()).isEqualTo(original);
        assertThat(original.selection().shippingInput().sender().city()).isEqualTo("Toronto");
        if (failure == FailurePoint.CONFIRMED) {
            assertThat(next.label("user", original.id()).bytes()).startsWith((byte) '%');
        } else {
            assertThatThrownBy(() -> next.retry("user", original.id()))
                    .isInstanceOf(com.enterprise.shipping.shared.domain.ShippingException.class);
        }
        assertThat(calls).hasValue(failure == FailurePoint.AFTER_CLAIM_BEFORE_SEND ? 0 : 1);
    }

    static class SimulatedCrash extends Error {
    }
}
