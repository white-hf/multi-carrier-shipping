package com.enterprise.shipping.booking.api;

import com.enterprise.shipping.booking.application.BookingResponse;

import com.enterprise.shipping.booking.application.BookingService;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.shared.api.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import com.enterprise.shipping.booking.port.BookingOwnerProvider;
import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Maps booking operations to HTTP; ownership comes from a trusted provider. */
@RestController
@RequestMapping("/api/shipping/bookings")
@org.springframework.context.annotation.Profile("!workflow")
public class BookingController {
    private static final String RETRY_AFTER_SECONDS = "1";
    private final BookingService service;
    private final BookingOwnerProvider owners;
    private final ShippingMarketPolicy market;
    public BookingController(BookingService service, BookingOwnerProvider owners, ShippingMarketPolicy market) {
        this.service = service;
        this.owners = owners;
        this.market = market;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> create(
            @RequestHeader(value = "Idempotency-Key", required = true) String key,
            @Valid @RequestBody CreateBookingRequest request, Principal principal) {
        BookingResponse response = service.create(owners.ownerFor(principal), key, request.toCommand(market.defaultCountry()));
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
    public ApiResponse<BookingResponse> get(@PathVariable String bookingId, Principal principal) {
        return ApiResponse.success(service.get(owners.ownerFor(principal), bookingId));
    }
}
