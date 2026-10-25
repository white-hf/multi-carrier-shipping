package com.enterprise.shipping.booking.port;

public interface ShippingBookingObserver {
    enum Operation { SUBMIT, REPLAY, CONFLICT, LABEL }
    enum Outcome { CONFIRMED, REJECTED, UNKNOWN, AVAILABLE, PENDING, ERROR }

    void record(Operation operation, Outcome outcome, long elapsedNanos);
}
