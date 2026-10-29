package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.provider.sandbox.labels.LabelIdentity;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelOperation;

/** Label reads never create or resubmit a shipment. */
public final class SandboxLabelAdapter implements LabelProviderPort {

    private final SandboxLabelOperation labelOperation;

    public SandboxLabelAdapter(SandboxLabelOperation labelOperation) {
        this.labelOperation = labelOperation;
    }

    @Override
    public Label fetch(Identity identity) {
        var result =
                labelOperation.fetch(
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
        return labelOperation.allowedUrl(url);
    }
}
