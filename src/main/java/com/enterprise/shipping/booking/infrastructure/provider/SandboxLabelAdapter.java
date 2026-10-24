package com.enterprise.shipping.booking.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.SandboxProviderClient;
import com.enterprise.shipping.provider.sandbox.model.ProviderModels.LabelIdentity;
import com.enterprise.shipping.booking.port.LabelProviderPort;

/** Label reads never create or resubmit a shipment. */
public final class SandboxLabelAdapter implements LabelProviderPort {
    private final SandboxProviderClient client;
    public SandboxLabelAdapter(SandboxProviderClient client) { this.client = client; }
    @Override public Label fetch(Identity identity) {
        var result = client.fetch(new LabelIdentity(identity.providerId(), identity.accountAlias(), identity.shipmentId()));
        return result == null ? null : new Label(result.bytes(), result.contentType(), result.url());
    }
    @Override public boolean allowedUrl(String url) { return client.allowedUrl(url); }
}
