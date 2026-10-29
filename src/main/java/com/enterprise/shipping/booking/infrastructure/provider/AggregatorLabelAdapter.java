package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.provider.aggregator.labels.AggregatorLabelApi;
import com.enterprise.shipping.provider.aggregator.labels.LabelIdentity;

/** Label reads never create or resubmit a shipment. */
public final class AggregatorLabelAdapter implements LabelProviderPort {

    private final AggregatorLabelApi labelApi;

    public AggregatorLabelAdapter(AggregatorLabelApi labelApi) {
        this.labelApi = labelApi;
    }

    @Override
    public Label fetch(Identity identity) {
        var result =
                labelApi.fetch(
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
        return labelApi.allowedUrl(url);
    }
}
