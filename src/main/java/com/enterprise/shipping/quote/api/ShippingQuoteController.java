package com.enterprise.shipping.quote.api;

import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quotes")
public class ShippingQuoteController {
    private final QuoteApplicationService service;

    public ShippingQuoteController(QuoteApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public QuoteApplicationService.Result compare(@RequestBody @Valid ShippingIntent intent) {
        return service.compare(intent);
    }
}
