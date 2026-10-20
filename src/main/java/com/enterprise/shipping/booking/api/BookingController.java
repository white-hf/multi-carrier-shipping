package com.enterprise.shipping.booking.api;

import com.enterprise.shipping.booking.application.BookingService;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.shared.api.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Local demo only. Production must replace the fixed owner with authenticated tenant context. */
@RestController
@Profile("demo")
@ConditionalOnProperty(name = "shipping.booking.enabled", havingValue = "true")
@RequestMapping("/api/shipping/bookings")
public class BookingController {
    private static final String RETRY_AFTER_SECONDS = "1";
    private final BookingService service;
    private final BookingOwner owner;
    public BookingController(BookingService service, BookingOwner owner) { this.service = service; this.owner = owner; }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> create(
            @RequestHeader(value = "Idempotency-Key", required = true) String key,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse response = service.create(owner, key, request.toCommand());
        HttpStatus status;
        if (response.status() == BookingStatus.CONFIRMED) status = HttpStatus.CREATED;
        else if (response.status() == BookingStatus.FAILED) status = HttpStatus.UNPROCESSABLE_ENTITY;
        else status = HttpStatus.ACCEPTED;
        var result = ResponseEntity.status(status).location(URI.create("/api/shipping/bookings/" + response.bookingId()));
        if (!response.status().isTerminal()) result.header("Retry-After", RETRY_AFTER_SECONDS);
        boolean accepted = response.status() != BookingStatus.FAILED;
        return result.body(new ApiResponse<>(accepted, accepted ? null : response.errorCode(),
                accepted ? "Booking operation recorded." : "Carrier rejected the booking.", response));
    }

    @GetMapping("/{bookingId}")
    public ApiResponse<BookingResponse> get(@PathVariable String bookingId) {
        return ApiResponse.success(service.get(owner, bookingId));
    }
}
