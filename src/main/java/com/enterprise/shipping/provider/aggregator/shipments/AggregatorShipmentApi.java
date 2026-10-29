package com.enterprise.shipping.provider.aggregator.shipments;

import com.enterprise.shipping.provider.aggregator.config.AggregatorContext;
import com.enterprise.shipping.provider.aggregator.shipments.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.aggregator.shipments.dto.CreateShipmentResponse;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.StatusPolicy;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.util.Objects;

/**
 * Self-contained shipment-creation API slice for the aggregator provider.
 * Owns its creation endpoint, status policy, and recovery capability metadata independently.
 */
public class AggregatorShipmentApi {

    private final OutboundClient outbound;
    private final AggregatorContext context;
    private final OutboundEndpoint<CreateShipmentRequest, CreateShipmentResponse> endpoint;

    public AggregatorShipmentApi(OutboundClient outbound, AggregatorContext context) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.context = Objects.requireNonNull(context, "context");
        this.endpoint =
                OutboundEndpoint.post(
                                "create",
                                context.properties().shipmentsPath(),
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
        this.outbound.register(this.endpoint);
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

        // 2. Invoke the declarative shipment creation endpoint in quote-only mode
        CreateShipmentResponse response =
                outbound.invoke(
                        endpoint,
                        new CreateShipmentRequest(submission.providerQuoteId()),
                        OutboundDeadline.after(context.properties().createTimeout()));

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
