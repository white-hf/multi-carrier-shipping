package com.enterprise.shipping.quote;

import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.quote.api.ShippingQuoteController;
import org.springframework.http.HttpStatus;
import com.enterprise.shipping.quote.domain.RateRequest;
import com.enterprise.shipping.quote.domain.ShippingIntentFingerprint;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.infrastructure.persistence.JdbcQuoteStore;
import com.enterprise.shipping.quote.port.RateComparisonPort;
import com.enterprise.shipping.quote.port.QuoteStore;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import com.enterprise.shipping.shared.infrastructure.persistence.ShippingMigrations;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Verifies real SQLite uniqueness, version-fenced rate claims, atomic quote persistence,
 * and schema migration preservation for idempotent rate requests.
 */
class ShippingRateRequestTest {

    @TempDir
     Path directory;

    HikariDataSource source;
    JdbcQuoteStore store;
    JdbcTemplate jdbc;

    final MutableClock clock = new MutableClock();
    final AtomicInteger calls = new AtomicInteger();

    RateComparisonPort rates =
            (intent, deadline) -> {
                calls.incrementAndGet();
                return offers("provider-quote", 2735);
            };

    final ShippingAddress address =
            new ShippingAddress(
                    "Test User",
                    null,
                    "100 Test St",
                    null,
                    "Toronto",
                    "ON",
                    "M5V 3L9",
                    "CA",
                    "4165550100",
                    null,
                    false);

    final ShippingIntent intent =
            new ShippingIntent(
                    address,
                    address,
                    new ShippingIntent.Package(
                            new BigDecimal("12"),
                            new BigDecimal("10"),
                            new BigDecimal("8"),
                            DimensionUnit.IN,
                            new BigDecimal("5"),
                            WeightUnit.LB,
                            "PACKAGE"),
                    null);

    @Test
    void bookingSelectionReadsOnlyEligibilityContext() {
        var created = service().create("user", "minimal-context", intent);
        String quoteId = created.result().quotes().get(0).quoteId();
        var storage = spy(store);
        assertThat(service(storage).resolve("user", quoteId).quoteId()).isEqualTo(quoteId);
        verify(storage).bookingContext("user", created.result().requestId());
        verify(storage, never()).request(anyString(), anyString());
        assertThat(store.bookingContext("other", created.result().requestId())).isEmpty();
    }

