package com.enterprise.shipping.quote.port;

import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.util.List;

public interface RateComparisonPort {
    List<ShippingQuote> compare(ShippingIntent intent, long deadlineNanos);
}
