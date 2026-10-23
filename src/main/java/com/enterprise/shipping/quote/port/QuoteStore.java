package com.enterprise.shipping.quote.port;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import java.util.*;

public interface QuoteStore {
    void begin(String userId, String requestId, ShippingIntent intent, long now);
    void complete(String userId, String requestId, List<ShippingQuote> quotes, long now);
    void fail(String userId, String requestId, long now);
    Optional<ShippingQuote> find(String userId, String quoteId);
}
