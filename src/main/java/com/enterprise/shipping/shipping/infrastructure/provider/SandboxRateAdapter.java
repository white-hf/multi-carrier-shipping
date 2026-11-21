package com.enterprise.shipping.shipping.infrastructure.provider;

import com.enterprise.shipping.provider.sandbox.rate.RateOffer;
import com.enterprise.shipping.provider.sandbox.rate.RateOperation;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shipping.model.ShippingQuote;
import com.enterprise.shipping.shipping.port.RateProvider;
import java.util.List;

/** The only class that sees both the business port and rate provider types. */
public final class SandboxRateAdapter implements RateProvider {
    private final RateOperation operation;

    public SandboxRateAdapter(RateOperation operation) {
        this.operation = operation;
    }

    @Override
    public List<ShippingQuote> fetchRates(ShippingIntent intent, long deadlineNanos) {
        return operation.fetchRates(intent, deadlineNanos).stream()
                .map(SandboxRateAdapter::toQuote)
                .toList();
    }

    private static ShippingQuote toQuote(RateOffer offer) {
        return new ShippingQuote(
                offer.providerId(),
                offer.accountAlias(),
                offer.providerQuoteId(),
                offer.carrierId(),
                offer.carrierName(),
                offer.serviceId(),
                offer.serviceName(),
                offer.totalMinor(),
                offer.currency(),
                offer.transitDays(),
                offer.expiresAt());
    }
}
