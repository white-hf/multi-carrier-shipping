package com.enterprise.shipping.shipping.port;

import com.enterprise.shipping.shipping.model.Label;

/** Consumer-owned label capability; URL trust remains a provider-owned policy. */
public interface LabelProvider {
    Label fetch(LabelIdentity identity);

    boolean allowedUrl(String url);

    record LabelIdentity(
            String providerId,
            String accountAlias,
            String shipmentId) {}
}
