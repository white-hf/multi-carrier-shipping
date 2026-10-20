package com.enterprise.shipping.booking;

import com.enterprise.shipping.shared.domain.ShipmentParcel;
import com.enterprise.shipping.booking.application.*;
import com.enterprise.shipping.booking.config.BookingProperties;
import com.enterprise.shipping.demo.booking.SqliteDemoCarrier;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.infrastructure.monitoring.MicrometerBookingObserver;
import com.enterprise.shipping.booking.infrastructure.persistence.*;
import com.enterprise.shipping.booking.port.*;
import com.enterprise.shipping.demo.booking.DemoBookingProperties.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingWorkflowTest {
    @TempDir Path directory;
    HikariDataSource database;
    HikariDataSource remoteDatabase;
    BookingStore store;
    JdbcTemplate jdbc;
    SqliteDemoCarrier remote;
    BookingJson json;
    BookingProperties properties;
    MutableClock clock;
    RecordingCarrier carrier;
    BookingService service;
    BookingOwner owner = new BookingOwner("MERCHANT", "merchant-a");
    BookingObserver observer = new MicrometerBookingObserver(new SimpleMeterRegistry());

    @BeforeEach void setup() {
        properties = properties(directory);
        clock = new MutableClock();
        json = new BookingJson(new ObjectMapper());
        openDatabases();
        service = service(store);
    }
    @AfterEach void close() { database.close(); remoteDatabase.close(); }
    void openDatabases() {
        database = SqliteDataSources.open(properties.databasePath(), "test-booking", 4, 50, "booking/schema-v1.sql");
        remoteDatabase = SqliteDataSources.open(directory.resolve("carrier.db").toString(), "test-carrier", 4, 50, "demo/carrier-schema-v1.sql");
        jdbc = new JdbcTemplate(database);
        store = new JdbcBookingStore(jdbc);
        remote = new SqliteDemoCarrier(new JdbcTemplate(remoteDatabase), json, new ObjectMapper());
        carrier = new RecordingCarrier(remote);
    }
    static BookingProperties properties(Path directory) {
        return properties(directory, Set.of("fedex", "ups"));
    }
    static BookingProperties properties(Path directory, Set<String> enabledCarriers) {
        return new BookingProperties(directory.resolve("bookings.db").toString(),
                500, 15000, 50, 4, 10, 1000, 3, 16, enabledCarriers);
    }
    BookingService service(BookingStore repository) {
        return new BookingService(repository, carrier, json, properties, clock, observer,
                com.enterprise.shipping.support.TestMarket.canadian());
    }
    BookingReconciliationWorker worker() {
        return new BookingReconciliationWorker(store, carrier, json, properties, clock, observer);
    }
    static BookingCommand command() {
        return new BookingCommand("order-1", "fedex", "Priority", new ShipmentParcel(
                "L6K 3V3", "M5V 2T6", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "CA", "CA"));
    }
    BookingRecord seed(String key, BookingCommand command) {
        long now = clock.millis();
        String id = UUID.randomUUID().toString();
        var record = new BookingRecord(id, owner, key, json.hash(command), json.write(command), command.carrierId(),
                "booking-" + id, BookingStatus.PENDING, null, null, null, null, null,
                now, now, now, 0, "initial-owner", now - 1, 0);
        assertThat(store.insertClaim(record)).isTrue();
        return record;
    }

    @Test void concurrentInsertRaceProducesOneInitialSubmission() throws Exception {
        int callers = 12;
        CyclicBarrier initialReads = new CyclicBarrier(callers);
        AtomicInteger reads = new AtomicInteger();
        BookingStore racing = spy(store);
        doAnswer(invocation -> {
            if (reads.getAndIncrement() < callers) {
                initialReads.await(3, TimeUnit.SECONDS);
                return Optional.empty();
            }
            return invocation.callRealMethod();
        }).when(racing).find(owner, "same-key");
        BookingService concurrent = service(racing);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<BookingResponse>> tasks = new ArrayList<>();
            for (int index = 0; index < callers; index++) tasks.add(() -> concurrent.create(owner, "same-key", command()));
            var results = executor.invokeAll(tasks);
            Set<String> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(5, TimeUnit.SECONDS).bookingId());
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_bookings", Integer.class)).isEqualTo(1);
            assertThat(carrier.submissions).hasValue(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void repeatedSuccessReplaysSavedResponseAndChangedIntentConflicts() {
        var command = command();
        var first = service.create(owner, "retry-key", command);
        assertThat(first.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(service.create(owner, "retry-key", command)).isEqualTo(first);
        var changed = new BookingCommand("different-order", command.carrierId(), command.serviceLevel(), command.parcel());
        assertThatThrownBy(() -> service.create(owner, "retry-key", changed))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.code()).isEqualTo(BookingException.Code.IDEMPOTENCY_CONFLICT));
        assertThat(carrier.submissions).hasValue(1);
    }

    @Test void normalizedEquivalentPayloadHasSameFingerprint() {
        var original = command();
        var equivalent = new BookingCommand(" order-1 ", "FEDEX", "priority", new ShipmentParcel(
                "l6k3v3", "m5v2t6", new BigDecimal("1.00"), new BigDecimal("10.0"), BigDecimal.TEN, BigDecimal.TEN, "CA", "CA"));
        assertThat(json.hash(equivalent)).isEqualTo(json.hash(original));
    }

    @Test void ownerAndOpaqueKeyComparisonAreIsolated() {
        var command = command();
        var first = service.create(owner, "Key-A", command);
        var second = service.create(owner, "key-a", command);
        var otherOwner = new BookingOwner("MERCHANT", "merchant-b");
        var third = service.create(otherOwner, "Key-A", command);
        assertThat(Set.of(first.bookingId(), second.bookingId(), third.bookingId())).hasSize(3);
        assertThatThrownBy(() -> service.get(otherOwner, first.bookingId()))
                .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.code()).isEqualTo(BookingException.Code.BOOKING_NOT_FOUND));
    }

    @Test void timeoutAfterAcceptReplaysUncertaintyAndReconcilesWithoutResubmit() {
        carrier.mode = Mode.TIMEOUT_AFTER_ACCEPT;
        var command = command();
        var first = service.create(owner, "timeout-key", command);
        assertThat(first.status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThat(service.create(owner, "timeout-key", command).status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        clock.advance(100);
        worker().reconcileBatch();
        assertThat(service.get(owner, first.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(1);
        assertThat(remote.submissionCount(first.customerReference())).isEqualTo(1);
    }

    @Test void separateDatabaseReopenPreservesUncertainBookingAndCarrierReceipt() {
        carrier.mode = Mode.TIMEOUT_AFTER_ACCEPT;
        var first = service.create(owner, "restart-key", command());
        database.close(); remoteDatabase.close();
        openDatabases();
        service = service(store);
        clock.advance(100);
        worker().reconcileBatch();
        var recovered = service.get(owner, first.bookingId());
        assertThat(recovered.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(0);
        assertThat(remote.submissionCount(first.customerReference())).isEqualTo(1);
    }

    @Test void stalePendingAfterCrashCanRecoverAnAlreadyAcceptedBooking() {
        var command = command();
        BookingRecord stale = seed("crash-key", command);
        remote.accept(stale.customerReference(), command);
        worker().reconcileBatch();
        assertThat(service.get(owner, stale.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(0);
    }

    @Test void notFoundIsNeverPermissionToResubmitAndEventuallyNeedsManualReview() {
        BookingRecord stale = seed("never-submitted", command());
        for (int attempt = 0; attempt < 3; attempt++) { worker().reconcileBatch(); clock.advance(2000); }
        assertThat(service.get(owner, stale.bookingId()).status()).isEqualTo(BookingStatus.MANUAL_REVIEW);
        worker().reconcileBatch();
        assertThat(carrier.inquiries).hasValue(3);
        assertThat(carrier.submissions).hasValue(0);
    }

    @Test void staleTokenCannotOverwriteWorkerConfirmation() {
        BookingRecord stale = seed("fenced-key", command());
        var leased = store.acquireInquiryLease(stale, "worker-token", clock.millis(), clock.millis() + 1000).orElseThrow();
        var receipt = remote.accept(stale.customerReference(), command());
        var snapshot = new BookingResponse(stale.bookingId(), BookingStatus.CONFIRMED, stale.carrierId(),
                stale.customerReference(), receipt.carrierBookingId(), receipt.trackingNumber(), receipt.labelUrl(), null);
        var confirmed = leased.restoreBooking();
        confirmed.confirm(receipt);
        assertThat(store.complete(leased, confirmed, json.write(snapshot), clock.millis())).isTrue();
        var uncertain = stale.restoreBooking();
        uncertain.markOutcomeUnknown();
        assertThat(store.defer(stale, uncertain, clock.millis(), clock.millis())).isFalse();
        var rejected = stale.restoreBooking();
        rejected.reject(RejectionReason.CARRIER_REJECTED);
        assertThat(store.complete(stale, rejected, json.write(BookingResponse.from(rejected)), clock.millis())).isFalse();
        assertThat(service.get(owner, stale.bookingId())).isEqualTo(snapshot);
    }

    @Test void competingWorkersAcquireOnlyOneLease() throws Exception {
        BookingRecord stale = seed("worker-race", command());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> claim = () -> { start.await(); return store.acquireInquiryLease(stale, UUID.randomUUID().toString(),
                    clock.millis(), clock.millis() + 1000).isPresent(); };
            Future<Boolean> first = executor.submit(claim), second = executor.submit(claim); start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
    }

    @Test void persistenceFailureAfterAcceptanceDoesNotTriggerAnotherSubmission() {
        BookingStore failing = spy(store);
        doThrow(new BookingException(BookingException.Code.STORE_UNAVAILABLE)).when(failing)
                .complete(any(), any(), any(), anyLong());
        assertThatThrownBy(() -> service(failing).create(owner, "save-failure", command()))
                .isInstanceOf(BookingException.class);
        var pending = store.find(owner, "save-failure").orElseThrow();
        assertThat(pending.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(service.create(owner, "save-failure", command()).status()).isEqualTo(BookingStatus.PENDING);
        clock.advance(properties.leaseMs() + 1);
        worker().reconcileBatch();
        assertThat(service.get(owner, pending.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(1);
    }

    @Test void databaseLockContentionIsNotMisclassifiedAsIdempotencyReplay() throws Exception {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE shipment_bookings SET updated_at=updated_at WHERE 1=0");
                assertThatThrownBy(() -> service.create(owner, "locked", command()))
                        .isInstanceOfSatisfying(BookingException.class, e -> assertThat(e.code()).isEqualTo(BookingException.Code.STORE_UNAVAILABLE));
                assertThat(carrier.submissions).hasValue(0);
            } finally { connection.rollback(); }
        }
        assertThat(service.create(owner, "locked", command()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test void definitiveRejectionIsTerminalAndReplayed() {
        carrier.mode = Mode.REJECTED;
        var command = command();
        var first = service.create(owner, "reject", command);
        assertThat(first.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(service.create(owner, "reject", command)).isEqualTo(first);
        assertThat(carrier.submissions).hasValue(1);
    }

    @Test void disablingCarrierBlocksNewBookingsButPreservesReplayAndRecovery() {
        carrier.mode = Mode.TIMEOUT_AFTER_ACCEPT;
        var intent = command();
        var first = service.create(owner, "before-disabled", intent);
        var disabledProperties = properties(directory, Set.of("ups"));
        var restricted = new BookingService(store, carrier, json, disabledProperties, clock, observer,
                com.enterprise.shipping.support.TestMarket.canadian());
        assertThat(restricted.create(owner, "before-disabled", intent)).isEqualTo(first);
        assertThatThrownBy(() -> restricted.create(owner, "after-disabled", intent))
                .isInstanceOfSatisfying(BookingException.class,
                        error -> assertThat(error.code()).isEqualTo(BookingException.Code.UNSUPPORTED_CARRIER));
        assertThat(store.find(owner, "after-disabled")).isEmpty();
        clock.advance(100);
        new BookingReconciliationWorker(store, carrier, json, disabledProperties, clock, observer).reconcileBatch();
        assertThat(restricted.create(owner, "before-disabled", intent).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(1);
    }

    @Test void invalidSubmissionReceiptRemainsRecoverableWithoutAnotherSubmission() {
        carrier.invalidSubmission = true;
        var intent = command();
        var first = service.create(owner, "invalid-submit", intent);
        assertThat(first.status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
        assertThat(first.errorCode()).isEqualTo("CARRIER_OUTCOME_UNKNOWN");
        assertThat(first.carrierBookingId()).isNull();
        assertThat(service.create(owner, "invalid-submit", intent)).isEqualTo(first);
        clock.advance(100);
        worker().reconcileBatch();
        assertThat(service.get(owner, first.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(1);
        assertThat(remote.submissionCount(first.customerReference())).isEqualTo(1);
    }

    @Test void aggregateCannotBePersistedUnderAnotherStorageIdentity() {
        var owned = seed("identity-guard", command());
        var other = ShipmentBooking.pending(new BookingId("other-booking"), "fedex", owned.customerReference());
        other.markOutcomeUnknown();
        assertThatThrownBy(() -> store.defer(owned, other, clock.millis(), clock.millis()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(owner, "identity-guard").orElseThrow().status()).isEqualTo(BookingStatus.PENDING);
    }

    @Test void invalidInquiryReceiptCannotConfirmBooking() {
        BookingRecord stale = seed("invalid-receipt", command());
        carrier.invalidInquiry = true;
        worker().reconcileBatch();
        assertThat(service.get(owner, stale.bookingId()).status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
    }

    @Test void brokenMetricsCannotFailSuccessfulBooking() {
        var registry = mock(io.micrometer.core.instrument.MeterRegistry.class);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(registry).counter(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(registry).counter(anyString(), anyString(), anyString(), anyString(), anyString());
        var isolated = new BookingService(store, carrier, json, properties, clock,
                new MicrometerBookingObserver(registry), com.enterprise.shipping.support.TestMarket.canadian());
        assertThat(isolated.create(owner, "metrics-failure", command()).status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    static class RecordingCarrier implements CarrierBookingPort {
        final SqliteDemoCarrier remote;
        final AtomicInteger submissions = new AtomicInteger(), inquiries = new AtomicInteger();
        Mode mode = Mode.NORMAL;
        boolean invalidInquiry;
        boolean invalidSubmission;
        RecordingCarrier(SqliteDemoCarrier remote) { this.remote = remote; }
        public CarrierReceipt submit(String reference, BookingCommand command) {
            submissions.incrementAndGet();
            if (mode == Mode.REJECTED) throw new Rejected();
            var receipt = remote.accept(reference, command);
            if (mode == Mode.TIMEOUT_AFTER_ACCEPT) throw new UnknownOutcome(new TimeoutException());
            return invalidSubmission ? new CarrierReceipt("wrong-reference", "id", "tracking", "label") : receipt;
        }
        public Optional<CarrierReceipt> inquire(String reference) {
            inquiries.incrementAndGet();
            if (invalidInquiry) return Optional.of(new CarrierReceipt("wrong-reference", "id", "tracking", "label"));
            return remote.find(reference);
        }
    }
    static class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(1700000000000L);
        void advance(long millis) { now.addAndGet(millis); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    }
}
