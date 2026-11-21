package com.enterprise.shipping.provider.sandbox.shipment;

import com.enterprise.shipping.provider.sandbox.ProviderJson;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.provider.sandbox.shipment.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.sandbox.shipment.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundDiagnostics;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundProtection;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** Create API: provider status meaning and conservative unknown-outcome handling. */
@Component
public class CreateOperation {
    private final ShipmentHttpClient http;
    private final OutboundProtection protection;
    private final SandboxProviderProperties properties;
    private final Duration createTimeout;

    @Autowired
    public CreateOperation(
            ShipmentHttpClient http,
            OutboundProtection protection,
            SandboxProviderProperties properties,
            @Value("${shipping.provider.create-timeout:60s}") Duration createTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (createTimeout == null || createTimeout.isZero() || createTimeout.isNegative()) {
            throw new IllegalArgumentException("Positive create deadline required");
        }
        this.createTimeout = createTimeout;
    }

    public CreationOutcome create(CreateSubmission submission) {
        // 1. Verify provider and account routing context before external dispatch.
        properties.requireContext(submission.providerId(), submission.accountAlias());

        // 2. Status interpretation and receipt validation remain inside the protected call.
        var diagnostic = OutboundDiagnostics.call(
                properties.providerId(),
                "shipments",
                status -> status == 200 || status == 201
                        ? OutboundDiagnostics.Classification.SUCCESS
                        : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE);
        var response = Mono.defer(
                        () -> http.create(new CreateShipmentRequest(submission.providerQuoteId())))
                .map(entity -> {
                    try {
                        return confirmed(entity.getBody());
                    } catch (ShippingException invalid) {
                        diagnostic.protocolError();
                        throw ShippingException.outcomeUnknown("SHIPMENT_OUTCOME_UNKNOWN");
                    }
                })
                .onErrorMap(DecodingException.class, invalid -> {
                    diagnostic.protocolError();
                    return ShippingException.outcomeUnknown("SHIPMENT_OUTCOME_UNKNOWN");
                })
                .onErrorResume(WebClientResponseException.class, failure -> {
                    int status = failure.getStatusCode().value();
                    return status == 400
                            ? Mono.just(CreationOutcome.rejection())
                            : Mono.error(
                                    ShippingException.outcomeUnknown("SHIPMENT_OUTCOME_UNKNOWN"));
                })
                .contextWrite(diagnostic::context);

        try {
            return protection.await("shipments", after(createTimeout), response);
        } catch (BulkheadFullException | CallNotPermittedException unsent) {
            // Only framework admission rejection proves the transport source was never subscribed.
            return CreationOutcome.notDispatched();
        }
    }

    private CreationOutcome confirmed(CreateShipmentResponse receipt) {
        if (receipt == null) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        String tracking =
                receipt.trackingNumbers().isEmpty() ? null : receipt.trackingNumbers().get(0);
        return CreationOutcome.confirmed(
                receipt.shipmentId(),
                tracking,
                properties.allowedUrl(receipt.labelUrl()) ? receipt.labelUrl() : null);
    }

    private static long after(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }
}
