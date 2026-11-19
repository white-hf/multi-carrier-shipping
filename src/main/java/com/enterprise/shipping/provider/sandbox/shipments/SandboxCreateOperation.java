package com.enterprise.shipping.provider.sandbox.shipments;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDiagnostics;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Self-contained shipment-creation API operation for the sandbox provider.
 * Owns its /rest/shipments endpoint, status policy, and recovery capability metadata independently.
 */
@Component
public class SandboxCreateOperation {

    private final SandboxShipmentsHttpService http;
    private final OutboundGuard guard;
    private final ProviderAccountContext context;
    private final Duration createTimeout;

    @Autowired
    public SandboxCreateOperation(
            @Qualifier("sandboxShipmentsHttpService") SandboxShipmentsHttpService http,
            @Qualifier("sandboxOutboundGuard") OutboundGuard guard,
            @Qualifier("sandboxAccountContext") ProviderAccountContext context,
            SandboxProviderProperties properties) {
        this(http, guard, context, properties.createTimeout());
    }

    public SandboxCreateOperation(
            SandboxShipmentsHttpService http,
            OutboundGuard guard,
            ProviderAccountContext context,
            Duration createTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.context = Objects.requireNonNull(context, "context");
        this.createTimeout = Objects.requireNonNull(createTimeout, "createTimeout");
        guard.registerOperation("shipments");
    }

    public Capabilities capabilities(String providerId, String accountAlias) {
        if (!context.matchesContext(providerId, accountAlias)) {
            return new Capabilities(Support.UNVERIFIED, Support.UNVERIFIED);
        }
        // No documented authoritative reference lookup or guaranteed safe creation retry.
        return new Capabilities(Support.UNSUPPORTED, Support.UNVERIFIED);
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public CreationOutcome create(CreateSubmission submission) {
        // 1. Verify provider and account routing context before external dispatch
        context.requireContext(submission.providerId(), submission.accountAlias());

        // 2. Status interpretation and receipt validation remain inside the guarded call.
        var diagnostic = OutboundDiagnostics.call(context.providerId(), "shipments", status ->
                status == 200 || status == 201 ? OutboundDiagnostics.Classification.SUCCESS
                        : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE);
        return guard.execute("default", "shipments", OutboundDeadline.after(createTimeout), effective -> {
            try {
                var response = OutboundDeadline.await(http.create(new CreateShipmentRequest(submission.providerQuoteId()))
                        .contextWrite(diagnostic::context), effective);
                return confirmed(response.getBody());
            } catch (WebClientResponseException failure) {
                if (failure.getStatusCode().value() == 400) {
                    return CreationOutcome.rejection();
                }
                throw ShippingException.outcomeUnknown("SHIPMENT_OUTCOME_UNKNOWN");
            } catch (DecodingException | ShippingException invalid) {
                diagnostic.protocolError();
                throw ShippingException.outcomeUnknown("SHIPMENT_OUTCOME_UNKNOWN");
            }
        });
    }

    private CreationOutcome confirmed(CreateShipmentResponse receipt) {
        if (receipt == null) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        String tracking = receipt.trackingNumbers().isEmpty() ? null : receipt.trackingNumbers().get(0);
        return new CreationOutcome(receipt.shipmentId(), tracking,
                context.allowedUrl(receipt.labelUrl()) ? receipt.labelUrl() : null, false);
    }
}
