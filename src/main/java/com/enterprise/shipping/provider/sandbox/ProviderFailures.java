package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.shared.domain.ShippingException;

/** Provider-owned HTTP status meaning; adapters and business code never reinterpret statuses. */
public final class ProviderFailures {
    private ProviderFailures() {
    }

    public static ShippingException readFailure(int status) {
        return switch (status) {
            case 401 -> ShippingException.integrationUnavailable("PROVIDER_AUTHENTICATION_FAILED");
            case 429 -> ShippingException.dependencyFailure("PROVIDER_RATE_LIMITED");
            default -> ShippingException.dependencyFailure(
                    status >= 500 ? "PROVIDER_UNAVAILABLE" : "PROVIDER_UNEXPECTED_RESPONSE");
        };
    }

    public static ShippingException invalidResponse() {
        return ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
    }
}
