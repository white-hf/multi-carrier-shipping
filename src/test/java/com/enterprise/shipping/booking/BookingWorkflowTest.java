package com.enterprise.shipping.booking;

import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.enterprise.shipping.shared.domain.ShipmentParcel;
import com.enterprise.shipping.booking.contract.recovery.Recovery.*;
import com.enterprise.shipping.reconciliation.application.*;
import com.enterprise.shipping.reconciliation.config.ReconciliationProperties;
import com.enterprise.shipping.reconciliation.infrastructure.persistence.JdbcReconciliationTaskStore;
import com.enterprise.shipping.reconciliation.port.*;
import com.enterprise.shipping.reconciliation.infrastructure.monitoring.MicrometerReconciliationObserver;
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
    ReconciliationTaskStore tasks;
    ReconciliationProperties recovery = new ReconciliationProperties(15000,500,10,1000,3,16,100);
    ReconciliationObserver recoveryObserver = new MicrometerReconciliationObserver(new SimpleMeterRegistry());
    JdbcTemplate jdbc;
    SqliteDemoCarrier remote;
    BookingRequestHasher hasher;
    BookingProperties properties;
    MutableClock clock;
    RecordingCarrier carrier;
    BookingService service;
    BookingOwner owner = new BookingOwner("MERCHANT", "merchant-a");
    BookingObserver observer = new MicrometerBookingObserver(new SimpleMeterRegistry());

    @BeforeEach void setup() {
        properties = properties(directory);
        clock = new MutableClock();
        hasher = new BookingRequestHasher(new ObjectMapper());
        openDatabases();
        service = service(store);
    }
    @AfterEach void close() { database.close(); remoteDatabase.close(); }
    void openDatabases() {
        database = SqliteDataSources.open(properties.databasePath(), "test-booking", 4, 50, "booking/schema-v1.sql");
        remoteDatabase = SqliteDataSources.open(directory.resolve("carrier.db").toString(), "test-carrier", 4, 50, "demo/carrier-schema-v1.sql");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("reconciliation/schema-v1.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        tasks = new JdbcReconciliationTaskStore(jdbc);
        store = new JdbcBookingStore(jdbc);
        remote = new SqliteDemoCarrier(new JdbcTemplate(remoteDatabase), hasher, new ObjectMapper());
        carrier = new RecordingCarrier(remote);
    }
    static BookingProperties properties(Path directory) {
        return properties(directory, Set.of("fedex", "ups"));
    }
    static BookingProperties properties(Path directory, Set<String> enabledCarriers) {
        return new BookingProperties(directory.resolve("bookings.db").toString(),
                500, 15000, 50, 4, "local", "test-account", enabledCarriers);
    }
    BookingService service(BookingStore repository) {
        return new BookingService(repository, carrier, hasher, properties, clock, observer,
                com.enterprise.shipping.support.TestMarket.canadian());
    }
    void reconcile() {
        var port = new BookingRecoveryService(store, clock);
        new ReconciliationDiscovery(port, tasks, recovery, clock, recoveryObserver).discoverBatch();
        new ReconciliationWorker(tasks, port, carrier, recovery, clock, recoveryObserver).runBatch();
    }
    static BookingCommand command() {
        return new BookingCommand("order-1", "fedex", "Priority", new ShipmentParcel(
                "L6K 3V3", "M5V 2T6", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "CA", "CA"));
    }
    BookingRecord seed(String key, BookingCommand command) {
        long now = clock.millis();
        String id = UUID.randomUUID().toString();
        var record = BookingRecord.claim(ShipmentBooking.pending(new BookingId(id), command.carrierId(), "booking-" + id),
                owner, key, hasher.hash(command), command, "local", "test-account", now, now - 1);
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
        assertThat(hasher.hash(equivalent)).isEqualTo(hasher.hash(original));
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
        reconcile();
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
        reconcile();
        var recovered = service.get(owner, first.bookingId());
        assertThat(recovered.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(0);
        assertThat(remote.submissionCount(first.customerReference())).isEqualTo(1);
    }

    @Test void stalePendingAfterCrashCanRecoverAnAlreadyAcceptedBooking() {
        var command = command();
        BookingRecord stale = seed("crash-key", command);
        remote.accept(stale.customerReference(), command);
        reconcile();
        assertThat(service.get(owner, stale.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(0);
    }

    @Test void notFoundIsNeverPermissionToResubmitAndEventuallyNeedsManualReview() {
        BookingRecord stale = seed("never-submitted", command());
        for (int attempt = 0; attempt < 3; attempt++) { reconcile(); clock.advance(2000); }
        assertThat(service.get(owner, stale.bookingId()).status()).isEqualTo(BookingStatus.MANUAL_REVIEW);
        reconcile();
        assertThat(carrier.inquiries).hasValue(3);
        assertThat(carrier.submissions).hasValue(0);
    }

    @Test void staleTokenCannotOverwriteWorkerConfirmation() {
        BookingRecord stale = seed("fenced-key", command());
        var leased = stale;
        var receipt = remote.accept(stale.customerReference(), command());
        var snapshot = new BookingResponse(stale.bookingId(), BookingStatus.CONFIRMED, stale.carrierId(),
                stale.customerReference(), receipt.carrierBookingId(), receipt.trackingNumber(), receipt.labelUrl(), null);
        var confirmed = leased.restoreBooking();
        confirmed.confirm(receipt);
        assertThat(store.saveOutcome(leased, confirmed, clock.millis())).isTrue();
        var uncertain = stale.restoreBooking();
        uncertain.markOutcomeUnknown();
        assertThat(store.saveOutcome(stale, uncertain, clock.millis())).isFalse();
        var rejected = stale.restoreBooking();
        rejected.reject(RejectionReason.CARRIER_REJECTED);
        assertThat(store.saveOutcome(stale, rejected, clock.millis())).isFalse();
        assertThat(service.get(owner, stale.bookingId())).isEqualTo(snapshot);
    }

    @Test void competingWorkersAcquireOnlyOneLease() throws Exception {
        BookingRecord stale = seed("worker-race", command());
        new ReconciliationDiscovery(new BookingRecoveryService(store, clock), tasks, recovery, clock, recoveryObserver).discoverBatch();
        var due = tasks.findDue(clock.millis(), 1).get(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> claim = () -> { start.await(); return tasks.claim(due, UUID.randomUUID().toString(),
                    clock.millis(), clock.millis() + 1000).isPresent(); };
            Future<Boolean> first = executor.submit(claim), second = executor.submit(claim); start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
    }

    @Test void persistenceFailureAfterAcceptanceDoesNotTriggerAnotherSubmission() {
        BookingStore failing = spy(store);
        doThrow(new BookingException(BookingException.Code.STORE_UNAVAILABLE)).when(failing)
                .saveOutcome(any(), any(), anyLong());
        assertThatThrownBy(() -> service(failing).create(owner, "save-failure", command()))
                .isInstanceOf(BookingException.class);
        var pending = store.find(owner, "save-failure").orElseThrow();
        assertThat(pending.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(service.create(owner, "save-failure", command()).status()).isEqualTo(BookingStatus.PENDING);
        clock.advance(properties.submissionDeadlineMs() + 1);
        reconcile();
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
        var restricted = new BookingService(store, carrier, hasher, disabledProperties, clock, observer,
                com.enterprise.shipping.support.TestMarket.canadian());
        assertThat(restricted.create(owner, "before-disabled", intent)).isEqualTo(first);
        assertThatThrownBy(() -> restricted.create(owner, "after-disabled", intent))
                .isInstanceOfSatisfying(BookingException.class,
                        error -> assertThat(error.code()).isEqualTo(BookingException.Code.UNSUPPORTED_CARRIER));
        assertThat(store.find(owner, "after-disabled")).isEmpty();
        clock.advance(100);
        reconcile();
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
        reconcile();
        assertThat(service.get(owner, first.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(carrier.submissions).hasValue(1);
        assertThat(remote.submissionCount(first.customerReference())).isEqualTo(1);
    }

    @Test void aggregateCannotBePersistedUnderAnotherStorageIdentity() {
        var owned = seed("identity-guard", command());
        var other = ShipmentBooking.pending(new BookingId("other-booking"), "fedex", owned.customerReference());
        other.markOutcomeUnknown();
        assertThatThrownBy(() -> store.saveOutcome(owned, other, clock.millis()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(owner, "identity-guard").orElseThrow().status()).isEqualTo(BookingStatus.PENDING);
    }

    @Test void invalidInquiryReceiptCannotConfirmBooking() {
        BookingRecord stale = seed("invalid-receipt", command());
        carrier.invalidInquiry = true;
        reconcile();
        assertThat(service.get(owner, stale.bookingId()).status()).isEqualTo(BookingStatus.PENDING_INQUIRY);
    }

    @Test void brokenMetricsCannotFailSuccessfulBooking() {
        var registry = mock(io.micrometer.core.instrument.MeterRegistry.class);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(registry).counter(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(registry).counter(anyString(), anyString(), anyString(), anyString(), anyString());
        var isolated = new BookingService(store, carrier, hasher, properties, clock,
                new MicrometerBookingObserver(registry), com.enterprise.shipping.support.TestMarket.canadian());
        assertThat(isolated.create(owner, "metrics-failure", command()).status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test void successfulAndRejectedBookingsNeverCreateRecoveryTasks() {
        service.create(owner, "normal-no-task", command());
        carrier.mode = Mode.REJECTED;
        service.create(owner, "rejected-no-task", command());
        reconcile();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_reconciliation_tasks", Integer.class)).isZero();
        assertThat(carrier.inquiries).hasValue(0);
        assertThat(jdbc.queryForList("PRAGMA table_info(shipment_bookings)").stream()
                .map(column -> column.get("name")).toList())
                .doesNotContain("inquiry_attempts", "next_inquiry_at", "lease_token", "lease_expires_at");
    }

    ReconciliationDiscovery discovery(ReconciliationTaskStore taskStore) {
        return new ReconciliationDiscovery(new BookingRecoveryService(store, clock), taskStore, recovery, clock, recoveryObserver);
    }
    ReconciliationWorker worker(ReconciliationTaskStore taskStore,
            com.enterprise.shipping.booking.contract.recovery.BookingRecoveryPort bookingPort) {
        return new ReconciliationWorker(taskStore, bookingPort, carrier, recovery, clock, recoveryObserver);
    }

    @Test void failedTaskRegistrationIsRecoveredByRepeatedDiscovery() {
        seed("registration-failure", command());
        var failing = spy(tasks);
        doThrow(new IllegalStateException("task DB unavailable")).doCallRealMethod().when(failing).ensure(any(), anyLong());
        var discovery = discovery(failing);
        assertThatThrownBy(discovery::discoverBatch).isInstanceOf(IllegalStateException.class);
        discovery.discoverBatch();
        discovery(tasks).discoverBatch();
        var due = tasks.findDue(clock.millis(), 10);
        assertThat(due).hasSize(1);
        assertThat(UUID.fromString(due.get(0).taskId()).version()).isEqualTo(7);
        assertThat(due.get(0).reference().carrierContextReference()).isEqualTo("test-account");
        assertThat(due.get(0).reference().customerReference()).startsWith("booking-");
    }

    @Test void expiredTaskLeaseCanBeTakenOverAndStaleOwnerCannotWrite() {
        seed("task-fence", command());
        discovery(tasks).discoverBatch();
        var candidate = tasks.findDue(clock.millis(), 1).get(0);
        var first = tasks.claim(candidate, "first", clock.millis(), clock.millis()+1000).orElseThrow();
        clock.advance(1001);
        var next = tasks.findDue(clock.millis(), 1).get(0);
        var second = tasks.claim(next, "second", clock.millis(), clock.millis()+1000).orElseThrow();
        var unknown = new Observation(first.reference(), ObservationType.UNRESOLVED, null);
        assertThat(tasks.saveObservation(first, unknown, null, clock.millis())).isEmpty();
        assertThat(tasks.delete(first)).isFalse();
        assertThat(tasks.retry(first, clock.millis(), "stale", false, clock.millis())).isFalse();
        assertThat(tasks.saveObservation(second, unknown, null, clock.millis())).isPresent();
        assertThat(store.find(owner, "task-fence").orElseThrow().version()).isZero();
    }

    @Test void persistedReceiptIsRedeliveredAfterLostAcknowledgementWithoutAnotherInquiry() {
        var record = seed("lost-ack", command());
        remote.accept(record.customerReference(), command());
        discovery(tasks).discoverBatch();
        var port = spy(new BookingRecoveryService(store, clock));
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("acknowledgement lost"); })
                .doCallRealMethod().when(port).apply(any());
        var worker = worker(tasks, port);
        worker.runBatch();
        assertThat(service.get(owner, record.bookingId()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT result_type FROM booking_reconciliation_tasks", String.class)).isEqualTo("FOUND");
        database.close(); remoteDatabase.close();
        openDatabases(); service = service(store);
        clock.advance(2000);
        worker(tasks, new BookingRecoveryService(store, clock)).runBatch();
        // Reopened worker delivers persisted evidence, without querying the carrier again.
        assertThat(carrier.inquiries).hasValue(0);
        assertThat(carrier.submissions).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_reconciliation_tasks", Integer.class)).isZero();
    }

    @Test void taskDeletionFailureReplaysStoredResultAfterLeaseExpiry() {
        var record = seed("delete-failure", command());
        remote.accept(record.customerReference(), command());
        discovery(tasks).discoverBatch();
        var failing = spy(tasks);
        doThrow(new IllegalStateException("storage unavailable")).doCallRealMethod().when(failing).delete(any());
        var worker = worker(failing, new BookingRecoveryService(store, clock));
        worker.runBatch();
        clock.advance(recovery.leaseMs()+1);
        worker.runBatch();
        assertThat(carrier.inquiries).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_reconciliation_tasks", Integer.class)).isZero();
    }

    @Test void unresolvedRetriesOnlyChangeBookingOnBusinessTransitions() {
        var record = seed("business-version", command());
        reconcile();
        long version = store.find(owner, "business-version").orElseThrow().version();
        clock.advance(2000);
        reconcile();
        assertThat(store.find(owner, "business-version").orElseThrow().version()).isEqualTo(version);
        clock.advance(2000);
        reconcile();
        assertThat(service.get(owner, record.bookingId()).status()).isEqualTo(BookingStatus.MANUAL_REVIEW);
        assertThat(jdbc.queryForObject("SELECT state FROM booking_reconciliation_tasks", String.class)).isEqualTo("PAUSED");
    }

    @Test void discoveryWrapsToFindOldBookingThatBecomesUncertainLater() {
        var early = seed("early", command());
        jdbc.update("UPDATE shipment_bookings SET submission_deadline_at=? WHERE booking_id=?", clock.millis()+10000, early.bookingId());
        clock.advance(1);
        seed("later", command());
        var one = new ReconciliationProperties(15000,500,10,1000,3,16,1);
        var port = new BookingRecoveryService(store, clock);
        var discovery = new ReconciliationDiscovery(port, tasks, one, clock, recoveryObserver);
        discovery.discoverBatch(); // cursor has moved past early
        var booking = early.restoreBooking(); booking.markOutcomeUnknown();
        assertThat(store.saveOutcome(early, booking, clock.millis())).isTrue();
        discovery.discoverBatch(); // wraps at end
        discovery.discoverBatch(); // revisits early
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_reconciliation_tasks", Integer.class)).isEqualTo(2);
    }

    @Test void recoveryRefusesMismatchedAccountReference() {
        var record = seed("reference-guard", command());
        var bad = new Reference(record.bookingId(), "local", "fedex", "other-account", record.customerReference());
        assertThatThrownBy(() -> new BookingRecoveryService(store, clock)
                .apply(new Observation(bad, ObservationType.UNRESOLVED, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(owner, "reference-guard").orElseThrow().version()).isZero();
    }

    @Test void taskDueQueryUsesPartialIndexWithoutSorting() {
        seed("task-index", command()); discovery(tasks).discoverBatch();
        var plan = jdbc.query("EXPLAIN QUERY PLAN " + JdbcReconciliationTaskStore.DUE_SQL,
                (row,index) -> row.getString("detail"), clock.millis(), 16);
        assertThat(plan).anyMatch(step -> step.contains("idx_reconciliation_due"));
        assertThat(plan).noneMatch(step -> step.contains("TEMP B-TREE"));
    }

    static class RecordingCarrier implements CarrierBookingPort, CarrierInquiryPort {
        final SqliteDemoCarrier remote;
        final AtomicInteger submissions = new AtomicInteger(), inquiries = new AtomicInteger();
        Mode mode = Mode.NORMAL;
        boolean invalidInquiry;
        boolean invalidSubmission;
        RecordingCarrier(SqliteDemoCarrier remote) { this.remote = remote; }
        public CarrierReceipt submit(String context, String reference, BookingCommand command) {
            submissions.incrementAndGet();
            if (mode == Mode.REJECTED) throw new Rejected();
            var receipt = remote.accept(reference, command);
            if (mode == Mode.TIMEOUT_AFTER_ACCEPT) throw new UnknownOutcome(new TimeoutException());
            return invalidSubmission ? new CarrierReceipt("wrong-reference", "id", "tracking", "label") : receipt;
        }
        public Optional<Evidence> inquire(Reference reference, long timeoutMs) {
            inquiries.incrementAndGet();
            if (invalidInquiry) return Optional.of(new Evidence("wrong-reference", "id", "tracking", "label"));
            return remote.find(reference.customerReference()).map(r -> new Evidence(r.customerReference(),
                    r.carrierBookingId(), r.trackingNumber(), r.labelUrl()));
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
