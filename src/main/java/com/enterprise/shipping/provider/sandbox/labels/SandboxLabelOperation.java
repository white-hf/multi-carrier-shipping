package com.enterprise.shipping.provider.sandbox.labels;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.StatusPolicy;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Self-contained label-retrieval API operation for the sandbox provider.
 * Owns its /rest/shipments/{shipmentId}/label endpoint, custom decoder, and URL allowlist checks.
 */
public class SandboxLabelOperation {

    private final OutboundClient outbound;
    private final ProviderAccountContext context;
    private final Duration readTimeout;
    private final OutboundEndpoint<Void, LabelData> endpoint;

    public SandboxLabelOperation(
            OutboundClient outbound,
            ProviderAccountContext context,
            String shipmentsPath,
            Duration readTimeout) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.context = Objects.requireNonNull(context, "context");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        this.endpoint =
                OutboundEndpoint.<Void, LabelData>get(
                                "labels",
                                shipmentsPath + "/{shipmentId}/label",
                                LabelData.class)
                        .decoder(new LabelResponseDecoder(context::allowedUrl))
                        .statusPolicy(
                                StatusPolicy.<LabelData>of2xx()
                                        .onExpected(202, () -> null)
                                        .onExpected(404, () -> null)
                                        .otherwiseThrow(ProviderAccountContext::readFailure));
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

        // 2. Invoke the declarative /rest/shipments/{shipmentId}/label endpoint
        return outbound.invoke(
                endpoint,
                Map.of("shipmentId", id),
                null,
                OutboundDeadline.after(readTimeout));
    }

    public boolean allowedUrl(String url) {
        return context.allowedUrl(url);
    }
}
