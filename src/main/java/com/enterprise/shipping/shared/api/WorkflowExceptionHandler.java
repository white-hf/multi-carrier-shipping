package com.enterprise.shipping.shared.api;

import java.util.*;
import org.slf4j.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;

/**
 * Maps standard exceptions and custom WorkflowExceptions to structured JSON errors.
 * Enforces security by preventing raw database errors or stack traces from leaking to the browser.
 */
@RestControllerAdvice
@Profile("workflow")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkflowExceptionHandler {

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<?> workflow(WorkflowException e) {
        return error(e.status(), e.code(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of(
                        "field", f.getField(),
                        "message", Objects.toString(f.getDefaultMessage(), "Invalid value")
                ))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", fields);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> header() {
        return error(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY", List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STORE_UNAVAILABLE", List.of());
    }

    private ResponseEntity<?> error(HttpStatus status, String code, List<?> fields) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", code.replace('_', ' '),
                "requestId", Objects.toString(MDC.get("requestId"), "unassigned"),
                "fieldErrors", fields
        ));
    }
}