package com.enterprise.shipping.quote;

import com.enterprise.shipping.quote.domain.ShippingQuote;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShippingQuoteTest {
    @Test
    void acceptanceUsesTheExactExpiryBoundaryAndRequiresProviderIdentity() {
        var quote = quote("provider-offer");
        assertThat(quote.eligibility(1000, 5000)).isEqualTo(ShippingQuote.Eligibility.BOOKABLE);
        assertThat(quote.eligibility(1001, 5000)).isEqualTo(ShippingQuote.Eligibility.EXPIRED);
        assertThat(quote(null).eligibility(1000, 0))
                .isEqualTo(ShippingQuote.Eligibility.NOT_BOOKABLE);
    }

    @Test
    void displayValidityDoesNotRequireACreationTokenAndUsesTheSameExpiryBoundary() {
        var readOnlyOffer = quote(null);
        assertThat(readOnlyOffer.reusable(1000, 5000)).isTrue();
        assertThat(readOnlyOffer.eligibility(1000, 5000))
                .isEqualTo(ShippingQuote.Eligibility.NOT_BOOKABLE);
        assertThat(readOnlyOffer.reusable(1001, 5000)).isFalse();
    }

    private ShippingQuote quote(String token) {
        return new ShippingQuote(
                "q", "r", "p", "account", token, "carrier", "Carrier", "s", "Service", 100, "CAD",
                1, 6001);
    }
}
