package com.enterprise.shipping.quote.api;

import com.enterprise.shipping.quote.domain.QuoteCommand;
import com.enterprise.shipping.quote.config.QuoteProperties;
import com.enterprise.shipping.shared.api.ApiResponse;
import com.enterprise.shipping.quote.domain.QuoteComparison;
import com.enterprise.shipping.quote.domain.ComparisonStatus;
import com.enterprise.shipping.quote.domain.OutcomeCode;
import com.enterprise.shipping.shared.api.ParcelRequest;
import com.enterprise.shipping.quote.application.HighFanOutAggregationEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Maps comparison outcomes to HTTP responses without exposing carrier exception details. */
@RestController
@RequestMapping("/api/shipping")
public class QuoteController {
    private static final String COMPARISON_FAILED = "COMPARISON_FAILED";

    private final HighFanOutAggregationEngine engine;
    private final QuoteProperties properties;

    public QuoteController(
            HighFanOutAggregationEngine engine, QuoteProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @PostMapping("/quotes")
    public ResponseEntity<ApiResponse<QuoteComparison>> getAggregatedShippingQuotes(
            @Valid @RequestBody ParcelRequest request) {
        QuoteComparison response =
                engine.executeFanOutRates(new QuoteCommand(request.toDomain()), properties.getGlobalTimeoutMs());
        if (response.status() == ComparisonStatus.FAILED) {
            return ResponseEntity.status(failureStatus(response))
                    .body(new ApiResponse<>(false, COMPARISON_FAILED,
                            "No carrier query succeeded.", response));
        }
        // Partial coverage and a valid no-services result are successful comparisons.
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private HttpStatus failureStatus(QuoteComparison response) {
        boolean allRejected = response.carrierOutcomes().stream()
                .allMatch(outcome -> outcome.code() == OutcomeCode.CAPACITY_REJECTED);
        if (allRejected) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        // Preserve the documented precedence for mixed failures: timeout before upstream error.
        boolean hasTimeout = response.carrierOutcomes().stream()
                .anyMatch(outcome -> outcome.code() == OutcomeCode.TIMEOUT);
        if (hasTimeout) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        return HttpStatus.BAD_GATEWAY;
    }
}
