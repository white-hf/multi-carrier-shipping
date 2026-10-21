package com.enterprise.shipping.reconciliation.application;

import com.enterprise.shipping.contracts.recovery.BookingRecoveryPort;
import com.enterprise.shipping.contracts.recovery.Recovery.Cursor;
import com.enterprise.shipping.reconciliation.config.ReconciliationProperties;
import com.enterprise.shipping.reconciliation.port.*;
import com.enterprise.shipping.reconciliation.port.ReconciliationObserver.*;
import java.time.Clock;

/** Recurring keyset sweeps discover both abandoned submissions and failed task registrations. */
public final class ReconciliationDiscovery {
    private final BookingRecoveryPort bookings;
    private final ReconciliationTaskStore tasks;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final ReconciliationObserver observer;
    private Cursor cursor = Cursor.START;
    public ReconciliationDiscovery(BookingRecoveryPort bookings, ReconciliationTaskStore tasks,
            ReconciliationProperties properties, Clock clock, ReconciliationObserver observer) {
        this.bookings = bookings; this.tasks = tasks; this.properties = properties; this.clock = clock; this.observer = observer;
    }
    public synchronized void discoverBatch() {
        var page = bookings.discover(cursor, properties.discoveryBatchSize());
        // Advance only after all ensures succeed. Retrying a page cannot create duplicate tasks.
        for (var candidate : page.candidates()) {
            boolean created = tasks.ensure(candidate.reference(), clock.millis());
            observer.record(Operation.DISCOVERY, created ? Outcome.CREATED : Outcome.EXISTING, 0);
        }
        cursor = page.nextCursor();
    }
}
