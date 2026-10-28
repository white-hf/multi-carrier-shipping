package com.enterprise.shipping.quote.domain;

/** NO_SERVICES is a successful carrier query, distinct from a failed dependency. */
public enum OutcomeCode {
    SUCCESS,
    NO_SERVICES,
    TIMEOUT,
    CIRCUIT_OPEN,
    CAPACITY_REJECTED,
    UPSTREAM_ERROR,
    INVALID_RESPONSE,
    INTERRUPTED
}
