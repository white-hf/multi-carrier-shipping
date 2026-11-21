package com.enterprise.shipping.shipping.port;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shipping.model.ShippingQuote;
import java.util.List;

/** Consumer-owned capability port; adapters map any provider call shape onto this contract. */
public interface RateProvider {
    List<ShippingQuote> fetchRates(ShippingIntent intent, long deadlineNanos);
}
