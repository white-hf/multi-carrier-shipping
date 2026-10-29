package com.enterprise.shipping.quote.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.rates.SandboxRateOperation;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.RateProviderPort;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.util.List;

/** Translates external offers; the provider never owns internal quote identities. */
public final class SandboxRateAdapter implements RateProviderPort {

    private final SandboxRateOperation rateOperation;

    public SandboxRateAdapter(SandboxRateOperation rateOperation) {
        this.rateOperation = rateOperation;
    }

    @Override
    public List<ShippingQuote> fetchRates(ShippingIntent intent, long deadline) {
        return rateOperation.fetchRates(intent, deadline).stream()
                .map(
                        q ->
                                new ShippingQuote(
                                        null,
                                        null,
                                        q.providerId(),
                                        q.accountAlias(),
                                        q.providerQuoteId(),
                                        q.carrierId(),
                                        q.carrierName(),
                                        q.serviceId(),
                                        q.serviceName(),
                                        q.totalMinor(),
                                        q.currency(),
                                        q.transitDays(),
                                        q.expiresAt()))
                .toList();
    }
}
