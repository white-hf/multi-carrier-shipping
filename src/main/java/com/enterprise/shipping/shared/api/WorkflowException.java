package com.enterprise.shipping.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Bounded application codes; provider bodies must never become public exception messages.
 */
public final class WorkflowException extends RuntimeException {

    public record Operation(String id, String statusUrl) {}

    private final String code;
    private final HttpStatus status;
    private Operation operation;

    public WorkflowException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Operation operation() {
        return operation;
    }

    public WorkflowException forOperation(String id, String statusUrl) {
        var failure = new WorkflowException(code, status);
        failure.operation = new Operation(id, statusUrl);
        return failure;
    }

    public static WorkflowException missing() {
        return new WorkflowException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
