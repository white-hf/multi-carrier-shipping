package com.enterprise.shipping.quote.api;

import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.identity.UserContext;
import com.enterprise.shipping.shared.api.ShippingLinks;
import com.enterprise.shipping.shared.domain.ShippingException.Resource;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles HTTP requests for idempotent Canadian domestic multi-carrier rating
 * and read-only status inspection.
 */
@RestController
@RequestMapping("/api/quotes")
public class ShippingQuoteController {

    private final QuoteApplicationService service;
    private final UserContext users;

    public ShippingQuoteController(QuoteApplicationService service, UserContext users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid ShippingIntent intent) {

        var result = service.create(users.userId(), key, intent);
        HttpStatus status =
                switch (result.disposition()) {
                    case CREATED -> HttpStatus.CREATED;
                    case EXISTING -> HttpStatus.OK;
                    case PENDING -> HttpStatus.ACCEPTED;
                };
        var response =
                ResponseEntity.status(status)
                        .location(
                                URI.create(
                                        ShippingLinks.status(
                                                Resource.QUOTE_REQUEST,
                                                result.result().requestId())));

        if (status == HttpStatus.ACCEPTED) {
            response.header("Retry-After", "2");
        }
        return response.body(result.result());
    }

    @GetMapping("/{requestId}")
    public QuoteApplicationService.Result get(@PathVariable String requestId) {
        return service.get(users.userId(), requestId);
    }
}
