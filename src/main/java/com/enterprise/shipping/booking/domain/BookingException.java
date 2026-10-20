package com.enterprise.shipping.booking.domain;

/** Safe operation-level failures; HTTP mapping belongs to the API layer. */
public class BookingException extends RuntimeException {
    public enum Code { IDEMPOTENCY_CONFLICT, BOOKING_NOT_FOUND, STORE_UNAVAILABLE, INVALID_IDEMPOTENCY_KEY }
    private final Code code;

    public BookingException(Code code) {
        super(code.name());
        this.code = code;
    }

    public BookingException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() { return code; }
}
