package com.enterprise.shipping.quote.infrastructure.provider;

import com.enterprise.shipping.provider.aggregator.rates.AggregatorRateApi;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.quote.port.RateProviderPort;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.util.List;

/** Translates external offers; the provider never owns internal quote identities. */
public final class AggregatorRateAdapter implements RateProviderPort {

    private final AggregatorRateApi rateApi;

    public AggregatorRateAdapter(AggregatorRateApi rateApi) {
        this.rateApi = rateApi;
    }

    @Override
    public List<ShippingQuote> fetchRates(ShippingIntent intent, long deadline) {
        return rateApi.fetchRates(intent, deadline).stream()
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
