package com.enterprise.shipping.provider.aggregator.labels;

import com.enterprise.shipping.provider.aggregator.config.AggregatorContext;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.StatusPolicy;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.util.Map;
import java.util.Objects;

/**
 * Self-contained label-retrieval API slice for the aggregator provider.
 * Owns its GET endpoint, custom decoder, and URL allowlist checks independently.
 */
public class AggregatorLabelApi {

    private final OutboundClient outbound;
    private final AggregatorContext context;
    private final OutboundEndpoint<Void, LabelData> endpoint;

    public AggregatorLabelApi(OutboundClient outbound, AggregatorContext context) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.context = Objects.requireNonNull(context, "context");
        this.endpoint =
                OutboundEndpoint.<Void, LabelData>get(
                                "labels",
                                context.properties().shipmentsPath() + "/{shipmentId}/label",
                                LabelData.class)
                        .decoder(new LabelResponseDecoder(context::allowedUrl))
                        .statusPolicy(
                                StatusPolicy.<LabelData>of2xx()
                                        .onExpected(202, () -> null)
                                        .onExpected(404, () -> null)
                                        .otherwiseThrow(AggregatorContext::readFailure));
        this.outbound.register(this.endpoint);
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public LabelData fetch(LabelIdentity identity) {
        // 1. Verify provider and account routing context and validate shipment identifier syntax
        context.requireContext(identity.providerId(), identity.accountAlias());
        String id = identity.shipmentId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }

        // 2. Invoke the declarative label endpoint with explicit path variable binding
        return outbound.invoke(
                endpoint,
                Map.of("shipmentId", id),
                null,
                OutboundDeadline.after(context.properties().readTimeout()));
    }

    public boolean allowedUrl(String url) {
        return context.allowedUrl(url);
    }
}
