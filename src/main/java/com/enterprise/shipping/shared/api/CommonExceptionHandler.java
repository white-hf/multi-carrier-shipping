package com.enterprise.shipping.shared.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/** Returns stable client errors; never echoes upstream payloads or exception messages. */
@RestControllerAdvice
public class CommonExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CommonExceptionHandler.class);
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        // Identify invalid fields without reflecting submitted address or measurement values.
        String fields = ex.getBindingResult().getFieldErrors().stream().map(e -> e.getField()).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST", "Invalid fields: " + fields));
    }
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingHeader(MissingRequestHeaderException exception) {
        // The header name comes from the controller contract, not a submitted header value.
        return ResponseEntity.badRequest().body(ApiResponse.failure(
                "INVALID_REQUEST", "Missing required header: " + exception.getHeaderName()));
    }
    @ExceptionHandler(com.enterprise.shipping.shared.domain.InvalidParcelException.class)
    public ResponseEntity<ApiResponse<Void>> invalidParcel() {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST", "Parcel does not satisfy market rules."));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed() {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST", "Malformed JSON request."));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("NOT_FOUND", "Resource not found."));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> method() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.failure("METHOD_NOT_ALLOWED", "Method not allowed."));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> media() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResponse.failure("UNSUPPORTED_MEDIA_TYPE", "Use application/json."));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        log.error("Unexpected request failure type={}", ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(ApiResponse.failure("INTERNAL_ERROR", "Unexpected error."));
    }
}
