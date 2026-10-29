package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.provider.aggregator.AggregatorProviderClient;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.LabelIdentity;

/** Label reads never create or resubmit a shipment. */
public final class AggregatorLabelAdapter implements LabelProviderPort {

    private final AggregatorProviderClient client;

    public AggregatorLabelAdapter(AggregatorProviderClient client) {
        this.client = client;
    }

    @Override
    public Label fetch(Identity identity) {
        var result =
                client.fetch(
                        new LabelIdentity(
                                identity.providerId(),
                                identity.accountAlias(),
                                identity.shipmentId()));
        return result == null
                ? null
                : new Label(result.bytes(), result.contentType(), result.url());
    }

    @Override
    public boolean allowedUrl(String url) {
        return client.allowedUrl(url);
    }
}
