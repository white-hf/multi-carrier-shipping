package com.enterprise.shipping.booking.api;

import com.enterprise.shipping.booking.application.ShipmentApplicationService;
import com.enterprise.shipping.shared.identity.UserContext;
import com.enterprise.shipping.shared.api.ShippingLinks;
import com.enterprise.shipping.shared.domain.ShippingException.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles HTTP requests for shipment booking creations, replays, status queries,
 * and on-demand shipping label lookups.
 */
@RestController
@RequestMapping("/api/bookings")
public class ShippingBookingController {

    private final ShipmentApplicationService service;
    private final UserContext users;

    public ShippingBookingController(ShipmentApplicationService service, UserContext users) {
        this.service = service;
        this.users = users;
    }

    public record Create(@NotBlank @Size(max = 100) String quoteId) {}

    /**
     * Enforces an Idempotency-Key header on booking creation requests.
     */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String key, @RequestBody @Valid Create body) {

        var result = service.create(users.userId(), key, body.quoteId());
        HttpStatus status =
                switch (result.disposition()) {
                    case CREATED -> HttpStatus.CREATED;
                    case EXISTING -> HttpStatus.OK;
                    case PENDING -> HttpStatus.ACCEPTED;
                    case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case CONFLICT -> HttpStatus.CONFLICT;
                };
        var booking = BookingResponse.from(result.booking());
        var response = ResponseEntity.status(status).location(URI.create(booking.statusUrl()));

        // Suggest polling client to retry after 2 seconds on asynchronous accepted outcomes (202)
        if (status == HttpStatus.ACCEPTED) {
            response.header("Retry-After", "2");
        }

        // Return conflicts/errors wrapped in error structures if supplied
        if (result.code() != null) {
            return response.body(Map.of("code", result.code(), "booking", booking));
        }

        return response.body(booking);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable String id) {
        return BookingResponse.from(service.get(users.userId(), id));
    }

    public record BookingResponse(
            String bookingId,
            String status,
            String shipmentId,
            String trackingNumber,
            String labelStatus,
            String labelUrl,
            String errorCode,
            String statusUrl) {
        static BookingResponse from(ShipmentApplicationService.View booking) {
            return new BookingResponse(
                    booking.bookingId(),
                    booking.status(),
                    booking.shipmentId(),
                    booking.trackingNumber(),
                    booking.labelStatus(),
                    "CONFIRMED".equals(booking.status())
                            ? ShippingLinks.label(booking.bookingId())
                            : null,
                    booking.errorCode(),
                    ShippingLinks.status(Resource.BOOKING, booking.bookingId()));
        }
    }

    /**
     * Streams PDF bytes or redirects/returns labelUrl based on Accept headers and label state.
     */
    @GetMapping("/{id}/label")
    public ResponseEntity<?> label(
            @PathVariable String id,
            @RequestHeader(value = "Accept", defaultValue = "") String accept) {

        var result = service.label(users.userId(), id);

        if (result == null) {
            return ResponseEntity.accepted()
                    .header("Retry-After", "3")
                    .body(Map.of("labelStatus", "PENDING"));
        }

        // Return JSON locator link if requesting application/json
        if (result.url() != null && accept.contains("application/json")) {
            return ResponseEntity.ok(Map.of("labelUrl", result.url()));
        }

        // Issue SEE_OTHER redirect if requesting directly in browser (HTTP GET)
        if (result.url() != null) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(result.url()))
                    .build();
        }

        // Stream raw PDF binary stream directly
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=shipping-label.pdf")
                .body(result.bytes());
    }
}
