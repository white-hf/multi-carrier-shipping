package com.enterprise.shipping.shared.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        if (e.operation() == null) {
            return error(e.status(), e.code(), List.of());
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("code", e.code());
        body.put("message", e.code().replace('_', ' '));
        body.put("requestId", Objects.toString(MDC.get("requestId"), "unassigned"));
        body.put("fieldErrors", List.of());
        body.put("operation", e.operation());

        return ResponseEntity.status(e.status())
                .location(URI.create(e.operation().statusUrl()))
                .body(body);
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
