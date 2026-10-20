package com.enterprise.shipping.booking.api;

import com.enterprise.shipping.booking.domain.BookingException;
import com.enterprise.shipping.shared.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@ConditionalOnProperty(name = "shipping.booking.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookingExceptionHandler {
    @ExceptionHandler(BookingException.class)
    public ResponseEntity<ApiResponse<Void>> handle(BookingException error) {
        HttpStatus status;
        String message;
        switch (error.code()) {
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                message = "Idempotency key was already used for a different request.";
            }
            case BOOKING_NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                message = "Booking not found.";
            }
            case AUTHENTICATION_REQUIRED -> {
                status = HttpStatus.UNAUTHORIZED;
                message = "Authentication is required.";
            }
            case UNSUPPORTED_CARRIER -> {
                status = HttpStatus.BAD_REQUEST;
                message = "Carrier is not enabled for new bookings.";
            }
            case INVALID_IDEMPOTENCY_KEY -> {
                status = HttpStatus.BAD_REQUEST;
                message = "Provide an Idempotency-Key of 1-64 ASCII letters, digits, or . _ : - characters.";
            }
            default -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                message = "Booking storage is unavailable. Retry with the same idempotency key; a shipment may already exist.";
            }
        }
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) response.header("Retry-After", "1");
        return response.body(ApiResponse.failure(error.code().name(), message));
    }
}
