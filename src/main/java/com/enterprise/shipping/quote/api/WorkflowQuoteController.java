package com.enterprise.shipping.quote.api;

import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.identity.UserContext;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Handles HTTP requests for Canadian domestic multi-carrier rating under the 'workflow' profile.
 */
@RestController
@Profile("workflow")
@RequestMapping("/api/quotes")
public class WorkflowQuoteController {

    private final QuoteApplicationService service;
    private final UserContext users;

    public WorkflowQuoteController(QuoteApplicationService service, UserContext users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody @Valid ShippingIntent intent) {
        var result = service.create(users.userId(), intent);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}