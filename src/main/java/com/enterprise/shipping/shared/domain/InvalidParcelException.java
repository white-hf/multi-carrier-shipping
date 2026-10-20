package com.enterprise.shipping.shared.domain;

/** Safe validation failure without reflecting submitted address data. */
public final class InvalidParcelException extends RuntimeException {
    public InvalidParcelException() { super("Parcel does not satisfy the configured market policy"); }
}
