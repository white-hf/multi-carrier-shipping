package com.enterprise.shipping.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** Demo contract: one domestic Canadian parcel, CAD prices supplied by simulated carriers. */
public final class ShipmentDTO {
    private ShipmentDTO() {}
    // Upper bounds are demo guardrails, not carrier service limits; postal checks validate shape only.
    public record QuoteRequest(
            @NotBlank @Pattern(regexp = "(?i)[A-Z]\\d[A-Z] ?\\d[A-Z]\\d") String originPostalCode,
            @NotBlank @Pattern(regexp = "(?i)[A-Z]\\d[A-Z] ?\\d[A-Z]\\d") String destinationPostalCode,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal weightKg,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal lengthCm,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal widthCm,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal heightCm) {}
    public record CarrierQuote(String carrierId, String carrierName, String serviceLevel,
                               BigDecimal rate, String currency, int estimatedDeliveryDays) {}
    /** NO_SERVICES is a successful query; the remaining non-success codes identify failure modes. */
    public enum OutcomeCode { SUCCESS, NO_SERVICES, TIMEOUT, CIRCUIT_OPEN, CAPACITY_REJECTED,
                              UPSTREAM_ERROR, INVALID_RESPONSE, INTERRUPTED }
    /** Coverage status counts successful carrier queries, not the number of returned quotes. */
    public enum ComparisonStatus { COMPLETE, PARTIAL, FAILED }
    public record CarrierOutcome(String carrierId, OutcomeCode code) {}
    public record AggregatedQuotesResponse(String requestId, ComparisonStatus status,
            List<CarrierQuote> successfulQuotes, List<CarrierOutcome> carrierOutcomes, long totalExecutionTimeMs) {
        public AggregatedQuotesResponse {
            // Freeze response collections before they cross the API boundary.
            successfulQuotes = List.copyOf(successfulQuotes);
            carrierOutcomes = List.copyOf(carrierOutcomes);
        }
    }
}
