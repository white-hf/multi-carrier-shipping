package com.enterprise.shipping.shipping.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.label.LabelData;
import com.enterprise.shipping.provider.sandbox.label.LabelOperation;
import com.enterprise.shipping.shipping.model.Label;
import com.enterprise.shipping.shipping.port.LabelProvider;

/** Maps provider label payloads to the business label value. */
public final class SandboxLabelAdapter implements LabelProvider {
    private final LabelOperation operation;

    public SandboxLabelAdapter(LabelOperation operation) {
        this.operation = operation;
    }

    @Override
    public Label fetch(LabelIdentity identity) {
        LabelData data = operation.fetch(
                new com.enterprise.shipping.provider.sandbox.label.LabelIdentity(
                        identity.providerId(),
                        identity.accountAlias(),
                        identity.shipmentId()));
        return data == null ? null : new Label(data.bytes(), data.contentType(), data.url());
    }

    @Override
    public boolean allowedUrl(String url) {
        return operation.allowedUrl(url);
    }
}
