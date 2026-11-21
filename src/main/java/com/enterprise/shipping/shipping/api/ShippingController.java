package com.enterprise.shipping.shipping.api;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.identity.UserContext;
import com.enterprise.shipping.shipping.application.BookingCommand;
import com.enterprise.shipping.shipping.application.BookingService;
import com.enterprise.shipping.shipping.application.QuoteService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles HTTP requests for shipping quotes, booking creation/replay, status queries and
 * on-demand shipping label lookups.
 */
@RestController
@RequestMapping("/api")
public class ShippingController {
    private final BookingService service;
    private final QuoteService quotes;
    private final UserContext users;

    public ShippingController(
            BookingService service,
            QuoteService quotes,
            UserContext users) {
        this.service = service;
        this.quotes = quotes;
        this.users = users;
    }

    @PostMapping("/quotes")
    public QuoteService.Result quotes(@RequestBody @Valid ShippingIntent input) {
        return quotes.compare(input);
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> create(@RequestBody BookingCommand body) {
        return response(service.create(users.userId(), body));
    }

    @PostMapping("/bookings/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable String id) {
        return response(service.retry(users.userId(), id));
    }

    @GetMapping("/bookings/lookup")
    public BookingResponse lookup(
            @RequestParam String providerId,
            @RequestParam String providerQuoteId) {
        return BookingResponse.from(
                service.lookup(users.userId(), providerId, providerQuoteId));
    }

    private ResponseEntity<?> response(BookingService.Submission result) {
        HttpStatus status = switch (result.disposition()) {
            case CREATED -> HttpStatus.CREATED;
            case EXISTING -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        var booking = BookingResponse.from(result.booking());
        var response = ResponseEntity.status(status).location(URI.create(booking.statusUrl()));

        // Suggest polling client to retry after 2 seconds on asynchronous accepted outcomes (202).
        if (status == HttpStatus.ACCEPTED) {
            response.header("Retry-After", "2");
        }

        return response.body(booking);
    }

    @GetMapping("/bookings/{id}")
    public BookingResponse get(@PathVariable String id) {
        return BookingResponse.from(service.get(users.userId(), id));
    }

    /** Streams PDF bytes or redirects/returns labelUrl based on Accept headers and label state. */
    @GetMapping("/bookings/{id}/label")
    public ResponseEntity<?> label(
            @PathVariable String id,
            @RequestHeader(value = "Accept", defaultValue = "") String accept) {
        var result = service.label(users.userId(), id);

        if (result == null) {
            return ResponseEntity.accepted()
                    .header("Retry-After", "3")
                    .body(Map.of("labelStatus", "PENDING"));
        }

        // Return JSON locator link if requesting application/json.
        if (result.url() != null && accept.contains("application/json")) {
            return ResponseEntity.ok(Map.of("labelUrl", result.url()));
        }

        // Issue SEE_OTHER redirect if requesting directly in browser (HTTP GET).
        if (result.url() != null) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(result.url()))
                    .build();
        }

        // Stream raw PDF binary stream directly.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=shipping-label.pdf")
                .body(result.bytes());
    }
}
