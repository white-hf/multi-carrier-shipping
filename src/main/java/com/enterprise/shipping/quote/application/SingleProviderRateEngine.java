package com.enterprise.shipping.quote.application;

import com.enterprise.shipping.quote.port.*;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.util.List;

/** One upstream collection call; deliberately independent of per-carrier scatter/gather. */
public final class SingleProviderRateEngine implements RateComparisonPort {
    private final RateProviderPort provider;
    public SingleProviderRateEngine(RateProviderPort provider) { this.provider=provider; }
    @Override public List<ShippingQuote> compare(ShippingIntent intent, long deadlineNanos) {
        return List.copyOf(provider.fetchRates(intent,deadlineNanos));
    }
}
