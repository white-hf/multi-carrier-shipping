package com.enterprise.shipping.adapter;

import com.enterprise.shipping.config.ShippingEngineConstants;
import com.enterprise.shipping.dto.ShipmentDTO.CarrierQuote;
import com.enterprise.shipping.dto.ShipmentDTO.OutcomeCode;
import com.enterprise.shipping.dto.ShipmentDTO.QuoteRequest;
import com.enterprise.shipping.exception.CarrierFailureException;
import com.enterprise.shipping.monitoring.MonitorCarrierApi;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Maps the simulated FedEx contract to domain quotes; not a production carrier integration. */
@Component
public class FedExCarrierAdapter implements CarrierAdapter {
    private static final String CARRIER_ID = "fedex";
    private static final String CARRIER_NAME = "FedEx";

    private final WebClient client;
    private final ShippingEngineConstants properties;

    public FedExCarrierAdapter(
            @Qualifier("fedexClient") WebClient client, ShippingEngineConstants properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String getCarrierId() {
        return CARRIER_ID;
    }

    @Override
    public String getCarrierName() {
        return CARRIER_NAME;
    }

    @Override
    public long getMaxSubTaskTimeoutMs() {
        return properties.carrier(CARRIER_ID).getTimeoutMs();
    }

    @Override
    @MonitorCarrierApi(carrier = CARRIER_ID, api = "get_quotes")
    public List<CarrierQuote> fetchQuotes(QuoteRequest request, long timeoutMs) {
        String requestId = Objects.requireNonNullElse(MDC.get("requestId"), "unknown");
        // One end-to-end reactive timeout covers acquisition, transport, and response decoding.
        // Blocking is intentional here: this method runs on a bounded carrier worker, not an event loop.
        RateResponse response = client.post().uri("/rates")
                .header("X-Request-ID", requestId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RateResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();
        if (response == null || response.services() == null
                || response.services().stream().anyMatch(Objects::isNull)) {
            throw new CarrierFailureException(OutcomeCode.INVALID_RESPONSE);
        }
        return response.services().stream()
                .map(service -> new CarrierQuote(CARRIER_ID, CARRIER_NAME,
                        service.serviceType(), service.totalRate(), service.currency(), service.transitDays()))
                .toList();
    }

    // Transport records remain local to the adapter; the engine consumes only CarrierQuote.
    public record RateResponse(List<FedExService> services) {}

    public record FedExService(String serviceType, BigDecimal totalRate, String currency, int transitDays) {}
}
