package com.enterprise.shipping.support;

import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import java.util.Map;

public final class TestMarket {
    private TestMarket() {
    }

    public static ShippingMarketPolicy canadian() {
        return new ShippingMarketPolicy(
                "CA", "CAD", Map.of("CA", "[A-Z]\\d[A-Z]\\d[A-Z]\\d", "US", "\\d{5}(-\\d{4})?"));
    }
}
