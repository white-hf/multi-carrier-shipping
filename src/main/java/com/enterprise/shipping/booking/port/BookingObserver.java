package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.BookingStatus;

/** Instrumentation must not throw or change booking outcomes. No keys or customer data are metric labels. */
public interface BookingObserver {
    void claim(boolean created);
    void outcome(String operation, BookingStatus status, long elapsedNanos);
    void conflict();
}
