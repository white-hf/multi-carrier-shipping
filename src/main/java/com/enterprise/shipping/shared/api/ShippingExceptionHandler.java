package com.enterprise.shipping.shared.api;

import com.enterprise.shipping.shared.domain.ShippingException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
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
 * Maps standard exceptions and domain ShippingExceptions to structured JSON HTTP errors.
 * Enforces security by preventing raw database errors or stack traces from leaking to the browser.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShippingExceptionHandler {

    @ExceptionHandler(ShippingException.class)
    public ResponseEntity<?> shipping(ShippingException e) {
        HttpStatus status = status(e.category());
        if (e.operation() == null) {
            return error(status, e.code(), List.of());
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("code", e.code());
        body.put("message", e.code().replace('_', ' '));
        body.put("requestId", Objects.toString(MDC.get("requestId"), "unassigned"));
        body.put("fieldErrors", List.of());
        String statusUrl = ShippingLinks.status(e.operation().resource(), e.operation().id());
        body.put("operation", Map.of("id", e.operation().id(), "statusUrl", statusUrl));

        var response = ResponseEntity.status(status).location(URI.create(statusUrl));
        if (e.code().equals("SHIPMENT_SUBMISSION_NOT_DISPATCHED")) {
            response.header("Retry-After", "2");
        }
        return response.body(body);
    }

    /** The only mapping from failure semantics to HTTP status categories. */
    static HttpStatus status(ShippingException.Category reason) {
        return switch (reason) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case RESOURCE_MISSING -> HttpStatus.NOT_FOUND;
            case STATE_CONFLICT -> HttpStatus.CONFLICT;
            case DEPENDENCY_FAILURE -> HttpStatus.BAD_GATEWAY;
            case DEPENDENCY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case LOCAL_CAPACITY, STORAGE_FAILURE, INTEGRATION_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case OUTCOME_UNKNOWN -> HttpStatus.ACCEPTED;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var fields =
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                f ->
                                        Map.of(
                                                "field", f.getField(),
                                                "message",
                                                        Objects.toString(
                                                                f.getDefaultMessage(),
                                                                "Invalid value")))
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
        return ResponseEntity.status(status)
                .body(
                        Map.of(
                                "code",
                                code,
                                "message",
                                code.replace('_', ' '),
                                "requestId",
                                Objects.toString(MDC.get("requestId"), "unassigned"),
                                "fieldErrors",
                                fields));
    }
}