    @Test
    void insertRejectsNonRatingStateWithoutPersistingIt() {
        var dated = intent.dated(LocalDate.of(2026, 10, 23));
        var invalid = new RateRequest(
                "user",
                "invalid-initial-state",
                "invalid-initial-key",
                ShippingIntentFingerprint.of(dated),
                dated,
                RateRequest.State.READY,
                0,
                null);
        assertThatThrownBy(() -> store.insert(invalid, clock.millis()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Initial rate request must be RATING");
        assertThat(count("shipment_requests")).isZero();
    }

    @Test
    void requestBookingEligibilityBelongsToTheDomainAndUsesTheSuppliedBusinessDate() {
        var today = LocalDate.of(2026, 10, 27);
        for (var state : RateRequest.State.values()) {
            var request =
                    new RateRequest(
                            "user", "request", "key", "hash", intent.dated(today), state, 0, null);
            assertThat(request.bookingEligibility(today))
                    .isEqualTo(
                            state == RateRequest.State.READY
                                    ? RateRequest.BookingEligibility.ELIGIBLE
                                    : RateRequest.BookingEligibility.NOT_READY);
            assertThat(request.bookingEligibility(today.plusDays(1)))
                    .isEqualTo(RateRequest.BookingEligibility.DATE_EXPIRED);
        }
        var future =
                new RateRequest(
                        "user",
                        "request",
                        "key",
                        "hash",
                        intent.dated(today.plusDays(1)),
                        RateRequest.State.READY,
                        0,
                        null);
        assertThat(future.bookingEligibility(today))
                .isEqualTo(RateRequest.BookingEligibility.ELIGIBLE);
        var undated =
                new RateRequest(
                        "user",
                        "request",
                        "key",
                        "hash",
                        intent,
                        RateRequest.State.READY,
                        0,
                        null);
        assertThat(undated.bookingEligibility(today))
                .isEqualTo(RateRequest.BookingEligibility.DATE_EXPIRED);
    }

    @BeforeEach
    void open() {
        source =
                SqliteDataSources.open(
                        directory.resolve("rates.db").toString(),
                        "rates-test",
                        4,
                        3000,
                        "db/shipping/bootstrap.sql");
        ShippingMigrations.migrate(source, "V001__shipping_schema.sql");
        jdbc = new JdbcTemplate(source);
        store =
                new JdbcQuoteStore(
                        jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @AfterEach
    void close() {
        if (source != null) {
            source.close();
        }
    }

    QuoteApplicationService service() {
        return service(store);
    }

    QuoteApplicationService service(QuoteStore storage) {
        return new QuoteApplicationService(
                storage, rates, clock, Duration.ofSeconds(1), ZoneOffset.UTC, (a, b) -> {
                                                                              });
    }

    List<ShippingQuote> offers(String token, long amount) {
        return List.of(
                new ShippingQuote(
                        null,
                        null,
                        "sandbox",
                        "account",
                        token,
                        "14",
                        "Carrier",
                        "1",
                        "Ground",
                        amount,
                        "CAD",
                        2,
                        clock.millis() + 600000));
    }

    @Test
    void sameKeyReplaysCanonicalContentAndRetainsOwnBusinessSnapshot() {
        var first = service().create("user", "same-key", intent);
        var equivalent =
                new ShippingIntent(
                        address,
                        address,
                        new ShippingIntent.Package(
                                new BigDecimal("12.00"),
                                new BigDecimal("10.0"),
                                new BigDecimal("8"),
                                DimensionUnit.IN,
                                new BigDecimal("5.000"),
                                WeightUnit.LB,
                                "PACKAGE"),
                        null);

        var replay = service().create("user", "same-key", equivalent);
        assertThat(replay.disposition()).isEqualTo(QuoteApplicationService.Disposition.EXISTING);
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(calls).hasValue(1);
        assertThat(count("shipment_requests")).isEqualTo(1);
        assertThat(
                        store.request("user", first.result().requestId())
                                .orElseThrow()
                                .intent()
                                .sender()
                                .addressLine1())
                .isEqualTo("100 Test St");

        assertThatThrownBy(
                        () ->
                                service()
                                        .create(
                                                "user",
                                                "same-key",
                                                new ShippingIntent(
                                                        address,
                                                        address,
                                                        new ShippingIntent.Package(
                                                                BigDecimal.ONE,
                                                                BigDecimal.ONE,
                                                                BigDecimal.ONE,
                                                                null,
                                                                BigDecimal.ONE,
                                                                null,
                                                                null),
                                                        null)))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void failureAndExplicitRetryReuseTheRequestWithoutAnErrorColumn() {
        rates =
                (input, deadline) -> {
                    calls.incrementAndGet();
                    throw ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
                };

        AtomicReference<String> id = new AtomicReference<>();
        assertThatThrownBy(() -> service().create("user", "retry-key", intent))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> {
                            assertThat(error.category())
                                    .isEqualTo(ShippingException.Category.DEPENDENCY_TIMEOUT);
                            id.set(error.operation().id());
                        });

        assertThat(service().get("user", id.get()).state()).isEqualTo("RATE_FAILED");
        assertThat(calls).hasValue(1);

        rates =
                (input, deadline) -> {
                    calls.incrementAndGet();
                    return offers("retry-success", 2735);
                };

        var retry = service().create("user", "retry-key", intent);
        assertThat(retry.result().requestId()).isEqualTo(id.get());
        assertThat(retry.result().state()).isEqualTo("READY");
        assertThat(store.byKey("user", "retry-key").orElseThrow().version()).isEqualTo(1);
        assertThat(count("shipment_requests")).isEqualTo(1);
        assertThat(
                        jdbc.queryForList("PRAGMA table_info(shipment_requests)").stream()
                                .map(row -> row.get("name")))
                .doesNotContain("last_error_code");
    }

    @Test
    void simultaneousEmptyReadsReturnTheWinnerIdentityWithoutASecondClaim() throws Exception {
        var storage = spy(store);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var initialReads = new AtomicInteger();
        doAnswer(
                        call -> {
                            Object result = call.callRealMethod();
                            if (initialReads.getAndIncrement() < 2) {
                                barrier.await(5, TimeUnit.SECONDS);
                            }
                            return result;
                        })
                .when(storage)
                .byKey("user", "insert-race");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> service(storage).create("user", "insert-race", intent));
            var b = executor.submit(() -> service(storage).create("user", "insert-race", intent));
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertThat(first.result().requestId())
                    .isNotBlank()
                    .isEqualTo(second.result().requestId());
            assertThat(calls).hasValue(1);
            verify(storage, times(2)).insert(any(), anyLong());
            verify(storage, never()).claim(any(), any(), anyLong());
            verify(storage, never()).fail(any(), anyLong());
            assertThat(count("shipment_requests")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void insertLoserChecksTheWinnerFingerprintWithoutDispatching() {
        service().create("user", "insert-conflict", intent);
        var storage = spy(store);
        doReturn(java.util.Optional.empty(), store.byKey("user", "insert-conflict"))
                .when(storage)
                .byKey("user", "insert-conflict");
        var changed =
                intent.dated(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1));
        assertThatThrownBy(() -> service(storage).create("user", "insert-conflict", changed))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(storage, never()).claim(any(), any(), anyLong());
        assertThat(calls).hasValue(1);
    }

    @Test
    void insertLoserReturnsFailedWinnerWithoutTakingOver() {
        rates =
                (input, deadline) -> {
                    calls.incrementAndGet();
                    throw ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
                };
        assertThatThrownBy(() -> service().create("user", "failed-winner", intent))
                .isInstanceOf(ShippingException.class);
        var storage = spy(store);
        doReturn(java.util.Optional.empty(), store.byKey("user", "failed-winner"))
                .when(storage)
                .byKey("user", "failed-winner");
        var result = service(storage).create("user", "failed-winner", intent);
        assertThat(result.result().state()).isEqualTo("RATE_FAILED");
        assertThat(result.disposition()).isEqualTo(QuoteApplicationService.Disposition.EXISTING);
        verify(storage, never()).claim(any(), any(), anyLong());
        verify(storage, never()).fail(any(), anyLong());
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryClaimLoserReturnsOnlyKnownIdentityWithoutRereadsOrWrites() {
        var row = active("claim-lost", clock.millis() - 1);
        var storage = spy(store);
        doReturn(false).when(storage).claim(any(), any(), anyLong());
        var result = service(storage).create("user", "claim-lost", row.intent());
        assertThat(result.disposition()).isEqualTo(QuoteApplicationService.Disposition.PENDING);
        assertThat(result.result().state()).isEqualTo("CONTENDED");
        assertThat(result.result().requestId()).isEqualTo(row.requestId());
        verify(storage, times(1)).byKey("user", "claim-lost");
        verify(storage, times(1)).claim(any(), any(), anyLong());
        verify(storage, never()).request(anyString(), anyString());
        verify(storage, never()).quotes(anyString(), anyString());
        verify(storage, never()).fail(any(), anyLong());
        assertThat(calls).hasValue(0);
    }

    @Test
    void quoteContentionResponseProvidesARealPollingLocation() {
        var row = active("claim-api", clock.millis() - 1);
        var storage = spy(store);
        doReturn(false).when(storage).claim(any(), any(), anyLong());
        var controller = new ShippingQuoteController(service(storage), () -> "user");
        var response = controller.create("claim-api", row.intent());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation().getPath())
                .isEqualTo("/api/quotes/" + row.requestId());
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody())
                .isInstanceOfSatisfying(
                        QuoteApplicationService.Result.class,
                        body -> {
                            assertThat(body.requestId()).isEqualTo(row.requestId());
                            assertThat(body.state()).isEqualTo("CONTENDED");
                            assertThat(body.quotes()).isEmpty();
                        });
        assertThat(calls).hasValue(0);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void completionLoserDoesNotFailAnOverdueSnapshot(CapturedOutput output) {
        var storage = spy(store);
        doAnswer(
                        call -> {
                            RateRequest claim = call.getArgument(0);
                            var newer =
                                    claim.claimed(claim.deadline(), claim.deadline() + 1000, false);
                            assertThat(store.claim(claim, newer, claim.deadline())).isTrue();
                            clock.advance(Duration.ofSeconds(20));
                            return call.callRealMethod();
                        })
                .when(storage)
                .complete(any(), anyList(), anyLong());
        var result = service(storage).create("user", "completion-lost", intent);
        assertThat(result.disposition()).isEqualTo(QuoteApplicationService.Disposition.PENDING);
        verify(storage, never()).fail(any(), anyLong());
        // One read belongs to the repository conflict snapshot; the service adds none.
        verify(storage, times(1)).request(anyString(), anyString());
        verify(storage, never()).quotes(anyString(), anyString());
        assertThat(calls).hasValue(1);
        var persisted = store.byKey("user", "completion-lost").orElseThrow();
        assertThat(persisted.state()).isEqualTo(RateRequest.State.RATING);
        assertThat(persisted.version()).isEqualTo(1);
        assertThat(output.getOut())
                .contains("Stale rate completion discarded requestId=")
                .doesNotContain("Rate execution expired requestId=");
    }

    @Test
    void concurrentMatchingRequestsHaveOneLiveProviderExecution() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rates =
                (input, deadline) -> {
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
                assertThat(result.disposition())
                        .isEqualTo(QuoteApplicationService.Disposition.PENDING);
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
        rates =
                (input, deadline) -> {
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
    void previousBusinessDateCannotReplayOrBookEvenBeforeQuoteTtlExpires() {
        // Toronto midnight is 04:00 UTC on this date; UTC itself does not cross midnight.
        clock.time.set(Instant.parse("2026-10-24T03:59:00Z").toEpochMilli());
        var service =
                new QuoteApplicationService(
                        store,
                        rates,
                        clock,
                        Duration.ofSeconds(1),
                        ZoneId.of("America/Toronto"),
                        (a, b) -> {
                        });
        var first = service.create("user", "midnight-key", intent);
        String quoteId = first.result().quotes().get(0).quoteId();
        assertThat(service.resolve("user", quoteId).quoteId()).isEqualTo(quoteId);

        clock.advance(Duration.ofMinutes(2));
        assertThat(store.find("user", quoteId).orElseThrow().expiresAt())
                .isGreaterThan(clock.millis());
        var historical = service.get("user", first.result().requestId());
        assertThat(historical.state()).isEqualTo("QUOTES_EXPIRED");
        assertThat(historical.quotes()).isEmpty();
        assertThatThrownBy(() -> service.create("user", "midnight-key", intent))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("REQUEST_DATE_EXPIRED"));
        assertThatThrownBy(() -> service.resolve("user", quoteId))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("REQUEST_DATE_EXPIRED");
                            assertThat(error.category())
                                    .isEqualTo(ShippingException.Category.STATE_CONFLICT);
                        });
        assertThat(calls).hasValue(1);
        assertThat(store.request("user", first.result().requestId()).orElseThrow().state())
                .isEqualTo(RateRequest.State.READY);
    }

    @Test
    void staleFailureReturnsSuccessfulNewExecution() throws Exception {
        staleFailureReturnsLatestState(true);
    }

    @Test
    void staleFailureReturnsNewExecutionInProgress() throws Exception {
        staleFailureReturnsLatestState(false);
    }

    private void staleFailureReturnsLatestState(boolean completeRetry) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rates =
                (input, deadline) -> {
                    calls.incrementAndGet();
                    started.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Old execution not released");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    throw ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
                };
        var worker = Executors.newSingleThreadExecutor();
        try {
            var old = worker.submit(() -> service().create("user", "stale-failure", intent));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(12));
            var previous = store.byKey("user", "stale-failure").orElseThrow();
            var retry = previous.claimed(clock.millis(), clock.millis() + 11000, false);
            assertThat(store.claim(previous, retry, clock.millis())).isTrue();
            if (completeRetry) {
                var quote = offers("new-token", 2735).get(0);
                assertThat(
                                store.complete(
                                                retry,
                                                List.of(
                                                        new ShippingQuote(
                                                                "new-quote",
                                                                retry.requestId(),
                                                                quote.providerId(),
                                                                quote.accountAlias(),
                                                                quote.providerQuoteId(),
                                                                quote.carrierId(),
                                                                quote.carrierName(),
                                                                quote.serviceId(),
                                                                quote.serviceName(),
                                                                quote.totalMinor(),
                                                                quote.currency(),
                                                                quote.transitDays(),
                                                                quote.expiresAt())),
                                                clock.millis())
                                        .applied())
                        .isTrue();
            }
            release.countDown();
            var result = old.get(5, TimeUnit.SECONDS);
            assertThat(result.disposition())
                    .isEqualTo(
                            completeRetry
                                    ? QuoteApplicationService.Disposition.EXISTING
                                    : QuoteApplicationService.Disposition.PENDING);
            assertThat(result.result().state()).isEqualTo(completeRetry ? "READY" : "RATING");
            assertThat(result.result().requestId()).isEqualTo(retry.requestId());
            assertThat(store.request("user", retry.requestId()).orElseThrow().version())
                    .isEqualTo(1);
            assertThat(calls).hasValue(1);
            if (completeRetry) {
                assertThat(result.result().quotes())
                        .extracting(QuoteApplicationService.QuoteView::quoteId)
                        .containsExactly("new-quote");
            }
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    RateRequest active(String key, long deadline) {
        long now = clock.millis();
        var dated = intent.dated(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate());
        var row =
                new RateRequest(
                        "user",
                        key,
                        key,
                        ShippingIntentFingerprint.of(dated),
                        dated,
                        RateRequest.State.RATING,
                        0,
                        deadline);
        assertThat(store.insert(row, now)).isTrue();
        return row;
    }

    ShippingQuote identifiedOffer(String id, String requestId, String token) {
        return new ShippingQuote(
                id,
                requestId,
                "sandbox",
                "account",
                token,
                "14",
                "Carrier",
                "1",
                "Ground",
                2735,
                "CAD",
                2,
                clock.millis() + 600000);
    }

    @Test
    void domainCompletionAndFailurePreserveTheExecutionVersionAndClearTheDeadline() {
        var row = active("domain", clock.millis() + 1000);
        var ready = row.completed(true, row.deadline() - 1);
        var empty = row.completed(false, row.deadline() - 1);
        var failed = row.failed();
        assertThat(ready.state()).isEqualTo(RateRequest.State.READY);
        assertThat(empty.state()).isEqualTo(RateRequest.State.NO_RATES);
        assertThat(failed.state()).isEqualTo(RateRequest.State.RATE_FAILED);
        for (var completed : List.of(ready, empty, failed)) {
            assertThat(completed.version()).isEqualTo(row.version());
            assertThat(completed.deadline()).isNull();
        }
        assertThatThrownBy(() -> row.completed(true, row.deadline()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ready::failed).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.completed(true, clock.millis()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row.state()).isEqualTo(RateRequest.State.RATING);
    }

    @Test
    void successfulRatingReturnsCommittedFactsWithoutReadingTheRequestOrQuotesAgain() {
        var storage = spy(store);
        doThrow(new AssertionError("Normal completion must not reread the request"))
                .when(storage)
                .request(anyString(), anyString());
        doThrow(new AssertionError("Normal completion must not reread the offers"))
                .when(storage)
                .quotes(anyString(), anyString());
        var result = service(storage).create("user", "no-reread", intent);
        assertThat(result.disposition()).isEqualTo(QuoteApplicationService.Disposition.CREATED);
        assertThat(result.result().state()).isEqualTo("READY");
        assertThat(result.result().quotes()).hasSize(1);
        assertThat(store.request("user", result.result().requestId()).orElseThrow().state())
                .isEqualTo(RateRequest.State.READY);
        verify(storage, never()).request(anyString(), anyString());
        verify(storage, never()).quotes(anyString(), anyString());
    }

    @Test
    void readyReplayLoadsOffersOnceAndReusesTheExistingRequestSnapshot() {
        var first = service().create("user", "ready-replay", intent);
        var storage = spy(store);
        var replay = service(storage).create("user", "ready-replay", intent);
        assertThat(replay.result()).isEqualTo(first.result());
        verify(storage, times(1)).quotes("user", first.result().requestId());
        verify(storage, never()).request(anyString(), anyString());
        assertThat(calls).hasValue(1);
    }

    @Test
    void pendingReplayDoesNotLoadOffersOrRedispatch() {
        active("pending-replay", clock.millis() + 10000);
        var storage = spy(store);
        var replay = service(storage).create("user", "pending-replay", intent);
        assertThat(replay.disposition()).isEqualTo(QuoteApplicationService.Disposition.PENDING);
        assertThat(replay.result().quotes()).isEmpty();
        verify(storage, never()).quotes(anyString(), anyString());
        verify(storage, never()).request(anyString(), anyString());
        assertThat(calls).hasValue(0);
    }

    @Test
    void emptyResultReplaysWithoutLoadingOffersOrStartingAnotherExecution() {
        rates =
                (input, deadline) -> {
                    calls.incrementAndGet();
                    return List.of();
                };
        var storage = spy(store);
        var service = service(storage);
        var first = service.create("user", "empty-replay", intent);
        var replay = service.create("user", "empty-replay", intent);
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(replay.result().state()).isEqualTo("NO_RATES");
        verify(storage, never()).quotes(anyString(), anyString());
        verify(storage, never()).request(anyString(), anyString());
        assertThat(calls).hasValue(1);
    }

    @Test
    void expiredExecutionFailureReturnsItsCommittedSnapshotWithoutAnotherSelect() {
        var row = active("expired-get", clock.millis());
        var storage = spy(store);
        var result = service(storage).get("user", row.requestId());
        assertThat(result.state()).isEqualTo("RATE_FAILED");
        verify(storage, times(1)).request("user", row.requestId());
        verify(storage, never()).quotes(anyString(), anyString());
        assertThat(store.request("user", row.requestId()).orElseThrow()).isEqualTo(row.failed());
    }

    @Test
    void expiryCompareAndSetLoserReadsTheWinningExecution() {
        var row = active("expiry-race", clock.millis());
        var storage = spy(store);
        doAnswer(
                        call -> {
                            var claimed =
                                    row.claimed(clock.millis(), clock.millis() + 10000, false);
                            assertThat(store.claim(row, claimed, clock.millis())).isTrue();
                            assertThat(
                                            store.complete(
                                                            claimed,
                                                            List.of(
                                                                    identifiedOffer(
                                                                            "winner",
                                                                            row.requestId(),
                                                                            "winner")),
                                                            clock.millis())
                                                    .applied())
                                    .isTrue();
                            return false;
                        })
                .when(storage)
                .fail(any(), anyLong());
        var result = service(storage).get("user", row.requestId());
        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.quotes())
                .extracting(QuoteApplicationService.QuoteView::quoteId)
                .containsExactly("winner");
        verify(storage, times(2)).request("user", row.requestId());
        assertThat(calls).hasValue(0);
    }

    @Test
    void staleCompletionReturnsTheNewVersionWithoutReplacingItsOffers() {
        var old = active("stale-completion", clock.millis() + 10000);
        assertThat(store.fail(old, clock.millis())).isTrue();
        var failed = old.failed();
        var retry = failed.claimed(clock.millis(), clock.millis() + 10000, false);
        assertThat(store.claim(failed, retry, clock.millis())).isTrue();
        assertThat(
                        store.complete(
                                        retry,
                                        List.of(
                                                identifiedOffer(
                                                        "new", old.requestId(), "new-token")),
                                        clock.millis())
                                .applied())
                .isTrue();
        var stale =
                store.complete(
                        old,
                        List.of(identifiedOffer("old", old.requestId(), "old-token")),
                        clock.millis());
        assertThat(stale.applied()).isFalse();
        assertThat(stale.request().version()).isEqualTo(1);
        assertThat(stale.quotes()).extracting(ShippingQuote::quoteId).containsExactly("new");
        assertThat(store.quotes("user", old.requestId())).isEqualTo(stale.quotes());
        assertThatThrownBy(() -> stale.quotes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void expiredCompletionFailsOnlyItsOwnVersionWithoutPublishingQuotes() {
        var row = active("expired-completion", clock.millis());
        var completion =
                store.complete(
                        row,
                        List.of(identifiedOffer("late", row.requestId(), "late-token")),
                        clock.millis());
        assertThat(completion.applied()).isFalse();
        assertThat(completion.outcome()).isEqualTo(QuoteStore.Completion.Outcome.EXPIRED);
        assertThat(completion.request()).isEqualTo(row.failed());
        assertThat(store.request("user", row.requestId()).orElseThrow()).isEqualTo(row.failed());
        assertThat(completion.quotes()).isEmpty();
        assertThat(count("shipment_quotes")).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void pausedExecutionReturnsFailedWithoutAStatusGet(CapturedOutput output) {
        var storage = spy(store);
        rates =
                (input, deadline) -> {
                    calls.incrementAndGet();
                    clock.advance(Duration.ofSeconds(20));
                    return offers("too-late", 2735);
                };
        var result = service(storage).create("user", "paused-completion", intent);
        assertThat(result.result().state()).isEqualTo("RATE_FAILED");
        assertThat(result.disposition()).isEqualTo(QuoteApplicationService.Disposition.EXISTING);
        assertThat(result.result().quotes()).isEmpty();
        verify(storage, times(1)).fail(any(), anyLong());
        verify(storage, never()).request(anyString(), anyString());
        verify(storage, never()).quotes(anyString(), anyString());
        assertThat(store.byKey("user", "paused-completion").orElseThrow().state())
                .isEqualTo(RateRequest.State.RATE_FAILED);
        assertThat(count("shipment_quotes")).isZero();
        assertThat(calls).hasValue(1);
        assertThat(output.getOut())
                .contains("WARN", "Rate execution expired requestId=")
                .doesNotContain("Stale rate completion discarded");
    }

    @Test
    void expiredCompletionCannotFailANewerClaim() {
        var row = active("expiry-takeover", clock.millis());
        var newer = row.claimed(clock.millis(), clock.millis() + 10000, false);
        var storage = spy(store);
        doAnswer(
                        call -> {
                            assertThat(store.claim(row, newer, clock.millis())).isTrue();
                            return call.callRealMethod();
                        })
                .when(storage)
                .fail(any(), anyLong());
        var completion =
                storage.complete(
                        row,
                        List.of(identifiedOffer("late", row.requestId(), "late-token")),
                        clock.millis());
        assertThat(completion.applied()).isFalse();
        assertThat(completion.outcome()).isEqualTo(QuoteStore.Completion.Outcome.STALE);
        assertThat(completion.request()).isEqualTo(newer);
        assertThat(store.request("user", row.requestId()).orElseThrow()).isEqualTo(newer);
        assertThat(count("shipment_quotes")).isZero();
        verify(storage, times(1)).fail(eq(row), anyLong());
        verify(storage, times(1)).request("user", row.requestId());
    }

    @Test
    void lostCommitAcknowledgementReturnsThePersistedSuccessWithoutAnotherProviderCall() {
        var storage = spy(store);
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new org.springframework.dao.DataAccessResourceFailureException(
                                    "Injected lost commit acknowledgement");
                        })
                .when(storage)
                .complete(any(), anyList(), anyLong());
        var result = service(storage).create("user", "commit-ack", intent);
        assertThat(result.disposition()).isEqualTo(QuoteApplicationService.Disposition.EXISTING);
        assertThat(result.result().state()).isEqualTo("READY");
        assertThat(result.result().quotes()).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void projectionFailureAfterCommitDoesNotMarkTheExecutionFailedOrRereadStorage() {
        var storage = spy(store);
        var invalidProjection = mock(ShippingQuote.class);
        when(invalidProjection.reusable(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("Injected projection failure"));
        doAnswer(
                        call -> {
                            QuoteStore.Completion saved =
                                    (QuoteStore.Completion) call.callRealMethod();
                            return new QuoteStore.Completion(
                                    saved.request(), List.of(invalidProjection), saved.outcome());
                        })
                .when(storage)
                .complete(any(), anyList(), anyLong());
        assertThatThrownBy(() -> service(storage).create("user", "projection-error", intent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Injected projection failure");
        assertThat(store.byKey("user", "projection-error").orElseThrow().state())
                .isEqualTo(RateRequest.State.READY);
        verify(storage, never()).fail(any(), anyLong());
        verify(storage, never()).request(anyString(), anyString());
        verify(storage, never()).quotes(anyString(), anyString());
        assertThat(calls).hasValue(1);
    }

    @Test
    void batchCompletionPersistsMultipleQuotesIncludingUnknownTransitTime() {
        rates =
                (input, deadline) ->
                        List.of(
                                new ShippingQuote(
                                        null,
                                        null,
                                        "sandbox",
                                        "account",
                                        "token-a",
                                        "14",
                                        "Carrier A",
                                        "1",
                                        "Ground",
                                        2735,
                                        "CAD",
                                        2,
                                        clock.millis() + 600000),
                                new ShippingQuote(
                                        null,
                                        null,
                                        "sandbox",
                                        "account",
                                        "token-b",
                                        "22",
                                        null,
                                        "2",
                                        null,
                                        3590,
                                        "CAD",
                                        null,
                                        clock.millis() + 600000));
        var result = service().create("user", "batch-key", intent);
        assertThat(result.result().state()).isEqualTo("READY");
        var saved = store.quotes("user", result.result().requestId());
        assertThat(saved).hasSize(2);
        assertThat(saved)
                .extracting(ShippingQuote::providerQuoteId)
                .containsExactlyInAnyOrder("token-a", "token-b");
        var unknownTransit =
                saved.stream()
                        .filter(q -> q.providerQuoteId().equals("token-b"))
                        .findFirst()
                        .orElseThrow();
        assertThat(unknownTransit.transitDays()).isNull();
        assertThat(unknownTransit.carrierName()).isNull();
        assertThat(unknownTransit.serviceName()).isNull();
    }

    @Test
    void batchFailureRestoresPreviousQuotesAndRequestState() {
        var initial = service().create("user", "batch-rollback", intent);
        String id = initial.result().requestId();
        var previous = store.quotes("user", id);
        clock.advance(Duration.ofMinutes(11));
        var row = store.byKey("user", "batch-rollback").orElseThrow();
        var claimed = row.claimed(clock.millis(), clock.millis() + 11000, true);
        assertThat(store.claim(row, claimed, clock.millis())).isTrue();
        var replacement =
                List.of(
                        new ShippingQuote(
                                "replacement-good",
                                id,
                                "sandbox",
                                "account",
                                "new-a",
                                "14",
                                "Carrier",
                                "1",
                                "Ground",
                                2800,
                                "CAD",
                                2,
                                clock.millis() + 600000),
                        new ShippingQuote(
                                "replacement-invalid",
                                id,
                                "sandbox",
                                "account",
                                "new-b",
                                "14",
                                "Carrier",
                                "1",
                                "Ground",
                                -1,
                                "CAD",
                                2,
                                clock.millis() + 600000));
        assertThatThrownBy(() -> store.complete(claimed, replacement, clock.millis()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Even the valid first batch row and the earlier DELETE must roll back.
        assertThat(store.quotes("user", id)).isEqualTo(previous);
        var current = store.request("user", id).orElseThrow();
        assertThat(current.state()).isEqualTo(RateRequest.State.RATING);
        assertThat(current.version()).isEqualTo(claimed.version());
        assertThat(current.deadline()).isEqualTo(claimed.deadline());
    }

    @Test
    void emptyBatchCompletesAsNoRatesAndClearsTheDeadline() {
        rates = (input, deadline) -> List.of();
        var result = service().create("user", "empty-batch", intent);
        assertThat(result.result().state()).isEqualTo("NO_RATES");
        assertThat(result.result().quotes()).isEmpty();
        var current = store.request("user", result.result().requestId()).orElseThrow();
        assertThat(current.state()).isEqualTo(RateRequest.State.NO_RATES);
        assertThat(current.deadline()).isNull();
        assertThat(count("shipment_quotes")).isZero();
    }

    @Test
    void failedQuoteInsertRollsBackOffersAndSuccessfulState() {
        rates = (input, deadline) -> offers("invalid-price", -1);
        assertThatThrownBy(() -> service().create("user", "storage-key", intent))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("STORE_UNAVAILABLE"));

        assertThat(store.byKey("user", "storage-key").orElseThrow().state())
                .isEqualTo(RateRequest.State.RATE_FAILED);
        assertThat(count("shipment_quotes")).isZero();
    }

    @Test
    void reopeningPreservesAbandonedClaimAndAllowsAnExplicitRetry() {
        var dated = intent.dated(LocalDate.of(2026, 10, 23));
        var claim =
                new RateRequest(
                        "user",
                        "abandoned",
                        "restart-key",
                        ShippingIntentFingerprint.of(dated),
                        dated,
                        RateRequest.State.RATING,
                        0,
                        clock.millis() + 11000);
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
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("REQUEST_DATE_EXPIRED"));
        assertThatThrownBy(() -> service().get("other", first.result().requestId()))
                .isInstanceOfSatisfying(
                        ShippingException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThat(count("shipment_requests")).isEqualTo(1);
    }

    @Test
    void expiredQuotesRefreshUnderTheSameRequest() {
        var first = service().create("user", "expiry-key", intent);
        clock.advance(Duration.ofMinutes(11));

        assertThat(service().get("user", first.result().requestId()).state())
                .isEqualTo("QUOTES_EXPIRED");
        var refreshed = service().create("user", "expiry-key", intent);
        assertThat(refreshed.result().requestId()).isEqualTo(first.result().requestId());
        assertThat(refreshed.result().quotes()).hasSize(1);
        assertThat(calls).hasValue(2);
        assertThat(count("shipment_requests")).isEqualTo(1);
    }

    @Test
    void baselineRestartPreservesRowsAndMaintainsFourApplicationTablesAndIndexes() {
        var original = service().create("user", "baseline-key", intent);
        ShippingMigrations.migrate(source, "V001__shipping_schema.sql");
        var row = store.request("user", original.result().requestId()).orElseThrow();
        assertThat(row.key()).isEqualTo("baseline-key");
        assertThat(row.state()).isEqualTo(RateRequest.State.READY);
        var jdbc = new JdbcTemplate(source);
        assertThat(
                        jdbc.queryForList(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                                String.class))
                .containsExactlyInAnyOrder(
                        "shipment_requests",
                        "shipment_quotes",
                        "shipment_bookings",
                        "booking_reconciliation_tasks",
                        "schema_migrations");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schema_migrations", Integer.class))
                .isEqualTo(1);
        var keyPlan =
                String.join(
                        " ",
                        jdbc.query(
                                "EXPLAIN QUERY PLAN SELECT request_id, state FROM shipment_requests WHERE user_id=? AND idempotency_key=?",
                                (rs, n) -> rs.getString("detail"),
                                "user",
                                "baseline-key"));
        assertThat(keyPlan).contains("uk_shipment_request_key");
        var quotesPlan =
                String.join(
                        " ",
                        jdbc.query(
                                "EXPLAIN QUERY PLAN SELECT quote_id, total_minor FROM shipment_quotes WHERE user_id=? AND request_id=?",
                                (rs, n) -> rs.getString("detail"),
                                "user",
                                row.requestId()));
        assertThat(quotesPlan).contains("idx_request_quotes");
    }

    @Test
    void preservesFullWebInputRoundTripAndClearsDeadlineWhenCompleted() {
        var richSender =
                new ShippingAddress(
                        "Alice Sender",
                        "Acme Logistics Ltd.",
                        "100 King St W",
                        "Floor 12, Suite 1200",
                        "Toronto",
                        "ON",
                        "M5X 1A9",
                        "CA",
                        "+1 (416) 555-0199",
                        "alice@acme.ca",
                        true);
        var richRecipient =
                new ShippingAddress(
                        "Bob Receiver",
                        "Maritime Imports Inc.",
                        "2000 Barrington St",
                        "Unit 4B",
                        "Halifax",
                        "NS",
                        "B3J 3K1",
                        "CA",
                        "902-555-0100",
                        "bob@maritime.ca",
                        false);
        var richIntent =
                new ShippingIntent(
                        richSender,
                        richRecipient,
                        new ShippingIntent.Package(
                                new BigDecimal("12.3456"),
                                new BigDecimal("10.5000"),
                                new BigDecimal("8.25"),
                                DimensionUnit.CM,
                                new BigDecimal("5.125"),
                                WeightUnit.KG,
                                "PACKAGE"),
                        LocalDate.of(2026, 10, 23));

        var created = service().create("user", "rich-roundtrip-key", richIntent);
        var saved = store.request("user", created.result().requestId()).orElseThrow();
        assertThat(saved.intent()).isEqualTo(richIntent);
        assertThat(saved.deadline()).isNull();
        assertThat(saved.state()).isEqualTo(RateRequest.State.READY);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static final class MutableClock extends Clock {
        final AtomicLong time =
                new AtomicLong(Instant.parse("2026-10-23T12:00:00Z").toEpochMilli());

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
