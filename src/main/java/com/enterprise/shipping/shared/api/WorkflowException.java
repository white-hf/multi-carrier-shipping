package com.enterprise.shipping.shared.api;

import org.springframework.http.HttpStatus;

/** Bounded application codes; provider bodies must never become public exception messages. */
public final class WorkflowException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public WorkflowException(String code, HttpStatus status) { super(code); this.code=code; this.status=status; }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static WorkflowException missing() { return new WorkflowException("RESOURCE_NOT_FOUND",HttpStatus.NOT_FOUND); }
}
