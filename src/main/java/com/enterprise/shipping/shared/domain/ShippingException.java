package com.enterprise.shipping.shared.domain;

import java.util.Objects;

/** Failure semantics and resource identity only; protocols map these at their own boundary. */
public class ShippingException extends RuntimeException {
    public enum Category {
        INVALID_INPUT,
        RESOURCE_MISSING,
        STATE_CONFLICT,
        DEPENDENCY_FAILURE,
        DEPENDENCY_TIMEOUT,
        LOCAL_CAPACITY,
        STORAGE_FAILURE,
        INTEGRATION_UNAVAILABLE,
        OUTCOME_UNKNOWN;

        /** Caller mistakes do not indicate that an external dependency is unhealthy. */
        public boolean isCallerFault() {
            return this == INVALID_INPUT || this == RESOURCE_MISSING || this == STATE_CONFLICT;
        }
    }

    public enum Resource {
        QUOTE_REQUEST,
        BOOKING
    }

    public record Operation(Resource resource, String id) {
        public Operation {
            Objects.requireNonNull(resource, "Operation resource required");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Operation ID required");
            }
        }
    }

    private final String code;
    private final Category category;
    private final Operation operation;

    public ShippingException(String code, Category category) {
        this(code, category, null);
    }

    private ShippingException(String code, Category category, Operation operation) {
        super(Objects.requireNonNull(code, "Failure code required"));
        this.code = code;
        this.category = Objects.requireNonNull(category, "Failure category required");
        this.operation = operation;
    }

    public static ShippingException invalidInput(String code) {
        return new ShippingException(code, Category.INVALID_INPUT);
    }

    public static ShippingException missing() {
        return new ShippingException("RESOURCE_NOT_FOUND", Category.RESOURCE_MISSING);
    }

    public static ShippingException conflict(String code) {
        return new ShippingException(code, Category.STATE_CONFLICT);
    }

    public static ShippingException dependencyFailure(String code) {
        return new ShippingException(code, Category.DEPENDENCY_FAILURE);
    }

    public static ShippingException dependencyTimeout(String code) {
        return new ShippingException(code, Category.DEPENDENCY_TIMEOUT);
    }

    public static ShippingException localCapacity(String code) {
        return new ShippingException(code, Category.LOCAL_CAPACITY);
    }

    public static ShippingException storageUnavailable() {
        return new ShippingException("STORE_UNAVAILABLE", Category.STORAGE_FAILURE);
    }

    public static ShippingException integrationUnavailable(String code) {
        return new ShippingException(code, Category.INTEGRATION_UNAVAILABLE);
    }

    public static ShippingException outcomeUnknown(String code) {
        return new ShippingException(code, Category.OUTCOME_UNKNOWN);
    }

    public String code() {
        return code;
    }

    public Category category() {
        return category;
    }

    public Operation operation() {
        return operation;
    }

    public ShippingException forQuoteRequest(String id) {
        return forOperation(Resource.QUOTE_REQUEST, id);
    }

    public ShippingException forBooking(String id) {
        return forOperation(Resource.BOOKING, id);
    }

    public ShippingException forOperation(Resource resource, String id) {
        return new ShippingException(code, category, new Operation(resource, id));
    }
}
