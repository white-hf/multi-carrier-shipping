package com.enterprise.shipping.provider.sandbox.shipments;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.StatusPolicy;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.time.Duration;
import java.util.Objects;

/**
 * Self-contained shipment-creation API operation for the sandbox provider.
 * Owns its /rest/shipments endpoint, status policy, and recovery capability metadata independently.
 */
public class SandboxCreateOperation {

    private static final OutboundEndpoint<CreateShipmentRequest, CreateShipmentResponse> ENDPOINT =
            OutboundEndpoint.post(
                            "shipments",
                            "/rest/shipments",
                            CreateShipmentRequest.class,
                            CreateShipmentResponse.class)
                    .statusPolicy(
                            StatusPolicy.<CreateShipmentResponse>of(200, 201)
                                    .onHandledError(400, CreateShipmentResponse::rejection)
                                    .otherwiseThrow(
                                            status ->
                                                    ShippingException.outcomeUnknown(
                                                            "SHIPMENT_OUTCOME_UNKNOWN"))
                                    .onDecodeFailure(
                                            error ->
                                                    ShippingException.outcomeUnknown(
                                                            "SHIPMENT_OUTCOME_UNKNOWN")));

    private final OutboundClient outbound;
    private final ProviderAccountContext context;
    private final Duration createTimeout;

    public SandboxCreateOperation(
            OutboundClient outbound,
            ProviderAccountContext context,
            Duration createTimeout) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.context = Objects.requireNonNull(context, "context");
        this.createTimeout = Objects.requireNonNull(createTimeout, "createTimeout");
        this.outbound.register(ENDPOINT);
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

        // 2. Invoke the declarative /rest/shipments endpoint in quote-only mode
        CreateShipmentResponse response =
                outbound.invoke(
                        ENDPOINT,
                        new CreateShipmentRequest(submission.providerQuoteId()),
                        OutboundDeadline.after(createTimeout));

        // 3. Map the creation outcome and filter any inline label URL through the allowlist
        if (response.rejected()) {
            return CreationOutcome.rejection();
        }
        String tracking =
                response.trackingNumbers().isEmpty()
                        ? null
                        : response.trackingNumbers().get(0);
        String url = response.labelUrl();
        return new CreationOutcome(
                response.shipmentId(),
                tracking,
                context.allowedUrl(url) ? url : null,
                false);
    }
}
